# WORKLOG

## 2026-11-10 — iter33p, ВТОРОЙ ЗАХОД (1.0.61): JIT ровно по референсу

**Почему второй заход.** Первый (`c8e4c9c`, CI 200/0) REVIEW признал неверным по существу:
построена СВОЯ механика (журнал шагов, TTL операции, окно 60 с, CONTINUE/COMPENSATE/HOLD,
watcher, boot-backfill) вместо копии `j9` → `la` → `e2`.

**Что сделано.**

1. `tools/JitNetCore.kt` — `JitRef` (константы и текст скрипта референса байт в строку,
   разбор `jit_restaurar`, формула `bb/r7`, решение о продолжении) + `JitOp` (ядро операции на
   швах `JitShell`/`JitStore`/`JitNetState`: клик, продолжение после zygote, возврат AP→Wi-Fi
   через 15000 мс, сверка) + взаимное исключение на старт операции.
2. `tools/JitNet.kt` — тонкие Android-адаптеры (`AdbClient`, `Prefs2`, `WifiManager`), без решений.
3. `tools/AppPerformance.kt` — `setAutoOptimize` → `jitApplyWithZygote` → `JitNet.click`; удалены
   `jitPersistBackfill`, `bootRestoreJit`, `jitProps()`, файл `jit_verified`, `lastJitInitKey`.
   На загрузке — только чтение (`jitBootProbe` печатает остатки с `need=owner_decision`).
4. `VoiceAssistantService.kt` — убран повтор через 30 с.
5. **AVAS отсоединён от JIT**: `AvasCore.kt` и `AvasRestore.kt` удалены целиком (существовали
   только ради JIT-рестарта); остаточный `filesDir/avas_snapshot` не применяется и не удаляется.
6. Шаг `umount -l` точек `/data/app/` реализован построчно, но выключен
   (`JitRef.MOUNT_STEP_ENABLED=false`): обратимость не доказана, нужно решение владельца.
7. Тесты: 9 классов (55 тестов) — `JitRefTest`, `JitChainScriptTest` (настоящий `sh`),
   `JitChainEndToEndTest` (сквозная операция + 4 одновременных клика), `JitBootTest`,
   `JitRulesReadOnlyTest`, `JitNoPersistTest`, `AvasIndependenceTest`, `JitTogglePathTest`,
   `JitBootMarkTest`; 5 классов удалено вместе с кодом (§4.1 отчёта).

**Результат.** CI run `42896558590` / job `130111086379` по `78a3248` — SUCCESS, **176 PASSED /
0 FAILED**, artifact `13802266250`. Локальный JVM-прогон: `PASS=55 FAIL=0`. На ГУ ничего не
запускалось, полевого успеха не заявляю.

**Грабли.** Первый прогон CI (`577d338`) упал на `Unresolved reference 'currentBootTag'` — при
удалении блока `jitProps()` ушла и локальная `currentBootTag()`; восстановлена делегированием в
`HvacDigitalPlugin.currentBootTag()`. Песочница не компилирует модуль (нет Android SDK), поэтому
это ловит только CI.

## 2026-10-28

### P1/P2 — Main-thread ban + BgWorker

**Проблема.** `AdbClient.execute()` и ряд `Runtime.exec()` / VHAL / `CarPropertyManager` вызовов запускались прямо с главного потока в:
- `UiBridge.onEvent` (обработчики `"dev.runCommand"`, `"dev.getLogcat"`, `"dev.simulateDoorOpen/Close"`, `"settings.change"`, `"audio.*"`, `"perf.saveKeepAlive"`, `"perf.getKeepAliveList"`, `"dev.testNotificationIcons"`)
- `UiBridge.onAppResumed` (`StatusIcons.syncAll`)
- `VoiceAssistantService.onStartCommand` (ветка `EXTRA_BOOT_RESTORE` — 6 AdbClient/Runtime вызовов на UI)
- `VoiceAssistantService.settingsWatchdog` (периодический тик раз в 60 с на handler(main) вызывал TTL/LockSound/Hvac/Brightness)
- `VoiceAssistantService.screenWakeReceiver.onReceive` (`SCREEN_ON`/`SCREEN_OFF` всегда main thread)
- `StatusIcons.startPeriodicSync` (тик раз в 15 с на main вызывал syncAll → VHAL getProperty)
- `ScreenBrightness.writeSysfs/writeProp` (`Runtime.exec()` внутри)

Следствие: белый экран на холодном старте (boot-ветка onStartCommand делала ~5-8 adb-команд последовательно на main), подвисания UI при переключении тумблеров, возможные ANR.

**Что сделано.**

1. **Добавлен `tools/BgWorker.kt`** — единый пул из 4 фоновых потоков (имя `CENTRAL_Bg`, daemon), `post(name, block)` для огневых задач, `run(name, block, onResult)` для задач с результатом и доставкой через `Handler(mainLooper)`, `assertNotMain(where)` — assertion для дебага и понятный error-log для релиза с указанием места вызова из стека.

2. **`AdbClient.execute()` жёстко детектирует main-thread вызовы:** пишет в лог полный стектрейс с точным class/method/file:line вызывающего, ставит команду в очередь (чтобы UI не блокировать), но возвращает `ERR_MAIN_THREAD_FORBIDDEN`. Добавлен новый метод `AdbClient.submit(cmd, timeout, expectRoot, onResult)` — асинхронный вызов с колбэком на main.

3. **Все точки входа с главного потока завернуты в `BgWorker`:**
   - `UiBridge.onEvent("settings.change", ...)` — неблокирующие тумблеры (theme/bright/immersive/projection) остаются на UI, все остальные уходят в `BgWorker.post/run` с `pushToolsState()` обратно на main.
   - `UiBridge.onEvent("audio.*", "dev.*", "perf.*")` — переведены на BgWorker.run/post, результаты публикуются через `runOnUiThread` в панель.
   - `UiBridge.onAppResumed` — `syncAll` уходит в `BgWorker.post`.
   - `VoiceAssistantService.onStartCommand(EXTRA_BOOT_RESTORE)` — блок из ScreenBrightness/StatusIcons/NetworkTtl/LockSound/HvacDigital/AppPerformance полностью перенесён в `BgWorker.post("bootRestore")`; оставлен только `startPeriodicSync` (он Handler.post и сам теперь отдаёт работу в BgWorker).
   - `VoiceAssistantService.settingsWatchdog` — все adb/runtime вызовы завернуты в `BgWorker.post("settingsWatchdog")`, чтобы цикл раз в 60 с не блокировал main.
   - `VoiceAssistantService.screenWakeReceiver` — `restoreIfEnabled`+`syncAll` и `captureLevel` унесены в `BgWorker.post`.
   - `HuNightMode.observe` callback обёрнут в `BgWorker.post`.
   - `StatusIcons.startPeriodicSync` — 15-секундный тик теперь только планирует на main, а реальную работу (syncAll → VHAL) уносит в `BgWorker.post("statusIcons.tick")`.

4. **Добавлены `BgWorker.assertNotMain(where)` guards** в прикладные методы, потенциально опасные на UI: `NetworkTtl.setTtl65Active/bootRestore`, `StatusIcons.setHvacDigital/bootRestoreHvacDigital/syncAll`, `AppPerformance.runSuCommand`, `ScreenBrightness.writeSysfs/writeProp`, `LockSound.onBootOrWatchdog`.

5. **Исправлен синтаксический баг в `AdbClient.executeAsync`** — не была закрыта фигурная скобка, весь файл AdbClient.kt не компилировался.

### TTL (-w 5 + VERIFY)

`NetworkTtl.CMD_ENABLE` / `CMD_DISABLE` переписаны:
- все вызовы iptables/ip6tables снабжены `-w 5` (ожидание xtables lock до 5 с).
- добавлены `TTL_BEFORE_V4/V6` для лога изменений.
- в конце делается `iptables -C` (check) и выводится `VERIFY_V4=OK/FAIL` и `VERIFY_V6=OK/FAIL` (ABSENT/PRESENT для disable).
- парсинг результата в `setTtl65Active` строже: `exitCode=0` не считается успехом сам по себе; проверяются `VERIFY_*` строки и счётчики TTL_V4/V6; детектируются маркеры `xtables lock` и `Permission denied`; статус OK/PARTIAL/FAILED отражает реальное состояние.
- лог строчки формата `CENTRAL_NetworkTtl: VERIFY TTL: expected … → got … → SUCCESS|FAILED`.

### P2 (screen_off_timeout vandalism) — ранее завершено
- Удалён watchdog на 30 сек, писавший `2147483647`.
- Удалён любой автоматический write в `SCREEN_OFF_TIMEOUT`.
- При первом старте читается текущее значение; если оно Int.MAX_VALUE — явное сообщение в лог с просьбой выставить таймаут вручную (заводское значение неизвестно до получения дампа прошивки).

### Ключевые оставшиеся задачи
- Получить и распаковать `geely_dump.tar` (LimeWire E2EE, ссылка с хешем `#vWZfRhAxeZ` содержит только 7 байт ключа; ожидаю новую ссылку от пользователя на незашифрованный хост).
- Достать из дампа `defaults.xml` / `build.prop` с заводским `screen_off_timeout` и правильными prop ID для batt/temp.
- Перевести `AdbClient` на реальную проверку `id → uid=0(root)` и VERIFY для всех root-команд (bind-mount, zygote restart, Wi-Fi toggle, SELinux audit), отключить `setenforce 0`.
- Lock sound: ретраи через root канал с ограничением (max 3) и детектом детерминированных ошибок (ENOENT, Permission denied).
- Объединить все log-теги под префикс `CENTRAL_*`.
- Остальные P3-P11 по плану.

## 2026-10-28 (dump получен)

Скачан `geely_dump.tar` (845 МБ, POSIX tar) из Google Drive (`1IqjOtjBqRZJxHS23kjx5-xiCJVA2717V`), распакован в `/home/user/dump/extracted/geely_dump/`. Состав:
- `getprop.txt` (701 пропа)
- `settings_system.txt` / `settings_global.txt` / `settings_secure.txt`
- `car_service_dump.txt` (5536 строк, список всех VHAL property + live values)
- `system_framework/`, `system_etc/`, `vendor_etc/`, `vendor_lib{,64}/` (framework, sepolicy, avas sounds, egl и т.п.)

### Ключевые факты из дампа:

1. **`screen_off_timeout=2147483647`** в текущих system settings — ГУ уже в сломанном состоянии от предыдущей версии приложения. **Заводское значение в дампе НЕ содержится** (нет `defaults.xml`, нет `ro.config.*screen_off_timeout` пропа, нет overlay XML). Android PQ2A.190405.003 (Android 9) framework default = 60000 мс (1 минута), но OEM (Geely) переопределяет в SettingsProvider overlay, который в этот дамп не попал. Придерживаемся безопасной политики: код НИКОГДА не пишет в `SCREEN_OFF_TIMEOUT` автоматически; пользователь должен выставить значение вручную один раз в системных настройках. Текущее поведение приложения остаётся корректным.

2. **HVAC digital props (актуальные имена)**:
   - `persist.sys.hvacdigital.batt=1`
   - `persist.sys.hvacdigital.temp=1`
   - `persist.sys.hvacdigital.exttemp=1` ← **а не `temp_ext`** (исправлено в `StatusIcons.kt`)
   - `persist.sys.hvacdigital.ext_temp=0` (старое имя, не используется)

3. **Правильные VHAL property ID для батареи и температуры** (из `car_service_dump.txt`):
   - **0x11600309 EV_BATTERY_LEVEL** — Float, range 0..100, changeMode=DYNAMIC — ЗАРЯД БАТАРЕИ %.
   - **0x11600703 ENV_OUTSIDE_TEMPERATURE** — Float, °C, changeMode=STATIC — внешняя температура.
   - 0x2140a6ed ED_EV_BATTERY_PERCENTAGE — Int ×10 (напр. 572 = 57.2%), запасной SOC.
   - В live-дампе: последнее событие ENV_OUTSIDE_TEMPERATURE = `floatValues: [25.0]` (25°C); ED_EV_BATTERY_PERCENTAGE = `int32Values: [572]` (57.2%).
   - Прежние угаданные ID `0x16400b00` и `0x16400c00` в car_service_dump **ОТСУТСТВУЮТ** — они неверные. Brute-force обход с шагом 256 по диапазонам приводил к попаданию на первые валидные float'ы (36/-22), не имеющие отношения к батарее/температуре.

### Исправления на основе дампа:

- `StatusIcons.SYS_HVAC_EXT` переименован с `persist.sys.hvacdigital.temp_ext` на **`persist.sys.hvacdigital.exttemp`** (соответствует пропу в getprop).
- Полностью переписаны `readSocScan()` и `readTempScan()` в `StatusIcons.kt`:
  - Первым источником выступают подтверждённые из дампа пропы (EV_BATTERY_LEVEL / ENV_OUTSIDE_TEMPERATURE / ED_EV_BATTERY_PERCENTAGE).
  - В лог пишется источник значения: `soc=74 (src=VHAL:0x11600309/EV_BATTERY_LEVEL raw=74.0 area=0)`, `temp=25 (src=VHAL:0x11600703/ENV_OUTSIDE_TEMPERATURE)`.
  - **Brute-force с шагом 256 по диапазонам 0x11400000-0x167FFFFF удалён** — он подбирал неверные пропы. Оставлен только фоллбэк по фиксированному списку PRECISE_COMMON с пометкой `[fallback]` в логе.
  - В Notification текст теперь содержит метку источника (`"$soc% · VHAL:0x11600309/..."`), чтобы видно было, откуда взялось значение.
- Пересоздан `BgWorker.kt` (оказался не сохранён в прошлом проходе).
- Исправлен пропущенный закрывающий `}` у `AdbClient.executeAsync()` и добавлен утерянный метод `AdbClient.submit()`.

---

## Оставшаяся работа (для следующего сеанса)

См. AUDIT_FIX.md «Оставшиеся блокеры» и «Требует проверки». Кратко:

1. **P7 LockSound** — копирование wav через root-канал AdbClient (base64 pipe), cap retries ≤3 с экспоненциальной задержкой, детерминированные ошибки (Permission/ENOENT/ERR_ROOT_CHANNEL_UNAVAILABLE) отключают тумблер, отключить штурм каждые 60 сек.
2. **P0 port 65010 диагностика** — добавить после AUTH/IOE диагностический вывод: getenforce, ls -la /dev/socket/adbd, service list | grep adbd, getprop init.svc.adbd, netstat -an | grep 65010, logcat -b all -d -t 30 adbd. Проверить uid=0 строгим regex `"uid=0(\\(root\\))?\\b"`.
3. **P4 TTL сеть** — BroadcastReceiver CONNECTIVITY_ACTION пере-применяет правила при wlan0 up; измерение реального исходящего TTL (ping -t 65 -c 1 1.1.1.1 / счётчики TTL).
4. **P8 VERIFY** в ScreenBrightness (writeProp → `settings get` подтверждение) и HvacDigitalPlugin (/proc/mounts VERIFY строка, вместо статусного кода).
5. **`./gradlew assembleDebug`** — нет Android SDK в sandbox, собирается на машине пользователя.
6. **Слить в main + push** после валидации на реальном устройстве (новый voiceapp3.log по всем §7 сценариям).

- **HvacDigitalPlugin / LockSound**: дочистил `setenforce 0` (был пропущен в enable/disable HvacDigitalPlugin и в LockSound.applySystemLockSoundBind). Вместо него — SELINUX_BEFORE/AFTER логи + pid before/after для SystemUI/audioserver + MOUNT_VERIFY_FAIL по inode в LockSound (P5/P6/P8).

---

## 2026-10-28 (вторая итерация, sandbox пересоздался между сессиями — все фиксы накатил заново)

### Сделано
- Инициализирован git в `/home/user/repo`, коммит `initial: import voiceapp3 Backup (pre-fixes baseline)`.
- Ветка `fix/log-driven-fixes`.
- BgWorker.kt: 4-поточный пул, post/run/assertNotMain/isMainThread, slow-task warning.
- AdbClient: фикс пропавшей `}` в executeAsync; добавлен submit() async c main-callback;
  строгая проверка uid=0(root) по regex; classifyOutput на 12+ маркеров;
  VERIFY EXEC линия на каждый вызов; жёсткий запрет на фоллбэк в sh для root-команд;
  пошаговая PORT65010 трассировка; portDiagnosticDump при AUTH/failure (getenforce,
  ls /dev/socket/adbd, netstat/ss listeners, ps adbd, adbd props, logcat -t 30 adbd).
- StatusIcons: убран брутфорс диапазонов; прямые VHAL-пропы из дампа
  (EV_BATTERY_LEVEL=0x11600309, ED_EV_BATT_PCT=0x2140a6ed/10, ENV_OUT_TEMP=0x11600703);
  SYS_HVAC_EXT=persist.sys.hvacdigital.exttemp; @Volatile socSource/tempSource;
  отдельные notification ID/каналы; syncAll на BgWorker; периодика постит тик через BgWorker.
- NetworkTtl: переписан с нуля; `-w 5` везде; TTL_BEFORE_*; VERIFY_V4/V6 через -C;
  счётчики до/после; ping-heuristic для фактического TTL; автоткл. тумблера при провале;
  BootReceiver VERIFY; CONNECTIVITY_ACTION ресивер пере-применяет при wlan0 up.
- AppPerformance: JIT — trap restore_wifi EXIT/INT/TERM, ZYGOTE_PID_BEFORE/AFTER,
  USEJIT_AFTER; video60fps — setenforce 0 убран, SELINUX_BEFORE/AFTER, MEDIA_PID_BEFORE/AFTER,
  SRC_INODE==TGT_INODE проверка + MOUNT_PRESENT, статусы OK/FAILED/PARTIAL/BLOCKED, VERIFY линия;
  assertNotMain на setAutoOptimize/setVideo60Fps.
- LockSound: копирование через ADB root-канал (base64 pipe); MAX_BIND_RETRIES=3 с backoff;
  детерминированные ошибки отключают тумблер (bindDisabledUntilBoot); VERIFY по inode;
  setenforce 0 убран; bind работает на BgWorker.
- VoiceAssistantService.onStartCommand boot-ветка вся на BgWorker; watchdog-тик тоже;
  NetworkTtl.registerNetworkReceiver вызывается при старте; HuNightMode.observe callback
  работает через BgWorker.
- UiBridge: onEvent settings.change/dev.runCommand/dev.getLogcat/simulateDoor*/
  testNotificationIcons/perf.* и onResume syncAll/syncOnContext обёрнуты в BgWorker
  с возвратом результата на main Looper.
- ScreenBrightness.writeSystemInt: readback VERIFY после записи.
- HvacDigitalPlugin enable/disable: setenforce 0 убран; SYSUI_PID_BEFORE/AFTER,
  SELINUX_BEFORE/AFTER, SRC_INODE/TGT_INODE; VERIFY линия; строгая классификация
  OK/FAILED/PARTIAL/BLOCKED.
