# Version-Independent Desktop Client Installer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create a lightweight, version-independent bootstrapper installer for all Latch desktop clients that detects existing installations, performs silent auto-installs/updates like Google Chrome Setup with 0 to 1 clicks, and allows reinstallation or uninstallation.

**Architecture:**
A unified distribution strategy composed of:
1. **Windows Native Bootstrapper (`LatchSetup.exe`)**: A lightweight (<200 KB) standalone Windows GUI executable built with native Windows APIs and WinForms. When opened, it inspects local registry and `%LOCALAPPDATA%\Latch` for existing Latch installations, queries the GitHub Releases API for the latest release, verifies SHA-256 checksums, and executes silent per-user MSI installation (`msiexec /i ... /qn`). If already up to date, it displays a minimal 3-option card: Launch, Reinstall, Uninstall.
2. **Linux Universal Installer (`install.sh`)**: Enhances existing `install.sh` to support detection of installed builds, silent upgrades, and CLI flags (`--uninstall`, `--reinstall`, `--update`).
3. **Windows PowerShell Web Installer (`install.ps1`)**: Complementary one-line script (`irm https://latch.vinnovateit.com/install.ps1 | iex`) matching `install.sh` for terminal-based automation on Windows.

**Tech Stack:**
- Windows: C# / WinForms targeting Windows built-in .NET Framework 4.8 (`csc.exe` in `%windir%\Microsoft.NET\Framework64\v4.0.30319`), compiled natively with zero extra dependencies or runtimes.
- Linux: POSIX shell (`sh`), `curl`, `tar`.
- PowerShell: Native Windows PowerShell 5.1+.
- Build & CI: Gradle packaging task and GitHub Actions release integration.

## Global Constraints
- Zero user intervention for fresh install and upgrade: double-clicking installer completes installation and launches Latch automatically.
- Version-independent: The installer binaries and scripts never contain hardcoded version numbers; they always query GitHub releases dynamically.
- Upgrade Code invariant: Windows installer must target Latch upgrade UUID `{6F3B9C84-1D52-4E7A-9B06-2A8F5C14D7E3}`.
- Per-user install invariant: No administrative elevation required; installs to `%LOCALAPPDATA%\Latch` and preserves `%LOCALAPPDATA%\VinnovateIT\Latch` user settings/credentials.
- Security: All downloaded packages must verify SHA-256 against release metadata before execution.

---

### Task 1: Windows Bootstrapper Core Logic & Engine

**Files:**
- Create: `installer/windows/src/Program.cs`
- Create: `installer/windows/src/ReleaseFetcher.cs`
- Create: `installer/windows/src/InstalledProduct.cs`
- Create: `installer/windows/src/InstallerRunner.cs`
- Create: `installer/windows/build.cmd`

**Interfaces:**
- Consumes: GitHub Releases API (`https://api.github.com/repos/vinnovateit/latch/releases/latest`), Windows MSI Installer (`msiexec.exe`).
- Produces: `InstalledProduct` (reads version, exe path, uninstall string from registry), `ReleaseFetcher` (resolves latest MSI asset URL and SHA-256 digest), `InstallerRunner` (executes silent install, uninstall, or launch).

- [ ] **Step 1: Write `InstalledProduct.cs` for Windows registry inspection**
  Inspect `HKCU\Software\Microsoft\Windows\CurrentVersion\Uninstall\{6F3B9C84-1D52-4E7A-9B06-2A8F5C14D7E3}` and fallback `%LOCALAPPDATA%\Latch\Latch.exe`. Detect whether Latch is installed, its version string, and installation path.

- [ ] **Step 2: Write `ReleaseFetcher.cs` for GitHub release discovery and verification**
  Fetch latest release metadata from `https://api.github.com/repos/vinnovateit/latch/releases/latest`. Extract asset `LatchSetup.msi` (or `Latch-*.msi`), target version, and SHA-256 digest. Implement streaming download with progress callback and SHA-256 verification.

- [ ] **Step 3: Write `InstallerRunner.cs` for MSI execution**
  Implement silent per-user installation: `msiexec.exe /i "<msi_path>" /qn /norestart`.
  Implement silent uninstallation: `msiexec.exe /x {6F3B9C84-1D52-4E7A-9B06-2A8F5C14D7E3} /qn /norestart`.
  Implement process launch for `%LOCALAPPDATA%\Latch\Latch.exe`.

- [ ] **Step 4: Create automated build script `installer/windows/build.cmd`**
  Use Windows native `%windir%\Microsoft.NET\Framework64\v4.0.30319\csc.exe` with `/target:winexe /win32icon:desktop/icons/latch.ico` to compile `LatchSetup.exe`. Verify compilation runs in under 2 seconds.

---

### Task 2: Chrome-Style Minimalist UI & CLI Argument Handling

**Files:**
- Create: `installer/windows/src/SetupForm.cs`
- Modify: `installer/windows/src/Program.cs`

**Interfaces:**
- Consumes: `InstalledProduct`, `ReleaseFetcher`, `InstallerRunner`.
- Produces: Chrome-style setup dialog with smooth progress bar and dynamic state transitions.

