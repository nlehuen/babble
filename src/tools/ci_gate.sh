#!/usr/bin/env bash
# Local parallel CI gate aligned with .github/workflows/maven.yml.
#
# Instead of GitHub Actions' distributed 6-shard fan-out, this script builds
# once and fans out all independent CI test jobs in parallel across the local
# machine's CPU cores and RAM (with bounded per-JVM JUnit parallelism and heap
# to avoid thread oversubscription), then runs the 6-scope JaCoCo coverage and
# 4-way behavioral parity gate.
#
# On underpowered hosts (<= 2 CPUs), a full CI gate automatically delegates to
# GitHub Actions via `gh workflow run maven.yml --ref <branch>` and watches the
# run with `gh run watch` (pass --local to force local execution, or --github
# to delegate explicitly from any host).
#
# Usage:
#   ./src/tools/ci_gate.sh [options]
#
# Options:
#   --container          Run inside the CI-parity container (JDK 21 + Maven 3.9 + Node 22)
#                        using podman (preferred) or docker, building/caching babble-ci:jdk21-node22
#                        [default].
#   --host               Bypass the container and run natively on the host toolchain (--no-container).
#   --github             Delegate the full CI gate to GitHub Actions (`gh workflow run` + `gh run watch`),
#                        even on hosts with > 2 CPUs (--remote).
#   --local              Force running the full CI gate locally even on hosts with <= 2 CPUs (--force-local).
#   --no-watch           When delegating to GitHub Actions, return after dispatching without watching.
#   --workflow <file>    GitHub Actions workflow to dispatch when delegating (default: maven.yml).
#   --ref <branch>       Git branch/ref to dispatch when delegating (default: current branch).
#   --rebuild-image      Force rebuilding the CI container image.
#   --fast               Skip JaCoCo instrumentation/coverage report (still runs all 4 unit modes,
#                        4 JVM batest modes at -PO2, and Wasm 3.0 batest in parallel).
#   --batest-filter <pat> Run only 4-mode JVM + Wasm batest matching <pat> (no coverage gate).
#   --unit-only          Run only the 4 Java unit-test modes in parallel (skip batest).
#   --batest-only        Run only the 4-mode JVM + Wasm batest and coverage gate (skip unit tests).
#   --skip-wasm          Skip WasmBatestTestCase and WasmCompilerTestCase (useful on host Node != 22).
#   --protect-batest     Fail if src/test/batest has any working-tree or branch diff vs origin/master.
#   --argon, --raytracer Also compile and render examples/raytracer/raytracer_ppm.ba and check
#                        byte-for-byte identity against examples/raytracer/assets/scene.ppm.
#   --format-apply       Run `mvn spotless:apply` before the build gate.
#   --jobs <N>           Max concurrent Maven test JVMs (default: auto from CPU count, up to 8).
#   --threads <T>        JUnit Jupiter threads per test JVM (default: auto = CPUS / jobs).
#   --heap <MB>          Max heap (-Xmx) per test JVM in MiB (default: auto from RAM, 768..2048).
#   --quiet-report       Do not print the full batest coverage report on success.
#   -h, --help           Show this help message.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
cd "${REPO_ROOT}"

# ------------------------------------------------------------------ defaults
USE_CONTAINER=1
DELEGATE_GITHUB=0
FORCE_LOCAL=0
GH_WATCH=1
GH_WORKFLOW="${BABBLE_CI_WORKFLOW:-maven.yml}"
GH_REF=""
GH_WATCH_INTERVAL="${BABBLE_CI_WATCH_INTERVAL:-15}"
MIN_LOCAL_CPUS="${BABBLE_CI_MIN_LOCAL_CPUS:-3}"
REBUILD_IMAGE=0
FAST_MODE=0
BATEST_FILTER=""
UNIT_ONLY=0
BATEST_ONLY=0
SKIP_WASM=0
PROTECT_BATEST=0
RUN_ARGON=0
FORMAT_APPLY=0
QUIET_REPORT=0
USER_JOBS=""
USER_THREADS=""
USER_HEAP_MB=""
IMAGE_NAME="${BABBLE_CI_IMAGE:-babble-ci:jdk21-node22}"
IN_CONTAINER="${BABBLE_CI_IN_CONTAINER:-0}"

FORWARD_ARGS=()