- Единые теги CENTRAL_* на всех tools/+VoiceAssistantService/+UiBridge; AppLog.TAG_BASE=CENTRAL.
- Создан AUDIT.md с таблицей и разделами: «Ложный успех», «Порт 65010»,
  «Оставшиеся блокеры», «Требует проверки на устройстве», «Отличия от оригинала».
- Два пуша в origin fix/log-driven-fixes.

### Оставшиеся блокеры
1. screen_off_timeout keep-awake gate на IHU629G и durable-механизм (нужен запуск на ГУ
   и анализ, кто именно сбрасывает значение — наш код или OEM DisplayManager).
2. Сборка APK и снятие нового voiceapp3.log — на машине пользователя.
3. Финальный merge в main — после валидации на устройстве.

## iter24 — 1.0.46 (versionCode 46), ветка fix/v2

- **P0.3**: порт ровно один — 5555. Удалены `ADB_PORT_ROOT`/`ROOT_65010`/alt-port retry; `PORTS = [5555]`;
  на `IOException` канал закрывается (`ROOT_5555 → NO_ROOT_SH`), а не переключается на другой порт.
  Ретраев нет: `connect` 1500 мс, бюджет пробы 3000 мс (`AdbClient.kt:44-52, 265-292, 310-327`).
  Grep: `65010` — 0 совпадений в `app/src/main/java`.
- **РЕФЕРЕНС**: `Lkotlinx/coroutines/scheduling/h;-><init>(I)` — везде `0x15b3` (w0/k1.smali:880, w0/d0.smali:62,
  w0/g2.smali:231, w0/u1.smali:121). `Lw0/m;->l()` (ensurePortMigrated, cex_out/sources/w0/m.java:481-491) —
  ЭТАЛОН: МЁРТВЫЙ КОД (вызовы только из `Lw0/b5;->run`, закрыты pref `adb_port_migrated`; клиент эталона туда
  не ходит; по 6 логам владельца второй порт не дал root ни разу).
- **P0.2**: класс исключения пробы пишется в файл (`classifyProbeError`, `ADB_PROBE … kind=`).
- **P0.1 `ADBDIAG`**: контроль закрытых портов 5556/65011 → сырое чтение 24 байт от 5555 (n/hex/cmd) →
  `/proc/net/tcp` **и** `/proc/net/tcp6` по hex `:15B3` + `ss`/`netstat` → снимок свойств (read-only) →
  `setprop persist.sys.hvacdigital.diag 46` + readback.
- **P0.4 `ADBFIX`**: 1) `settings put global adb_enabled 1` (с откатом при UP_NONROOT), 2) `tcpip:5555`
  в `/dev/socket/adbd`, 3) GPIO 151 (`out 151 1` → `mt_gpio`, референс `MainActivity#b0(Z)`,
  cex_out/sources/com/ex/auto/MainActivity.java:2834-2903, вызов `La1/i;->run` a1/i.smali:533) — сначала читаем
  пин. После успеха — `ADBFIX verify` (id/`screen_off_timeout`/prop/iptables) сразу и через 10 с +
  `replayDeferred()` (TTL, hvacdigital props, syncAll).
- **P2.5**: `makeWifiIconCex` — `Paint.textSize` до `fontMetrics`, `fit`-страховка, `glyph=fit=` в `ICON_GEOM`.
  Причина поломки в логе 1.0.45: `kind=wifi h=23px glyph=40,3dp` против `kind=batt h=111px`.
- CI: run **41497873248** success, artifact **13168556358**, APK `/home/user/app-iter24-v1.0.46.apk`
  (sha256 `ae1cbdda4bbea16fadb6e71c0a7afa46d34bed956f226c408728a6cf6b7d5a02`).
- Открытая находка для 1.0.47: в логе 1.0.45 `DOOR_CLOSE … pos=0 inSet=false → skip reason=not_near_10` —
  ветка «не около POS 10» срабатывает и при уже закрытом стекле; причёсывать вместе с P3.

## iter25 — 1.0.47 (HOTFIX, versionCode 47), ветка fix/v2

- **Причина аварии 1.0.46**: шаг 3 `ADBFIX` писал `out 151 1` в
  `/sys/devices/platform/1000b000.pinctrl/mt_gpio` на старте; на этой ГУ пин 151 гасит USB
  (`08:10:29 GPIO 151 por sysfs: pedi 1, li 0`, через ~40 с USB перестал определяться).
- **P0-A/B/C**: удалены `fixStepGpio/gpioRead/GPIO_PATH/GPIO_PIN`, `fixStepTcpip`, `fixStepSettings`,
  `restoreAdbEnabled`, `adbEnabledPrev`, весь `adbFix()`. Строка `ADBFIX disabled (iter25: …)`.
  Grep: `mt_gpio/GPIO_PIN` — только комментарий+лог в App.kt; `adb_enabled`, `tcpip:5555`, `65010`,
  `setprop *.adb.tcp.port`, `ctl.restart adbd` — 0 совпадений.
- **ЭТАЛОН**: `MainActivity#b0(Z)` + `a1/i#run` — ручной переключатель по удержанию 2 с, не автоматика.
- **P0-D**: `ADBDIAG` и `classifyProbeError` сохранены; окно `rawReadProbe` 30000 → 8000 мс (`RAW_READ_MS`).
- **P0-E**: `ADBDIAG raw5555 multi` (3 соединения), `ADBDIAG 5037 host:version|host:devices` (smart-socket),
  `ADBDIAG ps adbd`, `ADBDIAG unix adbd`, `ADBDIAG prop sys.usb.*` — всё только чтение.
- **P0-F**: `USB_GUARD: iter25 …` в логе сразу после `BUILD:` (App.kt:23).
- **Дополнительно**: `OpenAppHandler` больше не пишет `Settings.Global persist.switch.usbmode` на старте
  (было в конструкторе, т.е. при каждом запуске) — только по запросу «инженер».
- **Сбой по пути**: первая сборка (`41587541445`) упала — широкое удаление задело `shellLocal`,
  `logAdbPortReadback`, `executeSmart`; восстановлены, `41588731417` success.
- CI: run **41588731417** success, artifact **13209098988**, APK `/home/user/app-iter25-v1.0.47.apk`
  (sha256 `2c7048b5b9f983623d58391c58b53725a7ecd77e6bac3a366a4d461c27fe271d`).

## iter26 — (A) recovery-пакет + (B) 1.0.48 (versionCode 48), ветка fix/v2

