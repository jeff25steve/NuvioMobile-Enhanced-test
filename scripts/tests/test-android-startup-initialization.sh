#!/bin/sh
set -eu

ROOT_DIR="${1:-$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)}"
SOURCE="$ROOT_DIR/composeApp/src/androidMain/kotlin/com/nuvio/app/features/downloads/DownloadsLiveStatusPlatform.android.kt"

[ -f "$SOURCE" ] || {
  echo "FAIL: Android download live-status platform source is missing." >&2
  exit 1
}

# Creating the channel performs a system-service lookup and localized resource
# reads. It must not execute during Activity initialization when no download
# notification is being shown.
initialize_block="$(sed -n '/^    fun initialize(context: Context) {$/,/^    }$/p' "$SOURCE")"
[ -n "$initialize_block" ] || {
  echo "FAIL: could not locate DownloadsLiveStatusPlatform.initialize." >&2
  exit 1
}
if printf '%s\n' "$initialize_block" | grep -Fq 'ensureNotificationChannel'; then
  echo "FAIL: download notification channel setup must not run during app startup." >&2
  exit 1
fi

# The channel must be ready before the NotificationCompat.Builder is created,
# so each real notification path (including notifyTransfer) remains valid.
build_line="$(grep -nF 'internal fun buildNotification(context: Context, item: DownloadItem)' "$SOURCE" | cut -d: -f1)"
ensure_line="$(grep -nF 'ensureNotificationChannel(context)' "$SOURCE" | cut -d: -f1)"
builder_line="$(grep -nF 'NotificationCompat.Builder(context, channelId)' "$SOURCE" | cut -d: -f1)"
[ -n "$build_line" ] && [ -n "$ensure_line" ] && [ -n "$builder_line" ] || {
  echo "FAIL: notification channel setup must be called from buildNotification." >&2
  exit 1
}
[ "$ensure_line" -gt "$build_line" ] && [ "$builder_line" -gt "$ensure_line" ] || {
  echo "FAIL: the notification channel must be ensured before the notification is built." >&2
  exit 1
}
[ "$(grep -Fc 'ensureNotificationChannel(context)' "$SOURCE")" -eq 1 ] || {
  echo "FAIL: the lazy channel setup call must have one central owner." >&2
  exit 1
}

# Multiple download events may arrive from different callers; only the first
# event should perform the system channel lookup/resource work.
grep -Fq '@Volatile' "$SOURCE" &&
  grep -Fq 'private var notificationChannelReady = false' "$SOURCE" &&
  grep -Fq 'private val notificationChannelLock = Any()' "$SOURCE" &&
  grep -Fq 'synchronized(notificationChannelLock)' "$SOURCE" &&
  grep -Fq 'if (notificationChannelReady) return' "$SOURCE" || {
  echo "FAIL: lazy notification channel setup must be guarded and thread-safe." >&2
  exit 1
}

grep -Fq 'manager.getNotificationChannel(channelId)' "$SOURCE" &&
  grep -Fq 'manager.createNotificationChannel(' "$SOURCE" || {
  echo "FAIL: lazy channel setup must preserve existing-channel detection and creation." >&2
  exit 1
}

printf 'PASS: download notification channel work is lazy, one-time, and precedes notification construction.\n'

sh "$ROOT_DIR/scripts/tests/test-android-app-icon-initialization.sh" "$ROOT_DIR"
sh "$ROOT_DIR/scripts/tests/test-android-startup-trace-sections.sh" "$ROOT_DIR"
