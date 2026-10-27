#!/usr/bin/env python3
"""Check release modes with Python, PyYAML and the checked-in workflow."""

import itertools
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest

import yaml


ROOT = Path(__file__).resolve().parent.parent
WORKFLOW = yaml.load((ROOT / ".github/workflows/release.yml").read_text(), Loader=yaml.BaseLoader)
JOBS = WORKFLOW["jobs"]


def evaluate(expression, needs, cancelled=False, inputs=None):
    expression = expression.removeprefix("${{").removesuffix("}}").strip()
    expression = re.sub(r"needs\.([\w-]+)\.outputs\.(\w+)", r"needs['\1']['outputs']['\2']", expression)
    expression = re.sub(r"needs\.([\w-]+)\.result", r"needs['\1']['result']", expression)
    expression = re.sub(r"inputs\.(\w+)", r"inputs.get('\1', '')", expression)
    expression = expression.replace("&&", " and ").replace("||", " or ")
    expression = re.sub(r"!(?!=)", "not ", expression)
    return eval(f"({expression})", {"__builtins__": {}}, {
        "needs": needs,
        "inputs": inputs or {},
        "cancelled": lambda: cancelled,
        "always": lambda: True,
        "failure": lambda: any(job["result"] == "failure" for job in needs.values()),
        "success": lambda: all(job["result"] == "success" for job in needs.values()),
    })


def enabled(node, needs, cancelled=False, step=False):
    expression = node.get("if", "")
    if not re.search(r"\b(?:always|cancelled|failure|success)\(", expression):
        if cancelled or (not step and any(job["result"] != "success" for job in needs.values())):
            return False
    return evaluate(expression, needs, cancelled) if expression else True


def context(mode, linux="success", windows="success", prepare="success", release="success"):
    return {
        "prepare": {"result": prepare, "outputs": {"mode": mode}},
        "build-linux": {"result": linux},
        "build-windows": {"result": windows},
        "release": {"result": release},
    }


class ReleaseWorkflowTest(unittest.TestCase):
    def test_dispatch_and_tags_default_to_full(self):
        config = WORKFLOW["on"]["workflow_dispatch"]["inputs"]["release_mode"]
        self.assertEqual("full", config["default"])
        self.assertEqual(["full", "windows-desktop"], config["options"])
        self.assertEqual(["v*"], WORKFLOW["on"]["push"]["tags"])
        env = JOBS["prepare"]["steps"][1]["env"]["RELEASE_MODE"]
        self.assertEqual("full", evaluate(env, {}))
        self.assertEqual("windows-desktop", evaluate(env, {}, inputs={"release_mode": "windows-desktop"}))

    def test_publish_gate_for_every_build_outcome(self):
        outcomes = ("success", "failure", "cancelled", "skipped")
        for mode, prepare, windows, linux, cancelled in itertools.product(
            ("full", "windows-desktop"), outcomes, outcomes, outcomes, (False, True)
        ):
            needs = context(mode, linux, windows, prepare)
            needs.pop("release")
            expected = not cancelled and prepare == windows == "success"
            expected = expected and linux == ("success" if mode == "full" else "skipped")
            with self.subTest(mode=mode, prepare=prepare, windows=windows, linux=linux, cancelled=cancelled):
                self.assertEqual(expected, enabled(JOBS["release"], needs, cancelled))

    def test_platform_jobs_and_cli_steps_follow_the_mode(self):
        for mode in ("full", "windows-desktop"):
            needs = context(mode)
            only_prepare = {"prepare": needs["prepare"]}
            self.assertEqual(mode == "full", enabled(JOBS["build-linux"], only_prepare))
            self.assertTrue(enabled(JOBS["build-windows"], only_prepare))
            for job_id in ("publish-repos", "submit-winget"):
                downstream = {key: dict(needs[key]) for key in ("prepare", "release")}
                self.assertEqual(mode == "full", enabled(JOBS[job_id], downstream))
                downstream["release"]["result"] = "failure"
                self.assertFalse(enabled(JOBS[job_id], downstream))
            for step in JOBS["build-windows"]["steps"]:
                if "CLI" in step["name"]:
                    self.assertEqual(mode == "full", enabled(step, only_prepare), step["name"])

    def test_windows_only_keeps_qualification_and_publishes_msi_checksums(self):
        needs = context("windows-desktop", linux="skipped")
        steps = [step for step in JOBS["build-windows"]["steps"] if enabled(step, {"prepare": needs["prepare"]})]
        commands = "\n".join(step.get("run", "") for step in steps)
        for required in (":core:desktopTest", ":desktop:desktopTest", ":desktop:packageReleaseMsi",
                         "qualify-windows-msi.ps1", "-BaselineRepo", "-LegacyNotice", "LatchSetup.msi"):
            self.assertIn(required, commands)
        self.assertNotIn(":cli:", commands)
        self.assertNotIn("cli\\build", commands)
        release_steps = {step["name"]: step for step in JOBS["release"]["steps"]}
        self.assertFalse(enabled(release_steps["Download Linux artifacts"], needs, step=True))
        self.assertFalse(enabled(release_steps["Generate package-manager manifests"], needs, step=True))
        self.assertTrue(enabled(release_steps["Download Windows artifacts"], needs, step=True))
        publish = release_steps["Create GitHub release"]["with"]
        self.assertEqual("true", publish["make_latest"])
        self.assertEqual("${{ github.sha }}", publish["target_commitish"])
        self.assertEqual("release-assets/*", publish["files"])
        with tempfile.TemporaryDirectory() as directory:
            staging = Path(directory)
            (staging / "LatchSetup.msi").write_bytes(b"qualified MSI")
            (staging / "Latch-Update-Notice.msi").write_bytes(b"manual update notice")
            subprocess.run(["bash", "-ec", release_steps["Generate checksums"]["run"]], cwd=staging, check=True)
            subprocess.run(["sha256sum", "-c", "SHA256SUMS"], cwd=staging, check=True, capture_output=True)
            self.assertEqual(2, len((staging / "SHA256SUMS").read_text().splitlines()))

    def test_prepare_validates_version_tag_and_mode(self):
        script = JOBS["prepare"]["steps"][1]["run"]
        for mode, ref_type, ref_name, expected in (
            ("full", "branch", "main", True),
            ("windows-desktop", "branch", "main", True),
            ("full", "tag", "v1.5.1", True),
            ("windows-desktop", "tag", "v1.5.1", True),
            ("full", "tag", "v1.5.0", False),
            ("unknown", "branch", "main", False),
        ):
            with self.subTest(mode=mode, ref_type=ref_type, ref_name=ref_name):
                with tempfile.TemporaryDirectory() as directory:
                    work = Path(directory)
                    (work / "gradle.properties").write_text("latchVersion=1.5.1\n")
                    output = work / "output"
                    env = dict(os.environ, RELEASE_MODE=mode, GITHUB_REF_TYPE=ref_type,
                               GITHUB_REF_NAME=ref_name, GITHUB_OUTPUT=str(output))
                    result = subprocess.run(["bash", "-ec", script], cwd=work, env=env, capture_output=True)
                    self.assertEqual(expected, result.returncode == 0, result.stderr.decode())
                    if expected:
                        self.assertEqual(f"version=1.5.1\ntag=v1.5.1\nmode={mode}\n", output.read_text())


if __name__ == "__main__":
    unittest.main(verbosity=2)