- **A**: `ota/PERSISTENT_PROPS_READ.zip` (828 981 B, sha256 `55ce8d61…d743ba`) — только чтение:
  копирует `/data/property/persistent_properties` (+ `.raw`, od, grep по adb-ключам) в `/data/media/0`,
  затем `abort()`. `update-binary`/`otacert`/`metadata` — байт-в-байт из пакета владельца
  (`656512a9…`, `9837de02…`, `e16ef005…`); изменён только `updater-script`. Подпись — формат `SignApk -w`
  (whole-file PKCS#7 SHA-1/RSA): диапазон `data[0:size-2-comment_len]` подтверждён эмпирически на пакете
  владельца, DER взят его же, заменены только 256 байт подписи. Инструкция — `ota/README_PERSISTENT_PROPS_READ.md`.
- **B.1**: `ADB_TOGGLE` — `Settings.Global.ADB_ENABLED` 0→1 с восстановлением в `finally`, одна попытка за старт,
  `ADBFIX verify` (id / screen_off_timeout / prop / iptables с `$?` сразу после iptables).
- **B.2**: `probeUnixAdbd()` — `LocalSocket("adbd", ABSTRACT)` → CNXN → `OPEN shell:id`; при uid=0 → `ChannelState.ROOT_UNIX`,
  все root-команды через unix (`runCommand`), TCP не трогается.
- **B.3**: `measureAcceptQueue()` — `ADBDIAG accept: recvQ=… established=… inode=…` при открытом соединении.
- **B.4**: `RAW_READ_MS` 8000→3000, multi-probe 3→2, `ADBDIAG` один раз за загрузку (`adbdiag_boot_id` + boot_id).
- Известное ограничение: `ROOT_UNIX` не учитывается в `ToolsPanel` и `HvacDigitalPlugin.haveRoot` (мандат запретил
  их менять) — правка в 1.0.49.
- Сбой по пути: `run 41665587128` — `unresolved reference 'set'` (Boolean вместо AtomicBoolean), исправлено.
- CI: run **41667388778** success, artifact **13248025345**, APK `/home/user/app-iter26-v1.0.48.apk`
  (sha256 `5ea979d87a218f8d760d64f3551598ed8fd3a1f3acaa64fc4429c98087e1d42e`).

## iter27 — 1.0.49 (закрытие ревью iter26: D1–D4), ветка fix/v2

- **D1**: `probeUnixAdbd()` пробует ДВА пространства имён: `Namespace.FILESYSTEM` + `/dev/socket/adbd`
  (adbd создаёт файловый сокет), затем `Namespace.ABSTRACT` + `"adbd"`. Перед подключением лог
  `exists=/canRead=`, все строки с `ns=filesystem|abstract`; сработавшее пространство → `unixNs` и
  используется в `execAdbLocalSocket(..., nsName)`.
- **D2**: `AdbClient.hasRoot()` (ROOT_5555 ∪ ROOT_UNIX) и `channelLabel()`; `ToolsPanel` показывает
  «канал готов — unix-сокет «adbd»», `HvacDigitalPlugin.haveRoot = AdbClient.hasRoot()`,
  подпись шага `restore` — «5555 или unix-adbd». Ложный «канал недоступен» устранён.
- **D3**: пауза с выключенным `ADB_ENABLED` 3000 → 1500 мс; после восстановления читается
  `sys.usb.state`/`sys.usb.config` и пишется `ADB_TOGGLE usb_state=…`. Откат — только `finally`.
- **D4**: `ADBDIAG accept: (держим 1 соединение, пока читаем таблицы)`.
- Пакет `ota/PERSISTENT_PROPS_READ.zip` не менялся (ревью подтвердило подпись независимо).
- CI: run **41670466620** success, artifact **13248522532**, APK `/home/user/app-iter27-v1.0.49.apk`
  (sha256 `941917ae6e2b6e1f9cfac7f0f4c29293f550bd06963b6df278e730f9be06c2dc`).

## iter28 — 1.0.50 (versionCode 50), ветка fix/v2

- **Перевёрнутый диагноз (факт владельца)**: с ПК `adb connect <ip>:5555` даёт `uid=0(root)`, `u:r:su:s0`
  ⇒ канал жив, сломано НАШЕ рукопожатие. Все «оживляющие» шаги сняты со старта.
- **P0-A**: `A_VERSION=0x01000000`, `A_MAXDATA=262144`, баннер `host::features=…` (265 Б), второй вариант
  `0x01000001` + legacy-баннер. Единая `handshake()` для всех соединений. Логи `ADB_TX … hex=<24 байта>`,
  `ADB_RX attempt=… cmd=… hex=… dt=…`. Старый заголовок: `arg1=4096, len=20`; новый: `arg1=262144, len=265`.
- **P0-B**: новый `tools/AdbAuth.kt` — RSA-2048, ключ в `filesDir/adb_key.pk8`, публичная строка в `adb_key.pub`,
  подпись токена `SHA1withRSA`, обработка `AUTH arg0=1/2/3`, `ADB_PUBKEY`/`ADB_PUBKEY_LEN` в лог.
- **P0-C**: unix-сокет из bootstrap убран, осталась строка `ADBDIAG unix-socket exists/canRead`.
- **P0-D**: `ADB_TOGGLE` из bootstrap убран (в 1.0.49 `NO_EFFECT`, риск USB), `measureAcceptQueue` только
  в `ADBDIAG`, `replayDeferred()` возвращён после успеха.
- **P0-G**: `adbKeysSelfRegister()` — read/write проверка `/data/misc/adb/adb_keys`, дописывание только своей
  строки, `chmod 640`/`chown system:shell`/`restorecon`, логи `ADB_KEYS …`.
- **P0-E**: в `ADBDIAG` добавлены `ro.product.device`, `ro.build.version.sdk`, `ls -l /data/misc/adb/`,
  `wc -l < adb_keys`, строка `ADB_HINT`.
- Сбой по пути: `run 41693222975` — `Too many characters in a character literal` (`' | '`), исправлено.
- CI: run **41695214064** success, artifact **13260419331**, APK `/home/user/app-iter28-v1.0.50.apk`
  (sha256 `d6468cd53ff7644ff255626c8ca3dd74db43adcb20902e62da4edda586614481`).

## iter29 — 1.0.51 (versionCode 51), ветка fix/v2

- **Гипотеза AUTH/ключей закрыта навсегда** (факты владельца с ПК): `/data/misc/adb/` → `total 0`,
  `wc -l < adb_keys` → `No such file or directory`, `ro.secure=0` (лог 1.0.50) ⇒ adbd авторизацию
  не требует, а ПК получает `uid=0(root)`. `adbKeysSelfRegister()` удалён целиком, `handshakeExperiment()`
  и второй вариант CNXN убраны, формат пакета НЕ менялся (`arg0=0x01000000 arg1=262144 len=265`).
- **P0-A0**: `ADB_SELF: uid=… pid=… scontext=< /proc/self/attr/current >` — первой строкой `bootstrap()`.
- **P0-A**: `localTargets()` (Wi-Fi IPv4 → `127.0.0.1` → IPv6 → `::1`), `probeDestination()` на каждый адрес
  (connect 1500 мс → CNXN → окно 3 с) и строки `ADB_DEST addr=… connect=… rx=… cmd=… dt=…`,
  итог `ADB_DEST result=<ip|none> working=<ip>`. Рабочий адрес хранится в `@Volatile workingHost`,
  все команды (`execAdbSocket`) идут через него; в лог `ADB_CHANNEL host=… port=5555`.
  `channelLabel()` показывает реальный адрес вместо жёсткого `127.0.0.1`.
- **P0-B**: `AdbAuth.init()` — один раз только ради строки `ADB_PUBKEY`/`ADB_PUBKEY_LEN`; вместо
  self-register одна строка `ADB_KEYS: n/a (ro.secure=0 — adbd не требует ключей)`. Ветка `AUTH`
  в `handshake()` оставлена (реактивная, на старте не срабатывает). Записей в `adb_keys` нет.
- **P0-C**: `AUTH` от адреса → `auth=required`; все адреса `silent` → `ADB_DEST note=probe-without-pc-session`.
- **P0-D**: `ADBDIAG` сжат до `/proc/net/tcp6` (`:15B3`) + 5 `getprop` + `wc -l adb_keys` (≤3 с);
  удалены `measureAcceptQueue`, `smartSocketProbe`, контрольные `5556/65011`, `ps adbd`, `/proc/net/unix`,
  raw-чтения, `ADB_SERVER_PORT`. `ADB_TOGGLE` из bootstrap не вызывается (функция осталась приватной,
  без вызовов). Порядок старта: `ADB_SELF` → `ADB_PUBKEY` → `ADB_DEST` → (успех: `channelUp` +
  `adbFixVerify` + `replayDeferred`) → иначе `ADBDIAG`.
- **Запреты** (grep): `mt_gpio` 0 в коде, `65010` 0, `tcpip:5555` 0, `setprop *.adb.tcp.port` 0,
  `ctl.restart adbd` только комментарии, записей в `adb_keys` 0, `/sys/class/android_usb` 0.
- Сбой по пути: дубль-диспатч (`41773999518` отменён GitHub, `41774001800` success) — не код.
- CI: run **41774001800** success, artifact **13296771475**, APK `/home/user/app-iter29-v1.0.51.apk`
  (215 284 077 B, sha256 `1fbdadeae91f98367efe3b5a3a334d4f571a1ac1c02539e22304e6f3b9f7141a`).

## iter30 — правка версии 1.0.51 (номер НЕ менялся, `app/build.gradle.kts` не тронут)

- **P0-A**: порядок адресов изменён на Wi-Fi IPv4 → `127.0.0.1` → `::1` → остальные (`AdbClient.kt:418-448`),
  проба «3 попытки на адрес с паузой 1 с» (`:71-73`, `:608-641`), бюджет 32 с; строка на адрес
  `ADB_DEST attempt=<n> addr=… connect=… rx=… cmd=… dt=…`, итог `ADB_DEST result=… working=…`.
- **P0-B**: `ADB_LISTEN inode/uid/st/table` по `:15B3` (`:659-672`), `ADB_OWNER` — поиск `socket:[inode]`
  по `/proc/<pid>/fd` через `Os.readlink` (`:674-702`, `err=denied` — тоже результат), `ADB_PS` (`:704-708`),
  `ADB_SS` (`ss`/`netstat`, `has_adbd=`) (`:710-718`); всё — только после первой неудачной пробы.
- **P0-B2**: `ADB_SELF scontext=… uid=… pid=…` первым действием `bootstrap()` (`:319`, `:403-412`).
- **P0-C**: три снимка строки ядра по `s.getLocalPort()` — 0/500/2000 мс — `ADB_ROW t=… lport=… st=… rx_queue=…
  tx_queue=… found=… table=…` (`:497`, `:500`, `:506`, строка `:601`).
- **P0-D**: `ADB_LOGCAT src=tag_adbd|grep …` (tail 20, ≤10 строк, ключи `cannot bind`/`Address already in use`/
  `new connection on fd`/`send_auth_request`/`failed to write apacket`) — `:726-742`.
- **P0-E**: `EXEC_PROP key=diag … err=…` (уникальное значение), round-trip `batt`/`temp`/`ext_temp`
  (значения НЕ портим — они показываются в статус-баре), `EXEC_PROP keys=ok=<n>/3`, ветка `via=app`
  только если `via` пуст или уже `app` (`:756-798`, `runShellCapture` с отдельным stderr `:802-820`).
- **P0-F**: `ADB_VERDICT channel=… listener_owner=… rx_queue_last=… propwrite=…` (`:826-829`, вызовы `:352`,
  `:392`) и один отложенный ретрай `ADB_RETRY t=60s result=…` (`:832-859`).
- **Формат пакета не менялся** (`A_VERSION=0x01000000`, `A_MAXDATA=262144`, баннер с `features=`); проба
  «старыми байтами 1.0.43» (`arg1=4096`, `host::central-geely`) не добавлялась — предложена на 1.0.52.
- Сбой по пути: run `41792071025` — `failure` по вине раннера (`No space left on device`), тот же коммит
  перезапущен → `41793783872` success.
- CI: run **41793783872** success, artifact **13305252463**, APK `/home/user/app-iter30-v1.0.51.apk`
  (215 308 089 B, sha256 `d29d8c9b02546c6a0db135a061a6cd22423c558055a1d2a6c2e52fa9570aead7`).

## iter30b — дельта к 1.0.51 (номер версии НЕ менялся, `build.gradle.kts` не тронут)

- **F1**: проба `ADB_DEST` покрывает оба варианта пакета — `probeDestination(addr, attempt, variant)`
  (`AdbClient.kt:510`): `variant=0` → `arg1=262144` + `HOST_BANNER_FEATURES`; `variant=1` → `arg1=4096`
  (`A_MAXDATA_LEGACY`, `:68`) + `HOST_BANNER_LEGACY`. Чередование `a % 2 == 1 → 0, иначе 1` (`:668`);
  в логах появились поля `variant=` (`:522`, `:536`, `:556`, `:561`, `:565`) и `variants=0/1`
  в сводке по адресу (`:688`). Протокол/заголовок/размеры/порядок адресов не менялись.
- **F2**: `logAdbProps()` (`:709-714`) — `ADB_PROP key=<имя> val=<значение|(empty)> rc=<n>` для пяти
  adb-свойств, вызывается **всегда** сразу после `ADB_SELF` (`:332`), вне гейта `diagAllowedThisBoot`;
  дублирующий блок в `adbDiag` заменён комментарием (`:1008-1012`), литералы вынесены в `PROPS_ADB` (`:59`).
- **F3**: при `listenerOwner == "foreign"` — **одна** проба unix-сокета (`:380-397`), итог
  `ADB_UNIX verdict=up|down state=… uid=… ns=…` (`:386`), иначе строка `ADB_UNIX skipped reason=…` (`:398`).
  При успехе: `listenerOwner="unix"`, `channelUpUnix(dt)` (`:987-994`) — **не** `channelUp`, потому что тот
  ставит `state=ROOT_5555` и увёл бы команды на мёртвый TCP; далее `adbFixVerify`, `EXEC_PROP`,
  `replayDeferred`, `logVerdict(true)` и `return` (без `scheduleDeferredRetry`).
- Запреты (grep по `app/src/main`): `mt_gpio` 2 (комментарий + строка лога `App.kt`), `65010` 0,
  `tcpip:5555` 0, `setprop *.adb.tcp.port` 0 (`AdbClient.kt:35` — комментарий), `ctl.restart` — только
  комментарии в `AdbClient.kt` + чужие `AppPerformance.kt` (не менялись), записи в `adb_keys` 0,
  `android_usb` 0, `ADB_TOGGLE` — только внутри невызываемой `adbToggleFix()`, новых `settings put` 0.
- Сбои по пути: run `41796467201` — `failure` по вине раннера (`No space left on device`).
  В дело не идут промежуточные `41798265725` и `41800171837` (сборки до правок).
- CI: run **41802282277** success, artifact **13309251411**, APK `/home/user/app-iter30b-v1.0.51.apk`
  (215 312 005 B, sha256 `7569b9879bcb5685d0053c1a41f6394013eabcd6af702e0cf1870e9603e6461f`).

## iter30c — правка 1.0.51 (номер версии НЕ менялся, `build.gradle.kts` не тронут)

- **F4**: победивший вариант пакета теперь доходит до реального канала. Новое поле
  `@Volatile workingVariant` (`AdbClient.kt:134`), записывается в `discoverDestination` при `CNXN` (`:689`)
  и в AUTH-ветке (`:709`); `ADB_CHANNEL host=… port=… variant=…` (`:691`, `:1593`, `:1595`).
  `fullProbe()` передаёт вариант явно (`:961-965`), `execAdbSocket` по умолчанию берёт
  `workingVariant` (`variant: Int = -1` → `effVariant` `:1575-1582`, `:1605`).
- **Согласование байт**: в `handshake` вариант 1 = `version = A_VERSION (0x01000000)`,
  `arg1 = A_MAXDATA_LEGACY (4096)`, `HOST_BANNER_LEGACY` (`:1054-1059`); `0x01000001` из кода убран
  (осталось только упоминание в комментарии). Проба `:544-549` и рукопожатие `:1054-1059` —
  одна и та же формула; hex-заголовки совпадают (см. таблицу в `REPORT_iter30c.md` §2).
- **`ADB_TX`** печатает фактические значения + `variant=` (`:1060`), `ADB_RX` — `attempt=1 variant=…`
  (`:1068`, `:1071`); `attempt = variant + 1` убран (`:1605`).
- **`ADB_UNIX skipped`**: причины раздельно — `owner_adbd` / `owner_unknown` (`:407-409`).
- Запреты не нарушены (grep: `mt_gpio` — комментарий+лог в `App.kt`, `65010` 0, `tcpip:5555` 0,
  `setprop *.adb.tcp.port` 0, `ctl.restart adbd` только комментарии, `adb_keys` без записей,
  `android_usb` 0, `ADB_TOGGLE` только в невызываемой `adbToggleFix()`, новых `settings put` 0).
- CI: run **41805548902** success, artifact **13309338493**, APK `/home/user/app-iter30c-v1.0.51.apk`
  (215 313 093 B, sha256 `ae1ba6150835dc82d5b7a6b42f7cb4496e47bfafbaeef7fbd7bcfbc02e30433c`).
- Косметика: у коммита `98ae5b6` автор записан как `AlekseiAlekseiKov <x@y>` (мой промах);
  исправление требует `--force`, который запрещён, поэтому оставлено как есть.

---

## iter32 — 1.0.52 (константы ADB + два окна при двух дверях)

Коммиты `953efe0` (A) и `10d0d15` (B), ветка `fix/v2`. CI: прогон **41870804902** success,
артефакт **13344508455** (`app-debug`). Две предыдущие попытки (`41866855101`, `41868954365`)
упали на раннере: аннотация `System.IO.IOException: No space left on device` — не код.

**A. ADB-протокол (`AdbClient.kt`)**
- Корень «мёртвого канала»: `CNXN` был записан как `0x4e595343` ⇒ в сеть уходило `43 53 59 4e` = "CSYN"
  (`OKAY`="OAKB", `WRTE`="ETRW", `CLSE`="ESLC"). adbd такой пакет принимал, `case` не находил и молчал.
- Теперь константы **вычисляются** из имени: `cmdOf(name)` (LE-разбор ASCII) `:113-121`, руками не задать.
- `A_VERSION = 0x01000001` `:70`; `A_VERSION_LEGACY = 0x01000000` `:71` (пакет 1.0.43 = вариант 1 пробы);
  `versionOf(variant)` `:141`; `A_MAXDATA` не менялся.
- `sessionVersion` `:138` + `checksumFor()` `:1207-1213`: при версии ≥ `0x01000001` сумма = 0 (как platform-tools);
  версия из ответа adbd учитывается (`:1162`).
- `readMsg` `:1749-1758`: `sum == 0` валидна ВСЕГДА (+ `ADB_RX note=checksum_skipped len=…`), строгая проверка — только для `sum != 0`;
  `magic` по-прежнему строго `:1745`.
- `ADB_CONST`: `adbConstSelfTest()` `:502-530` — по строке на команду (`name/val/ascii/ok`) + независимая сверка с AOSP
  (`ADB_CONST aosp …`) + итог `ADB_CONST all=ok|FAIL`; вызывается первой строкой `AdbWorker` `:537`.
- `hex4=… ascii=…` в `ADB_TX`/`ADB_RX`: `:634`, `:1146`, `:1157`, `:1163`.
- Unix-путь не тронут функционально: по-прежнему пакет 1.0.43 (`:1319-1321`, `:1372-1374`).
- Диагностика iter29/30/30b/30c (`ADB_DEST`, `ADB_ROW`, `ADB_LISTEN`, `ADB_OWNER`, `ADB_SS`, `ADB_LOGCAT`, `EXEC_PROP`,
  `ADB_VERDICT`, `ADB_RETRY`, `probeUnixAdbd`) не изменена.

**B. Окна (`WindowDoors.kt`)**
- Плоский `activeVentWindows: HashSet<Int>` заменён на `ventOwners: HashMap<Int, MutableSet<Int>>` `:95` + `ownersOf()` `:98`
  (плюс `isOurs` `:101`, снимки `:104-113`).
- Открытие: после `set POS 10` владельцем окна становится конкретная дверь (`:428-436`, лог `VENT_OPEN … owners=…`).
- Закрытие: кандидаты — только окна, где закрывшаяся дверь есть в владельцах (`closeCandidates()` `:452-462`);
  `OPPOSITE_WINDOW` остался ТОЛЬКО для legacy-окон. Дальше `closeVentWindow()` `:467-508` →
  чистая `ventCloseDecision()` `:605-627`: `not_ours` / `needed_by_door_0x…` / `not_near_10` / `close`.
- Синхронизация владельцев `:474-480`: если окно напрямую принадлежит другой ОТКРЫТОЙ двери (`DOOR_TO_WINDOW[d] == w`),
  эта дверь становится владельцем — иначе окно осталось бы «ничьим» (единственное расширение правил мандата).
- Персист: новый ключ `vent_owners` формата `10=1,4;40=4` (`:54`, `:126-137`); старый `vent_windows` только читается
  для миграции (`:157-173`, лог `VENT_LEGACY_MIGRATE win=0x… (owners неизвестны)`), не пишется и не удаляется.
- Чистая функция `shouldCloseAfterDoorClose(...)` `:587-600` + `ventLogicSelfTest()` `:642-664` (6 кейсов мандата,
  лог `VENT_SELFTEST n=… decision=… expect=… ok=…`, итог `VENT_SELFTEST all=ok n=6`), вызов из `maybeInit` `:274`.
- Открытые вручную окна не закрываются (запись владельца появляется только после успешной записи POS 10).
- Порядок событий корректен: `doorStates[doorAreaId] = isOpen` (`:347`) — до любых решений.
- Экранной индикации в нашем коде нет (`grep WINDOW_POS` по `ui/` пуст) — правился источник (VHAL), `UiBridge.kt` не тронут.

**Отклонения/пробелы (честно)**
- Юнит-тест-инфраструктуры в проекте нет (`app/src/test` отсутствует, junit не подключён) → по разрешению мандата
  проверки оформлены как обычные функции с логом (`ADB_CONST all=…`, `VENT_SELFTEST all=…`).
- Проверка на автомобиле не выполнялась; байтовые доказательства — воспроизведение формулы в Python.

## iter33 — 1.0.53 (versionCode 53), ветка `fix/v2`

**Что сделано (файлы → суть)**
- `app/build.gradle.kts:60-61` — 53 / «1.0.53»; `:159` junit; `:218-221` `assembleDebug|assembleRelease` зависят от `testDebugUnitTest`
  (workflow не правился: PAT без scope `workflow`, push в `.github/workflows/build.yml` был отклонён).
- `tools/ScriptChunker.kt` (новый) — заливка скрипта **только через base64** (`chunks()` `:36`, `clearCommand()` `:50`,
  `chunkCommand()` `:59`, `verifyAndRunCommand()` `:67` c предохранителем `exit 99`). A0.
- `tools/AdbClient.kt:1748-1762` — `wrapForRc`/`splitRc`: `sh -c '<cmd>'; echo "__ADB_RC=$?"`, значение → `rc` (A1).
  `:257-280` — `executeScript` ≤3500 Б напрямую, иначе чанкер; `ERR_SCRIPT_UPLOAD` → `AppLog.e` (A0.2).
  ADB_TX/RX через `LogKit.trace` с `ADB_TRACE_SUPPRESSED` (C3). `Job.op` + порядок параметров (trailing lambda = `onResult`).
- `tools/LogKit.kt` (новый) — единственный источник структурированного лога: `SESSION_START`/`LOG_INDEX`/`OP_START`/`OP_STEP`/
  `OP_SUMMARY`/`CYCLE_*`/`HEARTBEAT` (`:244`) /`LOG_SUMMARY` (`:261`) /`SCRIPT_TRACE` (`:111`) /`RU:`; `app.ops.log` ≤200 строк
  (`flushOps` `:84`); подавление шума `trace()` `:293`; `kv()` `:325`. Писатель остался один — `AppLog.writeRaw` (C8).
- `tools/HvacDigitalPlugin.kt` — A3: ветка `PM_PATH=/data/app/*` → `pm uninstall` / `pm uninstall-system-updates` /
  `cmd package uninstall-system-updates` (+`HVAC_UNSHADOW: before=… result=OK|FAILED`, `:218/:224`, `exit 2` при провале);
  A4: убраны `setenforce 0`, `rm -rf /data/dalvik-cache/*`, `sleep 3`; `kill` SystemUI только при `APK_REPLACED=1` и
  один раз за процесс (`:23`, `:175`, `:264-283`); A5: `HVAC_PROBE`/`HVAC_RESULT` (`:329-338`); `###STAGE` → `SCRIPT_TRACE`.
- `tools/StatusIcons.kt:886-905` — A2: реальный `Status` через `when`; `bootRestoreHvacDigital` (`:939`) только читает;
  `HVACDIGITAL_PUSH` только при смене fingerprint (`:776-806`, B5).
- `tools/AppPerformance.kt:354-376,398-410` — `isVideoApplied()` первым делом, `SKIP (already applied)`, ожидание по `pidof`
  вместо `sleep 3` (`:470-480`); watchdog вызывает только `verifyVideoOnly`.
- `tools/NetworkTtl.kt:238-272` — read-first `ttlRuleCounts()`, `TTL: SKIP (rule present)` (B4).
- `tools/LockSound.kt:828-836` — звук через `executeScript` (чанкер), не в watchdog (B3).
- `VoiceAssistantService.kt:413-440` — watchdog только читает: `NetworkTtl.bootRestore`, `StatusIcons.bootRestoreHvacDigital`,
  `AppPerformance.verifyVideoOnly`, `LogKit.heartbeat()/writeSummary()`; `:447` `onDestroy` → `LOG_SUMMARY`.
- `app/src/test/.../ScriptChunkerTest.kt` — 4 теста, `scriptOfExactly(4963)` даёт ровно 4963 Б кириллицы.

**CI:** прогон **41996706107** = success (`> Task :app:testDebugUnitTest`, `BUILD SUCCESSFUL in 3m 16s`),
артефакт **13400294287**, zip **177 067 555 Б**, APK **215 383 361 Б**. До него 3 неудачных прогона (вложенный `/*`,
порядок параметров `Job`, генератор теста) — исправлены коммитами `44526ed`, `a3a4964`, `6acbc27`.

**Пробелы:** на ГУ не проверялось; бюджеты B5 не измерены (ждут первого лога 1.0.53); какая ветка
`uninstall-system-updates` нужна прошивке — НЕ ДОКАЗАНО. Подробно — `REPORT_iter33.md` §F3.

## iter33b — 1.0.53 (пересборка, версия НЕ поднята), ветка `fix/v2`

База `6acbc27`. Коммиты: `14dbf68` (все правки), `de67be0` (fix сборки), `455678e` (строка `restored_from_persist`).

**A — после ребута ГУ**
- `HvacDigitalPlugin.kt:382-419` `bootInstall()`: `AtomicBoolean` + преф `hvac_boot_install_uptime` (повтор <5 мин uptime = рестарт процесса),
  `checkStatus` + `stat -c '%i'` источника/цели ДО записи (`inodeMatchesStage`, `:421-431`), лог
  `HVAC_BOOT: attempt=1 reason=first_boot|not_mounted kill=0|1 result=OK|FAILED ms=…`; при FAILED повторов нет.
  Вход: `StatusIcons.bootInstallHvacDigital` (`:946`), вызов только из boot-ветки (`VoiceAssistantService.kt:368`).
- `AppPerformance.kt:655-687` `bootRestoreVideo()`: `applied_before=true` ⇒ SKIP без записи; иначе один `setVideo60Fps`
  за загрузку (`videoBootAttempted`), лог `VIDEO_BOOT: pref=on applied_before=… result=… ms=… action=skip|install`.
- `AppPerformance.kt:720-800` `bootRestoreJit(secondPass)`: маршрут 1 (persist `persist.sys.dalvik.vm.usejit`, пишется в
  `setAutoOptimize` `:252-262`) → `JIT_PERSIST_PROBE: supported=true|false`, `JIT_BOOT: match|restored_from_persist|deferred|unsupported`;
  маршрут 2 — три команды `setprop usejit/usejitprofiles` + `ctl.restart zygote` при `uptime<120 с`, метка `files/jit_boot_attempt`,
  через 20 с `JIT_VERIFY: runtime_after=… zygote_pid_before/after result=OK|FAILED`; watchdog — `verifyJitOnly` (getprop, без записи).

**B — лог**
- B1: `writeSummary()` убран из тика; таблица → `LOG_SUMMARY_ROW` в `app.log`, в выжимку только шапка + 5 действий.
- B2: `setThreadOp(null)` после `← execute` (`AdbClient.kt:330-338`) ⇒ `op=` есть в итоговой строке.
- B3: `LogKit.traceAdb` пишет под тегом `CENTRAL_AdbClient`, кольцо `jobRing` (40 строк) + `failJobTrace` при `rc!=0`
  ⇒ `ADB_TRACE_REPLAY` с полным hex/ASCII; `ADB_TRACE_SUPPRESSED … rc=`.
- B4: `flushOps` — не чаще 2 с, запись ВНУТРИ `synchronized(lock)`, принудительно в `heartbeat`/`[ERROR]`/`onDestroy`.
- B5: скрипт печатает `VIA=bind|nenhum|install`, регулярка `IGNORE_CASE` ⇒ `HVAC_PROBE … via=bind`.

**C/D**
- C1: `heavy = (n % 5 == 0)` — TTL+HVAC+видео+JIT только каждый 5-й тик ⇒ ≈10 команд/10 мин; яркость read-first (`ScreenBrightness.kt:161-176`).
- C2: `ttlRuleCounts()` вернул `-1` ⇒ `TTL: SKIP (unknown, channel down)`, заливки нет.
- D1: `MEDIA_PID_BEFORE/AFTER` — один `pidof media.swcodec` с обеих сторон (было три против одного ⇒ `MEDIA_WAIT_TICKS=0`).
- D2: `OP_SUMMARY … steps=<ok>/<N> ok= partial= failed= total=` (`ToolsBackend.kt:216-223`).

**CI:** `42002436101` failure (дубль `isAutoOptimizeEnabled`) → `42004017024` success (артефакт 13403369333) →
`42005750488` success, артефакт **13405077055**, zip **177 079 438 Б**, APK **215 509 471 Б**, `> Task :app:testDebugUnitTest` в логе,
`BUILD SUCCESSFUL in 4m 14s`. Отчёт — `REPORT_iter33b.md`.

## iter33c — 1.0.53 (вторая пересборка, версия та же), ветка `fix/v2`

База `c252ecf`. Коммиты: `edaebae` (правки), `29f18ec` (`testLogging`).
Рабочая папка `work9` оказалась обрезанной (пропал `.git`) → работа продолжена в свежем клоне `/home/user/work10`.

**A — вердикт плагина климата**
- Новый `tools/HvacVerdict.kt` — ЧИСТЫЙ разбор (без Android): `parse()` читает `###STAGE name=bind status=OK|SKIP|FAILED`
  (`reason=inode_match:<n>`), отдельные `^SRC_INODE=`/`^TGT_INODE=`, итоговую строку `^VIA=…$` (якоря обязательны)
  и служебное `unshadow_via=`; `statusOf()` — статус шага (`mountOk` + bind OK|SKIP ⇒ OK, PARTIAL только без вердикта bind).
- `HvacDigitalPlugin.kt`: скрипт печатает `TGT_INODE=` отдельной строкой (`:249`, `:265`), служебное поле
  `HVAC_UNSHADOW: … unshadow_via=…` (`:224`, `:232`); разбор заменён на `HvacVerdict` (`:313-355`);
  в `HVAC VERDICT:` добавлены `unshadow_via=` и `bind_stage=`.
- `app/src/test/.../HvacVerdictTest.kt` — 6 тестов (успех с `via=pm_uninstall` внутри, провал затмения,
  подчёркивание в служебном поле, мусор, SKIP, BLOCKED/FAILED).
- `app/build.gradle.kts:224-233` — `testLogging(passed,failed,skipped)`: имена и число тестов в логе CI.

**B — JIT**
- `AppPerformance.kt`: `attemptInThisLoad()` (`:845`) → `JIT_BOOT: match_after_zygote` вместо ложного
  `restored_from_persist` (`:742-760`); `persist_present=` во всех ветках `JIT_PERSIST_PROBE`;
  `jitPersistBackfill()` (`:833`) пишет единственную разрешённую persist-запись и по клику (`reason=toggle`),
  и на boot (`reason=boot_backfill`); маршрут 2 (`ctl.restart zygote`) — ТОЛЬКО на `secondPass` (`:772-777`),
  поэтому за загрузку ровно один перезапуск и он после установки плагина.

**C**
- `JIT_LATE_START_MS` → `JIT_ZYGOTE_WINDOW_MS` (`:688`), `reason=late_start` → `reason=outside_window` (`:779`).
- `LogKit.kt:112-141` — `scheduleTailFlush()`: хвост выжимки сбрасывается через 2 с, а не ждёт `HEARTBEAT` (60 с).

**CI:** `42018524877` (`edaebae`) success — артефакт 13410374029, zip 177 080 461 Б / APK 215 519 395 Б;
`42020153399` (`29f18ec`) success — артефакт **13411367824**, zip **177 080 524 Б**, APK **215 519 395 Б**,
`BUILD SUCCESSFUL in 2m 59s`, **10 тестов PASSED** (6 × HvacVerdictTest + 4 × ScriptChunkerTest). Отчёт — `REPORT_iter33c.md`.

## iter33d — 1.0.53 (третья пересборка, версия та же), ветка `fix/v2`

База `bf8ceb2`, коммит `1bde92f`.

**A — метка загрузки (главная функциональная правка)**
- Новый `tools/JitBootMark.kt` (чистый Kotlin, без Android): `format(tag, uptime)` → `"<tag>:<uptimeMs>"`,
  `sameLoad(marker, currentTag, tolerance=60_000)` — legacy-число без `:` ⇒ true; совпадение тега ⇒ true;
  числовые (epoch) теги ⇒ `abs(a-b) <= 60 c`; всё остальное ⇒ false. `isLegacy`, `uptimeOf`.
- `AppPerformance.kt:877` `currentBootTag()`: `/proc/sys/kernel/random/boot_id`, иначе
  `"epoch" + (currentTimeMillis - elapsedRealtime)/1000`.
- `AppPerformance.kt:733` — строка `JIT_BOOT: attempt_marker boot_tag=… same_load=… uptime_ms=… marker=…`
  на каждом проходе; `:737-741` — legacy-метка переписывается (`action=rewrite`).
- Guard маршрута 2 (`:807`) и `attemptInThisLoad` (`:866`) используют ОДНУ функцию `JitBootMark.sameLoad`.
  Удалены `JIT_ATTEMPT_GUARD_MS`, арифметика по разнице uptime и вторая копия эвристики.
- `app/src/test/.../JitBootMarkTest.kt` — 6 тестов (разные загрузки не блокируют, та же загрузка,
  больший uptime, legacy, мусор, epoch-допуск).

**B** — `LogKit.kt:136` хвост выжимки уходит в `BgWorker.postDelayed` (`BgWorker.kt:92`, планировщик `:39`);
`Handler`/`Looper` из `LogKit` удалены (в файле остался только комментарий). Максимум одна задача в очереди.

**C** — `AppPerformance.kt:750-761`: `persistBefore` снимается ДО дозаписи; во всех четырёх ветках пробы
печатаются `persist_before=`, `persist_present=`, `backfill=`.

**CI:** прогон **42023172917** (`1bde92f`) — success, джоба `127230879289`, `BUILD SUCCESSFUL in 2m 42s`,
**16 тестов PASSED / 0 FAILED**; артефакт **13412982735**: `zip` **177 081 062 Б**,
`apk/debug/app-debug.apk` **215 418 521 Б**, `logs/manifest-merger-debug-report.txt` **105 294 Б**.
Отчёт — `REPORT_iter33d.md`.

## iter33e — 1.0.53, четвёртая пересборка (`fix4`), 2026-11-05

- База `8b31998` (iter33d). Коммиты: `1919c24` (A1–A6/B/C1/C2), `0fddb68` (A3: удалена мёртвая ветка
  `attemptInThisLoad`/`runtime_raised_by_our_zygote`), `4ff7ead` (C1: ветка `match` тоже печатает
  `probeCommon` — 4 ветки из 4). Версия не менялась: `53` / `"1.0.53"`.
- A1: `AppPerformance.kt:820` метка → `:827` строка `JIT_BOOT: zygote_restart intent=pending` →
  `:831` файл `jit_pending_verify` → `:832` `LogKit.flushOpsNow()` → `:838-839` `runSuCommand(... ctl.restart zygote)`.
- A2: `checkPendingZygoteVerify` (`:905-928`) вызывается из `VoiceAssistantService.kt:266` (`onCreate`) и
  `:348` (`onStartCommand`) — оба **выше** проверки extra `:352`.
- A3: `JIT_BOOT: match_after_zygote` печатается только из A2 (`:922`), второй проход — только маршрут 1.
- A4: `JIT_PROBE: runtime=true …` (`:951`) один раз на загрузку, флаг — `files/jit_verified` (`:690`).
- A6: `JitBootMark.PendingDecision` + `pendingDecision` (`JitBootMark.kt:95`, `:105`) + 3 теста.
- B: `EPOCH_PREFIX`/`epochTag`/`stripEpochPrefix` (`:40`, `:48`, `:50`), допуск мс→с (`:84`), тесты на прод-функциях.
  Исправлен дефект единиц: раньше `epochTag` получал секунды при допуске в мс (±16 ч).
- C1: `persist_present` убран, добавлен `persist_after`; C2: `sameBootIgnoresUptime` с меньшим uptime.
- CI: прогон **`42029651330`** (job `127252532655`), `BUILD SUCCESSFUL in 4m 8s`, **20 PASSED / 0 FAILED**;
  артефакт **`13415209603`**: zip **177 084 785 Б** · apk **215 423 525 Б** · manifest-отчёт **105 294 Б**.
- Отчёт: `REPORT_iter33e.md` (таблица A1–A6/B/C1–C2/C3, 13 проверок с `sed -n`, примеры лога из формат-строк,
  ответ про `voiceapp3.log` после рестарта zygote, честные пробелы).

## iter33f — 1.0.53, пятая пересборка (`fix5`), 2026-11-05

- База `9d36e69` (код `4ff7ead`). Коммиты: `a1f4333` (A1/A3/B), `578f355` (A2: `claim none`).
  Версия не менялась: `53` / `"1.0.53"`.
- A1: `claimPendingIntent` (`AppPerformance.kt:964-999`) — синхронный `renameTo`
  `jit_pending_verify` → `.processing` (`:974`) **до** `BgWorker.post` (`:947`); флаг
  `jitVerifyScheduled` (`:710`); решение по содержимому захваченного файла (`:935`); `STALE` удаляет
  `.processing` (`:943`); `VERIFY` получает путь и удаляет файл в `OK` (`:1033`) и `FAILED` (`:1037`);
  остаток от убитого процесса забирается как `claim ok reason=leftover` (`:987`).
- A2: на один рестарт — 1 `claim ok` + 1 `claim taken reason=already_scheduled` + 1 `source=after_restart`
  + ≤1 `match_after_zygote`; таблица сценариев в отчёте.
- A3: `JitBootMark.VerifyOutcome` + `verifyOutcome` (`JitBootMark.kt:122-128`) + 3 теста → 13 `@Test`.
- B1/B2/B3: три исхода; `UNKNOWN` не сжигает намерение, ≤3 повторов по 10 с только `getprop`
  (`:1040-1050`), после — итоговая строка и возврат файла; пустые значения через `kvE` (`:717`).
- CI: прогон **`42035283163`** (job `127271703677`), `BUILD SUCCESSFUL in 4m 17s`, **23 PASSED / 0 FAILED**;
  артефакт **`13419106325`**: zip **177 085 868 Б** · apk **215 429 529 Б** · manifest-отчёт **105 294 Б**.
- Отчёт: `REPORT_iter33f.md` (в т.ч. C1–C4: `flushOpsNow` ≠ `app.log`, `JIT_PROBE` раз за загрузку,
  `grep -c persist_present` = 3 с выводом, правило «каждая `grep -c` с выводом»).

## iter33g — 1.0.53, шестая пересборка (`fix6`), 2026-11-05

- База `578f355`. Коммиты: `55aefd2` (C4: `git mv` 21 `*.md` агента в `docs/agent/`), `86a4238`
  (A/B/C1/C2), `df0bb25` (B2 fix: `sameTag`). Версия не менялась: `53` / `"1.0.53"`.
- A1: `JitBootMark.verifyOutcome` — четыре ветви, `persist` участвует (`JitBootMark.kt:132-137`):
  пустой `runtime` при прочитанном `persist` = `FAILED`, `UNKNOWN` только когда обе строки пусты.
- A2: `outcomeReason` (`:144-149`) → `runtime_not_true` / `runtime_empty` / `no_channel`;
  `AppPerformance.kt:1048-1051` печатает `result=… reason=…`.
- A3: `verifyOutcomeUnknownWhenEmpty` **изменён** (`("", "true")` теперь `FAILED`), добавлены
  `verifyOutcomeEmptyRuntimeWithPersistIsFailed`, `verifyOutcomeFailedKeepsRuntimeReason`,
  `cycleDecisionSkipsOnlySameBoot` → 16 `@Test` (в мандате 15: 16-й — по требованию B2 покрыть
  чистую функцию счётчика).
- B1: `files/jit_verify_cycle` + `skip reason=already_this_boot`; запись тега `:969` **выше**
  `BgWorker.post` `:970`.
- B2: `cycleDecision(stored, current)` → `RUN|SKIP` через `sameTag` (`:173-181`); первая версия через
  `sameLoad` упала в CI (`42039165265`, `cycleDecisionSkipsOnlySameBoot:187`) — исправлено `df0bb25`.
- C1: сверка перенесена **ниже** `AdbClient.attachContext` (`VAS:268` → `:270`), по-прежнему выше
  `EXTRA_BOOT_RESTORE` (`:353`). C2: `claim none` раз за процесс (`saidNoClaim`, `:718`, `:1016`).
- CI: прогон **`42041029635`** (job `127291563056`), `BUILD SUCCESSFUL in 4m 8s`, **26 PASSED / 0 FAILED**;
  артефакт **`13421983008`**: zip **177 087 351 Б** · apk **215 432 385 Б** · manifest-отчёт **105 294 Б**.
- Отчёт: `docs/agent/REPORT_iter33g.md` (два вида `JIT_VERIFY` разведены, честные пробелы).

## iter33h — 1.0.54 (A…I)

- База `6097ead`. Коммиты: `d62f364` (A + B), `67c7213` (D/E/F/G/H/I + тесты), `c77da0b` (фикс
  компиляции: закрыт блочный комментарий в `LockSound` из-за `avas/**` внутри KDoc; `sysPropFor`
  без удалённых свойств; наружная вынесена в `OutsideTemp.kt`), `77191bc` (фикс ожиданий тестов).
- Версия `54` / `"1.0.54"` (`app/build.gradle.kts:62-63`), плюс guard `gitSha` → `unknown` и `CI_RUN`
  из `GITHUB_RUN_ID`.
- A: единственный путь `/vendor/etc/carlock/<файл>.wav` (`LockSound.kt:860-878`, `isOurLockPath`
  `:1002-1008`), снятие только своих маунтов по своему списку (`:967-999`),
  `LOCKSOUND_BOOT enabled=false action=none` (`:766`); `pkill -9 -f avas` и `killall -9 audioserver`
  удалены (осталась строка-комментарий `:880`).
- B1: `AdbClient.RootState` + `probeRootState` (`:209`, `:246+`), `ADB_STATE` (`:230`),
  `ROOT_RESTORED bootrestore pass=1` (`:234`), `noRootUiText()` (`:1606`), проба из
  `settingsWatchdog` (`VoiceAssistantService.kt:439`). B2: `bootGateDecision` (`HvacDigitalPlugin.kt:50`),
  префы `hvac_boot_tag_v2`/`hvac_boot_result_v2`, `HVAC_BOOT: retry reason=root_back` (`:458`);
  `prev_uptime_ms` удалён (осталось 2 упоминания в комментариях).
- C: `ventOwnersAfterClose` (`WindowDoors.kt:637`), шаг 0 только для `legacy`, `WINDOW_EVENT …
  result=closed pos_after=…` (`:507-524`), `VENT_CLOSE_FAIL` (`:533`).
- D: один предикат `MOUNT_GREP` (`HvacDigitalPlugin.kt:28`) для pre/post/umount/`HVAC_PROBE`,
  `isOurMountLine`/`firstOurMountLine`/`countOurMounts`/`umountCountOf` (`:502-519`),
  `restoreOk = haveRoot && !post.overlayMounted` (`:674`), `RESTORE_RESULT` (`:675`).
- E: temp-push убран (`StatusIcons.kt:916-929`), ключ наружной один — `exttemp` (`:924`),
  `sysPropFor` без `temp` (`:992`).
- F: наружная = `0x11600703` с зонами {0,1,4,7} (`StatusIcons.kt:108-110`, `:391-435`,
  `OutsideTemp.kt`), `TEMP_SRC` (диагностика), read-only «Температуры: разведка» (`:461-518`,
  `ToolsPanel.kt:282`, `ToolsBackend.kt:978`).
- G: `ENABLE=<0|1>` вместо имени переменной (`TtlScript.kt:27`, `:230`), `TTL_RESULT` (`:228`),
  `ROUTE_SRC=none reason=…` (`:39`), `rule_not_installed` → FAILED (`NetworkTtl.kt:122`).
- H: `redactTrace` (`LogKit.kt:454`), краткая/полная трасса (`:385`), `traceHandshake` 3/процесс
  (`:417`), `checksum_skipped` 1/команду (`AdbClient.kt:1899`), бюджет 150 КБ/10 мин
  (`AppLog.kt:261-300`), `BUILD: … commit=<7> ci_run=<id>` (`App.kt:22-24`).
- I: `source=after_restart` только первая попытка (`AppPerformance.kt:1057-1063`),
  `JIT_VERIFY_ATTEMPTS_MAX` (`:701`), кэш `currentBootTag` (`:915-928`).
- Тесты: +6 классов (`BootGateTest`, `MountParseTest`, `RedactTraceTest`, `TempFormatTest`,
  `TtlScriptTest`, `VentOwnersTest`) → 43 `PASSED`.
- CI: два падения по пути — `42078312721` (`LockSound.kt:957 Missing '}'`, вложенный блочный
  комментарий из `avas/**`) и `42080288285` (неверные ожидания в трёх тестах); зелёный прогон
  **`42082578996`** (head `77191bc`), артефакт **`13442117857`**: zip **177 100 457 Б** ·
  apk **215 465 513 Б** · manifest-отчёт **105 294 Б**.

## iter33i — 1.0.54 fix1 (props = флаги `0|1`, tempProbe по реальным ID, плагин без полоски)

База `b355c45`; коммиты `25344d2` (код) и `3b30ba6` (комментарии props). Версия не менялась
(`versionCode = 54`, `versionName = "1.0.54"`).

- P0-a: семантика props доказана цитатами — референс пишет ФЛАГИ `0|1`
  (`w0/x7.java:137-147`), читает их плагин референса (его APK CEX валидирует по этим строкам,
  `w0/ah.java:17`, сброс — `w0/u5.java:27,342`); наш плагин их НЕ читает (0 вхождений
  `hvacdigital` в 2714 записях `res/raw`-APK). Записи приведены 1:1 к референсу:
  `HvacDigitalProps.flagsCommand` (`tools/HvacDigitalProps.kt:42-47`), вызов —
  `StatusIcons.kt:896-930`; было `setprop …temp 9.5` (`67c7213^:StatusIcons.kt:789`).
- P0-a.3: `via` в чужой prop не пишем — `AdbClient.kt:1005-1015` (только `getprop`); в
  `setprop` остаются `bind`/`nenhum` mount-скрипта (`HvacDigitalPlugin.kt:316,319`).
- P0-a.4: `HvacDigitalPropsTest` (5 тестов) — «состояние → строка setprop».
- P0-b: причина наложения — В КОДЕ ПЛАГИНА, не в props: `updateTextStyle()` принудительно
  показывал полоску (`HvacVehicleTempWidget.java:455-465`, `:700-706`), а цифра
  (`updateDigitalTemp`, `:436-453`) висит в том же слоте раскладки (`widget_vehicle_temp.xml:11-12`).
  Смали пропатчен (`{v0,v1}`→`{v0,v2}`, `{p1,v1}`→`{p1,p2}`, 0x8 = GONE), APK пересобран
  apktool 2.9.3 + zipalign + apksigner (platform-ключ, тот же cert `c8a2e9bc…`), заменён
  `app/src/main/res/raw/geely_auto_systemui_plugin_digital.apk`; stock-копия и подписанный APK —
  в `/home/user/artifacts/`. Размеры: APK 18 276 066 Б (было столько же), `classes.dex`
  6 799 536 → 6 799 912, `resources.arsc` 3 934 708 (без изменений). Runtime не проверен — скрин за владельцем.
- P1: `tempProbe` переписан на таблицу `tools/TempProbeTable.kt` (10 свойств, 18 строк):
  `AC_INSIDE_TEMP 0x2140A379`, `AC_INSIDE_TEMP_INVALID 0x2140A378`, `HVAC_IN_OUT_TEMP 0x2140101D`,
  `HU_ENVIRONMENTAL_TEMP 0x21404515`, `TEMPERATURE_STATE 0x2140A267`, отдельная строка
  `HVAC_TEMPERATURE_CURRENT 0x15600502` {1,4,0,7}, `ZONED_TEMP_SETPOINT 0x15600503` {1,49,68};
  `noId()` удалён; `TempProbeTableTest` (7 тестов) сверяет hex↔id↔имя с `ex2_carprops.csv`.
- P2: комментарии-«заглушки» вычищены (`StatusIcons.kt:83`, `:117-118`); кнопка →
  «Тест звука открытия» (`ui/ToolsPanel.kt:795`); вторая фикстура `/system/priv-app/…` в
  `MountParseTest` (`:50-64`).
- CI: прогон **`42090458736`** (head `3b30ba6`) — success, **56 PASSED / 0 FAILED**, артефакт
  **`13445912750`**: zip **176 999 349 Б** · apk **215 470 097 Б** · manifest-отчёт **105 294 Б**.
  Прогон `42089523005` (`25344d2`) — `cancelled` (вытеснен новым push).
- Открытые вопросы владельцу: маршрутная пилюля (оставить в своём UI/убрать) и скрин слота климата.

## iter33j — 1.0.55 (плагин референса как есть, честный маунт, температура по эталону, JIT без zygote)

База `3b30ba6` (iter33i). Коммиты: `b767e5f` (основное) → `5dcf60b` (фикс сборки). Версия `55` / `1.0.55`.

- Правило владельца 06.11: в системе ничего не правится. Smali/apktool-патчей в итерации НЕТ; в дереве
  нет ни `smali`, ни `apktool` (`git ls-files | grep -E "smali|apktool"` → пусто).
- P0.1: `res/raw/geely_auto_systemui_plugin_digital.apk` заменён на бинарь референса CentralEXAuto
  (коммит `99c4fe0…`), `sha256 de4b4742…4d24d0fd03`, 18 280 162 Б, md5 `2fa3d11d…`. OEM (1.0.53) и
  патч iter33i — в `/home/user/artifacts/`.
- P0.2-P0.5: маунт = свежий umount → `mount --bind` → inode **и** md5 обоих концов → одна строка
  `HVAC_MOUNT_RESULT mount=… inode_match=… md5_match=… verdict=…` (`HvacDigitalPlugin.kt:329-380`),
  FAILED при любом расхождении; стейдж `/data/local/hvac-digital/sysui.apk` с `CONFLICT другой файл,
  не затираю молча` (`:262-278`); опознание по размеру/md5 (`PLUGIN_APK_SIZE`, `:167-176`, `:436-479`);
  `MountResultTest` — 8 тестов.
- Флаги слотов не менялись: `HvacDigitalProps.kt:42-47` (1:1 к `w0/x7.java:137-147`).
- P1 температура: `decodeFlymeTemp (raw−80)/2` (`OutsideTemp.kt:14`), основной источник
  `AC_AMBIENT_TEMP 0x2140A377`, `ENV_OUTSIDE 0x11600703` — только fallback, `Locale.US`,
  `AC_AMBIENT_TEMP_INVALID` не гейт (`StatusIcons.kt:397-433`); `TempDecodeTest` — 5 тестов.
- P1 JIT: `ctl.restart zygote` удалён вообще — `grep -rn "ctl.restart zygote" app/src` → пусто;
  включение = два `setprop` + persist-маркер + текст «применится после перезагрузки ГУ»
  (`AppPerformance.kt:213-266`), boot-ветка без zygote (`:824-849`); раунд `JIT_ROUND` по избранным
  вместо `compile_all_apps` (`:545-583`), полная компиляция — отдельная кнопка с предупреждением;
  `ctl.restart adbd` (одна попытка + проба `id`, `:598-618`) и `ctl.restart CarplayMonitor` (`:642-662`),
  кнопки — `ToolsPanel.kt:819-822`, обработчики — `UiBridge.kt:703-724`.
- BUILD: SHA из `GITHUB_SHA` (CI), иначе git, иначе `unknown` (`app/build.gradle.kts:39-52`).
- Мусор persist: `PersistCleaner` (`temp_ext` → пусто, числа в флагах → `0`), вызов из `App.kt:42`,
  `PersistCleanerTest` — 3 теста. VHAL_WAIT вместо `SOC null`/`no CarPropertyManager`;
  `/proc/uptime` с `2>/dev/null || echo unavailable`.
- CI: прогон **`42194780607`** (head `5dcf60b`) — success, **72 PASSED / 0 FAILED**, артефакт
  **`13492735092`**: zip **177 108 547 Б** · apk **215 485 345 Б** · manifest-отчёт **105 294 Б**.
  Красная попытка этой итерации: `42193578514` (`b767e5f`) — вырезанные хелперы + вложенные кавычки
  + `${}` в raw-строке; исправлено в `5dcf60b`.
- Открыто: приёмка трёх слотов и температуры — за владельцем; материалы `РАЗБОР_1.0.54…md` и
  `voiceapp3-10.log` в окружение не переданы (в отчёте §8).

## 2026-11-06 — iter33k (1.0.56)

**Мандат:** `PROMPT_iter33k_1.0.56.md`. База `9d37799`, ветка `fix/v2`, `versionCode = 56`,
`versionName = "1.0.56"`. Сборка APK — только CI; владелец подписывает своим ключом.

### Что сделано

- **§1 P0 тумблеры = флаги + мягкий apply.** Новый сервис `HvacFlagsApply.kt`
  (`toggle`, `viaFor`, `flagsSegment`, `toggleLine`, `applyLine`, `parseFlags`, `pluginRestartScript`,
  `applyResult`, `applySystemUi`, `readFlagsLine`, `syncFromDevice`). `StatusIcons.setHvacDigital`
  (`StatusIcons.kt:1044`) больше НЕ вызывает `HvacDigitalPlugin.enable`: одна PUSH трёх флагов + `via`
  (`HvacDigitalProps.flagsCommand`), затем `kill $(pidof com.flyme.auto.systemuiplugin)` (no-op, если
  процесса нет). Одна строка лога `TOGGLE [hvacX] → ON|OFF | flags=… | via=bind|nenhum | apply=plugin_restart
  result=ok|noop|failed | ✓/✗` + `FLAG_APPLY method=plugin_restart …`. UI читает ФАКТ из `getprop`
  (`getHvacDigital` → `cachedFlags`, выравнивание префов со строкой `FLAGS_SYNC … action=ui_from_props`,
  вызов из `syncAllImpl`, `StatusIcons.kt:796`).
- **§2 P0 FLAG_APPLY.** Авто — только процесс плагина. Появились две кнопки: «Монтировать плагин»
  (`hvac_mount_btn` → `HvacDigitalPlugin.enable`) и «Применить слоты (SystemUI)» (`hvac_apply_sysui_btn` →
  `StatusIcons.applySlotsSystemUi` → `HvacFlagsApply.applySystemUi`, единственный клик, убивающий SystemUI),
  обе с предупреждением «CarPlay и музыка прервутся на ~10 сек».
- **§3 P0 TTL со скоупом.** `TtlScript.kt` переписан: `TTL_NET` (диагностика), выбор варианта
  `uplink` (`-o <if>`) → `exclude_carplay` (`! -o <carplay>`) → отказ (`variant=none
  reason=no_uplink_no_carplay`). Глобального правила без скоупа больше нет. Проверка — счётчики
  `grep -c 'ttl-set 65'`/`'hl-set 65'` (tcpdump удалён, в сводке `wire_v4=not_measured`), вердикт
  `SCOPED_OK|OFF_OK|CHECK_OWNER`. Выключение — generic-удаление по `iptables -S` + эталонный цикл `1..5`,
  строка `TTL_OFF v4=0 v6=0 result=OK`. Восстановление при загрузке печатает
  `TTL_BOOT rules_restored=… v4=… v6=… scoped=true` во всех ветках (`NetworkTtl.kt:271,283,291`).
  Новый `TtlRules.kt` (чистые функции: `uplinkOf`, `carplayIfaces`, `variants`, `ruleV4/V6`, строки лога).
- **§4 P1 рут.** `AdbClient.autoRecoverRoot` (`:249`): при переходе в `SHELL_NO_ROOT` — одна попытка
  `( sleep 1; setprop ctl.restart adbd ) &`, пауза 4 с, повтор `id`, строка
  `ADB_RECOVERY action=restart_adbd reason=… root_before=none root_after=root|none result=OK|FAILED`.
  Флаг «одна попытка на событие», зонд 60 с не тронут, `ctl.restart zygote` нет.
- **§5 P0 сборка.** Новый маркер `app/buildinfo.properties` (`SOURCE_SHA`), приоритет
  **маркер → `GITHUB_SHA` → `local`**, `ci_run` = `GITHUB_RUN_ID`|`local`
  (`app/build.gradle.kts:44-65,83,87`). `git rev-parse` удалён полностью (остались только комментарии).
  `versionCode = 56`, `versionName = "1.0.56"`.
- **§6 P1 тексты.** `ошибка:` в UI-строках убрано (`ToolsResult.kt:98`, `OfflineSheetController.kt:596,601`),
  PUSH ждёт канал с одним повтором через 5 с (`StatusIcons.kt:930-940`).
- **Тесты:** новые `TtlRulesTest` (6), `HvacFlagsApplyTest` (7), `BuildMarkerTest` (3); `TtlScriptTest`
  переписан под скоуп (6).

### CI

- `42248064281` (`ef721f0`) — FAILED: мой тест ждал `! -o $CARPLAY_IF`, в скрипте `$CP_FIRST` → исправлено.
- `42249659613` (`f903a1e`) — success, **88 PASSED / 0 FAILED**, артефакт `13521081145` (177 120 940 Б).
- `42251502684` (`704911c`) — FAILED по инфраструктуре раннера (`Install CMake 3.22.1` →
  `ZipException: Archive is not a ZIP archive`), код не компилировался.
- **`42252401367` (`96d22eb`) — success, 91 PASSED / 0 FAILED**, `BUILD SUCCESSFUL in 4m 4s`,
  job `128010643713`, артефакт `13521099296` (**177 121 097 Б**).
- **`42254379400` (`b90a8da`, handoff: маркер `SOURCE_SHA=96d22eb` + отчёт + WORKLOG) — success,
  91 PASSED / 0 FAILED**, `BUILD SUCCESSFUL in 3m 22s`, job `128017548566`, артефакт `13523295893`
  (177 121 100 Б). `BuildMarkerTest > commitSourceIsHonest PASSED` — маркер реально доходит до
  `BuildConfig.GIT_COMMIT` (владелец увидит `commit=96d22eb ci_run=local`).

### Отчёт

`docs/agent/REPORT_iter33k.md` — ✅/🟡/❌/⛔ с `файл:строка`, приёмка
`BUILD: versionCode=56 versionName=1.0.56 commit=96d22eb ci_run=local`.

### Публикация и CI (закрыто)

- **Опубликовано в `fix/v2`**: `3d486e0` (fix3+fix4, 13 файлов, +1405/−485) → `e248231`
  (правка устаревшего статического теста) → `d5bf108` (handoff: `SOURCE_SHA=e248231`).
  `main` не тронут, `--force` не использовался.
- **CI по вершине ветки**: run `42871326045`, job `130023223080` (`4ccecde`, только docs) — ✅ 206/0.
- **CI по опубликованному коду**: run `42868979959`, job `130014821740`, коммит `e248231` —
  ✅ `BUILD SUCCESSFUL in 4m 26s`, **206 PASSED / 0 FAILED**, artifact `13789547156` (`app-debug`).
- Первый прогон по публикации `42866873182` (job `130007455625`, `3d486e0`) — ❌ FAILED:
  `JitBootTest.zygoteRestartOnlyInsideJitToggleBlock` (тест iter33n-fix1 ожидал литерал
  `ctl.restart zygote` в `AppPerformance.kt`, а fix3/fix4 перенесли текст скрипта в
  `JitNetModel.watcherScript()`). Локальный стенд этот класс не гонял → зелёный локальный прогон
  не заменяет Android CI. Исправлено в `e248231` (только `src/test`).

### Открыто

- Полевой прогон JIT не выполнялся (по условию ревью — только после повторного REVIEW).

### CI (история)

- `42868979959` / job `130014821740` (`e248231`, fix3+fix4) — ✅ 206 PASSED / 0 FAILED,
  artifact `13789547156`.
- `42866873182` / job `130007455625` (`3d486e0`) — ❌ 1 FAILED (старый статический тест).
- `42858741744` (`25eef00`, fix2) — ✅ 193 PASSED / 0 FAILED, job `129978799323`,
  artifact `13785111537` (к fix3/fix4 не относится).

## iter33p (1.0.61, перезапуск задачи) — одна JIT-цепочка по референсу

**Референс проверен заново:** ZIP MD5 `8a65c6c29749c64d4e335c57490a347a`, внутренний APK MD5
`c3f1664125953b9851bf4483b265135f` — обе суммы совпали; разбор внешнего APK, jadx 1.4.7 + smali
(`w0/j9.onClick` и `CentralWatchdogService.U(...)` jadx не декомпилировал).

**Что делает референс:** `j9.onClick` читает `isWifiEnabled()` и `ap0` → один `commit()` префов
(`jit_pedido`, `perf_auto_otimizar`, `jit_restaurar="wifi=…,ap=…"`) → `la.run`: `svc wifi disable` →
слив `ip rule … oif wlan0` ≤20 с → `setprop dalvik.vm.usejit` → `setprop ctl.restart zygote`
**последней командой** (40 с, root-транспорт) → на старте сервиса `e2.run` (через 15 с) возвращает
**AP первым**, затем Wi-Fi, если они были включены. Проверка результата — `r7.run`:
`getprop` + `/proc/self/maps` + снятие `jit_pedido`; рестарт zygote в референсе **не подтверждается**.

**У нас:** watcher, файл-отмена и чтение `ARMED` убраны. Одна операция = снимок (`op`, `boot`, `wifi`,
`ap`, `want`, `zygote_pid`, `rt_before`, `ts`, подтверждение чтением) + один скрипт с журналом шагов
`JITSTEP op=<id> step=…` + разбор на старте процесса через `JitFlow.pendingAction`:
`CONTINUE_RESTORE` (pid zygote сменился — продолжение операции, без второго клика), `COMPENSATE_RESTORE`
(рестарт доказанно не состоится: окно 60 с истекло / скрипт вернул управление / была перезагрузка ГУ),
`DISCARD_NO_EFFECT` (сети не касались), `HOLD_UNKNOWN` (доказательств нет — ноль сетевых команд,
`need=owner_decision`). `JIT_APPLIED` печатает getprop/maps/рестарт/вердикт раздельно; `restart=ok`
только по смене pid zygote.

**Убрано:** watcher `/data/local/tmp/jit_zygote_watch.sh`, `nohup`, 8-секундная задержка, файлы
`jit_zygote_result.txt`/`jit_zygote_cancel`, файл-намерение `jit_pending_verify` (+`.processing`,
`jit_verify_cycle`), `dalvik.vm.usejitprofiles` в записи, удаление `ip rule … oif wlan0`
(теперь read-only подсчёт), `umount` не копировался.

**Тесты:** `JitNetCoreTest` 23, `JitChainScriptTest` 5 (настоящий `/bin/sh` с подставными `svc`/`ip`/
`setprop`/`getprop`/`sleep`), `JitRulesReadOnlyTest` 3, `JitToggleChainTest` 5, `JitBootTest` 6,
`AvasCoreTest` 13, `AvasContractTest` 5, `BuildMarkerTest` 3 — **63 PASS / 0 FAIL** локально.
`JitWatcherScriptTest` → `JitChainScriptTest`, `NetSanitizePlanTest` → `JitRulesReadOnlyTest`.

**AVAS:** поведение не менялось (снимок mode+sound type, только цепочка JIT и старт приложения,
read-back, привязка к операции); в KDoc снят недоказанный «точный график референса 5/12/30 с» —
это AEB observer, а `z()` в референсе возвращает муту, а не mode.

**Публикация:** `531ae76..c8e4c9c` в `fix/v2` (без `--force`, `main` не тронут). CI по коду:
run `42881198633`, job `130057601380` — BUILD SUCCESSFUL, **200 PASSED / 0 FAILED**, artifact
`13794792993`. `SOURCE_SHA=c8e4c9c`.

**Открыто:** полевого прогона JIT нет (по условию — после нового REVIEW); случай «снимок есть,
журнала нет, сеть уже не та» намеренно останавливается на `HOLD_UNKNOWN` и ждёт решения владельца.

## iter33p (1.0.61) — ТРЕТИЙ ЗАХОД: 11 замечаний REVIEW к `78a3248`, код `aed18fb`

**База:** `e8f5e23`. **Код:** `aed18fb` (`fix/v2`, fast-forward, без `--force`). **CI:** run
`42954771103`, job `130307151164` — `BUILD SUCCESSFUL in 4m 27s`, **186 PASSED / 0 FAILED**
(из полного лога: `grep -c ' PASSED$'` = 186, `grep -c ' FAILED$'` = 0), artifact `app-debug`
id `13826483630`, 177 171 113 Б.

**Что исправлено (по замечаниям):** признак выполнения цепочки — только сеть (совпадение
`getprop` больше не доказательство); снят запрет «одна попытка на процесс» + `startGate` против
двойного возврата; `perf_auto_otimizar` пишет ядро после ПОДТВЕРЖДЁННОГО снимка; `unknown` в
сверке не снимает намерение и не переписывает чужой снимок (`persist=false`); `parseProp` не
считает `unknown` обычный `\n` в конце вывода; `JitOp.click` возвращает реальный `ChainRun`
(раньше `run` всегда был `null`); `AndroidNetState.setWifi` — рабочий `setWifiEnabled(on)`
(прежняя запись была присваиванием-выражением и не компилировалась); восстановлен
`reportLegacyAvasSnapshot` (был вызов без определения); удалён допуск ±60 с в `JitBootMark`
(`sameLoad`/`format`/`stripEpochPrefix`) — недоказанный признак «той же загрузки»; убраны мёртвые
`JIT_STATE_ON_LINE/OFF_LINE`; скрипт приведён к литералу DEX символ в символ
(`_n=$((_n + 1))`, 392 символа — проверено независимым сравнением с `w0/la.smali:326,342`).

**Ядро:** `JitRef` (ключи и текст скрипта референса, `PropFact`/`Verify`, `Phase`, `BootMatch`,
`StartDecision`, `ClickGate`, `ChainOutcome`) + `JitOp` (клик под `startLock`, продолжение под
`startGate`, возврат AP→Wi-Fi с ожиданием факта, сверка с `rc`). Адаптеры `JitNet` —
`AndroidShell`/`AndroidStore` (два `commit()` + обратное чтение)/`AndroidNetState`. UI:
`perfJit` = запрос, `perfJitRuntime` = факт из кэша (`true|false|unknown`), строка
«Сейчас на устройстве: …» под тумблером, shell из главного потока не вызывается.

**Тесты:** 10 классов, **71 PASS / 0 FAIL** локально (`/home/user/runtests.sh`, kotlinc 1.9.24 +
`android.jar` + junit 4.13.2, настоящий `/bin/sh` с подставными командами) и те же 71 в CI.
Добавлены `JitOrchestratorTest` (20), `JitStartZeroWritesTest` (5), `JitLogsCancelTest` (5);
удалены `JitChainEndToEndTest` (14), `JitBootTest` (5), `JitBootMarkTest` (4), `JitTogglePathTest` (4)
— эквивалентное покрытие в отчёте §4.1.

**Шаг монтирований §1.1:** реализован символ в символ, выключен (`MOUNT_STEP_ENABLED=false`).
Анализ read-only (в отчёте §3.2): `umount -l` точек `/data/app/` бьёт по APK чужих приложений,
штатного возврата без перезагрузки нет ⇒ обратимость не доказана, нужно решение владельца.

**SOURCE_SHA** в handoff-коммите: `78a3248` → `aed18fb` (`app/buildinfo.properties`).

**Открыто:** полевого прогона JIT нет (владелец не запускает до нового REVIEW); вопросы владельцу —
монтирования, чистка остатков `persist.*` JIT, остаточный `avas_snapshot`, `ACTION_BOOT_HU`.
## iter33p (1.0.61) — ТРЕТИЙ ЗАХОД, ПРАВКА 2: 10 проблем §0.0 мандата, код `61bfddd`

**База:** `6436be8`. **Код:** `61bfddd` (`61bfddd0c55352156c34a55706c2811ae07e9634`) + `1d64e54`
(правка компиляции одной строки в `ToolsPanel.addOneShot`), `fix/v2`, fast-forward, без `--force`.
**CI:** итоговый прогон run `42965830756`, job `130342717357` (SHA `1ec62f1`) — `BUILD SUCCESSFUL
in 3m`, **210 PASSED / 0 FAILED**; до него run `42962463984`, job `130331961735` (`1d64e54`) —
тот же результат
(`grep -c 'Z .* PASSED$'` = 210, `FAILED$` = 0), artifact `app-debug` id `13831162922`, 177 178 299 Б.
Промежуточный прогон `42960987191` по `25ff688` упал на `:app:compileDebugKotlin`
(`ToolsPanel.kt:923 Unresolved reference 'jitConfirmed'` — лишняя вставка при патче, убрана).

**Нестабильный тест (исправлено).** Прогон `42963567210` по docs-коммиту `cf3b6b0` упал на
`JitLifecycleTest > clickAndStartNeverOverlap`: тест требовал «ровно один `STARTED`», но порядок
клика и старта решает планировщик — при обратном порядке клик честно получает `BUSY`, и это
законно. Тест переписан на инварианты, не зависящие от порядка; локально 3 прогона подряд —
`OK (95 tests)`.
**Локально:** `OK (95 tests)` — JIT/лог-подмножество на стабах и настоящем
`/bin/sh` (`/home/user/runtests.sh`), это НЕ полный набор CI.

**Причина правки.** В `aed18fb` признаком завершения нашего скрипта оставалось выключенное
радио. Мандат §0.0: сеть мог выключить другой shell, `svc wifi disable` — ПЕРВАЯ команда скрипта,
поэтому «сеть выключена» ничего не говорит о `setprop`/`ctl.restart zygote`. Отсюда 10 проблем:
ложный `DONE`/`RESTORED`, второй клик при живой очереди транспорта, недоказанная атомарность
CPS/DPS, запись по устаревшей копии снимка, тумблер из preference, неотличаемый `invoked` AP,
сверка до выполнения как результат.

**Что сделано.** Единственный доказанный признак — `Shell.REPLIED` (ответ транспорта на НАШ
вызов); нет ответа ⇒ `HOLD_UNSAFE_PHASE` (ноль записей, ноль сети, решение владельца).
`clickGate` блокирует второй клик и при `wifi=0,ap=0` этой загрузки. `judgeWrite` пишет в оба
хранилища и читает оба обратно (`ok`/`readback_ok_commit_false`/`readback_mismatch`/
`priority_read_mismatch`/`no_store`). Все записи — через `writeOp(before)`: если снимок изменил
другой писатель, запись отбрасывается (поздний shell не портит новую операцию). `JitRef.Request`
разделяет `invoked`/`accepted`/`confirmed`; `wifiOn()==null` ⇒ `wifi_state_unknown`, запрос AP не
отправляется. Сверка снимает намерение только при `definitive && want==true && REPLIED`.
UI-строка обновляется после фонового чтения (`refreshJitRuntimeAsync(..., onDone)`), новых кнопок
нет. Шаг монтирований (`umount -l` по `/data/app/`) по-прежнему выключен и в отчёте помечен ⛔.

**Тесты:** +`JitLifecycleTest` (7, барьеры по шагам цепочки), +`JitStoreConsistencyTest` (8,
CPS/DPS), +`JitUiStateTest` (5), +`JitLifecycleShell`; переписаны `JitOrchestratorTest` (23),
`JitRefTest` (14), `JitLogsCancelTest`, `JitFake`. Итого 95 локально. `runtests.sh` теперь сам
переходит в рабочую копию (`cd $W`) и компилирует `JitNet.kt` + стабы, иначе source-тесты падали
на чужом cwd.

**SOURCE_SHA** в handoff-коммите: `6436be8` → `1ec62f1` (последний коммит кода, прошедший CI) (`app/buildinfo.properties`).

**Открыто:** решение владельца по шагу монтирований (в §3.2 отчёта даны четыре read-only команды:
mountinfo root-транспорта + сравнение namespace с PID 1; `cat /proc/mounts` сам по себе ничего не
доказывает), остатки `persist.*` JIT, остаточный `avas_snapshot`, `ACTION_BOOT_HU`, полевой прогон
(не запускался, полевого успеха нет).


## iter34 (1.0.61) — JIT переписан как копия референса, свои механизмы удалены

Мандат: «ПРОМПТ_ТОЧНАЯ_КОПИЯ_JIT_РЕФЕРЕНСА_1.0.61». База: `fix/v2` @ `460f3b5`. Коммиты:
**`e0479a1`** (перенос ядра), **`9de9dce`** (шаг `umount -l` включён).

**Ядро перезаписано целиком** (`tools/JitNetCore.kt`, 1262 → 575 строк) под smali референса
`CentralEXAuto_v3.3.9.apk` (md5 `c3f16641…`, ZIP `8a65c6c2…`): `JitRef` — константы (20 итераций
слива, 40000 мс транспорта, 15000 мс до `e2`, 8000 мс на `getprop`, 25/20000/1000/3/2000 для `U`),
литерал `w0/la.run` символ в символ (392 симв.), `parseRestore` (`^wifi=([01]),ap=([01])$`,
`matchEntire`, без trim), `parseProp`, `verify`, `confirmedState`, `chainOutcome`; `JitOp` —
`click` (= `w0/j9.onClick`), `onProcessStart` (= `CentralWatchdogService.onCreate`: parse → remove →
sleep 15 000 → restore), `restoreNetwork` (= `w0/e2.run`), `openAp` (= `U(String,boolean)`),
`verifyApplied` (= `w0/bb` + `w0/r7`). `tools/JitNet.kt` — `AndroidShell`/`AndroidStore`/
`AndroidNetState`/`AndroidWaiter` под этот API. `AppPerformance.kt` и `ui/ToolsPanel.kt` переведены
на `JitRef`/`JitNet`; `readJitOp` и строка «операция: …» удалены.

**Удалено:** `HOLD_*`, фазы и журнал фаз, `Op`/`decodeOp`, `KEY_OP`, `ClickResult`, `StartDecision`,
второй watcher/`FileObserver`, файл отмены, `sleep 8`, boot-backfill и автоприменение JIT на boot,
метка загрузки, TTL снимков, PID-проверки, `jit_verified`/`jit_pending_verify`, санация `ip rule`.
Скрытый выключатель шага монтирований (`MOUNT_STEP`/`MOUNT_STEP_REASON`) **убран**: отправляется
ровно литерал референса, в логе `mount_step=present`.

**Тесты:** `JitOrchestratorTest` (24), `JitRefTest` (12), `JitChainScriptTest` (8),
`JitStartZeroWritesTest` (5), `JitLogsCancelTest` (5), `JitUiStateTest` (5),
`JitNoPersistTest` (3), `JitBootBoundaryTest` (4), `JitRulesReadOnlyTest` (2),
`AvasIndependenceTest` (5), `LogFilesTest` (6) — **локально `OK (79 tests)`**. Удалены
`JitLifecycleTest` (7), `JitLifecycleShell`, `JitStoreConsistencyTest` (8): они проверяли ровно
удалённую машину состояний, инварианты перенесены в `JitOrchestratorTest`/`JitStartZeroWritesTest`.

**CI опубликованного кода** (`9de9dce`): run `42994299986`, job `130434558159` — `success`,
`PASSED` 194 / `FAILED` 0 (полный лог: 194 строки `PASSED`, 0 строк `FAILED`, `BUILD SUCCESSFUL`),
artifact `app-debug` id `13843339117` (177 161 925 Б). Docs-коммит `b93eea8` (этот отчёт + WORKLOG +
`SOURCE_SHA`): run `42996265714`, job `130441021795` — `success`, `PASSED` 194 / `FAILED` 0,
artifact `13844292942` (177 161 997 Б).

**Отличия от референса (все заявлены):** нечитаемый `getprop` = «неизвестно» и `jit_pedido` не
снимается; тумблер показывает «состояние неизвестно»; два хранилища prefs проекта вместо одного;
продолжение вешает `VoiceAssistantService` (нет класса `CentralWatchdogService`); `U` без
`CountDownLatch` (опрос в `BgWorker` с теми же таймаутами); индикатор `postDelayed(ua, 45000)` —
строка лога. Ни одна команда цепочки и ни один ключ префов отличиями не затронуты.

**SOURCE_SHA** = `aa28dba`.

**Открыто:** `umount -l` на живом ГУ не исполнялся (нужны read-only `mountinfo` root-транспорта +
сравнение namespace с PID 1 либо явное «идём как есть»); остатки `persist.*` JIT и `avas_snapshot`
только фиксируются; полевой прогон не запускался, полевого успеха нет.

---

## iter35 (1.0.61) — JIT доведён до рабочего состояния; AVAS отдельно, причина не установлена

**Коммит кода:** `10cd137` (ветка `fix/v2`). **CI:** run `43014460281`, job `130501940144` —
`success`, `PASSED` 218 / `FAILED` 0 (полный лог: 218 строк `PASSED`, 0 `FAILED`,
`BUILD SUCCESSFUL`), artifact `app-debug` id `13851849907` (177 169 982 Б).
Локально на фейках/стенде: `OK (103 tests)`.

**Что сделано по §1:** `openAp` = полный `U` (ранний `v()`, ожидание `O()`, `c1()` с ENABLING и
исключением⇒true, owner-ветвь `p2`, флаг `V`, общий `CountDownLatch` 25 с, `d()` + опрос 20 с ×1 с,
**настоящий сигнал FAILED** `WIFI_AP_STATE_CHANGED/wifi_state==14` → ≤3 попытки с паузой 2 с,
снятие latch в `finally`); `onProcessStart` больше не спит в пуле (`remove` → `postDelayed(e2,15000)`,
риск смерти процесса назван); сверка `r7` `ok=(pedido==getprop)&&(!pedido||maps!=FALSE)` покрыта
таблицей всех сочетаний; `jit_pedido` **удаляется** (`contains=false`), ложный `write=none` убран;
тумблер `ToolsPanel` показывает подтверждённый `getprop`, обновляется программно без вызова
обработчика клика, `unknown` → «состояние неизвестно»; хранилища пишутся независимо
(`WriteResult(attempted, ok)`), `removed` только по подтверждению, `restarted=`/`net_restore_done`
из логов убраны.

**Что НЕ сделано и почему:** ⛔ `umount -l` — шаг перенесён и включён (`mount_step=present`), но
данных о его безопасности на этой ГУ нет; запускать JIT на устройстве до ответа владельца не нужно,
запрошены read-only `mountinfo` (наш namespace + PID 1), `ls -l ns/mnt`, `findmnt` по точкам
`/data/app/`. Ни `umount`, ни mount, ни reboot ради проверки не выполнялись.

**AVAS:** только диагностика. Подтверждено кодом: единственный путь звука открытия —
VHAL `PROP_DOOR_UNLOCK_STATE` (`WindowDoors.kt:232-240`) → дебаунс 5 с → `LockSound.playUnlockSound`
→ `playExternalSound` (usage 29/73/13); звук замка при boot пишет `Settings.System` и делает
bind-mount только в `/vendor/etc/carlock/`; `setAVASMode`/запись `/system/etc/avas/**`/трогание
`audioserver` в 1.0.61 отсутствуют. `MediaPlayer.start()` маршрут не доказывает. Причина не
установлена, лога 1.0.60 нет; владельцу заданы 4 вопроса, предложен read-only `dumpsys audio` +
датированный лог в момент симптома (без клика JIT и без перезагрузки).

**SOURCE_SHA** = `aa28dba`.

---

## iter36 (1.0.61) — JIT без точки доступа, честный клиентский Wi-Fi, реальная проверка JIT-кэша

**Коммит кода:** `b3705f3`. **CI:** run `43022611861`, job `130529271884` — `success`,
`PASSED` 221 / `FAILED` 0, artifact `app-debug` id `13856077487` (177 170 063 Б).
Локально: `OK (106 tests)`. База — HEAD `f2ccceb` / код `10cd137`, ничего чужого не откатывалось.

**Убрано из JIT целиком** (решение владельца): `U`/`openAp`, `ap0`, `startSoftAp`,
`WIFI_AP_STATE_CHANGED`/`wifi_state==14`, попытки/пауза 2 с/`FAILED`, `CountDownLatch`,
`AUTO_WIFI_ENABLED`, owner/`dono`, `CarplayMonitor`/`connadaptor_modo`, логи `JIT_AP`.
`JitRef.JitNetState` = ровно `wifiOn()`, `setWifi()`, `wifiAddress()` — мёртвого кода нет.

**Снимок:** новый минимальный `jit_restaurar="wifi=0|1"`; старый `wifi=…,ap=…` принимается,
`ap` игнорируется без вызовов AP; битая запись не выполняется и не чистится.

**Клик:** неизвестное состояние Wi-Fi (`null`, не `false`) или снимок, не подтверждённый чтением
(`confirmSnapshot`), блокируют отправку `svc wifi disable`; причина уходит в существующую панель
(`ToolsStep.Status.BLOCKED`) и лог `JIT_CLICK blocked=…`. Текст шага больше не обещает «AP, затем
Wi-Fi»: показано `restore=`, `confirmed=`, `internet=not_checked`.

**Найденная и исправленная ошибка:** `JitNet.kt` считал успехом ЛЮБУЮ строку `/proc/self/maps`
со словом `jit-cache`, включая `rw-p` без `x`, а отсутствие строк давало `false`. Теперь как
`w0/bb`→`w0/sa`→`w0/j5.d`: фильтр имён + права `\br[-w]x[ps]\b`, три значения `true/false/null`.
Проверено на синтетических строках и на реальных файлах (`JitMapsTest`).

**Гонки:** сверка не выполняется во время нашего клика (`click_in_flight`) и не снимает
`jit_pedido`, если значение изменилось (`race=pedido_changed`); async-ответ не переставляет
тумблер после нового клика (`jitGeneration`); пустой кэш больше не съедает callback
(`refreshJitRuntimeAsync`). Подтверждённый не-успех сверки показывается в той же read-only строке
панели (`JitRef.verifyHint`) — без новых кнопок и красного текста.

**⛔ `umount -l`:** шаг включён (`mount_step=present`), обратимость на ГУ не проверена; в отчёте
iter36 §3 — четыре read-only команды (`/proc/self/mountinfo` своего namespace и PID 1,
`ls -l ns/mnt`, `findmnt` по точкам), пустой вывод/нет `findmnt` = `unknown`.

**⛔ Продолжение:** что приложение запустится после `ctl.restart zygote`, недоказуемо без ГУ
(`BOOT_COMPLETED` при рестарте zygote не приходит, `START_STICKY` — не гарантия). Возврат сети не
заявляется.

**SOURCE_SHA** = `aa28dba`.

## iter37 (1.0.61) — финальная правка JIT: снимок по факту записи, гонки клика и сверки, антидубль на задачу, read-only диагностика `/data/app`

**Код:** `e4f9ce6`. **CI:** run `43065985922`, job `130680937719` — 257 PASSED / 0 FAILED. Локально: `OK (142 tests)`.

**§1 Снимок.** Успех записи теперь `commit()==true` **и** readback ВСЕХ трёх ключей в каждом
хранилище (`JitNet.kt:120–163`); «значение видно в памяти» успехом не считается. Добавлено
`SnapshotWrite.firstReadable` (`JitNetCore.kt:155–158`): если хранилище, которое продолжение
прочитает ПЕРВЫМ, запись не подтвердило, клик блокируется — иначе после перезапуска вернулось бы
прежнее значение. Конфликт CPS/DPS (разные значения) блокируется локально
(`snapshot_conflict`), семантика `Prefs2` не менялась. `remove` вызывается только для
хранилищ с подтверждённой записью, `perf_auto_otimizar` не удаляется никогда, отказ cleanup
виден в логе (`cleanup=pedido:x/y/restore:x/y`).

**§2 Старая задача.** Снимок снимается до планирования и после задачи не удаляется; одинаковые
значения идентификацией не считаются; отказ удаления — `removal_unconfirmed=true` без повторов.

**§3.1 Клики.** Атомарный слот: второй клик → `busy`, команд нет, исходный снимок не
перезаписывается. Таймаут транспорта → `chain_undefined` (команда могла остаться в очереди),
намерение не снимается.

**§3.2 Сверка.** Поколение запроса + проверка слота под тем же локом, что и удаление: нового окна
«проверил → удалил» нет (`pedido_changed`, `click_in_flight`). `getprop` совпал ≠ рестарт zygote.

**§3.3 Антидубль.** Флаг относится к задаче: проверка идёт ДО разбора снимка, поэтому
`onCreate`+`onStartCommand` одного снимка дают `duplicate`, а новый снимок в том же процессе
планируется снова.

**§4 UI.** Поколение поднимается самим событием клика (`ToolsPanel.kt:999` →
`AppPerformance.markJitClick`), устаревший ответ пропускается целиком (`JIT_UI stale_fact
skipped`). `setWifiEnabled` разложен на `ACCEPTED/REFUSED/UNKNOWN/EXCEPTION`, `internet=not_checked`.

**§5 Диагностика.** Новый `tools/JitMountDiag.kt`: одноразовый read-only сбор в датированный лог
при обычном старте (фон, 20 с после `attachContext`, один раз за процесс, без кнопок и без связи с
кликом). `id`+namespace self/`/proc/1`, `uid_map`, `/proc/mounts` по условию `' /data/app/'`,
`mountinfo` self и `/proc/1`, статусы `ok/empty/permission_denied/root_unavailable/timeout/
truncated/unknown`, метки BEGIN/SECTION/DATA/END, части с номерами и отметкой усечения.

**⛔ `umount -l`:** шаг по-прежнему включён, обратимость на ГУ не доказана; данных с ГУ ещё нет —
лог появится после обычного запуска приложения владельцем.

**SOURCE_SHA** = `aa28dba`.

## iter38 (1.0.61) — JIT: неопределённый исход, старая задача против новой операции, восстановление прежних значений, продолжение после смерти процесса; диагностика монтирований и сети

**Код:** `64ad851`. **CI:** run `43076766217`, job `130718505139` — 297 PASSED / 0 FAILED. Локально: `OK (182 tests)`.

**§1.1 Неопределённый исход.** После потери ответа транспорта повторный клик отклоняется
(`blocked=chain_undefined`) до чтения Wi-Fi и до записи снимка; оба направления тумблера ведут себя
одинаково. Доказательств «выполнено/не выполнено» в процессе нет — ни таймаут, ни `getprop`, ни
boot id, ни новый процесс ими не являются; блокер живёт до конца процесса, решение принимает
опознанный снимок на следующем старте. Без авто-JIT и без постоянного сторожа.

**§1.2 Старая задача vs новый клик.** Пока возврат Wi-Fi запланирован, клик отклоняется
(`restore_pending`) — вторая цепочка не ставится поверх отложенного возврата, снимок не
перезаписывается. Задача удаляет снимок только СВОЕЙ операции (по отметке), иначе
`cleanup=skipped reason=snapshot_belongs_to_other_op`; возврат пропускается, если идёт клик.

**§1.3 Прежние значения ключей.** Перед записью снимается состояние четырёх ключей в каждом
хранилище; при блокировке оно возвращается (`prev_restore=x/y`, `prev_restore_detail`), включая
случай `commit=false`, изменившего память. Отказ восстановления виден в логе; атомарность CPS↔DPS
не заявляется. Общая семантика `Prefs2` не менялась.

**§1.4 Смерть процесса до возврата.** Снимок больше НЕ удаляется на старте: удаление — только
после фактического возврата, и только своей операции. Отказ планировщика и смерть в 15-секундном
окне продолжение не теряют: следующий обычный запуск продолжит ту же операцию. ⛔ Гарантировать
запуск приложения после `ctl.restart zygote` по-прежнему нельзя.

**§2 Диагностика монтирований.** Маркеры кода возврата `wc`/`cat` + маркер конца вывода; полнота
по числу строк и байт (`wc -lc`), потеря середины ловится по длине; `channel_not_ready` вместо
«нет root», `root_unknown` при неизвестном uid, `id_ok_but_not_root` при uid≠0; строка самого `wc`
в «прочитанный файл» не попадает; длинные строки делятся с пометкой. Предел: контрольной суммы нет.

**§3 Диагностика интернета.** Новый `InternetDiag` + `NetInfoAndroid`: `ip addr show wlan0`,
`ip rule show`, `ip route show table all`, DNS-свойства, два ограниченных `ping`, Android
`VALIDATED`/captive/транспорты/DNS активной сети. Только чтение, свои метки и статусы, нейтральная
сводка. Запуск — при обычном старте через `requestDiagOnce` (фон, один раз за процесс, одноразовый
обработчик готовности канала + одна проверка через 30 с). Сеть не меняется, `zygote` не зовётся.

**§4 Обновление 1.0.60 → 1.0.61.** Снимок без отметки операции = `legacy_or_unknown`: не выполняется
и НЕ удаляется, данные остаются владельцу. ⛔ Безопасность установки на ГУ не проверена.

**SOURCE_SHA** = `aa28dba`.

## iter39 — критерий удаления снимка, согласованное удаление, пассивный транспорт (1.0.61, versionCode 61)

**§2.1 Критерий удаления.** Снимок и отметка удаляются только при `RestoreCompletion.COMPLETE`
(`wifi=1` включён и это подтверждено ЧТЕНИЕМ состояния) или `COMPLETE_WAS_OFF` (`wifi=0` — не
включали). `ACCEPTED` без подтверждения, отказ `setWifiEnabled`, `UNKNOWN`, исключение и
недоступный `WifiManager` → `JIT_RESTORE_PENDING`, данные сохранены, сторожа нет; повтор — при
следующем обычном запуске.

**§2.2 Согласованное условное удаление.** `JitStore.removeOwned(opId)`: в каждом хранилище, где
лежит отметка ЭТОЙ операции, снимок и отметка удаляются одним `editor/commit` с проверкой чтением;
хранилища с чужой отметкой не трогаются (`skip(<op>)`). `commit=false` = «не подтверждено» →
`restore_left/mark_left=true`, `data=kept_or_partial`, обещания продолжения по остатку нет.
Атомарность CPS↔DPS не заявляется.

**§2.3 Неопределённость через смерть процесса.** Отметка `sending`/`undefined` после рестарта
восстанавливает `CHAIN_UNDEFINED` (`JIT_START … action=restore_only`): новый клик блокируется,
сам факт нового процесса/ID/`getprop` доказательством не является. Блокировка снимается только
подтверждённым чтением состояния сети после возврата (`JIT_UNDEFINED outcome=still_unknown`).

**§2.4 Один протокол.** Клик, планирование, принадлежность отметки и изменение сети решаются под
`INTENT_LOCK` + `CLICK_SLOT`/`RESTORE_SLOT` (без длинных вызовов под локом). Устаревшая задача не
меняет сеть (`skipped reason=…`), отказ планировщика не даёт ложной гарантии.

**§2.5 «Ключа нет» ≠ «чтение неизвестно».** `KeyPrev.present: Boolean?` (`null` = чтение не
удалось); при недостоверном прежнем состоянии клик блокируется `prev_unknown`: ни записи, ни
shell, ни `Prefs2`.

**§3.1 Пассивный транспорт диагностики.** Новый `AdbPassive` (production-код, без Android-импортов)
+ `AdbClient.executePassive`/Job.passive: bootstrap не запускается, reconnect/`ctl.restart adbd`/
replay/смена состояния канала недостижимы на всех ветках; при недоступности — `unavailable`/
`channel_not_ready`/`timeout`. Диагностика ходит через `JitNet.DiagShell`; клик/сверка остаются на
прежнем `AndroidShell`.

**§3.2 Личные данные.** `InternetDiag.redact`: MAC в форматах `aa:bb:…`, `AA-BB-…`, `aabb.ccdd.eeff`,
`**:**:**`, SSID/BSSID — удаляются во всех новых разделах, включая ошибки транспорта
(`redacted=yes/no`); IP, маршруты, номера таблиц, DNS сохраняются. В `BEGIN` — предупреждение о
чувствительности и о том, что полный общий лог может содержать иные сведения.

**§3.3 Бюджет лога.** Новый `LogBudget`: `INTERNET_DIAG_*`/`JIT_MOUNT_DIAG_*` идут по своему
лимиту (96 КБ/240 строк на 10 мин), не съедаются общим trim; при исчерпании — одна явная строка
`*_TRUNCATED truncated=true`, `BEGIN`/`END` сохраняются всегда, нумерация `part=i/N` остаётся.
`LogFiles.append` возвращает подтверждение, ошибки видны через `ioErrors()`/`lastIoError()` и logcat,
fallback `filesDir/logs`.

**§3.4 Готовность и контекст.** `ready` и список обработчиков переключаются под одним локом
(однократное событие без гонки), отложенная запись «канал пока не готов» больше не занимает
единственный сбор; `statusOf`/`fileStatus` учитывают пассивный отказ, `transportRc`, маркеры, rc,
timeout и права; байты считаются в UTF-8, а не `String.length`; `root=unknown` при неизвестном uid,
контекст и время в строке.

**§4 Обновление.** Снимок 1.0.60 без отметки — `legacy_or_unknown`: не исполняется и не удаляется,
сеть не меняется, shell не вызывается. ⛔ Обратимость общего `umount` по-прежнему не доказана;
данных ГУ нет — JIT не нажимать.

**SOURCE_SHA** = `aa28dba`.

## iter40 — неопределённость операции отделена от восстановления сети (1.0.61, versionCode 61)

**§2.1.** Восстановление клиентского Wi-Fi и разрешение неопределённой операции разведены.
`JIT_UNDEFINED … network_state=restored operation=unresolved clicks=blocked` — сеть вернули, но
исход старой команды неизвестен: включённый Wi-Fi, IP, `getprop`, boot id и новый процесс
свидетельством не являются. Единственное принимаемое свидетельство — ответ ИМЕННО той команды:
`JitRef.JitLateEvidence` + `JitOp.onLateOutcome` (`JIT_CHAIN_LATE … evidence=command_finished`).
Для снимка `wifi=0` (`COMPLETE_WAS_OFF`) флаг тоже не снимается. При неподтверждённой очистке флаг
остаётся. Если транспорт такого свидетельства не умеет — `armed=none`, неопределённость остаётся ⛔.

**§2.2.** Барьер перед кликом — сохранённое состояние, прочитанное под тем же локом и ДО любой
записи/shell: `readOperation()` + `JitRef.assessStored` → `chain_undefined` / `restore_pending` /
`previous_unfinished` / `previous_ambiguous` / `previous_broken` / `prev_unknown`. Событие старта
приложения барьером больше не является (`startDuringClickReportsClickInFlight`).

**§2.3.** Любой неподтверждённый возврат оставляет пару «снимок+отметка» своей операции, поэтому
следующий клик (любого направления) блокируется `previous_unfinished` и снимок не перезаписывается.

**§2.4.** `KeyPrev.error`: «ключ есть, значение прочитать нельзя» (исключение, неожиданный тип,
`null`) — это НЕ известное значение. Текст читается по смыслу ключа (булевы — `getBoolean`), поэтому
`ClassCastException` не подменяет чтение. Откат не удаляет ключ с неизвестным прежним значением
(`cps:prev_unknown`).

**§2.5.** Пара «снимок+отметка» читается и оценивается ПО ХРАНИЛИЩАМ (`StoreOp`/`StoredOp`), поэтому
пара не собирается из разных копий; конфликт ID/значений, половинная пара, нечитаемое хранилище и
битый снимок дают разные причины (`op_conflict`, `snapshot_conflict`, `incomplete_pair`,
`stored_unreadable`, `snapshot_unparsable`). Межхранилищная атомарность не заявляется.

**§3.1.** Усечение бюджета считается ОТДЕЛЬНО по каждому блоку (`truncatedFor`/`droppedFor`), поэтому
сетевой блок получает свою `INTERNET_DIAG_TRUNCATED` даже если бюджет исчерпали монтирования.
`END` пишет `written=full|truncated|unknown` (прочитано ≠ записано), `BEGIN/END/*_TRUNCATED`
сохраняются всегда, а обещание «разделы сохраняются» убрано.

**§3.2.** `redactWithFlag`: SSID/BSSID в кавычках с пробелами и запятыми удаляются целиком; MAC не
режет группы IPv6 (`fe80::1a:2b:3c:4d:5e:6f` не меняется) и скрытые MAC `**:**` скрываются;
`redacted=yes/no` считается по ФАКТИЧЕСКОЙ замене в исходном выводе. У пассивного job в
`workerLoop` не печатаются ни команда, ни сырой обмен (`ADB_TRACE_REPLAY` не запускается),
`portDiagnosticDump` при AUTH в пассивном пути не вызывается.

**§4.2.** При неготовом канале всё равно собирается Android API-часть (`InternetDiag.collect` с
`channelReady=false`), shell-разделы честно `channel_not_ready`; отложенная запись не занимает
единственный сбор.

**Не проверено:** реальная очередь `AdbClient`, system shell, `WifiManager` и `SharedPreferences` ГУ в
JVM не исполняются — это модель. Полевых данных нет; JIT нажимать не просить (§6). ⛔

**SOURCE_SHA** = `aa28dba`.

## iter41 — исправление потери снимка JIT и достоверность записи диагностики (1.0.61, versionCode 61)

**§2.1/§2.2 — главное.** Отметка `sending`/`undefined` означает «исход старой команды неизвестен».
Раньше задача возврата в ветке `COMPLETE`/`COMPLETE_WAS_OFF` БЕЗУСЛОВНО вызывала `store.removeOwned(opId)`:
снимок и отметка удалялись, `CHAIN_UNDEFINED` жил только в памяти и после смерти процесса новый
процесс видел `NONE` — блокировка исчезала, а старая команда могла выполниться позже. Теперь при
`wasUndefined` очистки нет вообще: `JIT_RESTORE_KEPT … network_state=restored cleanup=none data=kept`,
снимок и отметка остаются принадлежащими операции, блокировка восстанавливается по диску.
`wifi=0` (`COMPLETE_WAS_OFF`) — то же самое.

**§3.1 — поздний результат.** `NONE` больше не считается разрешением: если снимок исчез до окончания
команды, пишем `JIT_CHAIN_LATE … reason=snapshot_lost chain_undefined=true clicks=blocked` (⛔).
Неудача записи `replied` (commit=false/исключение/частичный commit, `mark_write=1/2`) — тоже блокер
(`reason=mark_write_unconfirmed`), потому что на диске осталось `undefined`. Снятие неопределённости и
подтверждённый возврат сети — по-прежнему два разных факта: пара удаляется только когда в отметке уже
`replied` И возврат подтверждён чтением (обычно следующий обычный запуск).

**§3.2 — честно о транспорте.** `JitNet.AndroidShell` НЕ реализует `JitRef.JitLateEvidence`:
`AdbClient.execute` по таймауту возвращает `ERR_ADB_TIMEOUT`, но job остаётся в очереди и может
выполниться позже, слушателя у него уже нет, а пережить смерть процесса некому. Поэтому реального
свидетельства нет: `armed=none`, неопределённость сохраняется. Тестовый `LateShell` — проверка
логики, а не заявление о возможностях `AdbClient`.

**§4.1 — одинаковый ID ≠ одинаковое состояние.** `assessStored` сверяет ВСЕ существенные поля отметок
по хранилищам: `state` (`sending`/`replied`/`undefined`), `want`, `ts`. Расхождение — конфликт
(`mark_state_conflict`/`mark_want_conflict`/`mark_ts_conflict`) ⇒ `AMBIGUOUS`: ни клика, ни очистки,
ни выполнения.

**§4.2 — ошибка получения хранилища.** `AndroidStore` больше не «теряет» исключение провайдера:
`acquire()` возвращает и хранилища, и ошибки (`provider:<Тип>`, `provider_null`). Ошибка попадает в
`readOperation()` записью с `error` ⇒ `UNREADABLE` (а не `NONE`), а `readSnapshotState()` помечает
прежнее состояние недостоверным, поэтому откат по нему запрещён. DPS, совпавший с CPS (штатный
fallback `Prefs2`), — это ОДНО хранилище, а не ошибка.

**§5.1 — «получено» ≠ «записано».** Добавлены `LogBudget.WriteOutcome` (`WRITTEN`/`DROPPED`/`FAILED`),
`LogBudget.Sink` и `LogBudget.WriteTally`. Сборщики больше не принимают `(String) -> Unit`, а
возвращают факт записи; `AppLog.diagLine` отдаёт результат `LogFiles.append` (`writeRaw`/`writeRawLine`/
`enqueueOrWrite` теперь возвращают `Boolean`). `written=full` возможен только если КАЖДАЯ строка блока
подтверждена в ОДНОМ датированном файле и усечения этого блока не было; иначе `truncated`
(пропуск бюджетом), `partial` (сбой записи или части в разных файлах после фоллбэка), `none`,
`unknown`. Неудача записи самой `…_END` уходит в независимый канал `…_END_NOT_CONFIRMED`.

**§5.2 — «запущен» ≠ «собран».** `JitRef.DiagOnce` (не более 2 попыток за процесс) отделяет факт
успешного завершения обоих сборщиков от запуска; неудачная попытка не лишает владельца ограниченной
повторной попытки при событии готовности канала; исчерпание пишется честно (`status=exhausted`),
а не выдаётся за собранный лог.

**Тесты.** `JitIter41Test` (18) — жизненный цикл снимка на настоящем `AndroidStore` с раздельными
памятью и диском, «новый процесс», конфликты полей, ошибки получения хранилищ; `JitIter41DiagTest`
(15) — факт записи через настоящий `LogFiles.appendToDir` во временные каталоги, сбои/пропуски/
фоллбэк/END, лимит и учёт попыток сбора. Два теста iter40/iter39, требовавших удаления снимка при
`undefined`, исправлены — они противоречили §2.1.

**Не проверено:** реальная очередь `AdbClient` и system shell, `WifiManager`/`SharedPreferences` ГУ,
запись `AppLog` с настоящим Android `Context` (внешний каталог и фоллбэк). Полевых данных нет;
JIT на ГУ не просить (⛔, §6/§7).

**SOURCE_SHA** = `aa28dba`.

---

## iter42 — полный журнал без потерь, TTL-снимки по событиям, оценка `umount`

Мандат: `ПРОМПТ_iter42_ПОЛНЫЙ_ЛОГ_TTL_И_UMOUNT.md`. Ветка `fix/v2`, база — iter41 (`544b04e`).

**§2 «не выбрасывать полезные записи».** Причина потери в логе владельца — не текст предупреждения, а
`LOG_BUDGET exceeded … action=trim`: последующие строки отбрасывались (`dropped` 1→57→106→127).
Теперь общий бюджет НЕ режет строки: он ротирует ЦЕЛЫЙ файл (`LogFiles.rotateNow`, `action=rotate`), а
если ротация невозможна — честный `action=drop_after_limit reason=rotate_unavailable`, который виден
в независимом канале. `LogFiles` переписан: tier'ы `MAIN`/`VERBOSE`, лимит файла 8 МиБ, ≤6 файлов
на сутки, суммарный cap 64 МиБ, retention 14 дней, ротация только целыми файлами, `Result(written,
file, reason)` на каждую запись, счётчики `ioErrors/rotations/dropped/lastIoError`.
**Найдено и исправлено в этой итерации:** `partsOf` считал verbose-файлы частями основного журнала
(префикс `voiceapp3-` — префикс `voiceapp3-verbose-`), а `activeFile` выбирал «последний по имени»,
из-за чего после ротации активным снова становился базовый файл и почти каждая строка открывала
новую часть. Теперь индекс части разбирается строго (`0` — базовый, `n` — `<base>.<n>.log`), активный
файл — с максимальным индексом, лимит частей — суммарно на сутки.

**§2.4/§2.5 шум отдельно от журнала.** `AppLog.isVerboseLine` + `VERBOSE_PREFIXES` (ADB_TX/RX,
execute-трассы) уходят в отдельный tier (`voiceapp3-verbose-DD.MM.YYYY.log`, 4 МиБ × 4), счётчики —
`AppLog.verboseStats()` → строка `ADB_VERBOSE_STATS lines=… failed=… tier=verbose`. Обязательная
диагностика сети/JIT/TTL от verbose-шторма больше не страдает.

**§2.6 подтверждение записи.** `LogBudget.Sink.write` возвращает `LogWrite(outcome, file)`; `WriteTally`
учитывает ФАКТИЧЕСКИЙ файл каждой строки, поэтому смена приёмника (external→internal→END) больше не
даёт ложного `written=full`; неподтверждённая `…_END` уходит в `…_END_NOT_CONFIRMED`.

**§2.7 раздельные статусы.** `InternetDiag.collectWithReport()` / `JitMountDiag.collectWithReport()`
возвращают `JitRef.DiagReport` и `Report(sections, info)`: число секций, «определённых» секций,
строк, подтверждённых в файле, отказов записи, пропусков, вердикт и статус
`completed|partial|channel_not_ready|failed`. `completed` — только по проверяемым критериям (канал
готов, нет `unknown`/`timeout`, `failed==0`, вердикт `full|truncated`). `runBoth` пишет
`DIAG_SECTION_RESULT block=… status=…` и общий `status=…`; `runCatching.isSuccess` больше не считается
успехом.

**§3 TTL — только диагностика.** Добавлен `JitNet.requestEventSnapshot(app, reason, netInfo,
stableDelayMs, pairWith)`: снимок привязан к СУЩЕСТВУЮЩЕМУ событию (переключение TTL, значимое изменение
сети), выполняется в фоновом worker'е (не в UI-потоке), ограничен частотой (≥90 с, ≤6/час, дедуп
in-flight) и пишет `SNAP reason=… status=scheduled|skipped|completed`. Для пары «до/после» сделана
`JitRef.EventSnapshots.tryAcquirePaired`: `<событие>_pre` → `<событие>_post` не режется минимальным
интервалом (иначе «после» всегда отбрасывалось бы), ограничение часа действует, пара допускается только
внутри одного события и не позже 10 минут. Хуки: `ToolsBackend` (тумблер TTL — только при РЕАЛЬНОМ
изменении состояния) и `NetworkTtl` (после переприменения правил по событию сети). Сеть/TTL этим кодом
не меняются, новых кнопок нет.

