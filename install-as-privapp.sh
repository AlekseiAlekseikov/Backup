#!/usr/bin/env bash
# ---------------------------------------------------------------------------
#  Установка приложения как привилегированного системного (/system/priv-app)
#
#  ЗАЧЕМ. Разрешения вида android.car.permission.CONTROL_CAR_WINDOWS имеют
#  уровень signature|privileged. Получить их можно двумя путями:
#
#     1. подписать APK ключом платформы  — на этом эмуляторе НЕВОЗМОЖНО,
#        его ключ (30:1A:A3:CB:...) не входит в набор AOSP;
#     2. стать привилегированным системным приложением — этот скрипт.
#
#  ТРЕБОВАНИЯ. Эмулятор запущен с -writable-system и разрешает adb root.
#  На реальном ГУ это не нужно: там подходит подпись ключом platform.
#
#  Использование:
#     ./install-as-privapp.sh check     можно ли вообще
#     ./install-as-privapp.sh install   поставить (перезагрузит эмулятор)
#     ./install-as-privapp.sh verify    проверить разрешения
#     ./install-as-privapp.sh remove    убрать из системы
# ---------------------------------------------------------------------------

set -uo pipefail

RED=$'\033[0;31m'; GRN=$'\033[0;32m'; YLW=$'\033[1;33m'; CYN=$'\033[0;36m'; NC=$'\033[0m'
ok()   { echo "${GRN}  OK${NC}   $*"; }
fail() { echo "${RED}  FAIL${NC} $*"; }
warn() { echo "${YLW}  WARN${NC} $*"; }
info() { echo "${CYN}  ..${NC}   $*"; }
head_(){ echo; echo "${CYN}=== $* ===${NC}"; }

PKG="com.example.voiceapp3"
APK_LOCAL="app/build/outputs/apk/debug/app-debug.apk"
SYS_DIR="/system/priv-app/VoiceApp3"
SYS_APK="$SYS_DIR/VoiceApp3.apk"
PERM_XML="/system/etc/permissions/privapp-permissions-voiceapp3.xml"

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$SDK/platform-tools/adb"
[ -x "$ADB" ] || ADB="$(command -v adb || true)"

# Разрешения уровня signature|privileged, которые нужно внести в белый список.
# Чисто signature (без privileged) сюда добавлять бесполезно — не выдадутся.
PERMS=(
    android.car.permission.CONTROL_CAR_WINDOWS
    android.car.permission.CONTROL_CAR_DOORS
    android.car.permission.CONTROL_CAR_CLIMATE
    android.car.permission.CONTROL_CAR_SEATS
    android.car.permission.CONTROL_CAR_ENERGY_PORTS
    android.car.permission.CONTROL_CAR_EXTERIOR_LIGHTS
    android.car.permission.CONTROL_CAR_INTERIOR_LIGHTS
    android.car.permission.CONTROL_CAR_DISPLAY_UNITS
    android.car.permission.CAR_POWERTRAIN
    android.car.permission.CAR_SPEED
    android.car.permission.CAR_ENERGY_PORTS
    android.car.permission.CAR_IDENTIFICATION
    android.car.permission.CAR_VENDOR_EXTENSION
    android.permission.WRITE_SECURE_SETTINGS
    android.permission.MEDIA_CONTENT_CONTROL
    android.permission.INJECT_EVENTS
)

# ---------------------------------------------------------------------------
cmd_check() {
    head_ "Инструменты"
    [ -x "$ADB" ] && ok "adb: $ADB" || { fail "adb не найден"; exit 1; }
    "$ADB" get-state >/dev/null 2>&1 || { fail "устройство не подключено"; exit 1; }

    local model api
    model=$("$ADB" shell getprop ro.product.model | tr -d '\r')
    api=$("$ADB" shell getprop ro.build.version.sdk | tr -d '\r')
    ok "$model, API $api"

    head_ "Root"
    local out
    out=$("$ADB" root 2>&1)
    echo "  $out"
    sleep 2
    "$ADB" wait-for-device
    local who
    who=$("$ADB" shell id -u 2>/dev/null | tr -d '\r')
    if [ "$who" = "0" ]; then
        ok "adb работает от root"
    else
        fail "root недоступен (uid=$who)"
        echo "     Образ собран как production. Нужен эмулятор, запущенный так:"
        echo "     ${CYN}emulator -avd ИМЯ_AVD -writable-system${NC}"
        exit 1
    fi

    head_ "Запись в /system"
    if "$ADB" remount 2>&1 | tee /dev/stderr | grep -qi "succe\|remount succeeded"; then
        ok "/system доступен на запись"
    else
        warn "remount не подтвердился, пробую отключить verity"
        "$ADB" disable-verity 2>&1 | sed 's/^/     /'
        echo
        echo "  Если сказано про перезагрузку — выполните:"
        echo "     ${CYN}$ADB reboot${NC}   потом снова ${CYN}$0 check${NC}"
        exit 1
    fi

    head_ "Место на разделе"
    "$ADB" shell df -h /system 2>/dev/null | sed 's/^/     /'

    echo
    ok "всё готово, можно запускать: $0 install"
}

