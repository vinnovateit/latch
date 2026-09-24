#!/usr/bin/env bash
# Keeps exactly one pending winget-pkgs submission for VinnovateIT.LatchCLI:
# the newest released version. Run once per release; safe to re-run.
#
#   discover   what upstream has published, which of our fork branches have
#              open PRs, and any other open PR for this package
#   classify   decide whether this release needs a submission at all
#   submit     open the PR for this version, unless one is already open
#   supersede  close our own older pending PRs, now replaced by this one
#
# Expected no-op states (this version, or a newer one, is already pending or
# published) exit 0. Anything the script cannot prove safe -- an open PR for
# the package it does not own, an older PR that fails the ownership checks,
# an unexpected API response -- exits non-zero without creating a competing PR.
#
# Everything goes through the GitHub API rather than a clone: winget-pkgs
# holds roughly a million files and a submission adds three. `gh api` handles
# authentication, so the token never appears on a command line or in output.
set -euo pipefail

PACKAGE_ID="VinnovateIT.LatchCLI"
UPSTREAM="microsoft/winget-pkgs"
FORK="vinnovateit/winget-pkgs"
FORK_OWNER="vinnovateit"
PACKAGE_ROOT="manifests/v/VinnovateIT/LatchCLI"
BRANCH_PREFIX="latch-cli-"

usage() {
    echo "Usage: $0 [--dry-run] <version> <manifest-dir>" >&2
    echo "  manifest-dir holds the three $PACKAGE_ID.*.yaml files" >&2
    echo "  --dry-run discovers and prints the plan without changing anything" >&2
    exit 2
}

log() { echo "$*"; }
die() {
    echo "ERROR: $*" >&2
    exit 1
}

# --- Versions ---------------------------------------------------------------

# Latch releases are MAJOR.MINOR.PATCH; anything else is rejected rather than
# ordered by guesswork.
is_version() {
    [[ $1 =~ ^(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})$ ]]
}

# Prints -1, 0 or 1 for $1 <, =, > $2, comparing each part as a number, so
# 1.9.0 < 1.10.0. Returns 2 if either is not a version.
version_cmp() {
    is_version "$1" && is_version "$2" || return 2
    local -a a b
    IFS=. read -r -a a <<<"$1"
    IFS=. read -r -a b <<<"$2"
    local i
    for i in 0 1 2; do
        if ((a[i] < b[i])); then echo -1; return 0; fi
        if ((a[i] > b[i])); then echo 1; return 0; fi
    done
    echo 0
}

version_lt() { [[ $(version_cmp "$1" "$2") == -1 ]]; }
version_gt() { [[ $(version_cmp "$1" "$2") == 1 ]]; }

# --- GitHub API -------------------------------------------------------------

API_STATUS=""
API_BODY=""
API_RATE_REMAINING=""

# api METHOD PATH [gh api field flags...]
# Leaves the HTTP status in API_STATUS (000 when no response arrived) and the
# response body in the file named by API_BODY. Never fails by itself; callers
# state which statuses they accept with expect.
api() {
    local method=$1 path=$2
    shift 2
    local raw="$WORK/response"
    gh api --method "$method" --include "$path" "$@" >"$raw" 2>"$WORK/stderr" || true
    API_STATUS=$(head -n 1 "$raw" | awk '{print $2}')
    [[ $API_STATUS =~ ^[0-9]{3}$ ]] || API_STATUS=000
    API_RATE_REMAINING=$(grep -i -m 1 '^x-ratelimit-remaining:' "$raw" | awk '{print $2}' | tr -d '\r' || true)
    API_BODY="$WORK/body.json"
    awk 'in_body { print; next } /^\r?$/ { in_body = 1 }' "$raw" >"$API_BODY"
}

