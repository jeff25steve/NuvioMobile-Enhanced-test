#!/bin/sh
set -eu

ROOT_DIR="${1:-$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)}"
WORKFLOW="$ROOT_DIR/.github/workflows/android-startup-profile-benchmark.yml"
[ -f "$WORKFLOW" ] || { echo "FAIL: benchmark workflow not found: $WORKFLOW" >&2; exit 1; }

# This APK workflow must remain explicit/manual; an accidental push or PR
# trigger can start an expensive Android build without an authorized dispatch.
awk '
  /^on:[[:space:]]*$/ { in_on=1; seen_on=1; next }
  in_on && /^[^[:space:]#][^:]*:/ { in_on=0 }
  in_on && /^  [A-Za-z0-9_-]+:[[:space:]]*$/ {
    trigger=$1
    sub(/:$/, "", trigger)
    if (trigger == "workflow_dispatch") manual=1
    else unexpected=1
  }
  END {
    if (!seen_on || !manual || unexpected) {
      print "FAIL: benchmark workflow must use workflow_dispatch only; automatic triggers are not allowed." > "/dev/stderr"
      exit 1
    }
  }
' "$WORKFLOW" || exit 1

line_of() {
  grep -n -F -- "$1" "$WORKFLOW" | head -n 1 | cut -d: -f1
}

instructions_create="$(line_of "cat > phone-benchmark/START-HERE.txt <<'EOF'")"
instructions_check="$(line_of 'test -s phone-benchmark/START-HERE.txt')"
instructions_end="$(awk 'found && $0 == "          EOF" { print NR; exit } /cat > phone-benchmark\/START-HERE.txt <<.EOF./ { found=1 }' "$WORKFLOW")"
checksum_create="$(line_of 'xargs -0 sha256sum > SHA256SUMS.txt')"
checksum_verify="$(line_of 'sha256sum --check SHA256SUMS.txt')"
bundle_count_check="$(line_of 'Phone bundle must contain eight APKs, two scripts, START-HERE.txt and SHA256SUMS.txt.')"
upload_step="$(line_of '- name: Upload phone-ready benchmark APK bundle')"

for value in "$instructions_create" "$instructions_check" "$instructions_end" "$checksum_create" "$checksum_verify" "$bundle_count_check" "$upload_step"; do
  [ -n "$value" ] || { echo "FAIL: bundle workflow is missing a required assembly/checksum/upload operation." >&2; exit 1; }
done

[ "$instructions_create" -lt "$instructions_end" ] || {
  echo "FAIL: START-HERE.txt heredoc must have a closing delimiter after its creation." >&2
  exit 1
}
[ "$instructions_end" -lt "$instructions_check" ] || {
  echo "FAIL: START-HERE.txt must be created before it is checked for non-empty content." >&2
  exit 1
}
[ "$instructions_check" -lt "$checksum_create" ] && [ "$checksum_create" -lt "$checksum_verify" ] || {
  echo "FAIL: all bundle files must exist before checksums are generated and verified." >&2
  exit 1
}
[ "$checksum_verify" -lt "$bundle_count_check" ] && [ "$bundle_count_check" -lt "$upload_step" ] || {
  echo "FAIL: bundle integrity checks must complete before artifact upload." >&2
  exit 1
}

# The actual packaged R8 APKs must be audited, not inferred from variant names.
for required in \
  'manifest application-id' \
  'manifest debuggable' \
  'manifest print' \
  'android:shell="true"' \
  'outputs/mapping/' \
  'mapping.txt'; do
  grep -Fq -- "$required" "$WORKFLOW" || {
    echo "FAIL: benchmark APK contract audit is missing '$required'." >&2
    exit 1
  }
done


# CI build efficiency and recoverability are part of the phone-bundle contract.
grep -Fq 'cancel-in-progress: false' "$WORKFLOW" || {
  echo "FAIL: a newer push must not cancel a long-running authorized benchmark build." >&2
  exit 1
}
[ "$(grep -Fc 'uses: actions/cache@v6' "$WORKFLOW")" -eq 3 ] || {
  echo "FAIL: benchmark CI must cache the Cargo registry, native target directory and cargo-ndk binary." >&2
  exit 1
}
grep -Fq 'rustup target add aarch64-linux-android' "$WORKFLOW" || {
  echo "FAIL: phone-only native builds must install the arm64 Rust target." >&2
  exit 1
}
if grep -Eq 'rustup target add .* (armv7-linux-androideabi|i686-linux-android|x86_64-linux-android)' "$WORKFLOW"; then
  echo "FAIL: phone-only benchmark CI must not install unused non-arm64 Rust targets." >&2
  exit 1
fi
grep -Fq 'cargo install cargo-ndk --version 4.1.2 --locked' "$WORKFLOW" || {
  echo "FAIL: cargo-ndk must be version-pinned and skipped on a cache hit." >&2
  exit 1
}
grep -Fq 'native/target/' "$WORKFLOW" || {
  echo "FAIL: native Rust build outputs must be cached across CI runs." >&2
  exit 1
}
grep -Fq 'id: rust-toolchain' "$WORKFLOW" &&
grep -Fq '>> "$GITHUB_OUTPUT"' "$WORKFLOW" &&
grep -Fq 'steps.rust-toolchain.outputs.cache-key' "$WORKFLOW" || {
  echo "FAIL: the Rust toolchain fingerprint must be passed as a step output into cache keys." >&2
  exit 1
}
if grep -Fq 'env.RUSTC_CACHE_KEY' "$WORKFLOW"; then
  echo "FAIL: cache expressions must not depend on a shell-only GITHUB_ENV variable." >&2
  exit 1
fi

# Protect the hosted-runner resource budget and preserve a first-phase checkpoint.
for required in \
  '-Dorg.gradle.jvmargs=' \
  '-Xmx6g' \
  '-Pkotlin.daemon.jvmargs=' \
  '-Xmx6g' \
  '--max-workers=2' \
  'record_runner_snapshot() {' \
  'free -h' \
  'df -hT' \
  'memory.events' \
  'ps -eo pid,ppid,rss,%cpu,etime,comm' \
  'benchmark-diagnostics/runner-resources/' \
  '- name: Upload full-distribution build checkpoint'; do
  grep -Fq -- "$required" "$WORKFLOW" || {
    echo "FAIL: benchmark runner resource control/telemetry is missing '$required'." >&2
    exit 1
  }
done

full_profile_step="$(line_of '- name: Build full profile-capture APK pair')"
full_checkpoint_step="$(line_of '- name: Upload full profile-capture checkpoint')"
playstore_profile_step="$(line_of '- name: Build Play Store profile-capture APK pair')"
full_r8_step="$(line_of '- name: Build full R8 benchmark APK pair')"
playstore_r8_step="$(line_of '- name: Build Play Store R8 benchmark APK pair')"
[ -n "$full_profile_step" ] && [ -n "$full_checkpoint_step" ] &&
[ -n "$playstore_profile_step" ] && [ -n "$full_r8_step" ] && [ -n "$playstore_r8_step" ] &&
[ "$full_profile_step" -lt "$full_checkpoint_step" ] &&
[ "$full_checkpoint_step" -lt "$playstore_profile_step" ] &&
[ "$playstore_profile_step" -lt "$full_r8_step" ] &&
[ "$full_r8_step" -lt "$playstore_r8_step" ] || {
  echo "FAIL: four isolated profile/R8 build phases and the early checkpoint must remain ordered." >&2
  exit 1
}

[ "$(grep -Fc './gradlew "$@"' "$WORKFLOW")" -eq 1 ] || {
  echo "FAIL: measured Gradle helper must have one entry point." >&2
  exit 1
}
[ "$(grep -Fc 'run_gradle_group "' "$WORKFLOW")" -eq 4 ] || {
  echo "FAIL: each flavor/build-type pair must retain its own Gradle invocation to bound Kotlin compiler memory." >&2
  exit 1
}
for task in \
  :androidApp:assembleFullNonMinifiedRelease \
  :baselineProfile:assembleFullNonMinifiedRelease \
  :androidApp:assembleFullBenchmarkRelease \
  :baselineProfile:assembleFullBenchmarkRelease \
  :androidApp:assemblePlaystoreNonMinifiedRelease \
  :baselineProfile:assemblePlaystoreNonMinifiedRelease \
  :androidApp:assemblePlaystoreBenchmarkRelease \
  :baselineProfile:assemblePlaystoreBenchmarkRelease; do
  grep -Fq "$task" "$WORKFLOW" || {
    echo "FAIL: grouped Gradle build is missing required task $task." >&2
    exit 1
  }
done
grep -Fq -- '--profile' "$WORKFLOW" || {
  echo "FAIL: Gradle build profiling must be enabled for actionable CI timing evidence." >&2
  exit 1
}
for required in \
  'androidApp/build/outputs/mapping/' \
  'configanalyzer.html' \
  'benchmark-diagnostics/gradle-logs/' \
  'benchmark-diagnostics/gradle-profiles/' \
  'nuvio-android-startup-build-diagnostics-'; do
  grep -Fq -- "$required" "$WORKFLOW" || {
    echo "FAIL: build diagnostics are missing '$required'." >&2
    exit 1
  }
done

diagnostics_collect="$(line_of '- name: Collect R8 Configuration Analyzer and Gradle profiles')"
diagnostics_upload="$(line_of '- name: Upload benchmark build diagnostics')"
assembly_step="$(line_of '- name: Assemble phone-ready benchmark bundle')"
[ -n "$diagnostics_collect" ] && [ -n "$diagnostics_upload" ] && [ -n "$assembly_step" ] &&
[ "$diagnostics_collect" -lt "$diagnostics_upload" ] &&
[ "$diagnostics_upload" -lt "$assembly_step" ] || {
  echo "FAIL: diagnostics must be collected and uploaded before phone-bundle assembly." >&2
  exit 1
}

printf 'PASS: START-HERE creation precedes validation; checksum and bundle-count checks precede upload.\n'
