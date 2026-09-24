#!/usr/bin/env bash
# Tests packaging/submit-winget.sh without GitHub.
#
# A fake `gh` on PATH answers each API call from a per-case route table and
# logs every call, so each case asserts both the outcome and exactly which
# mutations (POST/PUT/PATCH/DELETE) were made. An unrouted call fails the case.
# PR shapes come from testdata/winget/pull.json and pull-files.json, trimmed
# from a real submission (microsoft/winget-pkgs#439934).
#
# Usage: packaging/test-submit-winget.sh
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
script="$here/submit-winget.sh"
fixtures="$here/testdata/winget"
root=$(mktemp -d)
trap 'rm -rf "$root"' EXIT

UP="repos/microsoft/winget-pkgs"
FK="repos/vinnovateit/winget-pkgs"
SEARCH="search/issues?q=repo:microsoft/winget-pkgs+is:pr+is:open+in:title+VinnovateIT.LatchCLI&per_page=100"
SECRET="fake-winget-token-TESTONLY-5f3a9c"

passed=0
failed=0
case_dir=""

# --- fake gh ----------------------------------------------------------------

mkdir -p "$root/bin"
cat >"$root/bin/gh" <<'EOF'
#!/usr/bin/env bash
# Fake gh: `gh api --method M --include PATH [flags]` and `gh repo sync ...`.
printf '%s\n' "$*" >>"$FAKE_DIR/calls.log"
if [[ $1 == repo ]]; then
    exit "${FAKE_SYNC_EXIT:-0}"