**§5 недостатки iter41.** (а) Набор хранилищ CPS/DPS, прочитанный до записи, сверяется с набором, куда
запись реально прошла: расхождение ⇒ `blocked=store_set_changed`, ни записи, ни shell (fail closed),
с объяснением причины в логе; блокировка не липкая — как только оба хранилища снова читаются, клик
проходит. (б) Пара `sending/undefined` сохранена как была (iter41): позднего свидетельства в
`AndroidShell` нет (`armed=none`), авторазблокировки по таймеру не обещаем.

**§6 сборка без тестов.** Удалена привязка `assembleDebug|assembleRelease → dependsOn("testDebugUnitTest")`
(`app/build.gradle.kts`). Тесты запускаются отдельно: `./gradlew :app:testDebugUnitTest`. Проверка
семантики на минимальном проекте Gradle 9.1 (тот же JDK 17): без привязки `--dry-run` для
`:app:assembleRelease` печатает только `:app:assembleRelease SKIPPED`; со старой привязкой —
`:app:testDebugUnitTest SKIPPED` + `:app:assembleRelease SKIPPED`. Полный `--dry-run` реального проекта
в песочнице не выполнен: нет предустановленных Gradle/SDK, а на 2 ГБ RAM daemon падает
(`Gradle build daemon disappeared unexpectedly`). **CI-покрытие тестами при этом теряется:** workflow
запускает только `assembleDebug`, а прав на `.github/workflows/` у токена нет (scope `repo`, без
`workflow`) — предложен diff (`docs/agent/ci-unit-tests.diff`), который нужно применить вручную.

