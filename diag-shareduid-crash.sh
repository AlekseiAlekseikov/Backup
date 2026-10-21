#!/bin/sh
# ---------------------------------------------------------------------------
#  Диагностика падения GeelyTools после добавления sharedUserId="android.uid.system"
#
#  Запуск с машины, где есть adb и устройство подключено:
#      sh diag-shareduid-crash.sh
#      sh diag-shareduid-crash.sh > diag.txt 2>&1     # чтобы прислать вывод
#
#  Скрипт только ЧИТАЕТ. Ничего не ставит, не удаляет, не перезагружает.
#  Каждый раздел помечен, вывод рассчитан на то, чтобы его можно было прислать
#  целиком — он короткий.
# ---------------------------------------------------------------------------

PKG=com.example.voiceapp3

sec() { printf '\n=== %s ===\n' "$1"; }
have(){ command -v "$1" >/dev/null 2>&1; }

have adb || { echo "НЕТ adb в PATH"; exit 1; }
adb get-state >/dev/null 2>&1 || { echo "УСТРОЙСТВО НЕ ПОДКЛЮЧЕНО (adb connect IP:5555)"; exit 1; }

S="adb shell"

sec "1. Установлено ли сейчас и с каким UID"
$S dumpsys package $PKG 2>/dev/null | grep -E "versionName|codePath|userId=|sharedUser|flags=|pkgFlags" | head -12
echo "--- если пусто, пакет не установлен ---"

sec "2. Что реально в манифесте установленного APK (sharedUserId)"
# aapt может отсутствовать; тогда смотрим строку в самом APK
$S pm dump $PKG 2>/dev/null | grep -iE "sharedUser|userId=" | head -5

sec "3. Каталог данных: владелец и SELinux-метка  <== ГЛАВНОЕ"
$S ls -ldnZ /data/data/$PKG 2>&1
$S ls -ldnZ /data/user/0/$PKG 2>&1
echo "--- ожидается: владелец 1000 и метка u:object_r:system_app_data_file:s0 ---"
echo "--- если владелец 10xxx или метка app_data_file -> причина найдена ---"

sec "4. Что внутри каталога данных (владелец подкаталогов)"
$S ls -ln /data/data/$PKG 2>&1 | head -20

sec "5. Куда ставится пакет: /data или /system"
$S pm path $PKG 2>&1

sec "6. SELinux: режим и последние denials по нашему пакету"
$S getenforce 2>&1
$S "dmesg 2>/dev/null | grep -iE 'avc: denied' | grep -iE 'voiceapp3|system_app' | tail -15" 2>&1
$S "logcat -d -b events 2>/dev/null | grep -i avc | tail -10" 2>&1

sec "7. Лог падения  <== ПРИСЛАТЬ ЭТОТ РАЗДЕЛ"
$S "logcat -d -v time -t 400 2>/dev/null | grep -iE 'FATAL|AndroidRuntime|EACCES|Permission denied|SecurityException|ClassNotFound|NoClassDefFound|UnsatisfiedLink|WebView|installd|seinfo|Failed to' | tail -40" 2>&1

sec "8. Как CentralEXAuto устроен на этом ГУ (для сравнения)"
CPKG=com.auto.central.ex
$S pm path $CPKG 2>&1
$S dumpsys package $CPKG 2>/dev/null | grep -E "codePath|userId=|sharedUser|flags=" | head -8
$S ls -ldnZ /data/data/$CPKG 2>&1
echo "--- если codePath у CentralEXAuto начинается с /system, а у нас с /data,"
echo "    разница именно в этом ---"

sec "9. Правила SELinux прошивки для системного UID"
$S "cat /system/etc/selinux/plat_seapp_contexts 2>/dev/null | grep -E '^user=system' " 2>&1
$S "cat /vendor/etc/selinux/vendor_seapp_contexts 2>/dev/null | grep -E '^user=system' " 2>&1
$S "cat /system/etc/selinux/plat_mac_permissions.xml 2>/dev/null | grep -c signer" 2>&1

sec "10. Внешнее хранилище доступно ли процессу с UID 1000"
$S ls -ldnZ /sdcard /storage/emulated/0 2>&1 | head -4

printf '\n=== ГОТОВО ===\n'
echo "Пришлите разделы 3, 7 и 8 — по ним причина определяется однозначно."