# expect CONTEXT STATUS... -- fails with a classified reason unless the last
# call returned one of the given statuses.
expect() {
    local context=$1
    shift
    local status
    for status in "$@"; do
        [[ $API_STATUS == "$status" ]] && return 0
    done
    local message
    message=$(jq -r 'if type == "object" then (.message // empty) else empty end' "$API_BODY" 2>/dev/null || true)
    local reason
    case $API_STATUS in
        000) reason="no response from GitHub (network or gh failure: $(head -c 200 "$WORK/stderr"))" ;;
        401) reason="authentication failed; check the WINGET_TOKEN secret" ;;
        403 | 429)
            if [[ $API_STATUS == 429 || $API_RATE_REMAINING == 0 ]]; then
                reason="rate limited by GitHub; re-run the job later"
            else
                reason="forbidden; the token may lack public_repo scope"
            fi
            ;;
        404) reason="not found" ;;
        409) reason="conflict" ;;
        422) reason="rejected as invalid" ;;
        5??) reason="GitHub server error; re-run the job later" ;;
        *) reason="unexpected HTTP status" ;;
    esac
    die "$context: HTTP $API_STATUS, $reason${message:+ ($message)}"
}

# require_json CONTEXT JQ-FILTER -- the last body must satisfy the filter.
require_json() {
    jq -e "$2" "$API_BODY" >/dev/null 2>&1 || die "$1: unexpected response shape from GitHub"
}

# --- Discovery --------------------------------------------------------------

upstream_latest=""          # newest version already published upstream
declare -a owned_versions=() # our fork branches, as versions
declare -A owned_pr=()       # version -> open PR number, for our branches
declare -A foreign_pr=()     # PR number -> "version|author" for PRs we do not own

discover_upstream() {
    api GET "repos/$UPSTREAM/contents/$PACKAGE_ROOT"
    if [[ $API_STATUS == 404 ]]; then
        return 0
    fi
    expect "Reading published $PACKAGE_ID versions" 200
    require_json "Reading published $PACKAGE_ID versions" 'type == "array"'
    local name
    while IFS= read -r name; do
        is_version "$name" || { log "  note: ignoring upstream directory '$name' (not a version)"; continue; }
        if [[ -z $upstream_latest ]] || version_gt "$name" "$upstream_latest"; then
            upstream_latest=$name
        fi
    done < <(jq -r '.[] | select(.type == "dir") | .name' "$API_BODY")
}

open_pr_for_branch() {
    api GET "repos/$UPSTREAM/pulls?state=open&head=$FORK_OWNER:$1&per_page=100"
    expect "Looking up the open PR for $FORK:$1" 200
    require_json "Looking up the open PR for $FORK:$1" 'type == "array" and length <= 1 and all(.[]; (.number | type) == "number")'
    jq -r '.[0].number // empty' "$API_BODY"
}

