#!/usr/bin/env bash
# Установка модели VOSK в assets проекта.
#
# Чинит: java.io.FileNotFoundException: model-ru/uuid
#
# Запускать из КОРНЯ проекта (там, где лежит settings.gradle):
#   chmod +x setup-vosk-model.sh
#   ./setup-vosk-model.sh

set -euo pipefail

MODEL_URL="https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip"
MODEL_ZIP="vosk-model-small-ru-0.22.zip"
MODEL_DIR="vosk-model-small-ru-0.22"
ASSETS="app/src/main/assets"
TARGET="$ASSETS/model-ru"

echo "=============================================="
echo " Установка русской модели VOSK"
echo "=============================================="
echo

# --- проверка, что мы в корне проекта ---
if [ ! -f "settings.gradle" ] && [ ! -f "settings.gradle.kts" ]; then
    echo "ОШИБКА: запустите из корня проекта (где settings.gradle)"
    echo "Сейчас: $(pwd)"
    exit 1
fi

# --- что уже есть ---
echo "Текущее содержимое assets:"
ls -la "$ASSETS" 2>/dev/null || echo "  (папки assets нет)"
echo

if [ -f "$TARGET/uuid" ]; then
    echo "Модель уже на месте: $TARGET"
    echo "Переустановить? [y/N]"
    read -r answer
    [ "$answer" != "y" ] && { echo "Отменено."; exit 0; }
    rm -rf "$TARGET"
fi

mkdir -p "$ASSETS"

# --- скачивание ---
if [ -f "$MODEL_ZIP" ]; then
    echo "Архив уже скачан: $MODEL_ZIP"
else
    echo "Скачиваю (~45 МБ)..."
    curl -L --progress-bar -o "$MODEL_ZIP" "$MODEL_URL"
fi

# --- распаковка ---
echo "Распаковываю..."
rm -rf "$MODEL_DIR"
unzip -q "$MODEL_ZIP"

echo "Переношу в $TARGET ..."
mv "$MODEL_DIR" "$TARGET"

# --- ГЛАВНОЕ: файл uuid ---
# vosk StorageService.sync() читает assets/<model>/uuid и падает без него.
# В архивах с alphacephei его обычно нет — создаём сами.
if [ ! -f "$TARGET/uuid" ]; then
    echo "Создаю файл uuid (в архиве его нет, а VOSK его требует)..."
    if command -v uuidgen >/dev/null 2>&1; then
        uuidgen > "$TARGET/uuid"
    else
        date +%s | shasum | head -c 36 > "$TARGET/uuid"
    fi
fi

echo
echo "=============================================="
echo " Готово"
echo "=============================================="
ls -la "$TARGET"
echo
echo "uuid = $(cat "$TARGET/uuid")"
echo
echo "Размер модели: $(du -sh "$TARGET" | cut -f1)"
echo

# --- проверка обязательных папок ---
echo "Проверка структуры:"
for d in am conf graph ivector; do
    if [ -d "$TARGET/$d" ]; then
        echo "  [OK]  $d/"
    else
        echo "  [!!]  $d/ отсутствует"
    fi
done

echo
echo "----------------------------------------------"
echo " ОСТАЛОСЬ: model-cmd"
echo "----------------------------------------------"
if [ -f "$ASSETS/model-cmd/vocab.txt" ]; then
    echo "  [OK] $ASSETS/model-cmd на месте"
else
    cat <<'EOF'
  [!!] Нет assets/model-cmd/vocab.txt

  Это ваш собственный ONNX-классификатор интентов, скачать его неоткуда.
  Нужны как минимум: vocab.txt, файл .onnx, список меток.

  Где искать:
    - в релизах репозитория (GitHub Releases)
    - в Git LFS:   git lfs install && git lfs pull
    - в .gitignore посмотрите, не исключены ли модели:
        grep -i model .gitignore
    - в старой рабочей сборке:
        unzip -l старый.apk | grep model-cmd

  Без него распознавание речи заработает, но команды
  распознаваться в интенты не будут.
EOF
fi

echo
echo "Дальше:  ./gradlew assembleDebug"
