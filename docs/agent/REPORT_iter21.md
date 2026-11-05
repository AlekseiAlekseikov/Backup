# iter21 REPORT — CentralEXAuto reference-first fixes

| Поле | Значение |
|------|----------|
| versionCode | 44 |
| versionName | 1.0.44 |
| head_sha | `06df32384e75d1fca238d69663573348f90a635d` |
| run_id (CI Build APK) | 41334050966 |
| APK path (в workspace) | `/home/user/work/app-iter21-v1.0.44.apk` |
| sha256 APK | `cd26f01b53af5b6e99a1abf95f9809539022cde1178c32b14e13ce7f81813232` |
| Эталон | CentralEXAuto 3.3.9 (`/home/user/cex/CentralEXAuto_v3.3.9.apk`, jadx → `/home/user/cex/cex_out/sources/`) |

Версионирование сброшено на 44/1.0.44 по мандату iter21.

---

## 1. Точки опоры из эталона (РЕФЕРЕНС)

ВЗЯТО ИЗ ЭТАЛОНА:

| # | Пункт ТЗ | РЕФЕРЕНС: файл/класс/метод эталона | Что повторяем один-в-один |
|---|----------|-----------------------------------|---------------------------|
| 1 | §1 TTL >4096 байт → скрипт в /data/local/tmp | НЕТ В ЭТАЛОНЕ (CEX не использует ADB-сокет вообще). Но идиома chunked-upload+wc -c verify+sh+rm — стандартная для push-через-adb; изобретение минимально, см. п.2. | AdbClient.executeScript: base64-chunk ≤3000 байт, `wc -c < path` сверка с ожидаемым размером, `sh path; rm -f path`. Для upload-команд >3800 байт — multipart heredoc append. Команда длиннее 4096 байт через execute() теперь возвращает строку `ERR_ADB_TOO_LONG: cmd_len=N > 4096` (не IllegalArgumentException). Все 3 вызова в NetworkTtl переведены на executeScript("ttl_script", …). |
| 2 | §2 mount detection `grep -F " $DST " /proc/mounts` | `com.ex.auto.CentralWatchdogService` (durable-bind скрипты: grep по точному пути точки монтирования) | Проверка ТОЧНО по `grep -F " $DST " /proc/mounts` (не по имени файла, не по GeelyAutoSystemUIPlugin, чтобы не словить chdir/строки из mountinfo). В enable добавлена pre-check/идемпотентность: inode SRC==TGT и mount-line уже есть → пропускаем mount и kill SystemUI. Добавлен RESTORE: umount всех GeelyAutoSystemUIPlugin точек + kill com.android.systemui + sleep 4 + повторная проверка. |
| 3 | §3 «Не гасить ГУ» — одноразовая кнопка | НЕТ В ЭТАЛОНЕ КАК ТУМБЛЕР/WATCHDOG. Эталон использует Settings.System API напрямую. | Кнопка `keepawake_btn`: `settings put system screen_off_timeout 2147483647` + `settings put global stay_on_while_plugged_in 7` + СРАЗУ readback обоих значений, verdict по фактическому after, log `KEEPAWAKE_BUTTON: before=… after=… stay_on=… rc=…`. Текст на кнопке — «Не гасить ГУ (до перезагрузки)», watchdog-циклов НЕТ, pref НЕ пишется. |
| 4 | §4 индикаторы в строке состояния без overlay | `com.ex.auto.TempBattPillFloaterService#onCreate` — `startForeground(109, k1.G(this, "temp_batt_pill_floater", …, 17301561, …, 48))`; helper `w0.k1.F()` создаёт NotificationChannel в NotificationManager с `IMPORTANCE_LOW`+`setShowBadge(false)`, строит Notification.Builder(chanId).setContentTitle().setSmallIcon(icon).build() | Реализована постоянная нотификация NOTIF_PILL=109 по каналу `temp_batt_pill_floater` (IMPORTANCE_LOW, showBadge=false), setSmallIcon(android.R.drawable.ic_menu_compass), setOngoing(true), setContentTitle("temp°  batt%  wifi"), contentIntent → телеметрия. WindowManager TYPE_APPLICATION_OVERLAY отключён: вызов StatusOverlay.update() удалён; рендер — ТОЛЬКО нотификация (FloaterService НЕ портирован, как требовал мандат). |
| 5 | §5 наружная температура: показ "--" до верификации | Эталон (TempBattPillFloaterService/TempStepperService) использует float из VHAL и отображает `Math.round(°C) + "°C"`, при null-значении — `"--°C"` (см. метод `f()` case null → "--°C"/"--%") | 0x11600703 убран из primary-чтения и из PRECISE_COMMON (всегда возвращал константу 25.0). Добавлено зондирование 5 новых ID (0x2140A863, 0x2140B009, 0x2140B00D, 0x2140A6DC, 0x21402063) по зонам {0,2,7} — float и int с двойной нормализацией (x/10 или as-is). Все raw значения выводятся в AppLog по шаблону `TEMP_PROBE: 0x...@area/type=raw->parsed`. Пока ни один ID не верифицирован человеком — UI показывает `--°`, НИ ОДИН ID не выбирается автоматически. |
| 6 | §6 uptime=-1 fix | `android.os.SystemClock.elapsedRealtime()` — стандартный Android API для секунд с boot; эталон им и пользуется | `Runtime.exec("sh","-c","cat /proc/uptime")` удалён (неверный argv давал -1). `uptimeS = SystemClock.elapsedRealtime() / 1000L`. Гейт `boot_id изменён И uptime < 10 мин` для durable-after-reboot уже существовал и сохранён. |
| 7 | §7 TTL debounce+single-flight | В эталоне QuickAccessFloaterService аналогично защищает broadcast-обработчики флагом `f1198v` (volatile singleton) с таймаутами | `@Volatile ttlInFlight`, `@Volatile ttlLastRunAtMs`, synchronized double-check; при in-flight и при повторном вызове <30 с от прошлого запуска возвращается ToolsStep со статусом BLOCKED (шаг `debounce`/`singleflight`, stderr с причиной), реальный запуск пропускается. `try/finally` гарантирует сброс флага. |
| 8 | TTL tcpdump kill diagnostics | Shell-паттерн эталона: `kill $pid; rc=$?; echo KILL_RC=$rc` | Добавлен вывод `TCPDUMP_KILL_RC=…` и `TCPDUMP_ALIVE=… (after kill -0)` в v4 и v6 capture-run — чтобы в логе было видно, был ли tcpdump жив к моменту kill (причина прошлого `tcpdump_pid=-1`). |

