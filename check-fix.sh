#!/bin/sh
# Проверка, что фиксы projection (геометрия + persistence) попали в СОБРАННЫЙ APK.
# Запуск из корня проекта: ./check-fix.sh [путь_к_apk]
APK="${1:-app/build/outputs/apk/release/app-release.apk}"
if [ ! -f "$APK" ]; then echo "НЕТ APK: $APK"; exit 1; fi
ok=1
if unzip -p "$APK" assets/ui/index.html | grep -q "Stage CPv4"; then
  echo "OK  геометрия: projection full-bleed (во весь экран, кроме панели)"
else
  echo "FAIL геометрия: правка full-bleed НЕ в APK"; ok=0
fi
if unzip -p "$APK" assets/ui/index.html | grep -q "body.projfull .topbar"; then
  echo "OK  full-bleed: шапка и заголовки скрыты на projection-экранах"
else
  echo "FAIL full-bleed: CSS projfull НЕ в APK"; ok=0
fi
if unzip -p "$APK" assets/ui/index.html | grep -q "'proj' in st"; then
  echo "OK  restore: тумблер читается из appState при загрузке"
else
  echo "FAIL restore: правка appState в index.html НЕ в APK"; ok=0
fi
if unzip -p "$APK" assets/ui/index.html | grep -q "projectionBetaEnabled:'proj'"; then
  echo "FAIL стомп: 1-Гц эхо движка всё ещё затирает тумблер"; ok=0
else
  echo "OK  стомп: 1-Гц эхо движка больше не трогает тумблер"
fi
if unzip -p "$APK" 'classes*.dex' 2>/dev/null | grep -aq "app_projection_beta_enabled"; then
  echo "OK  Kotlin: запись тумблера в prefs приложения в APK"
else
  echo "FAIL Kotlin: UiBridge.kt собран без правки persistence"; ok=0
fi
[ $ok -eq 1 ] && echo "=== ВСЕ ФИКСЫ В APK ===" || echo "=== ЕСТЬ ПРОБЛЕМЫ — APK собран из старых исходников ==="