**Тесты.** Новый `JitIter42Test.kt` (20): содержимое ФАЙЛОВ (ротация без потерь, лимит файла, verbose-
шторм не задевает основной журнал, смена даты, индекс ротации не путается с датой, retention не трогает
чужие файлы, недоступный каталог — честный отказ, конкурентные писатели), `store_set_changed`
(fail closed + возврат к работе), `EventSnapshots` (частота, дедуп, час, пара «до/после»).
Всего локально: **322 теста, 0 падений**.

**Не проверено:** Android-интеграция `LogFiles.append(context)`/`rotateNow(context)` (внешний каталог,
`usableSpace`, фоллбэк на `filesDir`), реальные `SharedPreferences`/`WifiManager` ГУ, поведение
системы при полном диске. Полевых данных нет; `umount`/`mount`/zygote/reboot не выполнялись, клик JIT
у владельца не запрашивался.

## iter43 — никакой бесследной потери: журнал, TTL-снимки, JIT-оркестрация

Мандат: `ПРОМПТ_iter43_ИСПРАВИТЬ_ПОТЕРЮ_ЛОГОВ_TTL_JIT.md`. Ветка `fix/v2`, база iter42 (`9debbd5`).
Коммиты: `6f1042b` (код + тесты), `9c311df` (маркер сборки), `ddabcab` (§1.4 остатки).