# ---------------------------------------------------------------------------
cmd_install() {
    head_ "Сборка"
    [ -f "./gradlew" ] || { fail "запускайте из корня проекта"; exit 1; }
    ./gradlew assembleDebug || { fail "сборка упала"; exit 1; }
    [ -f "$APK_LOCAL" ] || { fail "нет $APK_LOCAL"; exit 1; }
    ok "$APK_LOCAL ($(du -h "$APK_LOCAL" | cut -f1))"

    head_ "Подготовка"
    "$ADB" root >/dev/null 2>&1; sleep 2; "$ADB" wait-for-device
    "$ADB" remount >/dev/null 2>&1

    local U
    U=$("$ADB" shell am get-current-user 2>/dev/null | tr -d '\r\n' | tr -cd '0-9')
    [ -n "$U" ] || U=0
    ok "пользователь Android: $U"

    info "удаляю обычную установку, чтобы не конфликтовала"
    "$ADB" uninstall "$PKG" >/dev/null 2>&1 || true

    head_ "Копирование в /system/priv-app"
    "$ADB" shell mkdir -p "$SYS_DIR"
    "$ADB" push "$APK_LOCAL" "$SYS_APK" || { fail "push не прошёл — возможно, /system заполнен"; exit 1; }
    "$ADB" shell chmod 755 "$SYS_DIR"
    "$ADB" shell chmod 644 "$SYS_APK"
    "$ADB" shell chown root:root "$SYS_DIR" "$SYS_APK" 2>/dev/null
    ok "$SYS_APK"

    head_ "Белый список разрешений"
    local tmp="/tmp/privapp-permissions-voiceapp3.xml"
    {
        echo '<?xml version="1.0" encoding="utf-8"?>'
        echo '<permissions>'
        echo "    <privapp-permissions package=\"$PKG\">"
        for p in "${PERMS[@]}"; do
            echo "        <permission name=\"$p\"/>"
        done
        echo '    </privapp-permissions>'
        echo '</permissions>'
    } > "$tmp"

    "$ADB" push "$tmp" "$PERM_XML" >/dev/null || { fail "не записался $PERM_XML"; exit 1; }
    "$ADB" shell chmod 644 "$PERM_XML"
    ok "$PERM_XML (${#PERMS[@]} разрешений)"

    head_ "Перезагрузка"
    info "система должна перечитать priv-app при старте"
    "$ADB" reboot
    info "жду загрузки, это минута-две"
    "$ADB" wait-for-device
    while [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]; do
        sleep 3
    done
    sleep 5
    ok "загрузилось"

    echo
    echo "  Дальше: $0 verify"
    echo "  Не забудьте заново залить модели TTS и VOSK — данные приложения стёрты."
}

# ---------------------------------------------------------------------------
cmd_verify() {
    head_ "Где лежит приложение"
    "$ADB" shell pm path "$PKG" 2>/dev/null | sed 's/^/     /'

    head_ "Флаги"
    "$ADB" shell dumpsys package "$PKG" 2>/dev/null \
        | grep -E "userId=|flags=|privateFlags=|codePath=" | sed 's/^ */     /'

    head_ "Car-разрешения"
    local granted=0 denied=0
    while read -r line; do
        case "$line" in
            *"granted=true"*)  echo "${GRN}  выдано${NC}  $line"; granted=$((granted+1)) ;;
            *"granted=false"*) echo "${RED}  нет   ${NC}  $line"; denied=$((denied+1)) ;;
        esac
    done < <("$ADB" shell dumpsys package "$PKG" 2>/dev/null | grep -i "android.car.permission" | sed 's/^ *//')

    echo
    if [ "$granted" -gt 0 ]; then
        ok "выдано: $granted, отказано: $denied"
    else
        fail "ни одно car-разрешение не выдано"
        echo "     Смотрите причину: $ADB logcat -d | grep -i privapp"
    fi

    head_ "Живая проверка"
    echo "     ${CYN}$ADB logcat -c${NC}"
    echo "     ${CYN}$ADB logcat -s VoiceAssistantService:I WindowControlHandler:I VehiclePropertyHelper:I${NC}"
    echo "     скажите «открой все окна» — ждём Handler result: true"
}

# ---------------------------------------------------------------------------
cmd_remove() {
    head_ "Удаление из системы"
    "$ADB" root >/dev/null 2>&1; sleep 2; "$ADB" wait-for-device
    "$ADB" remount >/dev/null 2>&1
    "$ADB" shell rm -rf "$SYS_DIR"
    "$ADB" shell rm -f "$PERM_XML"
    ok "удалено, перезагружаю"
    "$ADB" reboot
}

case "${1:-}" in
    check)   cmd_check   ;;
    install) cmd_install ;;
    verify)  cmd_verify  ;;
    remove)  cmd_remove  ;;
    *)
        echo "Установка приложения как привилегированного системного"
        echo
        echo "  $0 check     проверить root и запись в /system — НАЧАТЬ С ЭТОГО"
        echo "  $0 install   собрать и установить (перезагрузит эмулятор)"
        echo "  $0 verify    проверить выданные разрешения"
        echo "  $0 remove    убрать из системы"
        ;;
esac