usage() {
    sed -n '2,/^$/p' "${BASH_SOURCE[0]}" | sed '/^$/d; s/^# \{0,1\}//'
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --container|--podman|--docker)
            USE_CONTAINER=1
            shift
            ;;
        --host|--no-container)
            USE_CONTAINER=0
            shift
            ;;
        --github|--remote)
            DELEGATE_GITHUB=1
            shift
            ;;
        --local|--force-local)
            FORCE_LOCAL=1
            FORWARD_ARGS+=("$1")
            shift
            ;;
        --no-watch)
            GH_WATCH=0
            shift
            ;;
        --workflow)
            if [[ $# -lt 2 ]]; then
                echo "Error : --workflow requires a workflow file or name" >&2
                exit 2
            fi
            GH_WORKFLOW="$2"
            shift 2
            ;;
        --ref)
            if [[ $# -lt 2 ]]; then
                echo "Error : --ref requires a branch or ref argument" >&2
                exit 2
            fi
            GH_REF="$2"
            shift 2
            ;;
        --rebuild-image)
            USE_CONTAINER=1
            REBUILD_IMAGE=1
            shift
            ;;
        --fast)
            FAST_MODE=1
            FORWARD_ARGS+=("$1")
            shift
            ;;
        --batest-filter)
            if [[ $# -lt 2 ]]; then
                echo "Error : --batest-filter requires a pattern argument" >&2
                exit 2
            fi
            BATEST_FILTER="$2"
            FORWARD_ARGS+=("$1" "$2")
            shift 2
            ;;
        --unit-only)
            UNIT_ONLY=1
            FORWARD_ARGS+=("$1")
            shift
            ;;
        --batest-only)
            BATEST_ONLY=1
            FORWARD_ARGS+=("$1")
            shift
            ;;
        --skip-wasm)
            SKIP_WASM=1
            FORWARD_ARGS+=("$1")
            shift
            ;;
        --protect-batest)
            PROTECT_BATEST=1
            FORWARD_ARGS+=("$1")
            shift
            ;;
        --argon|--raytracer)
            RUN_ARGON=1
            FORWARD_ARGS+=("$1")
            shift
            ;;
        --format-apply)
            FORMAT_APPLY=1
            FORWARD_ARGS+=("$1")
            shift
            ;;
        --jobs)
            USER_JOBS="$2"
            FORWARD_ARGS+=("$1" "$2")
            shift 2
            ;;
        --threads)
            USER_THREADS="$2"
            FORWARD_ARGS+=("$1" "$2")
            shift 2
            ;;
        --heap)
            USER_HEAP_MB="$2"
            FORWARD_ARGS+=("$1" "$2")
            shift 2
            ;;
        --quiet-report)
            QUIET_REPORT=1
            FORWARD_ARGS+=("$1")
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "Error : unknown option : $1" >&2
            usage >&2
            exit 2
            ;;
    esac
done

if [[ "${UNIT_ONLY}" -eq 1 && "${BATEST_ONLY}" -eq 1 ]]; then
    echo "Error : --unit-only and --batest-only are mutually exclusive" >&2
    exit 2
fi

if [[ "${DELEGATE_GITHUB}" -eq 1 && "${FORCE_LOCAL}" -eq 1 ]]; then
    echo "Error : --github and --local are mutually exclusive" >&2
    exit 2
fi

IS_FULL_GATE=1
if [[ "${FAST_MODE}" -eq 1 || "${UNIT_ONLY}" -eq 1 || "${BATEST_ONLY}" -eq 1 || -n "${BATEST_FILTER}" ]]; then
    IS_FULL_GATE=0
fi

if [[ "${DELEGATE_GITHUB}" -eq 1 && "${IS_FULL_GATE}" -eq 0 ]]; then
    echo "Error : --github always runs the full GitHub Actions workflow and cannot be combined with --fast, --unit-only, --batest-only, or --batest-filter" >&2
    exit 2
fi

# ------------------------------------------------------------------ formatting helpers
if [[ -t 1 ]]; then
    C_BOLD=$'\033[1m'
    C_GREEN=$'\033[32m'
    C_RED=$'\033[31m'
    C_YELLOW=$'\033[33m'
    C_CYAN=$'\033[36m'
    C_RESET=$'\033[0m'
else
    C_BOLD="" C_GREEN="" C_RED="" C_YELLOW="" C_CYAN="" C_RESET=""
fi

now_s() {
    date +%s
}

fmt_duration() {
    local secs="$1"
    if (( secs >= 60 )); then
        printf "%dm%02ds" $((secs / 60)) $((secs % 60))
    else
        printf "%ds" "${secs}"
    fi
}

banner() {
    echo ""
    echo "${C_BOLD}${C_CYAN}==> $*${C_RESET}"
}

detect_cpus() {
    if command -v nproc >/dev/null 2>&1; then
        nproc
    elif command -v sysctl >/dev/null 2>&1; then
        sysctl -n hw.ncpu
    else
        echo 4
    fi
}

detect_ram_mb() {
    if [[ -r /proc/meminfo ]]; then
        awk '/^MemTotal:/ { printf "%d\n", $2 / 1024 }' /proc/meminfo
    elif command -v sysctl >/dev/null 2>&1; then
        local bytes
        bytes="$(sysctl -n hw.memsize 2>/dev/null || echo 8589934592)"
        echo $(( bytes / 1024 / 1024 ))
    else
        echo 8192
    fi
}