**Найдено тестами и исправлено (два настоящих дефекта потери данных).**

1. `LogFiles.writeSplit` уходил в бесконечную рекурсию на строках > 256 КиБ: кусок был ровно
   `MAX_LINE_BYTES`, «кусок + маркер» снова превышал лимит → `writeAt` → `writeSplit` → … (стек).
   Теперь кусок = `MAX_LINE_BYTES - SPLIT_MARKER_RESERVE(96)`.
2. Там же разрез многобайтового символа проверялся по `bytes[cut-1]` вместо `bytes[cut]`, поэтому
   кириллица могла разрезаться посередине (`0xD1 | 0x8F`) и в файле появлялся `U+FFFD` — фактическая
   потеря байтов. Проверка исправлена; тест читает байты обратно и требует отсутствия `U+FFFD`.
3. `JitRef.EventSnapshots`: окно часовой квоты определялось как `windowStartMs == 0L`, из-за чего при
   старте с `nowMs = 0` окно сбрасывалось на КАЖДОМ вызове и квота не набиралась. Введён `windowStarted`.

**§1.1.** Подмены исходной строки уведомлением нет: `AppLog.writeRaw` пишет ровно исходную строку, а
уведомление (`*_TRUNCATED`, `LOG_WRITE_FAILED`, `LOG_LEGACY_FAILED`) — отдельная запись со своим
результатом (`AppLog.kt:400–470`). `budgetAllow` больше не режет по объёму: ограничение делает только
ротация по размеру, поэтому «ротировать каждые 150 КиБ» больше нечему.

