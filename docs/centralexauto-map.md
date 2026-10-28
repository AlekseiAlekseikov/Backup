# docs/centralexauto-map.md — соответствие w0/* CentralEXAuto → voiceapp3

Карта декомпилированных классов CentralEXAuto на наши файлы. Источник декомпиляции:
`reverse/jadx_out/sources/w0/{m.java, n.java, h.java}` и др.

| Центральный EX-класс | Наш файл | Роль | Статус порта |
|---|---|---|---|
| `w0/m.java` (ToolsBackend shell dispatcher) | `tools/AdbClient.kt` | Обёртка над ADB-сокетом: кэш порта `f3919a`, порядок проб `{65010, 5555}`, скрипт миграции порта `y(8000, SCRIPT)`, big-script base64-staging. | В работе: ADB сокет воспроизведён; миграция через ADB (не Runtime.sh). big-script base64-staging не нужен (наши команды <4096 байт). |
| `w0/n.java` case 0 | `AdbClient.tryProbePort` + `runCommand` | Проб рабочего порта, fallback-порядок 65010→5555, запоминание рабочего в `f3919a`. | Портирован. Добавлен fail-fast `ERR_ROOT_CHANNEL_UNAVAILABLE` для root-команд при NO_ROOT_SH. |
| `kotlinx/coroutines/scheduling/h.java` | `AdbClient.execAdbSocket` | Низкоуровневый ADB-протокол: CNXN/AUTH/OPEN/OKAY/WRTE/CLSE, баннер `host::central-geely\0`, maxpayload=4096. | Портирован 1:1. Добавлен детальный лог AUTH/refused/crash. |
| `w0/y`, `w0/x` (big-script staging) | — | base64-раскладка скриптов >3500 байт в `/data/local/tmp/_big_script_<ts>.b60`. | Пока не нужен; если появятся длинные скрипты — добавим. |
| `w0/qd` QuickAccessFloaterService | `ui/QuickPanel.kt`, `tools/WindowDoors.kt` | Быстрый доступ, открытие/закрытие стёкол при открытии двери. | Частично портирован. |
| `w0/kg.j` (status icons publisher) | `tools/StatusIcons.kt` | Иконки в статус-баре (batt/temp/wifi), notification-id 15020/15021/15024, отдельные каналы. | Каналы и id портированы; batt/temp переведены на VHAL пропы из дампа (`0x11600309 EV_BATTERY_LEVEL`, `0x11600703 ENV_OUTSIDE_TEMPERATURE`). |
| `w0/a3` (VHAL provider) | `tools/StatusIcons.readSocScan/readTempScan` | Чтение SOC/температуры из CarPropertyManager. | Исправлено: раньше был брутфорс диапазонов (давал 36%/-22°C), теперь — только подтверждённые пропы + фоллбэк по PRECISE_COMMON с логом источника. |
| `w0/rg.p` (VhalProviderService) | `tools/StatusIcons.ensureCar` | Подключение к Car service. | Портирован. |
| `w0/z8`, `w0/s8` (TTL) | `tools/NetworkTtl.kt` | `iptables -t mangle -A POSTROUTING -j TTL --ttl-set 65`, аналогично для IPv6 HL. | Добавлено `-w 5` (xtables lock), VERIFY через `iptables -C`, счётчики TTL_V4/V6, откат тумблера при провале. |
| `w0/la.java` case 0 (JIT/zygote) | `tools/AppPerformance.setAutoOptimize` | `svc wifi disable; umount /data/app/*; setprop dalvik.vm.usejit; ctl.restart zygote`. | Добавлен `trap restore_wifi EXIT` (гарантированное включение Wi-Fi обратно), верификация pid zygote до/после и usejit проперти. |
| w0 Video60Fps bind-mount | `tools/AppPerformance.setVideo60Fps` | `mount --bind` подмены `libvcodecdrv.so` + ctl.restart media.swcodec|vendor.mediacodec|media. | Убран `setenforce 0`; VERIFY по `SRC_INODE==TGT_INODE` и `MOUNT_PRESENT`; FAILBIND → FAILED. |
| `w0/fh` (Wi-Fi click) | — (используем системные вызовы) | Включение/выключение Wi-Fi. | Wi-Fi больше не ломаем (см. P5/P6). |
| w0.p (AVAS/lock sound) | `tools/LockSound.kt` | Кастомный звук замка: копирование wav в /data/local/tmp, bind-mount на `/vendor/etc/carlock/*.wav`, `killall audioserver`. | Начальная стадия. Предстоит: копировать через ADB (root канал), cap retries=3 при детерминированной ошибке, не штурмить каждые 60 сек. |
| w0 watchdog (r2 case 10) | `VoiceAssistantService.settingsWatchdog` + `StatusIcons.startPeriodicSync` | Периодический тик раз в 15/60 сек — публикация иконок и пере-применение настроек (TTL, bind-mount, prop'ы). | Watchdog работает на BgWorker (не блокирует main); тик StatusIcons уносится туда же. |
| w0 brightness restore (z/w) | `tools/ScreenBrightness.kt` | Восстановление яркости по режиму день/ночь (фары), capture bucket. | Портирован. **Удалён код, писавший 2147483647 в SCREEN_OFF_TIMEOUT** — приложение никогда не меняет системный таймаут автоматически. |
| w0.t1 case 17 (brightness watchdog) | `VoiceAssistantService.brightnessSync` | Периодическая синхронизация яркости раз в минуту. | Выполняется на внутреннем worker-потоке ScreenBrightness. |

## Ключевые различия от оригинала

1. **`Runtime.exec("sh")` не используется как root-путь** (в оригинале тоже не использовался — всё шло через ADB сокет). Наше падение в NO_ROOT_SH оставлено только для не-root отладочных команд.
2. **Главный поток никогда не делает блокирующих вызовов** (P1/P2): добавлен `BgWorker` на 4 потока, `AdbClient.execute()` ругается на вызов с main и не блокирует UI.
3. **VERIFY-контракт (P8)**: `exitCode=0` сам по себе не достаточен; все команды проверяют реальное системное состояние (iptables -C, grep /proc/mounts, inode, getprop). Обнаруженные маркеры ошибок (`Permission denied`, `FAILBIND`, `xtables lock`, `ERR_ROOT_CHANNEL_UNAVAILABLE`) переводят статус в FAILED.
4. **Нет `setenforce 0`** (P5): из HvacDigitalPlugin, AppPerformance, LockSound убрано. Логируем `SELINUX_BEFORE/AFTER`.
5. **Wi-Fi всегда возвращается включённым** (P6): `trap restore_wifi EXIT` в JIT-скрипте.
6. **Batt/temp значения из правильных VHAL property** (P3): EV_BATTERY_LEVEL / ENV_OUTSIDE_TEMPERATURE по дампу `car_service_dump.txt`, с логом `(src=VHAL:...)`.
7. **Единые теги `CENTRAL_*`** (P9): устаревшие `GeelyTools`/`ToolsBackend`/`GeelyIcons` заменены на `CENTRAL_LockSound`/`CENTRAL_ToolsBackend`/`CENTRAL_StatusIcons` и т.п.