check_protect_batest() {
    banner "Phase 0 : Protecting src/test/batest"
    git config --global --add safe.directory "${REPO_ROOT}" >/dev/null 2>&1 || true
    local wt_diff br_diff=""
    wt_diff="$(git status --porcelain -- src/test/batest)"
    if git rev-parse --verify origin/master >/dev/null 2>&1; then
        br_diff="$(git diff --name-only origin/master..HEAD -- src/test/batest)"
    fi
    if [[ -n "${wt_diff}" || -n "${br_diff}" ]]; then
        echo "${C_RED}FAILED : src/test/batest was modified!${C_RESET}" >&2
        [[ -n "${wt_diff}" ]] && echo "${wt_diff}" >&2
        [[ -n "${br_diff}" ]] && echo "${br_diff}" >&2
        exit 1
    fi
    echo "${C_GREEN}✓${C_RESET} src/test/batest is untouched"
}

run_github_ci_gate() {
    local reason="$1"
    if [[ "${PROTECT_BATEST}" -eq 1 ]]; then
        check_protect_batest
    fi
    if [[ "${FORMAT_APPLY}" -eq 1 ]]; then
        echo "Error : --format-apply modifies local files and cannot be combined with GitHub Actions delegation; apply formatting, commit, and push first." >&2
        exit 2
    fi
    if ! command -v gh >/dev/null 2>&1; then
        echo "Error : gh CLI is required to delegate CI to GitHub Actions (pass --local to run locally)." >&2
        exit 1
    fi
    if ! gh auth status >/dev/null 2>&1; then
        echo "Error : gh CLI is not authenticated; sign in to GitHub or pass --local to run locally." >&2
        exit 1
    fi

    local current_branch=""
    current_branch="$(git symbolic-ref --short HEAD 2>/dev/null || true)"
    local target_ref="${GH_REF:-${current_branch}}"
    if [[ -z "${target_ref}" ]]; then
        echo "Error : detached HEAD; checkout a branch or pass --ref <branch> when delegating to GitHub Actions." >&2
        exit 2
    fi

    if [[ -z "${GH_REF}" || "${target_ref}" == "${current_branch}" ]]; then
        if [[ -n "$(git status --porcelain)" ]]; then
            echo "Error : working tree has uncommitted changes; commit and push before delegating CI to GitHub Actions (or pass --local)." >&2
            exit 1
        fi
    fi

    local head_sha=""
    head_sha="$(git rev-parse HEAD)"
    local remote_sha=""
    remote_sha="$(git ls-remote origin "refs/heads/${target_ref}" "refs/tags/${target_ref}" 2>/dev/null | awk 'NR==1 {print $1}')"
    if [[ -z "${remote_sha}" ]]; then
        echo "Error : ref '${target_ref}' is not pushed to origin; run 'git push -u origin ${target_ref}' first (or pass --local)." >&2
        exit 1
    fi

    if [[ -z "${GH_REF}" || "${target_ref}" == "${current_branch}" ]]; then
        if [[ "${remote_sha}" != "${head_sha}" ]]; then
            echo "Error : local HEAD (${head_sha:0:8}) differs from origin/${target_ref} (${remote_sha:0:8}); push your commits before delegating CI to GitHub Actions." >&2
            exit 1
        fi
    fi

    echo "${C_BOLD}Babble CI Gate (GitHub Actions delegation)${C_RESET}"
    echo "  Reason   : ${reason}"
    echo "  Workflow : ${GH_WORKFLOW}"
    echo "  Ref      : ${target_ref} (${remote_sha:0:8})"
    if [[ "${RUN_ARGON}" -eq 1 ]]; then
        echo "${C_YELLOW}  Note     : --argon is not part of ${GH_WORKFLOW}; run the raytracer byte-for-byte check separately if needed.${C_RESET}"
    fi

    banner "Dispatching ${GH_WORKFLOW} on ref ${target_ref}"
    local dispatch_start dispatch_out
    dispatch_start="$(now_s)"
    if ! dispatch_out="$(gh workflow run "${GH_WORKFLOW}" --ref "${target_ref}" 2>&1)"; then
        echo "${C_RED}Error : gh workflow run failed :${C_RESET}" >&2
        echo "${dispatch_out}" >&2
        exit 1
    fi
    [[ -n "${dispatch_out}" ]] && echo "${dispatch_out}"

    local run_url="" run_id=""
    run_url="$(printf '%s\n' "${dispatch_out}" | grep -Eo 'https://[^[:space:]]+/actions/runs/[0-9]+' | head -n 1 || true)"
    run_id="${run_url##*/}"

    if [[ -z "${run_id}" ]]; then
        echo "Waiting for dispatched workflow run ID..."
        for _ in $(seq 1 15); do
            sleep 2
            run_id="$(gh run list --workflow "${GH_WORKFLOW}" --branch "${target_ref}" --event workflow_dispatch --limit 5 \
                --json databaseId,headSha \
                --jq "[.[] | select(.headSha == \"${remote_sha}\")] | .[0].databaseId // empty" 2>/dev/null || true)"
            if [[ -n "${run_id}" ]]; then
                run_url="$(gh run view "${run_id}" --json url --jq .url 2>/dev/null || true)"
                break
            fi
        done
    fi

    if [[ -z "${run_id}" ]]; then
        echo "${C_RED}Error : workflow was dispatched, but could not determine the run ID.${C_RESET}" >&2
        echo "Check manually with : gh run list --workflow ${GH_WORKFLOW} --branch ${target_ref}" >&2
        exit 1
    fi

    if [[ "${GH_WATCH}" -eq 0 ]]; then
        echo "${C_GREEN}✓${C_RESET} Dispatched GitHub Actions run ${run_id}${run_url:+ (${run_url})}"
        echo "  Watch : gh run watch ${run_id} --compact --exit-status --interval ${GH_WATCH_INTERVAL}"
        exit 0
    fi

    trap 'echo ""; echo "${C_YELLOW}Detached from watch; GitHub Actions run ${run_id} is still running (cancel with: gh run cancel ${run_id}).${C_RESET}" >&2; exit 130' INT TERM

    banner "Watching GitHub Actions run ${run_id}${run_url:+ (${run_url})}"
    # Guard against cli/cli#14948: immediate `gh run watch` after `gh workflow run`
    # can intermittently fail with HTTP 404 before GitHub finishes registering jobs.
    sleep 5
    local watch_rc=0 attempt=0 run_status=""
    while true; do
        attempt=$((attempt + 1))
        watch_rc=0
        gh run watch "${run_id}" --compact --exit-status --interval "${GH_WATCH_INTERVAL}" || watch_rc=$?
        if [[ "${watch_rc}" -eq 0 ]]; then
            break
        fi
        run_status="$(gh run view "${run_id}" --json status --jq .status 2>/dev/null || echo "unknown")"
        if [[ "${run_status}" != "completed" && "${attempt}" -lt 4 ]]; then
            echo "${C_YELLOW}Note : gh run watch exited early while run is '${run_status}'; retrying (${attempt}/3)...${C_RESET}"
            sleep 5
            continue
        fi
        break
    done

    local total_dur=$(( $(now_s) - dispatch_start ))
    banner "Summary (GitHub Actions run ${run_id}, total $(fmt_duration "${total_dur}"))"
    if [[ "${watch_rc}" -eq 0 ]]; then
        echo "${C_BOLD}${C_GREEN}ALL CI CHECKS PASSED${C_RESET}${run_url:+ (${run_url})}"
        exit 0
    fi

    echo "${C_BOLD}${C_RED}GITHUB ACTIONS CI FAILED${C_RESET}${run_url:+ (${run_url})}"
    gh run view "${run_id}" --json jobs \
        --jq '.jobs[] | select(.conclusion != "success" and .conclusion != "skipped") | "  ✗ \(.name) (\(.conclusion))"' 2>/dev/null || true
    echo ""
    echo "Inspect failed step logs : gh run view ${run_id} --log-failed"
    exit 1
}

NUM_CPUS="$(detect_cpus)"
RAM_MB="$(detect_ram_mb)"

# Delegate full CI gate to GitHub Actions when explicitly requested or when the
# host has <= 2 CPUs (unless --local was passed or already inside the container).
if [[ "${IN_CONTAINER}" -ne 1 ]]; then
    if [[ "${DELEGATE_GITHUB}" -eq 1 ]]; then
        run_github_ci_gate "--github requested"
    elif [[ "${FORCE_LOCAL}" -eq 0 && "${IS_FULL_GATE}" -eq 1 && "${NUM_CPUS}" -lt "${MIN_LOCAL_CPUS}" ]]; then
        run_github_ci_gate "host has ${NUM_CPUS} CPU(s) (<= 2); pass --local to force local execution"
    fi
fi

# ------------------------------------------------------------------ container trampoline
if [[ "${USE_CONTAINER}" -eq 1 && "${IN_CONTAINER}" -ne 1 ]]; then
    if command -v podman >/dev/null 2>&1; then
        RUNTIME="podman"
    elif command -v docker >/dev/null 2>&1; then
        RUNTIME="docker"
    else
        echo "Error : --container requested but neither podman nor docker is installed" >&2
        exit 1
    fi

    if [[ "${REBUILD_IMAGE}" -eq 1 ]] || ! "${RUNTIME}" image exists "${IMAGE_NAME}" >/dev/null 2>&1; then
        banner "Building CI container image ${IMAGE_NAME} (${RUNTIME})"
        "${RUNTIME}" build \
            -f "${REPO_ROOT}/src/tools/Containerfile.ci" \
            -t "${IMAGE_NAME}" \
            "${REPO_ROOT}/src/tools"
    fi

    mkdir -p "${HOME}/.m2"
    VOL_FLAGS=(
        -v "${REPO_ROOT}:/work:z"
        -v "${HOME}/.m2:/m2:z"
    )
    # Support git worktrees whose .git file points to an external common git dir.
    GIT_COMMON_DIR="$(git -C "${REPO_ROOT}" rev-parse --path-format=absolute --git-common-dir 2>/dev/null || true)"
    if [[ -n "${GIT_COMMON_DIR}" && "${GIT_COMMON_DIR}" != "${REPO_ROOT}"/* && "${GIT_COMMON_DIR}" != "${REPO_ROOT}/.git" ]]; then
        VOL_FLAGS+=(-v "${GIT_COMMON_DIR}:${GIT_COMMON_DIR}:ro,z")
    fi

    RUNTIME_FLAGS=(
        --rm
        --entrypoint ""
        -w /work
        -e HOME=/tmp
        -e BABBLE_CI_IN_CONTAINER=1
        -e BABBLE_CI_M2=/m2/repository
    )
    if [[ "${RUNTIME}" == "podman" ]]; then
        RUNTIME_FLAGS+=(--userns=keep-id)
    fi

    banner "Running CI gate inside ${IMAGE_NAME} (${RUNTIME})"
    exec "${RUNTIME}" run "${RUNTIME_FLAGS[@]}" "${VOL_FLAGS[@]}" \
        "${IMAGE_NAME}" \
        bash /work/src/tools/ci_gate.sh "${FORWARD_ARGS[@]}"
fi

# Maven common args (support custom repo mount inside container).
MVN_BASE=(mvn -B --file pom.xml)
if [[ -n "${BABBLE_CI_M2:-}" ]]; then
    MVN_BASE+=(-Dmaven.repo.local="${BABBLE_CI_M2}")
fi

# Check Node availability & version for Wasm tests.
NODE_VERSION="none"
if command -v node >/dev/null 2>&1; then
    NODE_VERSION="$(node -v 2>/dev/null || echo unknown)"
fi
JAVA_VER_LINE="$(java -version 2>&1 | head -n 1)"

# Count how many parallel jobs will run in Phase 2 so we budget CPU/RAM cleanly.
PLANNED_JOBS=()
if [[ -n "${BATEST_FILTER}" ]]; then
    PLANNED_JOBS+=(batest-interpret batest-compile batest-compile-O0 batest-compile-O2)
    [[ "${SKIP_WASM}" -eq 0 ]] && PLANNED_JOBS+=(wasm-batest)
else
    if [[ "${BATEST_ONLY}" -eq 0 ]]; then
        PLANNED_JOBS+=(unit-interpret unit-compile unit-compile-O0 unit-compile-O2)
    fi
    if [[ "${UNIT_ONLY}" -eq 0 ]]; then
        PLANNED_JOBS+=(batest-interpret batest-compile batest-compile-O0 batest-compile-O2)
        [[ "${SKIP_WASM}" -eq 0 ]] && PLANNED_JOBS+=(wasm-batest)
    fi
fi
[[ "${RUN_ARGON}" -eq 1 ]] && PLANNED_JOBS+=(argon)

TOTAL_JOBS="${#PLANNED_JOBS[@]}"

if [[ -n "${USER_JOBS}" ]]; then
    MAX_CONCURRENT="${USER_JOBS}"
else
    # Keep at least 2 cores per concurrent JVM when possible, up to all queued jobs.
    AUTO_JOBS=$(( NUM_CPUS / 2 ))
    (( AUTO_JOBS < 1 )) && AUTO_JOBS=1
    (( AUTO_JOBS > TOTAL_JOBS )) && AUTO_JOBS="${TOTAL_JOBS}"
    MAX_CONCURRENT="${AUTO_JOBS}"
fi
(( MAX_CONCURRENT < 1 )) && MAX_CONCURRENT=1
(( MAX_CONCURRENT > TOTAL_JOBS )) && MAX_CONCURRENT="${TOTAL_JOBS}"

if [[ -n "${USER_THREADS}" ]]; then
    THREADS_PER_JOB="${USER_THREADS}"
else
    THREADS_PER_JOB=$(( NUM_CPUS / MAX_CONCURRENT ))
    (( THREADS_PER_JOB < 1 )) && THREADS_PER_JOB=1
fi

if [[ -n "${USER_HEAP_MB}" ]]; then
    HEAP_MB="${USER_HEAP_MB}"
else
    # Budget up to half of system RAM across concurrent JVMs, clamped to [768, 2048] MiB.
    HEAP_MB=$(( RAM_MB / (2 * MAX_CONCURRENT) ))
    (( HEAP_MB < 768 )) && HEAP_MB=768
    (( HEAP_MB > 2048 )) && HEAP_MB=2048
fi

GC_THREADS="${THREADS_PER_JOB}"
(( GC_THREADS < 2 )) && GC_THREADS=2
(( GC_THREADS > 8 )) && GC_THREADS=8

SUREFIRE_JVM_ARGS="-Xmx${HEAP_MB}m -XX:+UseParallelGC -XX:-OmitStackTraceInFastThrow -XX:ParallelGCThreads=${GC_THREADS} -Djunit.jupiter.execution.parallel.config.strategy=fixed -Djunit.jupiter.execution.parallel.config.fixed.parallelism=${THREADS_PER_JOB}"

START_ALL="$(now_s)"
LOG_DIR="${REPO_ROOT}/target/ci-local"
mkdir -p "${LOG_DIR}"

echo "${C_BOLD}Babble Local CI Gate${C_RESET}"
echo "  Hardware : ${NUM_CPUS} CPUs, $(( RAM_MB / 1024 )) GiB RAM"
echo "  Budget   : ${MAX_CONCURRENT} concurrent JVMs x ${THREADS_PER_JOB} JUnit threads, -Xmx${HEAP_MB}m each"
echo "  Runtime  : ${JAVA_VER_LINE} | node ${NODE_VERSION}"
echo "  Jobs (${TOTAL_JOBS}) : ${PLANNED_JOBS[*]}"

if [[ "${SKIP_WASM}" -eq 0 && "${NODE_VERSION}" != v22.* ]]; then
    echo "${C_YELLOW}  Note     : GitHub CI runs Node 22 for Wasm 3.0 (current: ${NODE_VERSION}). Omit --host for exact JDK 21 + Node 22 container parity, or pass --skip-wasm.${C_RESET}"
fi

# ------------------------------------------------------------------ trap cleanup
ACTIVE_PIDS=()
cleanup() {
    local sig="$1"
    if (( ${#ACTIVE_PIDS[@]} > 0 )); then
        echo ""
        echo "${C_YELLOW}Interrupted (${sig}) : stopping ${#ACTIVE_PIDS[@]} running job(s)...${C_RESET}" >&2
        kill "${ACTIVE_PIDS[@]}" 2>/dev/null || true
        wait "${ACTIVE_PIDS[@]}" 2>/dev/null || true
    fi
    exit 130
}
trap 'cleanup INT' INT
trap 'cleanup TERM' TERM

# ------------------------------------------------------------------ Phase 0: pre-flight checks
if [[ "${PROTECT_BATEST}" -eq 1 ]]; then
    check_protect_batest
fi

if [[ "${FORMAT_APPLY}" -eq 1 ]]; then
    banner "Pre-build : Applying Spotless Java formatting"
    "${MVN_BASE[@]}" -q com.diffplug.spotless:spotless-maven-plugin:apply
fi

# ------------------------------------------------------------------ Phase 1: build once + hygiene
banner "Phase 1 : Build once (Spotless check + compile + package)"
P1_START="$(now_s)"

# Run hard file-size audit alongside or just before packaging.
if command -v python3 >/dev/null 2>&1 && [[ -f .agents/skills/periodic-refactor/scripts/audit_file_sizes.py ]]; then
    python3 .agents/skills/periodic-refactor/scripts/audit_file_sizes.py --check-hard-limit
fi

"${MVN_BASE[@]}" package -DskipTests
java -jar target/babble-1.0-SNAPSHOT.jar -format -check examples src/main/java/org/babblelang/axioms
P1_END="$(now_s)"
echo "${C_GREEN}✓${C_RESET} Build + Spotless + Babblefmt passed ($(fmt_duration $((P1_END - P1_START))))"

# Clean stale coverage/outcomes before parallel fan-out.
rm -rf target/jacoco-*.exec target/batest-report target/batest-shards "${LOG_DIR}"
mkdir -p target/batest-report "${LOG_DIR}"

# ------------------------------------------------------------------ Phase 2: parallel fan-out
banner "Phase 2 : Running ${TOTAL_JOBS} test jobs in parallel (max ${MAX_CONCURRENT} concurrent)"
P2_START="$(now_s)"

UNIT_EXCLUDES="!BatestTestCase,!WasmBatestTestCase"
if [[ "${SKIP_WASM}" -eq 1 ]]; then
    UNIT_EXCLUDES="${UNIT_EXCLUDES},!WasmCompilerTestCase"
fi

SUREFIRE_PLUGIN="org.apache.maven.plugins:maven-surefire-plugin"
JACOCO_PLUGIN="org.jacoco:jacoco-maven-plugin"
EXEC_PLUGIN="org.codehaus.mojo:exec-maven-plugin"

run_job_cmd() {
    local job="$1"
    local log="${LOG_DIR}/${job}.log"
    local t0 t1 rc
    t0="$(now_s)"
    rc=0
    case "${job}" in
        unit-interpret)
            "${MVN_BASE[@]}" "${SUREFIRE_PLUGIN}:test@default-test" \
                -Dtest="${UNIT_EXCLUDES}" \
                -Dsurefire.jvmArgs="${SUREFIRE_JVM_ARGS}" >"${log}" 2>&1 || rc=$?
            ;;
        unit-compile)
            "${MVN_BASE[@]}" "${SUREFIRE_PLUGIN}:test@compiled-tests" \
                -Dtest="${UNIT_EXCLUDES}" \
                -Dsurefire.jvmArgs="${SUREFIRE_JVM_ARGS}" >"${log}" 2>&1 || rc=$?
            ;;
        unit-compile-O0)
            "${MVN_BASE[@]}" "${SUREFIRE_PLUGIN}:test@compiled-tests-O0" \
                -Dtest="${UNIT_EXCLUDES}" \
                -Dsurefire.jvmArgs="${SUREFIRE_JVM_ARGS}" >"${log}" 2>&1 || rc=$?
            ;;
        unit-compile-O2)
            "${MVN_BASE[@]}" -PO2 "${SUREFIRE_PLUGIN}:test@compiled-tests-O2" \
                -Dtest="${UNIT_EXCLUDES}" \
                -Dsurefire.jvmArgs="${SUREFIRE_JVM_ARGS}" >"${log}" 2>&1 || rc=$?
            ;;
        batest-interpret)
            if [[ "${FAST_MODE}" -eq 1 || -n "${BATEST_FILTER}" ]]; then
                "${MVN_BASE[@]}" "${SUREFIRE_PLUGIN}:test@default-test" \
                    -Dtest=BatestTestCase \
                    -Dbatest.filter="${BATEST_FILTER}" \
                    -Dsurefire.jvmArgs="${SUREFIRE_JVM_ARGS}" >"${log}" 2>&1 || rc=$?
            else
                "${MVN_BASE[@]}" -Pbatest \
                    "${JACOCO_PLUGIN}:prepare-agent@prepare-agent-interpret" \
                    "${SUREFIRE_PLUGIN}:test@default-test" \
                    -Dsurefire.jvmArgs="${SUREFIRE_JVM_ARGS}" >"${log}" 2>&1 || rc=$?
            fi
            ;;
        batest-compile)
            if [[ "${FAST_MODE}" -eq 1 || -n "${BATEST_FILTER}" ]]; then
                "${MVN_BASE[@]}" "${SUREFIRE_PLUGIN}:test@compiled-tests" \
                    -Dtest=BatestTestCase \
                    -Dbatest.filter="${BATEST_FILTER}" \
                    -Dsurefire.jvmArgs="${SUREFIRE_JVM_ARGS}" >"${log}" 2>&1 || rc=$?
            else
                "${MVN_BASE[@]}" -Pbatest \
                    "${JACOCO_PLUGIN}:prepare-agent@prepare-agent-compile" \
                    "${SUREFIRE_PLUGIN}:test@compiled-tests" \
                    -Dsurefire.jvmArgs="${SUREFIRE_JVM_ARGS}" >"${log}" 2>&1 || rc=$?
            fi
            ;;
        batest-compile-O0)
            if [[ "${FAST_MODE}" -eq 1 || -n "${BATEST_FILTER}" ]]; then
                "${MVN_BASE[@]}" "${SUREFIRE_PLUGIN}:test@compiled-tests-O0" \
                    -Dtest=BatestTestCase \
                    -Dbatest.filter="${BATEST_FILTER}" \
                    -Dsurefire.jvmArgs="${SUREFIRE_JVM_ARGS}" >"${log}" 2>&1 || rc=$?
            else
                "${MVN_BASE[@]}" -Pbatest \
                    "${JACOCO_PLUGIN}:prepare-agent@prepare-agent-compile-O0" \
                    "${SUREFIRE_PLUGIN}:test@compiled-tests-O0" \
                    -Dsurefire.jvmArgs="${SUREFIRE_JVM_ARGS}" >"${log}" 2>&1 || rc=$?
            fi
            ;;
        batest-compile-O2)
            if [[ "${FAST_MODE}" -eq 1 || -n "${BATEST_FILTER}" ]]; then
                "${MVN_BASE[@]}" -PO2 "${SUREFIRE_PLUGIN}:test@compiled-tests-O2" \
                    -Dtest=BatestTestCase \
                    -Dbatest.filter="${BATEST_FILTER}" \
                    -Dsurefire.jvmArgs="${SUREFIRE_JVM_ARGS}" >"${log}" 2>&1 || rc=$?
            else
                "${MVN_BASE[@]}" -Pbatest \
                    "${JACOCO_PLUGIN}:prepare-agent@prepare-agent-compile-O2" \
                    "${SUREFIRE_PLUGIN}:test@compiled-tests-O2" \
                    -Dsurefire.jvmArgs="${SUREFIRE_JVM_ARGS}" >"${log}" 2>&1 || rc=$?
            fi
            ;;
        wasm-batest)
            "${MVN_BASE[@]}" "${SUREFIRE_PLUGIN}:test@compiled-tests" \
                -Dtest=WasmBatestTestCase \
                -Dbatest.filter="${BATEST_FILTER}" \
                -Dsurefire.jvmArgs="${SUREFIRE_JVM_ARGS}" >"${log}" 2>&1 || rc=$?
            ;;
        argon)
            (
                java -jar target/babble-1.0-SNAPSHOT.jar -compile \
                    examples/raytracer/raytracer_ppm.ba -o "${LOG_DIR}/raytracer.jar"
                java -jar "${LOG_DIR}/raytracer.jar" > "${LOG_DIR}/raytracer.ppm"
                cmp -s "${LOG_DIR}/raytracer.ppm" examples/raytracer/assets/scene.ppm
            ) >"${log}" 2>&1 || rc=$?
            ;;
        *)
            echo "Unknown job : ${job}" >"${log}"
            rc=2
            ;;
    esac
    t1="$(now_s)"
    echo "${rc} $((t1 - t0))" > "${LOG_DIR}/${job}.status"
    if [[ "${rc}" -eq 0 ]]; then
        printf "  %s✓%s %-20s %s\n" "${C_GREEN}" "${C_RESET}" "${job}" "$(fmt_duration $((t1 - t0)))"
    else
        printf "  %s✗%s %-20s %s (exit %d, log: target/ci-local/%s.log)\n" \
            "${C_RED}" "${C_RESET}" "${job}" "$(fmt_duration $((t1 - t0)))" "${rc}" "${job}"
    fi
    return "${rc}"
}

# Launch jobs up to MAX_CONCURRENT at a time.
declare -A PID_TO_JOB=()
RUNNING=0
for job in "${PLANNED_JOBS[@]}"; do
    while (( RUNNING >= MAX_CONCURRENT )); do
        # Wait for any single background job to complete.
        wait -n 2>/dev/null || true
        NEW_ACTIVE=()
        for p in "${ACTIVE_PIDS[@]}"; do
            if kill -0 "${p}" 2>/dev/null; then
                NEW_ACTIVE+=("${p}")
            fi
        done
        ACTIVE_PIDS=("${NEW_ACTIVE[@]}")
        RUNNING="${#ACTIVE_PIDS[@]}"
    done

    run_job_cmd "${job}" &
    pid=$!
    ACTIVE_PIDS+=("${pid}")
    PID_TO_JOB["${pid}"]="${job}"
    RUNNING="${#ACTIVE_PIDS[@]}"
done

# Wait for all remaining background jobs.
for p in "${ACTIVE_PIDS[@]}"; do
    wait "${p}" || true
done
ACTIVE_PIDS=()

P2_END="$(now_s)"

FAILED_JOBS=()
for job in "${PLANNED_JOBS[@]}"; do
    read -r rc dur < "${LOG_DIR}/${job}.status"
    if [[ "${rc}" -ne 0 ]]; then
        FAILED_JOBS+=("${job}")
    fi
done

# ------------------------------------------------------------------ Phase 3: coverage & parity gate
RUN_REPORT=0
if [[ "${UNIT_ONLY}" -eq 0 && "${FAST_MODE}" -eq 0 && -z "${BATEST_FILTER}" ]]; then
    RUN_REPORT=1
fi

REPORT_RC=0
P3_DUR=0
if [[ "${RUN_REPORT}" -eq 1 ]]; then
    banner "Phase 3 : 6-scope JaCoCo coverage & 4-way batest parity gate"
    P3_START="$(now_s)"
    REPORT_LOG="${LOG_DIR}/batest-report.log"
    if "${MVN_BASE[@]}" -Pbatest \
        "${JACOCO_PLUGIN}:report@report-interpret" \
        "${JACOCO_PLUGIN}:report@report-compile" \
        "${JACOCO_PLUGIN}:report@report-opt" \
        "${JACOCO_PLUGIN}:report@report-compile-O0" \
        "${JACOCO_PLUGIN}:report@report-opt-O2" \
        "${EXEC_PLUGIN}:java@batest-report" >"${REPORT_LOG}" 2>&1; then
        REPORT_RC=0
    else
        REPORT_RC=$?
    fi
    P3_END="$(now_s)"
    P3_DUR=$((P3_END - P3_START))

    if [[ "${REPORT_RC}" -eq 0 ]]; then
        echo "${C_GREEN}✓${C_RESET} batest-report passed ($(fmt_duration "${P3_DUR}"))"
        if [[ "${QUIET_REPORT}" -eq 0 && -f "${REPORT_LOG}" ]]; then
            echo ""
            awk '/^=== BATEST RESULTS/{p=1} /^=== UNCOVERED HOTSPOTS/{p=0} p' "${REPORT_LOG}"
        fi
    else
        echo "${C_RED}✗${C_RESET} batest-report FAILED ($(fmt_duration "${P3_DUR}"), exit ${REPORT_RC})"
        FAILED_JOBS+=("batest-report")
    fi
fi

# ------------------------------------------------------------------ summary
END_ALL="$(now_s)"
TOTAL_DUR=$((END_ALL - START_ALL))

banner "Summary (total $(fmt_duration "${TOTAL_DUR}") : build $(fmt_duration $((P1_END - P1_START))), parallel $(fmt_duration $((P2_END - P2_START)))$( (( RUN_REPORT )) && printf ', report %s' "$(fmt_duration "${P3_DUR}")" ))"

if (( ${#FAILED_JOBS[@]} == 0 )); then
    echo "${C_BOLD}${C_GREEN}ALL CI CHECKS PASSED${C_RESET}"
    exit 0
fi

echo "${C_BOLD}${C_RED}FAILED JOBS (${#FAILED_JOBS[@]}) : ${FAILED_JOBS[*]}${C_RESET}"
for job in "${FAILED_JOBS[@]}"; do
    log="${LOG_DIR}/${job}.log"
    echo ""
    echo "${C_BOLD}${C_RED}--- ${job} (tail of target/ci-local/${job}.log) ---${C_RESET}"
    if [[ -f "${log}" ]]; then
        grep -E '^\[ERROR\]|Failures:|Errors:|AssertionFailedError|CompileError|FAILED|Exception:' "${log}" | tail -n 30 || true
        echo "--- last 25 lines ---"
        tail -n 25 "${log}"
    fi
done
exit 1