discover_owned() {
    api GET "repos/$FORK/git/matching-refs/heads/$BRANCH_PREFIX"
    expect "Listing $FORK submission branches" 200
    require_json "Listing $FORK submission branches" 'type == "array" and all(.[]; (.ref | type) == "string")'
    local ref version number
    while IFS= read -r ref; do
        version=${ref#refs/heads/"$BRANCH_PREFIX"}
        is_version "$version" || { log "  note: ignoring fork branch ${ref#refs/heads/} (not a submission branch)"; continue; }
        owned_versions+=("$version")
    done < <(jq -r '.[].ref' "$API_BODY")
    for version in ${owned_versions[@]+"${owned_versions[@]}"}; do
        number=$(open_pr_for_branch "$BRANCH_PREFIX$version")
        [[ -n $number ]] && owned_pr[$version]=$number
    done
    return 0
}

# Open PRs for the package from anyone else. Search finds PRs by title; ours
# are already known from the fork, so only the rest are recorded.
discover_foreign() {
    api GET "search/issues?q=repo:$UPSTREAM+is:pr+is:open+in:title+$PACKAGE_ID&per_page=100"
    expect "Searching open $PACKAGE_ID PRs" 200
    require_json "Searching open $PACKAGE_ID PRs" 'type == "object" and (.items | type) == "array"'
    if jq -e '.incomplete_results == true' "$API_BODY" >/dev/null; then
        die "Searching open $PACKAGE_ID PRs: GitHub returned incomplete results; re-run the job later"
    fi
    local -A ours=()
    local version number
    for version in "${!owned_pr[@]}"; do ours[${owned_pr[$version]}]=1; done
    local -a numbers
    mapfile -t numbers < <(jq -r '.items[].number' "$API_BODY")
    for number in ${numbers[@]+"${numbers[@]}"}; do
        [[ -n ${ours[$number]:-} ]] && continue
        api GET "repos/$UPSTREAM/pulls/$number"
        expect "Reading PR #$number" 200
        require_json "Reading PR #$number" '(.title | type) == "string" and (.state | type) == "string"'
        [[ $(jq -r .state "$API_BODY") == open ]] || continue
        # Titles can mention the ID without being a submission for it.
        jq -e --arg id "$PACKAGE_ID" '.title | test("(^|[^A-Za-z0-9.])" + ($id | gsub("\\."; "\\.")) + "([^A-Za-z0-9.]|$)")' "$API_BODY" >/dev/null || continue
        version=$(jq -r --arg id "$PACKAGE_ID" '.title | capture("\($id | gsub("\\."; "\\.")) version (?<v>[0-9][0-9A-Za-z.+-]*)$").v' "$API_BODY" 2>/dev/null || true)
        version=${version:-unknown}
        foreign_pr[$number]="$version|$(jq -r '.user.login // "unknown"' "$API_BODY")|$(jq -r '.head.repo.full_name // "deleted fork"' "$API_BODY")"
    done
    return 0
}

# --- Ownership --------------------------------------------------------------

# An older PR is closed only when every one of these holds; any doubt leaves it
# alone and stops the run before a competing PR is opened.
verify_owned() {
    local version=$1 number=$2
    api GET "repos/$UPSTREAM/pulls/$number"
    expect "Reading PR #$number" 200
    jq -e \
        --arg upstream "$UPSTREAM" --arg fork "$FORK" \
        --arg branch "$BRANCH_PREFIX$version" \
        --arg title "New version: $PACKAGE_ID version $version" '
        .state == "open" and .merged != true
        and .base.repo.full_name == $upstream
        and .head.repo.full_name == $fork
        and .head.ref == $branch
        and .title == $title' "$API_BODY" >/dev/null ||
        return 1
    api GET "repos/$UPSTREAM/pulls/$number/files?per_page=100"
    expect "Reading the files of PR #$number" 200
    jq -e --arg dir "$PACKAGE_ROOT/$version/" '
        type == "array" and length > 0
        and all(.[]; (.filename | type) == "string" and (.filename | startswith($dir)))' "$API_BODY" >/dev/null
}

# --- Mutations --------------------------------------------------------------

DRY_RUN=0

mutating() {
    if ((DRY_RUN)); then
        log "  [dry-run] would $*"
        return 1
    fi
    return 0
}

submit() {
    local version=$1 manifest_dir=$2
    local branch="$BRANCH_PREFIX$version"
    local -a manifests
    shopt -s nullglob
    manifests=("$manifest_dir"/"$PACKAGE_ID"*.yaml)
    shopt -u nullglob
    ((${#manifests[@]} > 0)) || die "No $PACKAGE_ID manifests found in $manifest_dir"
    local manifest
    for manifest in "${manifests[@]}"; do
        grep -qx "PackageVersion: $version" "$manifest" ||
            die "$(basename "$manifest") is not a $version manifest"
    done

    if ! mutating "open a PR for $version from $FORK:$branch"; then
        new_pr="(dry-run)"
        return 0
    fi

    api GET "repos/$UPSTREAM"
    expect "Reading $UPSTREAM" 200
    require_json "Reading $UPSTREAM" '(.default_branch | type) == "string"'
    local default_branch
    default_branch=$(jq -r .default_branch "$API_BODY")

    # Branching from a synced default branch keeps the PR to our three files.
    # A token that cannot sync still produces a valid PR, so this only warns.
    if ! gh repo sync "$FORK" --source "$UPSTREAM" --branch "$default_branch" --force >/dev/null 2>"$WORK/stderr"; then
        echo "Warning: could not sync $FORK with $UPSTREAM; branching from the fork as it is" >&2
    fi
    api GET "repos/$FORK/git/ref/heads/$default_branch"
    expect "Reading $FORK $default_branch" 200
    require_json "Reading $FORK $default_branch" '(.object.sha | type) == "string"'
    local base_sha
    base_sha=$(jq -r .object.sha "$API_BODY")

    # A leftover branch with no open PR (a failed earlier run, or a PR that
    # was closed) is rebuilt from the current base. Checked again right before
    # deleting, since deleting a PR's branch would close that PR.
    api GET "repos/$FORK/git/ref/heads/$branch"
    if [[ $API_STATUS == 200 ]]; then
        local existing
        existing=$(open_pr_for_branch "$branch")
        [[ -z $existing ]] ||
            die "$FORK:$branch gained open PR #$existing while this job ran; re-run the job"
        api DELETE "repos/$FORK/git/refs/heads/$branch"
        expect "Deleting the stale branch $FORK:$branch" 204
        log "  deleted stale branch $FORK:$branch (no open PR)"
    else
        expect "Checking for $FORK:$branch" 404
    fi

    api POST "repos/$FORK/git/refs" -f "ref=refs/heads/$branch" -f "sha=$base_sha"
    expect "Creating $FORK:$branch" 201
    for manifest in "${manifests[@]}"; do
        api PUT "repos/$FORK/contents/$PACKAGE_ROOT/$version/$(basename "$manifest")" \
            -f "message=Add $PACKAGE_ID $version ($(basename "$manifest"))" \
            -f "content=$(base64 -w0 <"$manifest")" \
            -f "branch=$branch"
        expect "Adding $(basename "$manifest")" 201 200
    done

    api POST "repos/$UPSTREAM/pulls" \
        -f "title=New version: $PACKAGE_ID version $version" \
        -f "head=$FORK_OWNER:$branch" \
        -f "base=$default_branch" \
        -f "body=Automated submission from the [Latch release workflow](https://github.com/vinnovateit/latch/blob/main/.github/workflows/release.yml).

- Installer: https://github.com/vinnovateit/latch/releases/download/v$version/latch-cli-$version-windows-x64.zip
- Release: https://github.com/vinnovateit/latch/releases/tag/v$version

Checksums in the manifest are generated from the published release assets."
    if [[ $API_STATUS == 422 ]] && jq -e '(.errors // []) | any(.message? // "" | test("already exists"))' "$API_BODY" >/dev/null 2>&1; then
        new_pr=$(open_pr_for_branch "$branch")
        [[ -n $new_pr ]] || die "GitHub reports a PR for $FORK:$branch already exists, but none is open"
        log "  PR #$new_pr for $version already existed"
        return 0
    fi
    expect "Opening the PR for $version" 201
    require_json "Opening the PR for $version" '(.number | type) == "number"'
    new_pr=$(jq -r .number "$API_BODY")
    log "  opened $(jq -r '.html_url // empty' "$API_BODY")"
}

# Closes one of our own older PRs that the current one replaces, then removes
# its branch. Only called for PRs that passed verify_owned.
supersede() {
    local version=$1 number=$2 current=$3 current_pr=$4
    mutating "comment on and close #$number ($version), then delete $FORK:$BRANCH_PREFIX$version" || return 0
    api POST "repos/$UPSTREAM/issues/$number/comments" \
        -f "body=Superseded by $PACKAGE_ID $current (#$current_pr)."
    expect "Commenting on #$number" 201
    api PATCH "repos/$UPSTREAM/pulls/$number" -f state=closed
    expect "Closing #$number" 200
    require_json "Closing #$number" '.state == "closed"'
    log "  closed #$number ($version)"
    delete_branch "$version"
}

delete_branch() {
    local branch="$BRANCH_PREFIX$1"
    api DELETE "repos/$FORK/git/refs/heads/$branch"
    case $API_STATUS in
        204) log "  deleted $FORK:$branch" ;;
        404 | 422) log "  $FORK:$branch was already gone" ;;
        *) expect "Deleting $FORK:$branch" 204 ;;
    esac
}

# --- Main -------------------------------------------------------------------

new_pr=""

main() {
    if [[ ${1:-} == --dry-run ]]; then
        DRY_RUN=1
        shift
    fi
    [[ $# -eq 2 ]] || usage
    local version=$1 manifest_dir=$2
    is_version "$version" || die "'$version' is not a MAJOR.MINOR.PATCH version"
    [[ -n ${GH_TOKEN:-} ]] || die "GH_TOKEN must hold a token with public_repo scope"
    command -v jq >/dev/null || die "jq is required"

    WORK=$(mktemp -d)
    trap 'rm -rf "$WORK"' EXIT

    discover_upstream
    discover_owned
    discover_foreign

    local v n author head pending="" others=""
    for v in ${owned_versions[@]+"${owned_versions[@]}"}; do
        [[ -n ${owned_pr[$v]:-} ]] && pending+="${pending:+, }$v (#${owned_pr[$v]})"
    done
    for n in "${!foreign_pr[@]}"; do
        IFS='|' read -r v author head <<<"${foreign_pr[$n]}"
        others+="${others:+, }$v (#$n by $author from $head)"
    done
    log "WinGet state for $PACKAGE_ID:"
    log "  upstream:  ${upstream_latest:-not yet published}"
    log "  pending:   ${pending:-none}"
    [[ -n $others ]] && log "  others:    $others"
    log "  requested: $version"
    log "Action:"

    # Already published, or superseded upstream.
    if [[ -n $upstream_latest ]] && ! version_lt "$upstream_latest" "$version"; then
        if [[ $upstream_latest == "$version" ]]; then
            log "  none: $version is already published in $UPSTREAM"
        else
            log "  none: $UPSTREAM already publishes $upstream_latest, newer than $version"
        fi
        return 0
    fi

    # Someone else's submission: never compete with it, never touch it.
    for n in "${!foreign_pr[@]}"; do
        IFS='|' read -r v author head <<<"${foreign_pr[$n]}"
        if is_version "$v" && ! version_lt "$v" "$version"; then
            log "  none: #$n by $author already submits $v"
            return 0
        fi
    done
    if [[ -n $others ]]; then
        die "open $PACKAGE_ID PRs not created by this automation: $others. Not opening a competing PR; resolve them manually, then re-run this job."
    fi

    # Our own newer or identical submission.
    local -a older=()
    for v in ${owned_versions[@]+"${owned_versions[@]}"}; do
        [[ -n ${owned_pr[$v]:-} ]] || continue
        if version_gt "$v" "$version"; then
            log "  none: $v is already pending as #${owned_pr[$v]}, newer than $version"
            return 0
        fi
        version_lt "$v" "$version" && older+=("$v")
    done

    # Prove every older PR is ours before creating anything, so a failed proof
    # never leaves a second PR open beside it.
    for v in ${older[@]+"${older[@]}"}; do
        verify_owned "$v" "${owned_pr[$v]}" ||
            die "#${owned_pr[$v]} ($v) is on $FORK:$BRANCH_PREFIX$v but does not look like a submission from this automation; not touching it or opening a competing PR."
    done

    if [[ -n ${owned_pr[$version]:-} ]]; then
        new_pr=${owned_pr[$version]}
        if ((${#older[@]} == 0)); then
            log "  none: $version already has open PR #$new_pr"
            return 0
        fi
        log "  keep #$new_pr for $version"
    else
        log "  submit $version"
        submit "$version" "$manifest_dir"
    fi

    for v in ${older[@]+"${older[@]}"}; do
        log "  supersede our $v submission #${owned_pr[$v]}"
        supersede "$v" "${owned_pr[$v]}" "$version" "$new_pr"
    done

    # Branches of ours for older versions whose PRs are no longer open.
    for v in ${owned_versions[@]+"${owned_versions[@]}"}; do
        if [[ -z ${owned_pr[$v]:-} ]] && version_lt "$v" "$version"; then
            log "  remove stale branch $FORK:$BRANCH_PREFIX$v (no open PR)"
            mutating "delete $FORK:$BRANCH_PREFIX$v" && delete_branch "$v"
        fi
    done
    return 0
}

if [[ ${BASH_SOURCE[0]} == "$0" ]]; then
    main "$@"
fi
