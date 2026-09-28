#!/usr/bin/env bash
# Test sur émulateur : l'appli s'ouvre, puis le bot Python démarre et contacte Telegram
# (avec un faux token, on attend le message « Token refusé »).
set -euo pipefail

PKG=com.botdl.telegram
APK=telegram-bot/android/app/build/outputs/apk/debug/app-debug.apk
DATA=/data/data/$PKG

adb root >/dev/null
adb wait-for-device
adb install -r "$APK"

echo "== Ouverture de l'écran principal"
adb shell am start -W -n $PKG/.MainActivity
sleep 5
if ! adb shell pidof $PKG >/dev/null; then
  adb logcat -d | grep -E "AndroidRuntime|$PKG" | tail -60
  exit 1
fi
adb shell am force-stop $PKG

echo "== Démarrage du bot"
cat > /tmp/bot.xml <<'XML'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="token">123456:ABCDEFGHIJKLMNOPQRSTUVWXYZabcdef12</string>
    <string name="admin_ids"></string>
    <boolean name="wanted_running" value="true" />
</map>
XML
adb push /tmp/bot.xml /data/local/tmp/bot.xml >/dev/null
adb shell "mkdir -p $DATA/shared_prefs && cp /data/local/tmp/bot.xml $DATA/shared_prefs/bot.xml \
  && chown -R \$(stat -c %u:%g $DATA) $DATA/shared_prefs && restorecon -R $DATA/shared_prefs"
adb shell am start-foreground-service -n $PKG/.BotService

log=""
for _ in $(seq 1 60); do
  log=$(adb shell cat $DATA/files/bot.log 2>/dev/null || true)
  if grep -q "Token refusé" <<<"$log"; then
    echo "$log"
    echo "✅ Le bot tourne sur Android"
    exit 0
  fi
  sleep 3
done
echo "$log"
adb logcat -d | grep -iE "python|chaquopy|BotService|AndroidRuntime" | tail -100
exit 1
