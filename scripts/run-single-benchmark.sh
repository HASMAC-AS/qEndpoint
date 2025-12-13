#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  scripts/run-single-benchmark.sh [--module <maven-module>] [--enable-jfr] <include-regex> [-- <jmh args...>]

Examples:
  scripts/run-single-benchmark.sh NtGzToHdtAndIndexesBenchmark -- -p ntGzPath=/data/wikidata.nt.gz
  scripts/run-single-benchmark.sh --enable-jfr NtGzToHdtAndIndexesBenchmark
EOF
}

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODULE="qendpoint-benchmarks"
ENABLE_JFR=false

INCLUDE_REGEX=""
JMH_ARGS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help)
      usage
      exit 0
      ;;
    --module)
      MODULE="$2"
      shift 2
      ;;
    --enable-jfr)
      ENABLE_JFR=true
      shift
      ;;
    --)
      shift
      JMH_ARGS+=("$@")
      break
      ;;
    *)
      if [[ -z "${INCLUDE_REGEX}" ]]; then
        INCLUDE_REGEX="$1"
      else
        JMH_ARGS+=("$1")
      fi
      shift
      ;;
  esac
done

if [[ -z "${INCLUDE_REGEX}" ]]; then
  usage
  exit 2
fi

build_offline() {
  mvn -T 1C -o -Dmaven.repo.local="${ROOT}/.m2_repo" -DskipTests clean install
  mvn -T 1C -o -Dmaven.repo.local="${ROOT}/.m2_repo" -pl "${MODULE}" -DskipTests package
}

build_online_then_offline() {
  mvn -T 1C -Dmaven.repo.local="${ROOT}/.m2_repo" -DskipTests clean install
  mvn -T 1C -Dmaven.repo.local="${ROOT}/.m2_repo" -pl "${MODULE}" -DskipTests package
  build_offline
}

if ! build_offline; then
  echo "Offline build failed; retrying once with network..." >&2
  build_online_then_offline
fi

JAR="${ROOT}/${MODULE}/target/benchmarks.jar"
if [[ ! -f "${JAR}" ]]; then
  echo "Benchmarks jar not found: ${JAR}" >&2
  exit 3
fi

RUN_ARGS=("${INCLUDE_REGEX}")

if [[ "${ENABLE_JFR}" == "true" ]]; then
  JFR_DIR="${ROOT}/benchmarks-jfr"
  mkdir -p "${JFR_DIR}"
  echo "JFR recordings will be written under: ${JFR_DIR}" >&2
  RUN_ARGS+=("-wi" "0" "-i" "1" "-f" "1" "-prof" "jfr:dir=${JFR_DIR}")
fi

RUN_ARGS+=("${JMH_ARGS[@]}")

java -jar "${JAR}" "${RUN_ARGS[@]}"

