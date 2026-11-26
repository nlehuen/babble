#!/usr/bin/env bash
# Local parallel CI gate aligned with .github/workflows/maven.yml.
#
# Instead of GitHub Actions' distributed 6-shard fan-out, this script builds
# once and fans out all independent CI test jobs in parallel across the local
# machine's CPU cores and RAM (with bounded per-JVM JUnit parallelism and heap
# to avoid thread oversubscription), then runs the 6-scope JaCoCo coverage and
# 4-way behavioral parity gate.
#
# Usage:
#   ./src/tools/ci_gate.sh [options]
#
# Options:
#   --container          Run inside a CI-parity container (JDK 21 + Maven 3.9 + Node 22)
#                        using podman (preferred) or docker, building/caching babble-ci:jdk21-node22.
#   --rebuild-image      Force rebuilding the CI container image (implies --container).
#   --fast               Skip JaCoCo instrumentation/coverage report (still runs all 3 unit modes,
#                        4 JVM batest modes at -PO2, and Wasm 3.0 batest in parallel).
#   --filter <pattern>   Run only 4-mode JVM + Wasm batest matching <pattern> (no coverage gate).
#   --unit-only          Run only the 3 Java unit-test modes in parallel (skip batest).
#   --batest-only        Run only the 4-mode JVM + Wasm batest and coverage gate (skip unit tests).
#   --skip-wasm          Skip WasmBatestTestCase and WasmCompilerTestCase (useful on host Node != 22).
#   --protect-batest     Fail if src/test/batest has any working-tree or branch diff vs origin/master.
#   --argon              Also compile and render examples/raytracer-argon and check byte-for-byte
#                        identity against examples/raytracer-argon/scene.ppm.
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
USE_CONTAINER=0
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
    sed -n '2,31p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --container|--podman|--docker)
            USE_CONTAINER=1
            shift
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
        --filter)
            if [[ $# -lt 2 ]]; then
                echo "Error : --filter requires a pattern argument" >&2
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
        --argon)
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

# ------------------------------------------------------------------ hardware detection & budgeting
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

NUM_CPUS="$(detect_cpus)"
RAM_MB="$(detect_ram_mb)"

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
        PLANNED_JOBS+=(unit-interpret unit-compile unit-compile-O0)
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

SUREFIRE_JVM_ARGS="-Xmx${HEAP_MB}m -XX:+UseParallelGC -XX:ParallelGCThreads=${GC_THREADS} -Djunit.jupiter.execution.parallel.config.strategy=fixed -Djunit.jupiter.execution.parallel.config.fixed.parallelism=${THREADS_PER_JOB}"

START_ALL="$(now_s)"
LOG_DIR="${REPO_ROOT}/target/ci-local"
mkdir -p "${LOG_DIR}"

echo "${C_BOLD}Babble Local CI Gate${C_RESET}"
echo "  Hardware : ${NUM_CPUS} CPUs, $(( RAM_MB / 1024 )) GiB RAM"
echo "  Budget   : ${MAX_CONCURRENT} concurrent JVMs x ${THREADS_PER_JOB} JUnit threads, -Xmx${HEAP_MB}m each"
echo "  Runtime  : ${JAVA_VER_LINE} | node ${NODE_VERSION}"
echo "  Jobs (${TOTAL_JOBS}) : ${PLANNED_JOBS[*]}"

if [[ "${SKIP_WASM}" -eq 0 && "${NODE_VERSION}" != v22.* ]]; then
    echo "${C_YELLOW}  Note     : GitHub CI runs Node 22 for Wasm 3.0 (current: ${NODE_VERSION}). Use --container for exact JDK 21 + Node 22 parity, or --skip-wasm.${C_RESET}"
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
    banner "Phase 0 : Protecting src/test/batest"
    git config --global --add safe.directory "${REPO_ROOT}" >/dev/null 2>&1 || true
    WT_DIFF="$(git status --porcelain -- src/test/batest)"
    BR_DIFF=""
    if git rev-parse --verify origin/master >/dev/null 2>&1; then
        BR_DIFF="$(git diff --name-only origin/master..HEAD -- src/test/batest)"
    fi
    if [[ -n "${WT_DIFF}" || -n "${BR_DIFF}" ]]; then
        echo "${C_RED}FAILED : src/test/batest was modified!${C_RESET}" >&2
        [[ -n "${WT_DIFF}" ]] && echo "${WT_DIFF}" >&2
        [[ -n "${BR_DIFF}" ]] && echo "${BR_DIFF}" >&2
        exit 1
    fi
    echo "${C_GREEN}✓${C_RESET} src/test/batest is untouched"
fi

if [[ "${FORMAT_APPLY}" -eq 1 ]]; then
    banner "Pre-build : Applying Spotless Java formatting"
    "${MVN_BASE[@]}" -q spotless:apply
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

run_job_cmd() {
    local job="$1"
    local log="${LOG_DIR}/${job}.log"
    local t0 t1 rc
    t0="$(now_s)"
    rc=0
    case "${job}" in
        unit-interpret)
            "${MVN_BASE[@]}" surefire:test@default-test \
                -Dtest="${UNIT_EXCLUDES}" \
                -Dsurefire.jvmArgs="${SUREFIRE_JVM_ARGS}" >"${log}" 2>&1 || rc=$?
            ;;
        unit-compile)
            "${MVN_BASE[@]}" surefire:test@compiled-tests \
                -Dtest="${UNIT_EXCLUDES}" \
                -Dsurefire.jvmArgs="${SUREFIRE_JVM_ARGS}" >"${log}" 2>&1 || rc=$?
            ;;
        unit-compile-O0)
            "${MVN_BASE[@]}" surefire:test@compiled-tests-O0 \
                -Dtest="${UNIT_EXCLUDES}" \
                -Dsurefire.jvmArgs="${SUREFIRE_JVM_ARGS}" >"${log}" 2>&1 || rc=$?
            ;;
        batest-interpret)
            if [[ "${FAST_MODE}" -eq 1 || -n "${BATEST_FILTER}" ]]; then
                "${MVN_BASE[@]}" surefire:test@default-test \
                    -Dtest=BatestTestCase \
                    -Dbatest.filter="${BATEST_FILTER}" \
                    -Dsurefire.jvmArgs="${SUREFIRE_JVM_ARGS}" >"${log}" 2>&1 || rc=$?
            else
                "${MVN_BASE[@]}" -Pbatest \
                    jacoco:prepare-agent@prepare-agent-interpret \
                    surefire:test@default-test \
                    -Dsurefire.jvmArgs="${SUREFIRE_JVM_ARGS}" >"${log}" 2>&1 || rc=$?
            fi
            ;;
        batest-compile)
            if [[ "${FAST_MODE}" -eq 1 || -n "${BATEST_FILTER}" ]]; then
                "${MVN_BASE[@]}" surefire:test@compiled-tests \
                    -Dtest=BatestTestCase \
                    -Dbatest.filter="${BATEST_FILTER}" \
                    -Dsurefire.jvmArgs="${SUREFIRE_JVM_ARGS}" >"${log}" 2>&1 || rc=$?
            else
                "${MVN_BASE[@]}" -Pbatest \
                    jacoco:prepare-agent@prepare-agent-compile \
                    surefire:test@compiled-tests \
                    -Dsurefire.jvmArgs="${SUREFIRE_JVM_ARGS}" >"${log}" 2>&1 || rc=$?
            fi
            ;;
        batest-compile-O0)
            if [[ "${FAST_MODE}" -eq 1 || -n "${BATEST_FILTER}" ]]; then
                "${MVN_BASE[@]}" surefire:test@compiled-tests-O0 \
                    -Dtest=BatestTestCase \
                    -Dbatest.filter="${BATEST_FILTER}" \
                    -Dsurefire.jvmArgs="${SUREFIRE_JVM_ARGS}" >"${log}" 2>&1 || rc=$?
            else
                "${MVN_BASE[@]}" -Pbatest \
                    jacoco:prepare-agent@prepare-agent-compile-O0 \
                    surefire:test@compiled-tests-O0 \
                    -Dsurefire.jvmArgs="${SUREFIRE_JVM_ARGS}" >"${log}" 2>&1 || rc=$?
            fi
            ;;
        batest-compile-O2)
            if [[ "${FAST_MODE}" -eq 1 || -n "${BATEST_FILTER}" ]]; then
                "${MVN_BASE[@]}" -PO2 surefire:test@compiled-tests-O2 \
                    -Dtest=BatestTestCase \
                    -Dbatest.filter="${BATEST_FILTER}" \
                    -Dsurefire.jvmArgs="${SUREFIRE_JVM_ARGS}" >"${log}" 2>&1 || rc=$?
            else
                "${MVN_BASE[@]}" -Pbatest \
                    jacoco:prepare-agent@prepare-agent-compile-O2 \
                    surefire:test@compiled-tests-O2 \
                    -Dsurefire.jvmArgs="${SUREFIRE_JVM_ARGS}" >"${log}" 2>&1 || rc=$?
            fi
            ;;
        wasm-batest)
            "${MVN_BASE[@]}" surefire:test@compiled-tests \
                -Dtest=WasmBatestTestCase \
                -Dbatest.filter="${BATEST_FILTER}" \
                -Dsurefire.jvmArgs="${SUREFIRE_JVM_ARGS}" >"${log}" 2>&1 || rc=$?
            ;;
        argon)
            (
                java -jar target/babble-1.0-SNAPSHOT.jar -compile \
                    examples/raytracer-argon/raytracer.ba -o "${LOG_DIR}/argon.jar"
                java -jar "${LOG_DIR}/argon.jar" > "${LOG_DIR}/argon.ppm"
                cmp -s "${LOG_DIR}/argon.ppm" examples/raytracer-argon/scene.ppm
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
        jacoco:report@report-interpret \
        jacoco:report@report-compile \
        jacoco:report@report-opt \
        jacoco:report@report-compile-O0 \
        jacoco:report@report-mir \
        exec:java@batest-report >"${REPORT_LOG}" 2>&1; then
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