fi
method=$3 path=$5
# Routes: "METHOD PATH STATUS BODYFILE [RATE-REMAINING]", consumed in order
# per METHOD+PATH, the last one repeating.
count_file="$FAKE_DIR/count.$(printf '%s %s' "$method" "$path" | md5sum | cut -c1-16)"
seen=$(cat "$count_file" 2>/dev/null || echo 0)
mapfile -t matches < <(awk -v m="$method" -v p="$path" '$1 == m && $2 == p' "$FAKE_DIR/routes")
if ((${#matches[@]} == 0)); then
    echo "UNROUTED $method $path" >>"$FAKE_DIR/unrouted.log"
    echo "gh: unrouted $method $path" >&2
    exit 1
fi
index=$((seen < ${#matches[@]} ? seen : ${#matches[@]} - 1))
echo $((seen + 1)) >"$count_file"
read -r _ _ status body rate <<<"${matches[$index]}"
if [[ $status == 000 ]]; then
    echo "error connecting to api.github.com" >&2
    exit 1
fi
printf 'HTTP/2.0 %s Fake\r\n' "$status"
printf 'X-Ratelimit-Remaining: %s\r\n' "${rate:-4999}"
printf '\r\n'
[[ $body != - ]] && cat "$body"
[[ $status == 2?? ]]
EOF
chmod +x "$root/bin/gh"

# --- fixture builders -------------------------------------------------------

# pull NUMBER VERSION [HEAD-REPO] [USER] [TITLE-VERSION]
pull() {
    local number=$1 version=$2 repo=${3:-vinnovateit/winget-pkgs} user=${4:-rugbedbugg} tv=${5:-$2}
    jq --argjson n "$number" --arg v "$version" --arg repo "$repo" --arg user "$user" --arg tv "$tv" '
        .number = $n | .url |= sub("[0-9]+$"; ($n | tostring)) | .html_url |= sub("[0-9]+$"; ($n | tostring))
        | .title = "New version: VinnovateIT.LatchCLI version \($tv)"
        | .user.login = $user
        | .head.ref = "latch-cli-\($v)" | .head.label = "\($repo | split("/")[0]):latch-cli-\($v)"
        | .head.repo.full_name = $repo' "$fixtures/pull.json"
}
files() {
    jq --arg v "$1" 'map(.filename |= sub("/LatchCLI/[^/]+/"; "/LatchCLI/\($v)/"))' "$fixtures/pull-files.json"
}
json() { # json NAME CONTENT -> path of a body file
    printf '%s' "$2" >"$case_dir/$1.json"
    echo "$case_dir/$1.json"
}
route() { echo "$*" >>"$case_dir/routes"; }

# Discovery routes for: upstream versions ("" for none), our fork branches
# with their open PR numbers ("1.4.1:439934 1.4.0:"), and search hits.
state() {
    local upstream=$1 branches=$2 search_numbers=$3
    if [[ -z $upstream ]]; then
        route GET "$UP/contents/manifests/v/VinnovateIT/LatchCLI" 404 "$(json nf '{"message":"Not Found"}')"
    else
        route GET "$UP/contents/manifests/v/VinnovateIT/LatchCLI" 200 "$(json contents "$(tr ' ' '\n' <<<"$upstream" | jq -R '{name: ., type: "dir"}' | jq -s .)")"
    fi
    local refs="[]" entry v n
    for entry in $branches; do
        v=${entry%%:*} n=${entry#*:}
        refs=$(jq --arg v "$v" '. + [{ref: "refs/heads/latch-cli-\($v)"}]' <<<"$refs")
        if [[ -n $n ]]; then
            route GET "$UP/pulls?state=open&head=vinnovateit:latch-cli-$v&per_page=100" 200 "$(json "open-$v" "[$(pull "$n" "$v")]")"
            route GET "$UP/pulls/$n" 200 "$(json "pull-$n" "$(pull "$n" "$v")")"
            route GET "$UP/pulls/$n/files?per_page=100" 200 "$(json "files-$n" "$(files "$v")")"
        else
            route GET "$UP/pulls?state=open&head=vinnovateit:latch-cli-$v&per_page=100" 200 "$(json empty '[]')"
        fi
    done
    route GET "$FK/git/matching-refs/heads/latch-cli-" 200 "$(json refs "$refs")"
    route GET "$SEARCH" 200 "$(json search "$(tr ' ' '\n' <<<"$search_numbers" | jq -R 'select(length > 0) | {number: tonumber}' | jq -s '{total_count: length, incomplete_results: false, items: .}')")"
}

# Routes for opening the PR for VERSION as NUMBER from a clean fork.
submission() {
    local version=$1 number=$2
    route GET "$UP" 200 "$(json upstream '{"default_branch":"master"}')"
    route GET "$FK/git/ref/heads/master" 200 "$(json base '{"object":{"sha":"abc123"}}')"
    route GET "$FK/git/ref/heads/latch-cli-$version" 404 "$(json nf '{"message":"Not Found"}')"
    route POST "$FK/git/refs" 201 "$(json created '{"ref":"refs/heads/x"}')"
    local name
    for name in VinnovateIT.LatchCLI.installer.yaml VinnovateIT.LatchCLI.locale.en-US.yaml VinnovateIT.LatchCLI.yaml; do
        route PUT "$FK/contents/manifests/v/VinnovateIT/LatchCLI/$version/$name" 201 "$(json put '{}')"
    done
    route POST "$UP/pulls" 201 "$(json "new-$number" "$(pull "$number" "$version" | jq '{number, html_url}')")"
}

supersession() { # supersession NUMBER VERSION
    route POST "$UP/issues/$1/comments" 201 "$(json comment '{}')"
    route PATCH "$UP/pulls/$1" 200 "$(json "closed-$1" "$(pull "$1" "$2" | jq '.state = "closed"')")"
    route DELETE "$FK/git/refs/heads/latch-cli-$2" 204 -
}

# --- running and asserting --------------------------------------------------

new_case() {
    case_dir="$root/case-$((passed + failed))"
    mkdir -p "$case_dir/manifests"
    : >"$case_dir/routes"
    : >"$case_dir/calls.log"
    : >"$case_dir/out"
}

# run VERSION [--dry-run] -- runs the script, leaving status/output behind.
run() {
    local version=$1
    shift
    local name
    for name in VinnovateIT.LatchCLI.installer.yaml VinnovateIT.LatchCLI.locale.en-US.yaml VinnovateIT.LatchCLI.yaml; do
        printf 'PackageIdentifier: VinnovateIT.LatchCLI\nPackageVersion: %s\n' "$version" >"$case_dir/manifests/$name"
    done
    status=0
    PATH="$root/bin:$PATH" FAKE_DIR="$case_dir" GH_TOKEN="$SECRET" \
        "$script" "$@" "$version" "$case_dir/manifests" >"$case_dir/out" 2>&1 || status=$?
}

mutations() { grep -E -- '--method (POST|PUT|PATCH|DELETE) ' "$case_dir/calls.log" | awk '{print $3, $5}' || true; }

check() { # check NAME CONDITION-COMMAND...
    local name=$1
    shift
    if "$@"; then return 0; fi
    echo "    assertion failed: $name" >&2
    return 1
}

finish() { # finish CASE-NAME RESULT
    if [[ $2 == 0 ]] && [[ ! -s $case_dir/unrouted.log ]] &&
        ! grep -qF "$SECRET" "$case_dir/out" "$case_dir/calls.log"; then
        passed=$((passed + 1))
        echo "ok   $1"
    else
        failed=$((failed + 1))
        echo "FAIL $1"
        [[ -s $case_dir/unrouted.log ]] && sed 's/^/    /' "$case_dir/unrouted.log"
        grep -qF "$SECRET" "$case_dir/out" "$case_dir/calls.log" && echo "    the token appeared in output"
        sed 's/^/    | /' "$case_dir/out"
    fi
}

expect_exit() { [[ $status == "$1" ]]; }
not() { ! "$@"; }
says() { grep -qF -- "$1" "$case_dir/out"; }
no_mutations() { [[ -z $(mutations) ]]; }
mutated() { mutations | grep -qx -- "$1"; }
mutation_count() { [[ $(mutations | grep -cx -- "$1") == "$2" ]]; }
before() { # before A B -- mutation A happened before mutation B
    local a b
    a=$(mutations | grep -nx -- "$1" | head -1 | cut -d: -f1)
    b=$(mutations | grep -nx -- "$2" | head -1 | cut -d: -f1)
    [[ -n $a && -n $b && $a -lt $b ]]
}

scenario() { # scenario NAME -- runs the function of the same name
    new_case
    local result=0
    "$1" || result=1
    finish "$1" "$result"
}

# --- cases ------------------------------------------------------------------

version_ordering() {
    # shellcheck source=packaging/submit-winget.sh
    (source "$script"
        check "1.4.2 < 1.5.0" [ "$(version_cmp 1.4.2 1.5.0)" = -1 ] &&
        check "1.9.0 < 1.10.0" [ "$(version_cmp 1.9.0 1.10.0)" = -1 ] &&
        check "1.10.0 > 1.9.0" [ "$(version_cmp 1.10.0 1.9.0)" = 1 ] &&
        check "2.0.0 > 1.99.99" [ "$(version_cmp 2.0.0 1.99.99)" = 1 ] &&
        check "1.4.2 = 1.4.2" [ "$(version_cmp 1.4.2 1.4.2)" = 0 ] &&
        check "1.4.10 > 1.4.9" [ "$(version_cmp 1.4.10 1.4.9)" = 1 ] &&
        check "rejects 1.4" bash -c "source '$script'; ! version_cmp 1.4 1.4.0" &&
        check "rejects 01.4.2" bash -c "source '$script'; ! is_version 01.4.2" &&
        check "rejects 1.4.2-beta" bash -c "source '$script'; ! is_version 1.4.2-beta")
}

no_pending_submission_submits() {
    state "" "" ""
    submission 1.4.2 500001
    run 1.4.2
    check "exit 0" expect_exit 0 &&
        check "reports not published" says "upstream:  not yet published" &&
        check "one PR opened" mutation_count "POST $UP/pulls" 1 &&
        check "three manifests" [ "$(mutations | grep -c '^PUT ')" = 3 ] &&
        check "nothing closed" not mutated "PATCH $UP/pulls/500001"
}

same_version_pending_is_noop() {
    state "" "1.4.2:440281" "440281"
    run 1.4.2
    check "exit 0" expect_exit 0 &&
        check "reports existing PR" says "1.4.2 already has open PR #440281" &&
        check "no mutations" no_mutations
}

older_owned_pending_is_superseded() {
    state "" "1.4.1:439934" "439934"
    submission 1.4.2 500002
    supersession 439934 1.4.1
    run 1.4.2
    check "exit 0" expect_exit 0 &&
        check "reports pending" says "pending:   1.4.1 (#439934)" &&
        check "new PR opened" mutation_count "POST $UP/pulls" 1 &&
        check "old PR commented" mutated "POST $UP/issues/439934/comments" &&
        check "comment text" grep -q "body=Superseded by VinnovateIT.LatchCLI 1.4.2 (#500002)." "$case_dir/calls.log" &&
        check "old PR closed" mutated "PATCH $UP/pulls/439934" &&
        check "old branch deleted" mutated "DELETE $FK/git/refs/heads/latch-cli-1.4.1" &&
        check "new PR exists before the old one closes" before "POST $UP/pulls" "PATCH $UP/pulls/439934"
}

newer_owned_pending_is_noop() {
    state "" "1.4.2:440281" "440281"
    run 1.4.1
    check "exit 0" expect_exit 0 &&
        check "reports newer pending" says "1.4.2 is already pending as #440281, newer than 1.4.1" &&
        check "no mutations" no_mutations
}

older_foreign_pr_is_never_touched() {
    state "" "" "600001"
    route GET "$UP/pulls/600001" 200 "$(json foreign "$(pull 600001 1.4.0 someone/winget-pkgs someone)")"
    run 1.4.2
    check "fails" expect_exit 1 &&
        check "names the foreign PR" says "#600001 by someone from someone/winget-pkgs" &&
        check "no mutations" no_mutations
}

newer_foreign_pr_is_noop() {
    state "" "" "600002"
    route GET "$UP/pulls/600002" 200 "$(json foreign "$(pull 600002 1.5.0 someone/winget-pkgs someone)")"
    run 1.4.2
    check "exit 0" expect_exit 0 &&
        check "defers to it" says "#600002 by someone already submits 1.5.0" &&
        check "no mutations" no_mutations
}

unprovable_owned_pr_is_never_touched() {
    state "" "1.4.1:439934" "439934"
    # Same fork and branch, but the PR also changes a file outside the package.
    json "files-439934" "$(files 1.4.1 | jq '. + [{filename: "manifests/o/Other/App/1.0/Other.App.yaml"}]')" >/dev/null
    run 1.4.2
    check "fails" expect_exit 1 &&
        check "explains" says "does not look like a submission from this automation" &&
        check "no mutations" no_mutations
}

malformed_response_fails_safely() {
    route GET "$UP/contents/manifests/v/VinnovateIT/LatchCLI" 404 "$(json nf '{"message":"Not Found"}')"
    route GET "$FK/git/matching-refs/heads/latch-cli-" 200 "$(json refs '<html>unicorn</html>')"
    run 1.4.2
    check "fails" expect_exit 1 &&
        check "explains" says "unexpected response shape" &&
        check "no mutations" no_mutations
}

server_error_fails_safely() {
    state "" "" ""
    : >"$case_dir/routes"
    route GET "$UP/contents/manifests/v/VinnovateIT/LatchCLI" 404 "$(json nf '{"message":"Not Found"}')"
    route GET "$FK/git/matching-refs/heads/latch-cli-" 200 "$(json refs '[]')"
    route GET "$SEARCH" 503 "$(json err '{"message":"Service Unavailable"}')"
    run 1.4.2
    check "fails" expect_exit 1 &&
        check "classifies" says "HTTP 503, GitHub server error" &&
        check "no mutations" no_mutations
}

rate_limit_fails_safely() {
    route GET "$UP/contents/manifests/v/VinnovateIT/LatchCLI" 403 "$(json err '{"message":"API rate limit exceeded"}')" 0
    run 1.4.2
    check "fails" expect_exit 1 && check "classifies" says "rate limited" && check "no mutations" no_mutations
}

auth_failure_fails_safely() {
    route GET "$UP/contents/manifests/v/VinnovateIT/LatchCLI" 401 "$(json err '{"message":"Bad credentials"}')"
    run 1.4.2
    check "fails" expect_exit 1 && check "classifies" says "authentication failed" && check "no mutations" no_mutations
}

network_failure_fails_safely() {
    route GET "$UP/contents/manifests/v/VinnovateIT/LatchCLI" 000 -
    run 1.4.2
    check "fails" expect_exit 1 && check "classifies" says "no response from GitHub" && check "no mutations" no_mutations
}

failure_while_opening_pr_is_visible() {
    state "" "" ""
    submission 1.4.2 500003
    route POST "$UP/pulls" 403 "$(json err '{"message":"Resource not accessible by personal access token"}')" 4000
    : >"$case_dir/routes.tmp"
    grep -v "^POST $UP/pulls 201" "$case_dir/routes" >"$case_dir/routes.tmp" && mv "$case_dir/routes.tmp" "$case_dir/routes"
    run 1.4.2
    check "fails" expect_exit 1 && check "classifies" says "forbidden; the token may lack public_repo scope"
}

rerun_after_success_is_noop() {
    # State after the supersede case: 1.4.2 open, 1.4.1 closed and its branch gone.
    state "" "1.4.2:500002" "500002"
    run 1.4.2
    check "exit 0" expect_exit 0 && check "no mutations" no_mutations
}

rerun_after_partial_supersede_converges() {
    # 1.4.2 was opened but the job died before closing 1.4.1.
    state "" "1.4.1:439934 1.4.2:500002" "439934 500002"
    supersession 439934 1.4.1
    run 1.4.2
    check "exit 0" expect_exit 0 &&
        check "keeps the new PR" says "keep #500002 for 1.4.2" &&
        check "no second PR" mutation_count "POST $UP/pulls" 0 &&
        check "closes the old one" mutated "PATCH $UP/pulls/439934"
}

pr_created_by_a_racing_run_is_reused() {
    state "" "" ""
    submission 1.4.2 500004
    grep -v "^POST $UP/pulls 201" "$case_dir/routes" >"$case_dir/routes.tmp" && mv "$case_dir/routes.tmp" "$case_dir/routes"
    route POST "$UP/pulls" 422 "$(json dup '{"message":"Validation Failed","errors":[{"message":"A pull request already exists for vinnovateit:latch-cli-1.4.2."}]}')"
    route GET "$UP/pulls?state=open&head=vinnovateit:latch-cli-1.4.2&per_page=100" 200 "$(json open-new "[$(pull 500004 1.4.2)]")"
    run 1.4.2
    check "exit 0" expect_exit 0 && check "reuses it" says "PR #500004 for 1.4.2 already existed" &&
        check "one attempt" mutation_count "POST $UP/pulls" 1
}

stale_branches_without_prs_are_rebuilt_and_removed() {
    state "" "1.4.0: 1.4.2:" ""
    submission 1.4.2 500005
    # latch-cli-1.4.2 exists (a failed earlier run) with no open PR.
    grep -v "^GET $FK/git/ref/heads/latch-cli-1.4.2 404" "$case_dir/routes" >"$case_dir/routes.tmp" && mv "$case_dir/routes.tmp" "$case_dir/routes"
    route GET "$FK/git/ref/heads/latch-cli-1.4.2" 200 "$(json ref '{"object":{"sha":"old"}}')"
    route DELETE "$FK/git/refs/heads/latch-cli-1.4.2" 204 -
    route DELETE "$FK/git/refs/heads/latch-cli-1.4.0" 204 -
    run 1.4.2
    check "exit 0" expect_exit 0 &&
        check "stale 1.4.2 branch rebuilt" before "DELETE $FK/git/refs/heads/latch-cli-1.4.2" "POST $FK/git/refs" &&
        check "PR opened" mutation_count "POST $UP/pulls" 1 &&
        check "stale 1.4.0 branch removed" mutated "DELETE $FK/git/refs/heads/latch-cli-1.4.0"
}

upstream_has_version_is_noop() {
    state "1.4.1 1.4.2" "" ""
    run 1.4.2
    check "exit 0" expect_exit 0 && check "reports" says "1.4.2 is already published" && check "no mutations" no_mutations
}

upstream_newer_is_noop() {
    state "1.10.0" "" ""
    run 1.9.0
    check "exit 0" expect_exit 0 &&
        check "refuses downgrade" says "already publishes 1.10.0, newer than 1.9.0" &&
        check "no mutations" no_mutations
}

dry_run_changes_nothing() {
    state "" "1.4.0: 1.4.1:439934" "439934"
    run 1.4.2 --dry-run
    check "exit 0" expect_exit 0 &&
        check "plans the submission" says "[dry-run] would open a PR for 1.4.2" &&
        check "plans the supersede" says "[dry-run] would comment on and close #439934 (1.4.1)" &&
        check "no mutations" no_mutations
}

for case_name in \
    version_ordering \
    no_pending_submission_submits \
    same_version_pending_is_noop \
    older_owned_pending_is_superseded \
    newer_owned_pending_is_noop \
    older_foreign_pr_is_never_touched \
    newer_foreign_pr_is_noop \
    unprovable_owned_pr_is_never_touched \
    malformed_response_fails_safely \
    server_error_fails_safely \
    rate_limit_fails_safely \
    auth_failure_fails_safely \
    network_failure_fails_safely \
    failure_while_opening_pr_is_visible \
    rerun_after_success_is_noop \
    rerun_after_partial_supersede_converges \
    pr_created_by_a_racing_run_is_reused \
    stale_branches_without_prs_are_rebuilt_and_removed \
    upstream_has_version_is_noop \
    upstream_newer_is_noop \
    dry_run_changes_nothing; do
    scenario "$case_name"
done

echo "$passed passed, $failed failed"
((failed == 0))
