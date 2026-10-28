#!/usr/bin/env bash
# ---------------------------------------------------------------------------
#  Подпись GeelyTools платформенным ключом AOSP (macOS / Linux)
#
#  Зачем: CONTROL_CAR_WINDOWS и остальные car-разрешения — signature.
#  Debug-ключ Android Studio их не даёт. Этот скрипт подписывает тем же
#  ключом, которым собрана прошивка IHU629G.
#
#  Использование (из корня GalaxyVoiceAssistant-main):
#
#     chmod +x sign-apk.sh
#     ./sign-apk.sh              # соберёт release APK и подпишет
#     ./sign-apk.sh путь/к.apk   # подпишет уже собранный APK
#     ./sign-apk.sh --install    # подпишет и поставит на ГУ
# ---------------------------------------------------------------------------

set -euo pipefail

RED=$'\033[0;31m'; GRN=$'\033[0;32m'; YLW=$'\033[1;33m'; CYN=$'\033[0;36m'; NC=$'\033[0m'
ok()   { echo "${GRN}  OK${NC}   $*"; }
fail() { echo "${RED}  FAIL${NC} $*"; exit 1; }
warn() { echo "${YLW}  WARN${NC} $*"; }
info() { echo "${CYN}  ..${NC}   $*"; }
head_(){ echo; echo "${CYN}=== $* ===${NC}"; }

PKG="com.example.voiceapp3"
WANT_SHA="C8A2E9BCCF597C2FB6DC66BEE293FC13F2FC47EC77BC6B2B0D52C11F51192AB8"
WANT_PRETTY="C8:A2:E9:BC:CF:59:7C:2F:B6:DC:66:BE:E2:93:FC:13:F2:FC:47:EC:77:BC:6B:2B:0D:52:C1:1F:51:19:2A:B8"

# Ключи: рядом со скриптом, в проекте или в ~/platform-keys
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
for d in "$SCRIPT_DIR/platform-keys" "$PWD/platform-keys" "$HOME/platform-keys"; do
    if [ -s "$d/platform.pk8" ] && [ -s "$d/platform.x509.pem" ]; then
        KEYDIR="$d"
        break
    fi
done
[ -n "${KEYDIR:-}" ] || fail "нет platform-keys/platform.pk8 — скопируйте папку platform-keys рядом со скриптом"

DO_INSTALL=0
APK_IN=""
for arg in "$@"; do
    case "$arg" in
        --install|-i) DO_INSTALL=1 ;;
        --help|-h)
            sed -n '2,16p' "$0"
            exit 0
            ;;
        *) APK_IN="$arg" ;;
    esac
done

# --- инструменты -----------------------------------------------------------

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[ -n "$SDK" ] || SDK="$HOME/Library/Android/sdk"
[ -d "$SDK" ] || SDK="$HOME/Android/Sdk"

ADB="$(command -v adb || true)"
[ -x "$ADB" ] || ADB="$SDK/platform-tools/adb"

APKSIGNER="$(command -v apksigner || true)"
if [ ! -x "$APKSIGNER" ]; then
    APKSIGNER="$(ls -d "$SDK"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1 || true)"
fi
ZIPALIGN="$(ls -d "$SDK"/build-tools/*/zipalign 2>/dev/null | sort -V | tail -1 || true)"

head_ "Инструменты"
[ -x "$APKSIGNER" ] || fail "apksigner не найден. Поставьте Build-Tools в SDK Manager"
ok "apksigner: $APKSIGNER"
[ -x "$ADB" ] && ok "adb: $ADB" || warn "adb не найден — установку пропустим"
ok "ключи: $KEYDIR"

# --- APK -------------------------------------------------------------------

head_ "APK"
if [ -z "$APK_IN" ]; then
    [ -f "./gradlew" ] || fail "нет APK и нет gradlew — запустите из корня проекта или укажите путь к APK"
    info "собираю assembleRelease (debug = testOnly, AppControl без -t не ставит)"
    ./gradlew assembleRelease
    APK_IN="app/build/outputs/apk/release/app-release.apk"
fi
[ -f "$APK_IN" ] || fail "APK не найден: $APK_IN"
ok "$APK_IN ($(du -h "$APK_IN" | awk '{print $1}'))"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
UNSIGNED="$WORK/unsigned.apk"
ALIGNED="$WORK/aligned.apk"
OUT="${APK_IN%/*}/app-platform-signed.apk"
[ -n "${APK_IN%/*}" ] || OUT="app-platform-signed.apk"
# кладём результат в корень проекта, его легко найти
OUT="$PWD/app-platform-signed.apk"

cp "$APK_IN" "$UNSIGNED"
# снимаем старую JAR-подпись, иначе в META-INF остаются чужие .SF/.RSA
zip -qd "$UNSIGNED" "META-INF/*.SF" "META-INF/*.RSA" "META-INF/*.DSA" "META-INF/*.EC" 2>/dev/null || true

if [ -x "$ZIPALIGN" ]; then
    "$ZIPALIGN" -f -p 4 "$UNSIGNED" "$ALIGNED"
    UNSIGNED="$ALIGNED"
    ok "zipalign"
fi

head_ "Подпись"
"$APKSIGNER" sign \
    --v1-signing-enabled true \
    --v2-signing-enabled true \
    --key  "$KEYDIR/platform.pk8" \
    --cert "$KEYDIR/platform.x509.pem" \
    --out  "$OUT" \
    "$UNSIGNED" || fail "apksigner sign не удался"

"$APKSIGNER" verify --verbose "$OUT" >/dev/null && ok "подпись валидна"

GOT="$("$APKSIGNER" verify --print-certs "$OUT" 2>/dev/null \
      | grep -m1 "SHA-256 digest:" | sed 's/.*SHA-256 digest: *//' | tr -d ' :' | tr 'A-Z' 'a-z')"
WANT_LC="$(echo "$WANT_SHA" | tr 'A-Z' 'a-z')"
echo "  сертификат: $GOT"
echo "  ожидаем:    $WANT_LC"
if [ "$GOT" = "$WANT_LC" ]; then
    ok "отпечаток совпал с прошивкой IHU629G"
else
    fail "отпечаток НЕ тот — этот APK на машине не получит car-разрешения"
fi

ok "готово: $OUT ($(du -h "$OUT" | awk '{print $1}'))"

# --- установка -------------------------------------------------------------

if [ "$DO_INSTALL" -eq 1 ]; then
    head_ "Установка"
    [ -x "$ADB" ] || fail "adb не найден"
    "$ADB" get-state >/dev/null 2>&1 || fail "устройство не подключено (adb connect IP:5555)"
    info "снимаю старую версию — подпись сменилась, поверх не встанет"
    "$ADB" uninstall "$PKG" >/dev/null 2>&1 || true
    "$ADB" install -r "$OUT" || fail "adb install не прошёл"
    ok "установлено $PKG"

    echo
    info "проверка CONTROL_CAR_WINDOWS:"
    "$ADB" shell dumpsys package "$PKG" 2>/dev/null \
        | grep -i "CONTROL_CAR_WINDOWS" | sed 's/^/     /' || true
fi

echo
echo "  Поставить на ГУ поверх (настройки сохраняются):"
echo "     ${CYN}adb install -r $OUT${NC}"
echo "  Если старая версия была под другой подписью:"
echo "     ${CYN}adb uninstall $PKG && adb install -r $OUT${NC}"
echo
echo "  Пользователю достаточно открыть приложение и разрешить микрофон."
echo "  adb appops для оверлея больше не нужен."
