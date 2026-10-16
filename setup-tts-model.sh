#!/usr/bin/env bash
# ---------------------------------------------------------------------------
#  Установка русской модели синтеза речи (TTS) для sherpa-onnx
#
#  Из лога:
#    sherpa-onnx W  --vits-model: '/data/user/10/com.example.voiceapp3/files/
#                    tts_models/ru_RU-dmitri-medium.onnx' does not exist
#    CarAudioPlayer E  Failed to init TTS
#      RuntimeException: OfflineTts_newFromFile: No graph was found in the protobuf
#
#  Модели нет в репозитории — та же история, что с model-ru и model-cmd.
#  Скрипт качает её с релизов sherpa-onnx и раскладывает по нужным путям.
#
#  Модель: vits-piper-ru_RU-dmitri-medium  (мужской голос, ~65 МБ)
#
#  Использование:  ./setup-tts-model.sh
# ---------------------------------------------------------------------------

set -uo pipefail

RED=$'\033[0;31m'; GRN=$'\033[0;32m'; YLW=$'\033[1;33m'; CYN=$'\033[0;36m'; NC=$'\033[0m'
ok()   { echo "${GRN}  OK${NC}   $*"; }
fail() { echo "${RED}  FAIL${NC} $*"; }
warn() { echo "${YLW}  WARN${NC} $*"; }
info() { echo "${CYN}  ..${NC}   $*"; }
head_(){ echo; echo "${CYN}=== $* ===${NC}"; }

PKG="com.example.voiceapp3"
NAME="vits-piper-ru_RU-dmitri-medium"
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/${NAME}.tar.bz2"
WORK="$HOME/tts-model-tmp"

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$SDK/platform-tools/adb"
[ -x "$ADB" ] || ADB="$(command -v adb || true)"

# ---------------------------------------------------------------------------
head_ "Проверки"
[ -x "$ADB" ] || { fail "adb не найден"; exit 1; }
ok "adb: $ADB"

"$ADB" get-state >/dev/null 2>&1 || { fail "устройство не подключено"; exit 1; }
ok "устройство на связи"

# В automotive-эмуляторе приложение живёт под пользователем 10, а не 0.
# Из лога видно путь /data/user/10/... — определяем номер автоматически.
USER_ID=$("$ADB" shell am get-current-user 2>/dev/null | tr -d '\r\n' | tr -cd '0-9')
[ -n "$USER_ID" ] || USER_ID=0
ok "текущий пользователь Android: $USER_ID"

"$ADB" shell pm list packages --user "$USER_ID" 2>/dev/null | grep -q "$PKG" \
    && ok "$PKG установлен" \
    || { fail "$PKG не установлен для пользователя $USER_ID"; exit 1; }

# ---------------------------------------------------------------------------
head_ "Загрузка модели"
mkdir -p "$WORK"; cd "$WORK" || exit 1

if [ -d "$NAME" ]; then
    ok "модель уже распакована в $WORK/$NAME"
else
    if [ -s "${NAME}.tar.bz2" ]; then
        ok "архив уже скачан ($(du -h "${NAME}.tar.bz2" | cut -f1))"
    else
        info "качаю ~65 МБ, это займёт минуту"
        curl -fL --progress-bar "$URL" -o "${NAME}.tar.bz2" \
            || { fail "не скачалось: $URL"; exit 1; }
        ok "скачано ($(du -h "${NAME}.tar.bz2" | cut -f1))"
    fi
    info "распаковываю"
    tar xjf "${NAME}.tar.bz2" || { fail "архив битый — удалите его и запустите снова"; exit 1; }
    ok "распаковано"
fi

# ---------------------------------------------------------------------------
head_ "Что внутри"
MODEL="$WORK/$NAME/ru_RU-dmitri-medium.onnx"
TOKENS="$WORK/$NAME/tokens.txt"
ESPEAK="$WORK/$NAME/espeak-ng-data"

for p in "$MODEL" "$TOKENS"; do
    [ -f "$p" ] && ok "$(basename "$p")  ($(du -h "$p" | cut -f1))" \
                || { fail "нет файла $p"; ls -la "$WORK/$NAME"; exit 1; }
done
[ -d "$ESPEAK" ] && ok "espeak-ng-data ($(find "$ESPEAK" -type f | wc -l | tr -d ' ') файлов)" \
                 || { fail "нет каталога espeak-ng-data"; exit 1; }

# ---------------------------------------------------------------------------
head_ "Заливка на устройство"
# Внутренний каталог приложения недоступен напрямую — идём через /data/local/tmp
# и run-as. Для этого сборка должна быть debuggable (assembleDebug — да).
TMP="/data/local/tmp/tts_stage"

info "чищу временный каталог"
"$ADB" shell rm -rf "$TMP" >/dev/null 2>&1
"$ADB" shell mkdir -p "$TMP" >/dev/null 2>&1

info "push модели (самый долгий шаг)"
"$ADB" push "$MODEL"  "$TMP/" >/dev/null || { fail "push модели"; exit 1; }
"$ADB" push "$TOKENS" "$TMP/" >/dev/null || { fail "push tokens"; exit 1; }
"$ADB" push "$ESPEAK" "$TMP/" >/dev/null || { fail "push espeak-ng-data"; exit 1; }
ok "файлы во временном каталоге"

info "открываю доступ на чтение"
"$ADB" shell chmod -R 755 "$TMP" >/dev/null 2>&1

RUNAS="run-as $PKG --user $USER_ID"

info "проверяю run-as"
if ! "$ADB" shell "$RUNAS id" >/dev/null 2>&1; then
    fail "run-as не работает"
    echo "     Причина: APK собран не как debuggable, либо пакета нет у пользователя $USER_ID."
    echo "     Проверьте: $ADB shell $RUNAS id"
    exit 1
fi
ok "run-as доступен"

info "копирую внутрь приложения"
"$ADB" shell "$RUNAS mkdir -p files/tts_models"
"$ADB" shell "$RUNAS cp $TMP/ru_RU-dmitri-medium.onnx files/tts_models/"
"$ADB" shell "$RUNAS cp $TMP/tokens.txt               files/tts_models/"
"$ADB" shell "$RUNAS cp -r $TMP/espeak-ng-data        files/tts_models/"
ok "скопировано"

info "убираю временные файлы"
"$ADB" shell rm -rf "$TMP" >/dev/null 2>&1

# ---------------------------------------------------------------------------
head_ "Проверка"
echo "  Содержимое files/tts_models:"
"$ADB" shell "$RUNAS ls -la files/tts_models" 2>/dev/null | sed 's/^/     /'

SIZE=$("$ADB" shell "$RUNAS stat -c %s files/tts_models/ru_RU-dmitri-medium.onnx" 2>/dev/null | tr -d '\r')
if [ -n "$SIZE" ] && [ "$SIZE" -gt 1000000 ]; then
    ok "модель на месте: $SIZE байт"
else
    fail "модель не скопировалась"
    exit 1
fi

# ---------------------------------------------------------------------------
head_ "Дальше"
echo "  1) перезапустите приложение:"
echo "     ${CYN}$ADB shell am force-stop $PKG${NC}"
echo
echo "  2) смотрите лог:"
echo "     ${CYN}$ADB logcat -c && $ADB logcat -s sherpa-onnx:V CarAudioPlayer:V${NC}"
echo
echo "  Раньше было:"
echo "     W  --vits-model: '...ru_RU-dmitri-medium.onnx' does not exist"
echo "     E  Failed to init TTS"
echo
echo "  Должно стать: строк об ошибке нет, ассистент заговорит."