## 2. Небольшие изобретения

ИЗОБРЕТЕНО НАМИ (≤2 пунктов):

1. **executeScriptMultipart fallback** (CEX не ходит через ADB-сокет). Для случая, когда скрипт настолько большой (~10+ кБ в будущем), что даже базовый upload-командник (base64+chunks+chmod+wc+sh+rm) не влезает в 3800 байт, используется многошаговый append через heredoc `cat >> path <<'CEXCHUNK' ... CEXCHUNK` по 2000 байт телом. Это не функциональное новшество, а техническая необходимость для обхода лимита канала.
2. **Никаких других изобретений.** Всё остальное — либо прямое соответствие эталону, либо просто перенос существующей функциональности на executeScript/TEMP_PROBE/нотификацию.

---

## 3. Шаги проверки на ГУ

1. Установить APK: `adb install -r app-iter21-v1.0.44.apk`. Убедиться, что versionName=1.0.44.
2. **§2 RESTORE шторки**: зайти в Инструменты → нажать «Восстановить шторку» (кнопка `hvac_restore_btn`). В логе `CENTRAL_HvacDigitalPlugin` должна появиться строка `HVAC_DIGITAL_RESTORE`, а затем `RESTORE_OK`. Шторка системы должна подняться за 4–5 секунд. Повторно включить «Цифровой климат» (batt/temp/ext_temp) — в логе должно быть `ALREADY_MOUNTED (idempotent)` при втором нажатии.
3. **§3 Кнопка «Не гасить ГУ»**: нажать «Не гасить ГУ (до перезагрузки)». В логе найти строку `KEEPAWAKE_BUTTON: before=… after=2147483647 stay_on=…→7 rc=0 → OK`. Проверить: `settings get system screen_off_timeout` = 2147483647. Watchdog-потоков по экрану нет. Перезагрузить ГУ — значение должно сброситься (дurable-логика не удерживает после кнопки, т.к. кнопка не пишет pref).
4. **§4 OVERLAY выключен**: верхней полупрозрачной плашки справа больше нет. В строке состояния висит постоянная нотификация с каналом `temp_batt_pill_floater` (id=109), в title которой — температура и батарея и Wi-Fi одной строкой, например `22°  87%  3/4`.
5. **§5 Температура = "--°" до верификации**: на нотификации и в блоке «Снаружи» отображается `--°C`, источник не верифицирован. В логе найти `TEMP_PROBE (iter21 §5)` и прислать ВСЕ строки формата `0x...@<area>/<type>=<raw>-><parsed>` — по ним определим реальный ID наружки. 25.0 больше не должно появляться нигде, кроме заводского константного датчика, который мы более не выбираем.
6. **§6 uptimeS реальный**: после (пере)запуска приложения в логе `DURABLE_PREBOOT_READBACK: actual=… uptime=…s` вместо `-1` — реальное число секунд с буты.
7. **§7 TTL debounce**: подряд ткнуть TTL дважды в течение 30 с — второй запуск должен вернуть шаг `debounce` со статусом BLOCKED, без повторного запуска скрипта.
8. **§1 Длинный TTL-скрипт проходит**: в логе `TTL script ... SCRIPT_BYTES=9139 HEAD=...` — размер совпадает с фактическим (не ERR_SCRIPT_UPLOAD, не ERR_ADB_TOO_LONG), после чего идёт TTL_VERDICT.

## 4. Сопутствующие проверки (regression)

- Запуск приложения, голосовой помощник — без падений.
- Tools-каталог открывается, все существующие тумблеры работают.
- Wi-Fi/Battery нотификации поднимаются и кликаются в Телеметрию.
- После RESTORE (п. 2) нижняя шторка Системы должна полностью открываться (проблема из логов v1.0.43).
