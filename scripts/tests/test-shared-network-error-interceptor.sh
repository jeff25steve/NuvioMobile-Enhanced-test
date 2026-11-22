#!/bin/sh
set -eu

ROOT_DIR="${1:-$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)}"
SHARED="$ROOT_DIR/composeApp/src/androidMain/kotlin/com/nuvio/app/core/network/SharedOkHttpClient.kt"
API_CLIENT="$ROOT_DIR/composeApp/src/androidMain/kotlin/com/nuvio/app/core/network/ApiHttpClient.android.kt"
APPLICATION="$ROOT_DIR/composeApp/src/androidMain/kotlin/com/nuvio/app/NuvioApplication.kt"

for source in "$SHARED" "$API_CLIENT" "$APPLICATION"; do
  [ -s "$source" ] || {
    echo "FAIL: required Android network client source is missing or empty: $source" >&2
    exit 1
  }
done

shared_registration_count="$(grep -F -c 'addInterceptor(NetworkErrorInterceptor())' "$SHARED" || true)"
[ "$shared_registration_count" -eq 1 ] || {
  echo "FAIL: SharedOkHttpClient must own exactly one NetworkErrorInterceptor registration; found $shared_registration_count." >&2
  exit 1
}

for source in "$API_CLIENT" "$APPLICATION"; do
  grep -Fq 'preconfigured = SharedOkHttpClient.base' "$source" || {
    echo "FAIL: $source must continue using the shared OkHttp client." >&2
    exit 1
  }
  if grep -Fq 'addInterceptor(NetworkErrorInterceptor())' "$source"; then
    echo "FAIL: $source re-registers the interceptor already installed by SharedOkHttpClient." >&2
    exit 1
  fi
done

if grep -Fq 'import com.nuvio.app.core.network.NetworkErrorInterceptor' "$APPLICATION"; then
  echo "FAIL: NuvioApplication retains an unused NetworkErrorInterceptor import." >&2
  exit 1
fi

printf 'PASS: Android API and Coil Ktor clients inherit one shared NetworkErrorInterceptor registration.\n'