- [ ] **Step 1: Design `SetupForm.cs`**
  Compact, clean modern form (380x190 px, fixed border, centered on screen, dark/light aware or neutral modern styling):
  - Latch icon + "Latch Setup" title.
  - Status message: "Checking for updates...", "Downloading Latch v1.4.3...", "Installing...", "Latch is up to date."
  - Native progress bar (`ProgressBar` with smooth continuous animation).
  - Action button container (hidden during download/install; shown only when app is already up to date):
    - `[Launch]` (Default)
    - `[Reinstall]`
    - `[Uninstall]`

- [ ] **Step 2: Implement State Flow in `Program.cs`**
  - Parse CLI arguments: `--silent` / `-s`, `--uninstall`, `--reinstall`, `--launch`.
  - If `--silent`: run headlessly with exit codes (0 = success, 1 = failure).
  - If GUI:
    - If not installed -> automatically download, verify, silently install, launch Latch, exit.
    - If installed and newer version exists -> automatically download, silently update, launch Latch, exit.
    - If installed and up-to-date -> display status and show Launch / Reinstall / Uninstall buttons.

- [ ] **Step 3: Test Windows Bootstrapper locally**
  Compile `LatchSetup.exe` and test:
  1. Detection of currently installed Latch build.
  2. `--silent` flags.
  3. Action buttons (Launch, Reinstall, Uninstall).

---

### Task 3: Linux Universal Installer Enhancements (`install.sh`)

**Files:**
- Modify: `install.sh`
- Test: `packaging/test-install-script.sh`

**Interfaces:**
- Consumes: `/opt/latch/bin/Latch` or `~/.local/share/latch/bin/Latch`, GitHub releases API.
- Produces: CLI options `--uninstall`, `--reinstall`, `--update`, and interactive detection prompt.

- [ ] **Step 1: Add installation detection in `install.sh`**
  Inspect whether `/opt/latch` or `~/.local/share/latch` exists. Read installed version if available.

- [ ] **Step 2: Add CLI flags (`--uninstall`, `--reinstall`, `--update`)**
  - `--uninstall`: Remove `/opt/latch` or `~/.local/share/latch`, delete `/usr/share/applications/latch.desktop` or `~/.local/share/applications/latch.desktop`, delete symlinks in `/usr/local/bin/latch` or `~/.local/bin/latch`.
  - `--reinstall`: Bypass up-to-date check, download latest tarball, and extract.
  - `--update`: Only install if remote version > local version.

- [ ] **Step 3: Interactive handling for up-to-date installations**
  If run in an interactive terminal (`[ -t 0 ]`) and the local version is already up to date:
  Prompt the user:
  ```text
  Latch v1.4.3 is already installed and up to date.
  1) Launch Latch
  2) Reinstall
  3) Uninstall
  4) Exit
  ```
  If run non-interactively (e.g. `curl ... | sh` without TTY), exit cleanly or update if out of date.

---

### Task 4: Windows PowerShell One-Liner Installer (`install.ps1`)

**Files:**
- Create: `install.ps1`
- Test: Local PowerShell invocation

**Interfaces:**
- Consumes: Windows Installer, GitHub API.
- Produces: One-line bootstrap command: `irm https://latch.vinnovateit.com/install.ps1 | iex`.

- [ ] **Step 1: Implement `install.ps1`**
  - Query latest release from GitHub API.
  - Detect installed Latch via Registry `{6F3B9C84-1D52-4E7A-9B06-2A8F5C14D7E3}`.
  - Support parameters: `-Uninstall`, `-Reinstall`, `-UpdateOnly`, `-Quiet`.
  - Download MSI, verify SHA-256 digest, run silent `msiexec.exe /i ... /qn /norestart`.
  - Launch Latch on completion.

---

### Task 5: Gradle Packaging & Release CI Integration

**Files:**
- Modify: `desktop/build.gradle.kts`
- Modify: `.github/workflows/release.yml`
- Modify: `README.md`

**Interfaces:**
- Consumes: `installer/windows/build.cmd`.
- Produces: `LatchSetup.exe` built as an artifact alongside `LatchSetup.msi` during release workflow.

- [ ] **Step 1: Add Gradle task `:desktop:packageWindowsBootstrapper`**
  Register a task in `desktop/build.gradle.kts` that compiles `installer/windows/src/*.cs` using Windows `csc.exe` into `desktop/build/distributions/LatchSetup.exe`.

- [ ] **Step 2: Integrate `LatchSetup.exe` and `install.ps1` into `release.yml`**
  In `.github/workflows/release.yml`, upload `LatchSetup.exe` and `install.ps1` to GitHub release assets.

- [ ] **Step 3: Update `README.md` and Documentation**
  Update installation instructions:
  - Windows: "Download and run `LatchSetup.exe` (auto-detects and installs latest version with 1 click) or run `irm https://latch.vinnovateit.com/install.ps1 | iex`."
  - Linux: "`curl -fsSL https://latch.vinnovateit.com/install.sh | sh` (now supports update, reinstall, and uninstall)."

---

### Task 6: Verification and End-to-End Testing

**Files:**
- Test scripts & dry runs

- [ ] **Step 1: Test `LatchSetup.exe` clean install flow**
- [ ] **Step 2: Test `LatchSetup.exe` upgrade flow**
- [ ] **Step 3: Test `LatchSetup.exe` up-to-date detection (Launch, Reinstall, Uninstall buttons)**
- [ ] **Step 4: Test `install.sh` flags (`--uninstall`, `--reinstall`, interactive menu)**
- [ ] **Step 5: Verify all existing tests pass (`./gradlew :core:desktopTest :desktop:smoke`)**