**§1.2/§1.3 `LogFiles`.** Возрастающая нумерация частей на дату/tier, лимиты по числу файлов и байтам
(не по диапазону номеров), блокировка каталога `.logfiles.lock`, `rotateNow` без подтверждённого
маркера ≠ `true`, `freeSpace` различает «место есть/нет/неизвестно» (`LogFiles.kt:198`), при неизвестном
месте пишется `LOGFILES_SPACE_UNKNOWN`. Добавлен честный суммарный учёт
(`LogFiles.kt:616–700`): `DirTotal` отделяет СВОИ байты от `voiceapp3.log` (legacy) и
`voiceapp3-important.log`, `reportTotals()` печатает `LOG_TOTALS`/`LOG_TOTALS_SUM` с оговоркой, что
`64 МиБ + 16 МиБ` — лимит НА КАТАЛОГ, а не абсолютный предел (каталогов два). `partsOf` не задевает
важный/legacy файлы (тест на фактических файлах).

**§1.4 приватность.** Новый `LogRedact` (`LogRedact.kt:23`): тела литералов → `'<N bytes>'`, длинные
base64/hex-блоки → `<blob:N>`, значения `key/token/secret/...` → `<redacted>`, длина считается в
БАЙТАХ UTF-8. Через него идут `last_cmd` (`AdbClient.kt:275`), `VERIFY EXEC … cmd=` (`:1898`), хвост
вывода при провале (`:1966`), диагностический dump (`:2221`), logcat adbd (`:2232`), строка о вызове
с main-потока (`:415`) и `recovery=none cmd=`/`ADB_TRACE`-путь (`:511`, `:654`).

