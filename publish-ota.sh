#!/usr/bin/env bash
# Публикация обновления: поднять версию, собрать, залить на GitHub.
# Запускать из корня GalaxyVoiceAssistant-main / GeelyVoiceAssistant.
#
#   ./publish-ota.sh 1.0.1 "Что нового"
#
set -euo pipefail

NEW_NAME="${1:-}"
NOTES="${2:-Обновление голосового ассистента}"
[ -n "$NEW_NAME" ] || { echo "usage: $0 1.0.1 \"changelog\""; exit 1; }

GRADLE="app/build.gradle.kts"
[ -f "$GRADLE" ] || GRADLE="app/build.gradle"
[ -f "$GRADLE" ] || { echo "нет app/build.gradle.kts"; exit 1; }

OLD_CODE=$(grep -oE 'versionCode\s*=\s*[0-9]+' "$GRADLE" | head -1 | grep -oE '[0-9]+')
NEW_CODE=$((OLD_CODE + 1))

if grep -q "versionCode = " "$GRADLE"; then
    sed -i.bak -E "s/versionCode = [0-9]+/versionCode = ${NEW_CODE}/" "$GRADLE"
    sed -i.bak -E "s/versionName = \"[^\"]+\"/versionName = \"${NEW_NAME}\"/" "$GRADLE"
else
    sed -i.bak -E "s/versionCode [0-9]+/versionCode ${NEW_CODE}/" "$GRADLE"
    sed -i.bak -E "s/versionName \"[^\"]+\"/versionName \"${NEW_NAME}\"/" "$GRADLE"
fi
rm -f "$GRADLE.bak"

mkdir -p ota
cat > ota/version.json <<EOF
{
  "versionCode": ${NEW_CODE},
  "versionName": "${NEW_NAME}",
  "apkUrl": "https://github.com/AlekseiAlekseikov/GeelyVoiceAssistant/releases/download/v${NEW_NAME}/app-platform-signed.apk",
  "changelog": "${NOTES}"
}
EOF

echo "versionCode $OLD_CODE → $NEW_CODE"
echo "versionName $NEW_NAME"
echo
echo "Дальше:"
echo "  1) ./sign-apk.sh"
echo "  2) git add app/build.gradle.kts ota/version.json && git commit -m \"release v${NEW_NAME}\" && git push"
echo "  3) gh release create \"v${NEW_NAME}\" app-platform-signed.apk --title \"${NEW_NAME}\" --notes \"versionCode: ${NEW_CODE}\" --notes \"$NOTES\""
echo
echo "Или без gh: GitHub → Releases → Draft → приложить app-platform-signed.apk"
