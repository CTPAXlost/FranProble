#!/usr/bin/env bash
set -u -o pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

LOG_DIR="$ROOT_DIR/ci-logs"
mkdir -p "$LOG_DIR"
find "$LOG_DIR" -maxdepth 1 -type f ! -name 'bootstrap-*.log' -delete

overall=0

record_status() {
  local label="$1"
  local status="$2"
  echo "$status" > "$LOG_DIR/${label}.exit-code"
  if [[ $status -ne 0 ]]; then
    echo "STAGE FAILED: $label (exit $status)"
    overall=1
  else
    echo "STAGE PASSED: $label"
  fi
}

run_command() {
  local label="$1"
  shift
  echo
  echo "============================================================"
  echo "STAGE: $label"
  printf 'COMMAND:'
  printf ' %q' "$@"
  echo
  echo "============================================================"
  set +e
  "$@" 2>&1 | tee "$LOG_DIR/${label}.log"
  local status=${PIPESTATUS[0]}
  set -e
  record_status "$label" "$status"
}

run_gradle() {
  local label="$1"
  shift
  echo
  echo "============================================================"
  echo "STAGE: $label"
  echo "COMMAND: ./gradlew $*"
  echo "============================================================"
  set +e
  ./gradlew "$@" --stacktrace --warning-mode all --console=plain 2>&1 | tee "$LOG_DIR/${label}.log"
  local status=${PIPESTATUS[0]}
  set -e
  record_status "$label" "$status"
}

set -e
{
  echo "FranProbe CI environment"
  echo "Generated: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "Runner: $(uname -a)"
  echo "JAVA_HOME: ${JAVA_HOME:-unset}"
  java -version 2>&1
  echo
  ./gradlew --version
  echo
  echo "ANDROID_HOME: ${ANDROID_HOME:-unset}"
  if [[ -n "${ANDROID_HOME:-}" ]]; then
    find "$ANDROID_HOME/platforms" "$ANDROID_HOME/build-tools" -maxdepth 1 -mindepth 1 -type d 2>/dev/null | sort || true
  fi
} | tee "$LOG_DIR/00-environment.log"
echo 0 > "$LOG_DIR/00-environment.exit-code"

run_command "01-source-validation" python3 scripts/validate_source.py
run_gradle "02-clean" :app:clean
run_gradle "03-dependencies" :app:dependencies --configuration debugRuntimeClasspath
run_gradle "04-core-insight" :app:dependencyInsight --dependency androidx.core --configuration debugRuntimeClasspath
run_gradle "05-lifecycle-insight" :app:dependencyInsight --dependency androidx.lifecycle --configuration debugRuntimeClasspath
run_gradle "06-compose-insight" :app:dependencyInsight --dependency androidx.compose.ui --configuration debugRuntimeClasspath
run_gradle "07-aar-metadata" :app:checkDebugAarMetadata
run_gradle "08-manifest" :app:processDebugMainManifest
run_gradle "09-resources" :app:mergeDebugResources
run_gradle "10-kotlin-compile" :app:compileDebugKotlin
run_gradle "11-unit-test-compile" :app:compileDebugUnitTestKotlin
run_gradle "12-unit-tests" :app:testDebugUnitTest
run_gradle "13-lint" :app:lintDebug
run_gradle "14-assemble-apk" :app:assembleDebug

{
  echo "FranProbe CI summary"
  echo "Generated: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo
  for code_file in "$LOG_DIR"/*.exit-code; do
    stage="$(basename "$code_file" .exit-code)"
    code="$(cat "$code_file")"
    if [[ "$code" == "0" ]]; then
      echo "[PASS] $stage"
    else
      echo "[FAIL:$code] $stage"
    fi
  done
} | tee "$LOG_DIR/00-summary.txt"

if [[ $overall -ne 0 ]]; then
  echo
  echo "One or more mandatory stages failed. See ci-logs/00-summary.txt and the per-stage logs."
  exit 1
fi

test -f app/build/outputs/apk/debug/app-debug.apk
echo "All mandatory stages passed and APK exists."