**§2.1/§2.2.** `END` строится из фактов ДО записи и содержит `written=unconfirmed`; факт пишется
отдельной `_END_CONFIRM` со `written=full|partial|truncated|none|unknown`, отказ —
`*_END_NOT_CONFIRMED`/`*_END_CONFIRM_NOT_WRITTEN`. `completed` ⟺ канал готов ∧ секции непусты ∧ все
`ok|empty` ∧ `verdict=full`; `truncated` → `partial`, причина `truncation_not_checkable` при
невозможности пробы усечения (проверено тестом `endSummaryIsFactsNotDiagnosis`).

**§2.3 оркестрация.** `JitRef.DiagRunner` (`JitNetCore.kt:1003`): не более одного сбора одновременно
(`in_flight`), отказ при `already_completed`/`attempts_exhausted`, ограниченные повторы через `post` с
проверкой поколения, отмена страховки «через 30 с» после успеха, строки `DIAG_ORCH …`. Исправлена
ловушка: повтор получал обёртку над самим собой (`runRef` → `StackOverflowError`), теперь повтор
получает ИСХОДНОЕ тело попытки. `JitNet.requestDiagOnce` использует `startWithRetry`.

**§3 TTL-снимки.** `JitRef.TtlSnapshotFlow` (`JitNetCore.kt:1178`) — метки `pre_confirmed`/`pre_overlap`/
`pre_late`/`pre_unknown` и `post_after_apply`/`post_without_confirmed_apply` считаются по ФАКТИЧЕСКИМ
временам; `EventSnapshots` получил отдельную квоту на действия владельца (`userMaxPerHour=12`,
`tryAcquireUser`/`tryAcquireUserPaired`), `remainingMs()` для trailing-снимка; `JitNet.requestEventSnapshot`
получил `userEvent`/`phaseLabelProvider`/`onCollected`/`scheduleTrailing` (один trailing-снимок),
`beginTtlSnapshot`/`finishTtlSnapshot` пишут `TTL_SNAP_PAIR`. `ToolsBackend.Toggle.TTL_65` заказывает пару
«до/после» реального переключения. Сеть и TTL не меняются.

**§4 CPS/DPS.** `Prefs2.dpsOrNull` (`Prefs2.kt:33`) — без скрытого фоллбэка на CPS; `JitNet` берёт DPS
только через него (`JitNet.kt:116`), поэтому «две копии» больше не могут оказаться одной. Если DPS вернул
ту же копию, что CPS, это явно пишется (`dps_distinct=false … одна копия`). `SnapshotWrite` получил
`appeared`/`absent`: хранилище, появившееся между чтением и записью, и пропавшее — перечисляются явно,
клик блокируется (fail closed). Откат по-прежнему только по своим ключам, с честным отчётом о частичном
отказе.

**§5 сборка/CI.** Привязки тестов к `assemble*` нет (iter42). CI `Build APK` (`assembleDebug`) по
`ddabcab`/`6f1042b`: см. статус ниже. Полный `--dry-run` реального проекта в песочнице НЕ выполнялся —
2 ГБ RAM, daemon падает (OOM); выдавать это за выполненное нельзя.

**Тесты.** Новый `JitIter43Test.kt` (29 тестов, 5 классов): учёт своих/чужих байтов, недоступный
каталог, `partsOf`/`pruneTier` не трогают важный и legacy файлы, 12 частей с уникальными возрастающими
номерами и одним активным файлом, разбиение длинной строки по БАЙТАМ UTF-8 без `U+FFFD`, приватность на
маркерах искусственных секретов, `DiagRunner` на синтетическом планировщике, метки TTL-снимков и
user-квота. Локально: **351 тест, 0 падений** (`/home/user/runtests.sh`), `APOLOG_TYPES_OK`.

**Не проверено (честно).** Android-интеграция `LogFiles.append(context)`/`rotateNow(context)` (внешний
каталог, `usableSpace`, фоллбэк на `filesDir`), реальные `SharedPreferences`/`WifiManager` ГУ, поведение
при полном диске, `umount`/`mount`/zygote/reboot — на ГУ ничего не запускалось, JIT-клик не выполнялся.

## iter44 — рабочий JIT + интернет после `zygote` + полный лог (база `adf0bcd`, `e21a5a9`/`797c463`)

Полный отчёт: `docs/agent/REPORT_iter44_rabota_jit_polnyj_log.md`. Локально: **370 тестов, 0 падений**,
`APOLOG_TYPES_OK`. На ГУ ничего не выполнялось.

* **§1.1 (исправление)** Утверждение iter43 «шаг `umount` выключен» — **ложно**. `umount -l` по точкам
  `grep ' /data/app/' /proc/mounts` исполняется на каждом клике (`JitNetCore.kt:722-725`), между
  `svc wifi disable`+дренажом `ip rule` и `setprop dalvik.vm.usejit`/`ctl.restart zygote`. Namespace не
  проверяется (как и в референсе `w0/la.run`). Текст iter43 исправлен пометками на месте.
* **§1.2/§1.3** `appeared`/`absent`/подмена идентичности блокируют скрипт (`JitNetCore.kt:1631`,
  `JitNet.identityOf`); `planRestore` → `owned/foreign/unknown`, откат только своим, иначе
  `nothing_proven_owned`; восстановление Wi-Fi возвращает **исходное** состояние.
* **§2** `LogBudget.DiagState` не отбрасывает диагностику (`LogBudget.kt:293-358`, `*_BUDGET_HIGH`);
  `writeSplit` считает каждую часть (`LogFiles.kt:580`), старые части не удаляются до успешной записи;
  `parseOwn` до 9 цифр; ретеншн во время работы.
* **§3** `TtlSnapshotFlow` привязан к `opId`: `pre_unknown/pre_unfinished/pre_confirmed/pre_late/
  pre_overlap`, `post_after_apply[_not_ok:<result>]`; начало сбора — по факту (`onCollectStart`);
  снимок сети заказывается **независимо** от TTL (`NetworkTtl.kt:388-397`).
* **§4** Параллельная диагностика убрана: единственный владелец попыток — `DiagRunner`;
  `CHANNEL_NOT_READY` больше не расходует попытку; флаг «уже пытались» не блокирует сбор после
  реальной готовности канала.
* **§5** `--dry-run` реального проекта не выполнялся (OOM); CI — `43367011105` по `797c463`.

## iter45 — память тумблеров, TTL, звук замка, JIT (база `025cc98`, `5794fec`/`15dd3b2`)

Полный отчёт: `docs/agent/REPORT_iter45_pamyat_tumblerov_ttl_zvuk_jit.md`. Локально **391 тест, 0 падений**;
CI `43464916714` (`15dd3b2`) — success (сборка; unit-тестов в workflow нет). `versionCode=63`, `1.0.63`.

* **§4 общая причина «ложных» тумблеров:** снимок `tools.state` отправлялся ТОЛЬКО после клика
  (`UiBridge.pushToolsState` во всех обработчиках), поэтому холодное открытие рисовало `def`
  (17 из 19 тумблеров → OFF), а первый клик привозил снимок и «включал» соседние. Теперь снимок
  запрашивается при показе/старте/resume (`ToolsPanel.show` → `onNeedState`, `UiBridge.requestToolsState`
  в BgWorker), снимки нумеруются (`seq`, старый не откатывает новый), `"false"` больше не читается как
  ВКЛ, «не прочитано» ≠ OFF.
* **§5 TTL:** намерение `ttl_65_ativo` больше не выключается автоматикой (убраны `putBoolean(false)`
  в провале клика и в сетевом ресивере — это и было «TTL постоянно отключается», лог 2880/14322/18389);
  факт — `ttl_65_applied` + причина, поколение намерения отбрасывает старый результат.
* **§6 звук:** отказ bind/копирования (`лог 5549`, `ERR_SCRIPT_UPLOAD_CLEA…`) больше не пишет
  `lock_car_audio_enable=false`; факт `pending`; неудачный поиск на USB не снимает работающий звук.
* **§7 JIT:** окно жизни операции 15 мин: устаревшая `undefined`-отметка разблокирует НОВЫЙ клик и
  НЕ применяет старый снимок Wi-Fi (`JIT_OP_EXPIRED`, `JIT_START … stale=1`).
* **§9.4:** `AdbClient.execute` с main (лог 17057/23016) — приёмник `SCREEN_ON` уводит яркость/иконки
  в BgWorker.
* **§10:** `AppFileLogger` пишет под файловым локом + fsync; хвост `0x00` (3296 байт в legacy-файле)
  детектируется строкой `LOG_TAIL_NUL_DETECTED`, файл не переписывается.

## iter46 — 2026-11-13 (коммиты `c818ffe` … `3d2cf85`)

Задача: довести до рабочего состояния тумблеры (§4), TTL (§5), звук замка (§6), JIT (§7.2),
журналы (§8) и §9, не выдавая JVM-тесты за проверку на ГУ.

### Причины (по коду и логу 13.11, заново лог не разбирался)
- §4.1: `tools.state` слался только из обработчиков клика → холодное открытие подставляло `def`
  (17 из 19 тумблеров = false); `seq` выдавался ПОСЛЕ формирования снимка → старый ответ получал
  больший номер.
- §4.2: `Prefs2.getBoolean = cps || dps` → старое `true` перебивало новый OFF
  (`master_remember_brightness=reg:true/dps:false`).
- §5: намерение писалось после отказов; debounce/in-flight теряли клик; `applied` прошлого сеанса
  выдавался за текущий.
- §6: `bindDisabledUntilBoot` без сброса + `ERR_ROOT_CHANNEL_UNAVAILABLE` как «детерминированный»
  → звук не работал до ребута (лог `:5549`).
- §8: `AppFileLogger.kt:117–132` ротация ДО лока, ошибки глотались, `fsync` не было; init-строка
  писалась до проверки NUL. В хвосте файла — 3296 Б `0x00` на границе смерти процесса.

### Сделано
- `ui/UiBridge.kt` — номер снимка в момент запроса, доставка только через CAS
  `markToolsStateDelivered`, отказ → `TOOLS_STATE_STALE`.
- `tools/ToolsBackend.kt` — `revision()`, `$keys`/`$rev` в снимке (вместо зашитой константы 19).
- `tools/Prefs2.kt` + `tools/Toggles45.kt` — ревизии `<key>#rev`, побеждает последняя запись,
  миграция без потери настроек; `pickBoolean`/`pickString`.
- `tools/TtlReconcile.kt` (новый) — «последнее намерение», добивание ≤3, `networkDirty`.
- `tools/NetworkTtl.kt` — намерение первым, `applyForIntent`, boot-scoped факт, одноразовый recheck.
- `tools/LockSound.kt` — `bindGen`, backoff 5/10/20/40 с, отмена по поколению, root-ошибки временные.
- `tools/JitNetCore.kt` — `restoreMayEnableWifi`: поздний независимый старт НЕ включает Wi-Fi.
- `tools/LogFiles.kt` — жертвы по возрасту, защита активной части дня, отказы delete/lock видны.
- `tools/FileLogCore.kt` (новый) + `tools/AppFileLogger.kt` — лок+ротация+запись одной операцией,
  NUL до записи, `LOG_WRITE_FAILED` вместо тишины.
- `tools/WindowDoors.kt` — `VENT_CLOSE_UNCONFIRMED` + один поздний readback (не цикл).
- Тесты: `Iter46Test` (16 методов) + правки существующих (реальные отметки времени вместо `ts=0`).

### Проверено / не проверено
- `bash runtests.sh` → **OK (415 tests)** (база `2893d5b`: 391).
- CI run `43476301200` на `fd0529e` = **success** (компиляция + debug APK; тестов в workflow нет).
  Run `43473746255` = failure: незакрытый `apply` и `"$rev"` как интерполяция в `ToolsBackend.kt`;
  локальный стенд этого не видел, потому что не компилирует `ToolsBackend.kt` (нет Android-стабов).
  `SOURCE_SHA=fd0529e`, handoff-коммит `c56e4eb`.
- **1.0.63 на машине НЕ проверялась.** Слышимость звука, TTL на живой сети, JIT-компиляция,
  `umount`/namespace — за владельцем; перезагрузка ГУ как лечение по-прежнему запрещена.
