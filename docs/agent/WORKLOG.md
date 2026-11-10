# WORKLOG

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

### Открыто

1. 🟡 §2.4 — применяются ли слоты мягким `kill` плагина или только перезапуском SystemUI (нужен лог владельца).
2. 🟡 раскладка ≠ скриншот 2 — нужен read-only `adb pull` SystemUI/плагина.
3. 🟡 boot-маунт плагина (`HvacDigitalPlugin.bootInstall`) один раз перезапускает SystemUI при загрузке —
   принято в iter33b, в этой итерации не менялось (правило «принятое без мандата не трогать»).

## 2026-11-06 — iter33k **fix1** (1.0.56, до установки)

**Правка владельца:** «Нужно сделать точную реализацию референса, а не придумывать какие-то кнопки».
Мандат: `PROMPT_iter33k_fix1_1.0.56_fix.md`. Версия остаётся `versionCode = 56`, `versionName = "1.0.56"`.

- **Удалено (моя выдумка):** кнопка «Применить слоты (SystemUI)» (`ToolsPanel.kt`, `ToolsBackend.kt`,
  `StatusIcons.applySlotsSystemUi`, `HvacFlagsApply.applySystemUi`), «мягкий apply»
  `pluginRestartScript()` + `PLUGIN_PKG` + `applyResult()` — в дексе референса нет pidof пакета
  плагина, они его не убивают.
- **Сделано как в референсе:** `HvacFlagsApply.recargaScript()` — 1:1 с `w0/x7.g("tela")`:
  `PB=$(pidof com.android.systemui); [ -z "$PB" ] || kill $PB`, до **20 попыток × 500 мс**
  (таймаут 20 с), проверка смены pid, строки `RECARGA_OK/RECARGA_FAIL … (barra NAO reiniciou)`.
  Лог клика: `FLAG_APPLY method=bar_recarga tries=… result=ok|fail dt=…ms`, при провале
  `bar_recarga fail (barra NAO reiniciou) tries=…`, затем `TOGGLE … apply=bar_recarga …`.
- **Без изменений:** `flagsCommand` (`x7.i`), `via=bind|nenhum`, `FLAGS_SYNC`, кнопка «Монтировать
  плагин», root/CarPlay-кнопки, TTL со скоупом, авто-восстановление рута, маркер `buildinfo`,
  отсутствие красных текстов.
- **Тесты:** `HvacFlagsApplyTest` 7 → 9 (`recargaScript`, `recargaResult`, новый формат `applyLine`),
  новый `NoApplyButtonTest` (3: нет кнопки, нет kill процесса плагина, recarga — единственный
  перезапуск панели из клика).
- **CI:** прогон `42263967081` (`60c8a0b`) — success, **94 PASSED / 0 FAILED**, job `128050870657`,
  артефакт `13525909172` (177 120 961 Б); финальный прогон **`42265910234`** (`dcf80b1`: код `09c7f87`
  + маркер + отчёты) — success, **94 PASSED / 0 FAILED**, `BUILD SUCCESSFUL in 3m 29s`,
  job `128057633161`, артефакт `13527033977` (177 120 994 Б), `BuildMarkerTest > commitSourceIsHonest PASSED`;
  прогон доков `42267658779` (`04910f8`) — success, 94/0, job `128063673059`.
- **Отчёт:** `docs/agent/REPORT_iter33k_fix1.md`.
- **Для владельца:** клик тумблера перезапускает панель, интерфейс мигает несколько секунд —
  это штатный механизм референса, не дефект. Отдельной кнопки применения нет и не будет.

## 2026-11-07 — iter33l (1.0.57)

**Мандат:** `PROMPT_iter33l_1.0.57.md`. База `811ced6`, ветка `fix/v2`, `versionCode = 57`,
`versionName = "1.0.57"`. Разбор лога `voiceapp3 (23).log` (07.11, сеансы 15:02–15:05 и 15:09–15:25).

### Корень «деградации транспорта» (главная находка)

Не таймауты adbd: **две параллельные заливки одного скрипта**. В `15:10:04.931` (`CENTRAL_Bg-2`) и
`15:10:05.056` (`CENTRAL_Bg-3`) два потока льют `ttl_script` в один и тот же
`/data/local/tmp/voiceapp3_ttl_script.sh`; `rm -f` одного стирает файл другого, чанки идут в общий
файл → `SCRIPT_BYTES=12296 EXPECTED=6148` (ровно ×2), затем `No such file`. В `15:04:03` то же с
`hvac_enable` (`SCRIPT_BYTES= EXPECTED=7408`). Отсюда `rc=99` → `rule_not_installed` у TTL и общий
вид «транспорт сломался».

### Сделано

- **§1 слоты:** `FLAG_VERIFY wrote=… via=… found=… match=ok|rewritten` после каждой recarga
  (`HvacFlagsApply.verifyNow`), повторный push до 3 раз без новых kill (`FLAG_REWRITE`);
  `EXT_PROBE` — эксперимент с обоими именами наружней (`exttemp` и легаси `temp_ext`) + кнопка
  «Проба слотов (наружняя)»; `PersistCleaner` больше не удаляет `temp_ext` (нормализует в `0`).
- **§2 тумблеры:** mutex клика (`TOGGLE_SKIP reason=busy`), успех только при `rc=0` И `match=ok`,
  периодический PUSH из sync убран → одноразовое `FLAG_ALIGN` (`alignHvacFlagsOnce`).
- **§3 рут:** `AdbClient.reconnectRoot()` — полное переподключение (тот же цикл, что старт:
  `localTargets` → `discoverDestination` → `fullProbe` → `channelUp`), до 3 попыток, строка
  `ADB_RECOVERY action=reconnect … root_after=root|none`; `ctl.restart adbd` — только крайняя мера
  после провала reconnect (`action=restart_adbd_after_reconnect`); кнопка «Восстановить root-канал»
  теперь зовёт reconnect; root-команда без канала инициирует reconnect, а не тихий отказ;
  `CHANNEL_LOST last_cmd=… dt=… reason=…` на обрывах и таймаутах; заливки скриптов сериализованы
  (`synchronized(scriptUploadLock)`) и повторяются до 3 раз со сверкой размера (`size_check=ok` /
  `SCRIPT_UPLOAD GIVEUP`).
- **§3.5 JIT:** основной ключ `persist.dalvik.vm.usejit` (init поднимет `dalvik.vm.usejit`),
  `persist.sys.dalvik.vm.usejit` остаётся маркером; boot-строка
  `JIT_BOOT runtime=… persist=… init=… expect_runtime_true_after_reboot=…`.
- **§6 тесты:** новые `FlagVerifyTest` (5), `ToggleNoLieTest` (3), `ReconnectRecoveryTest` (5),
  `JitPersistTest` (4); `BuildMarkerTest` → 57; `PersistCleanerTest` → нормализация.

### CI

- `42375642156` (`e7f191c`) — компиляция: `Unsupported escape sequence` в `noteCmd` → исправлено.
- `42376515294` (`f0ce4c9`) — тест `JitPersistTest > bootProbePrintsThreeValues` (ожидал
  неинтерполированный литерал) → исправлено.
- **`42377387076` (`3441865`) — success, 111 PASSED / 0 FAILED**, `BUILD SUCCESSFUL in 4m 19s`,
  job `128417673619`, артефакт `13576018450` (177 124 140 Б); handoff-прогон **`42378330319`**
  (`98d1ae1`, маркер `SOURCE_SHA=3441865` + отчёты) — success, **111 PASSED / 0 FAILED**,
  job `128420457583`, артефакт `13577001294` (177 124 163 Б), `BuildMarkerTest > commitSourceIsHonest PASSED`.

### Открыто

1. 🟡 §1.3: нужен `SystemUI.apk` (read-only pull) для точных имён пропов и правила склейки ячеек.
2. 🟡 Какой ключ наружней читает панель — решит эксперимент `EXT_PROBE` по логу владельца.
3. 🟡 TTL: повторный тест владельцем после лечения канала.
4. ⛔ Не делалось: правки системы, smali, `setenforce 0`, `pm install`, `pkill avas`,
   `ctl.restart zygote`, глобальное правило TTL, перезагрузка ГУ как лечение.

## 2026-11-07 — iter33m (1.0.58)

### Сделано

- **§1 P0 TTL + CarPlay.** `del_all()` переписан без word-splitting:
  `iptables -t mangle -S POSTROUTING | grep 'ttl-set' | sed -n 's/^-A POSTROUTING //p' | while IFS= read -r spec`
  → `-D POSTROUTING $spec` (то же для `ip6tables`/`hl-set`), эталонный цикл `1..5 ‖ break` оставлен как
  дополнение. Скоуп ровно один — `-o <uplink>`; fallback `! -o <carplay>` (`exclude_carplay`) удалён и из
  `TtlScript`, и из `TtlRules.variants`; uplink-интерфейс CarPlay (`p2p*`, `rndis*`, `ncm*`, `usb*`, `ap0*`)
  отбраковывается (`TTL_UPLINK_REJECT`). Нет uplink → правило не ставится (`TTL_TRY variant=none
  reason=no_uplink`, `TTL_SCOPE result=failed`), тумблер `✗`. Санация при старте/ON/OFF печатает
  `TTL_SANITIZED removed=N v4_removed=… v6_removed=…`, судьбу каждого правила (`TTL_DEL v4|v6 spec="…" rc=…`)
  и чужое правило (`TTL_FOREIGN`). Честный OFF: `VERIFY_V4=OFF_OK` + `TTL_OFF … result=OK` только при
  `TTL_V4=0 && TTL_V6=0`; `NetworkTtl.parseVerifyResult` больше не возвращает `PARTIAL` — только `OK`/`FAILED`
  (отсюда исчезло «✓ SUCCESS | применено частично»). Попутно: boot-ветка ставит правило только при
  включённом тумблере (`buildScript(active)`), раньше ставила всегда.
- **§2 P0 тумблеры.** Источник истины — префы `hvacdigital_temp/_batt/_temp_ext`: `getHvacDigital` читает преф,
  клик пишет преф и сразу обновляет `cachedFlags`; `syncFromDevice` — read-only (`FLAGS_SYNC … action=props_readonly`),
  обратная запись `ui_from_props` удалена; стартовое выравнивание теперь ПРЕФЫ→props
  (`alignPropsToPrefs`, `FLAG_ALIGN … action=align_props_to_prefs`); трасса `PREF TRACE write/read key=hvacdigital_*`.
- **§3 P0 EXT_PROBE_V2.** Поочерёдный перебор трёх имён наружней (`exttemp`, `temp_ext`, новое из `getprop.txt`
  `ext_temp`): одно `=1`, остальные `=0` одной командой → полный цикл recarga → read всех трёх → строка
  `EXT_PROBE_V2 step=i/3 prop=… wrote=1 found=… result=ok|missing owner_look=wait15s` → пауза 15 с; в конце
  `EXT_PROBE_V2 props_follow_prefs=ok|fail`. Ничего не удаляется. `PersistCleaner` нормализует в `0` все три
  легаси-имени. Стартовая read-only диагностика: `EXT_DIAG prop=…` (6 props) + `PLUGIN_DIAG`
  (`sysui_plugin_shortcut_settings`, `sysui_tuner_version`). Своя раскладка строки состояния не рисовалась.
- **§5 тесты.** `TtlScriptTest` 6→8, `ToggleNoLieTest` 3→4 (`prefsAreSourceOfTruth`), новый `ExtProbeV2Test` (5),
  `PersistCleanerTest` (три имени), `TtlRulesTest` (fallback удалён), `BuildMarkerTest` → 58/`1.0.58`.
  Было 111 PASSED → стало **119 PASSED / 0 FAILED**.

### CI

- `42387938562` (`0e611d0`) — ❌ компиляция: незакрытая кавычка в `startupDiagnostics` (ошибка патча).
- `42388708679` (`1fe50d1`) — ❌ компиляция: наложение правки съело ветку `when` для `SYSUI_TUNER`.
- `42389628649` (`bfa4409`) — ❌ тесты: 119 completed, 2 failed — `ToggleNoLieTest.prefsAreSourceOfTruth`
  (слово `action=ui_from_props` осталось в KDoc) и `TtlScriptTest.offVerdictNeedsZeroRules` (проверка ловила
  grep-шаблон `^-A POSTROUTING -o`, а не добавление правила).
- **`42390693171` (`d62b5bd`) — success, 119 PASSED / 0 FAILED**, job `128457450413`, артефакт `13581512748`
  (177 129 381 Б), `BUILD SUCCESSFUL in 3m 23s`, `BuildMarkerTest > commitSourceIsHonest PASSED`;
  handoff-прогон **`42391607364`** (`a1d4d5b`, маркер `SOURCE_SHA=d62b5bd` + отчёт/WORKLOG) — success,
  **119 PASSED / 0 FAILED**, job `128460179802`, артефакт `13581349184` (177 129 336 Б),
  `BUILD SUCCESSFUL in 4m 15s`.

### Открыто

1. 🟡 Какое из трёх имён наружней читает панель — решит `EXT_PROBE_V2` по логу и скрину владельца.
2. 🟡 TTL: повторная проверка владельцем, что CarPlay жив при включённом тумблере и что OFF даёт `TTL_V4=0 V6=0`.
3. 🟡 Экран не менялся даже при `FLAG_VERIFY match=ok` — ждём `EXT_DIAG`/`PLUGIN_DIAG` и скрин панели 1.0.58.
4. ⛔ Не делалось: правки системы, smali, `setenforce 0`, `pm install`, `pkill avas`, `ctl.restart zygote`,
   глобальное правило TTL, перезагрузка ГУ как лечение, своя раскладка строки состояния.

---

## iter33n — 1.0.59 (база `e70e615`, код `056d14e`, маркер `SOURCE_SHA=056d14e`)

### Сделано

- **§1 P0 слоты.** Разобран плагин `geely_auto_systemui_plugin_digital.apk` (androguard, smali):
  `StatusIconManager` (условия приёма, `crateAppStatusIconInfo`, `mIdRankMap`, `addDefaultStatusIcon`),
  `StatusContentLayout.addIcon`, `StatusIconPositionManager`, `SmartBarController`, `HvacVehicleTempWidget`/`HvacDigitalHelper`.
  Итог: extras у нас 1:1 с `kg.j`; направление иконки в протоколе отсутствует и в байткоде
  для уведомлений жёстко `LEFT` ⇒ «справа» через этот API недостижимо (правый кластер — только родные
  иконки плагина + SmartBar-плитки). Наше уведомление приведено к единой таблице
  `StatusIconContract`/`contractExtras()`/`cexTable()`; `StatusIconContractTest` (5 тестов) держит контракт.
  `HvacDigitalHelper` берёт режим из VHAL `354419984` area 5, строк `persist.sys.hvacdigital` в APK нет.
- **§2 P0 `EXT_PROBE_V2`.** Каждый шаг в `try/catch(Throwable)` с `EXT_PROBE_V2_ERROR step=i/3 …`,
  пауза через `pauseLogged()` (логи `wait`/`wait_done`, сон по 2.5 с), восстановление флагов и строка
  `props_follow_prefs` — в `finally`, т.е. выполняется всегда.
- **§3 P0 `JIT_BOOT`.** `AppPerformance.jitBootProbe()` вызывается из `App.onCreate` через `BgWorker`
  без условий, одна проба за процесс, печатает `runtime/persist/profiles/init/expect_runtime_true_after_reboot/marker/key/pref`,
  при `runtime!=true && persist|marker` — `reason=init_did_not_carry`.
- **§4.** `VER` из `BuildConfig.VERSION_NAME`; строгий разбор `TTL_V4/TTL_V6` (ложный `v6=null` снят);
  `dedupe()` + `TTL_ADD_COUNT n=… v6=…` в ON; `FLAG_ALIGN` не с main thread (попытка не сжигается);
  `isActionLine()` + `ACTION_PREFIXES` — строки `OP_/TOGGLE/TTL_/EXT_PROBE/FLAG_/JIT_/ADB_/…` вне лог-бюджета.
- **§6 тесты.** +`StatusIconContractTest` (5), +`JitBootTest` (3), `TtlScriptTest` 8→10,
  `ExtProbeV2Test` 5, `BuildMarkerTest` 59/1.0.59. Итого **129**.

### CI

- `42497296670` (`4a64bc7`) — ❌ `compileDebugKotlin`: дубль `PROP_USEJIT_PROFILES` (вставка константы,
  которая уже была в файле). Исправлено в `60689c9`.
- `42498093676` (`60689c9`) — ❌ `compileDebugUnitTestKotlin`: `TtlScriptTest.kt:126` неэкранированный
  `$SCOPE` в сообщении ассерта → `Unresolved reference 'SCOPE'`. Исправлено в `056d14e`.
- **`42499097146` (`056d14e`) — success, 129 PASSED / 0 FAILED**, job `128787726449`,
  артефакт `13624524776` (177 142 576 Б), `BUILD SUCCESSFUL in 3m 18s`.

### Открыто

1. ⛔ Слоты в правом кластере строки состояния недостижимы через notification-API плагина
   (направление жёстко `LEFT`). Нужно решение владельца: принять левый кластер / SmartBar-плитка дока /
   правка APK плагина (сейчас запрещена).
2. 🟡 Проверка владельцем: `JIT_BOOT` на старте, три шага `EXT_PROBE_V2`, `TTL_ADD_COUNT n=1`,
   `TTL_OFF … result=OK`, CarPlay при ON.
- **`42500110715`** (`28fdac1`, handoff: отчёт + WORKLOG + `SOURCE_SHA=056d14e`) — **success,
  129 PASSED / 0 FAILED**, job `128790823798`, артефакт `13625751211` (177 142 520 Б),
  `BUILD SUCCESSFUL in 2m 53s`.
- **`42500938241`** (`1b69177`, финальная сдача) — **success, 129 PASSED / 0 FAILED**, job `128793320740`,
  артефакт `13626041033` (177 142 563 Б), `BUILD SUCCESSFUL in 4m 20s`.

---

## iter33n-fix1 — 1.0.59 (база `af913cd`, код `69729e9`, маркер `SOURCE_SHA=69729e9`)

### Сделано

- **§1 P0 — плагин 1.4.1.** `res/raw/geely_auto_systemui_plugin_digital.apk` заменён на сборку
  `CentralEXAuto e0bd36c9` (md5 `5aab306b…`, sha256 `fabc06a3…`, 18 284 258 Б) — проверено локально
  байт-в-байт. Константы `PLUGIN_APK_MD5/SIZE/SHA256` обновлены (`HvacDigitalPlugin.kt:31-33`),
  `OEM_PKG` стал `internal` (`:18`). `PLUGIN_DIAG` теперь печатает `md5=… expected_md5=… md5_match=…`
  для СМОНТИРОВАННОГО apk (`HvacFlagsApply.kt:533-558`). Старых значений md5/размера в коде — 0.
- **§2 P0 — JIT механизмом референса.** `setAutoOptimize` → `jitApplyWithZygote` (блок
  `JIT_ZYGOTE_BEGIN/END`, `AppPerformance.kt:981-1120`): setprop → detached watcher
  (`/data/local/tmp/jit_zygote_watch.sh`) → `setprop ctl.restart zygote` → ожидание нового pid
  zygote/system_server → `getprop` + восстановление wifi → итог в `/data/local/tmp/jit_zygote_result.txt`.
  Наше приложение к тому моменту убито рестартом, поэтому итог печатает `jitPendingVerify`
  (`:903-980`) на следующем старте: `JIT_APPLIED runtime=… want=… zygote_restart=… maps=… result=… via=pending_verify`
  (`maps` — по `/proc/self/maps` своего процесса). Намерение `jit_pending_verify` пишется ДО рестарта
  и не сжигается, если канал молчит (там же — `reconnectRoot`). UI-предупреждение — `ToolsCatalog.kt:31`.
  «Раунд оптимизации» при тумблере пропускается (`JIT_ROUND skipped reason=zygote_restart_armed`).
- **§3 P1 — TTL debounce.** `NetworkTtl.kt:162` `TTL_ON_DEBOUNCE_MS = 3_000L`; OFF не блокируется
  никогда (`:178`). Правила TTL не менялись.
- **§4 тесты.** +`PluginAssetTest` (5), +`TtlDebounceTest` (3), `JitBootTest` 3→4
  (`zygoteRestartOnlyInsideJitToggleBlock`), `JitPersistTest` 4→3 (абсолютный запрет zygote заменён
  проверкой call-sites), `MountResultTest` — новый размер. Итого **137**.

### CI

- `42508998476` (`0339d34`) — ❌ `compileDebugKotlin` `AppPerformance.kt:1034`: литеральный перевод
  строки попал в Kotlin-строку при генерации патча. Исправлено в `69729e9`.
- **`42510035315` (`69729e9`) — success, 137 PASSED / 0 FAILED**, job `128820887253`,
  артефакт `13628863500` (177 148 033 Б), `BUILD SUCCESSFUL in 4m 20s`.

### Открыто

1. 🟡 Поведение `ctl.restart zygote` на IHU629G проверяется только владельцем (первый раз за всё время).
2. 🟡 Стек «°C» над «%» даёт именно бандл 1.4.1 — нужен скрин после установки.
3. ⛔ Не делалось: правки системы/smali/`setenforce 0`/`pm install`, глобальное правило TTL,
   своя раскладка строки состояния, смена версии.
- **`42511098442`** (`74af9c8`, handoff: отчёт + WORKLOG + `SOURCE_SHA=69729e9`) — success,
  **137 PASSED / 0 FAILED**, job `128824133411`, артефакт `13629277892` (177 147 942 Б),
  `BUILD SUCCESSFUL in 4m 12s`.
- **`42512116433`** (`47e927b`, HEAD) — success, **137 PASSED / 0 FAILED**, job `128827221487`,
  артефакт `13630073416` (177 148 005 Б), `BUILD SUCCESSFUL in 4m 8s`.

---

## iter33o — 1.0.60 (база `739bc32`, код `a155e79`, маркер `SOURCE_SHA=a155e79`)

### Сделано

- **§1 P0 — AVAS по механизму референса.** Новый `tools/AvasRestore.kt`: `Car.createCar` →
  `getCarManager("audio")` → рефлексия `getAVASMode()`/`setAVASMode(Integer)` (как `w0/d0`, `w0/df`, `w0/qa`).
  Сохранение перед рестартом (`AVAS_SAVE mode=<int>`), восстановление после (`AVAS_RESTORE … result=ok|failed`),
  `skipped reason=already_ok|no_getAVASMode`. Только возврат сохранённого значения; звук/sound type/демон не трогаем.
  Точки вызова: тумблер JIT (`AppPerformance.kt:1170`), старт приложения (`:873`), boot-restore (§1.5).
  Плюс итог рестарта больше не «unknown»: `jitPendingVerify` ждёт вторую строку `JIT_ZYGOTE_DONE` (6 × 7.5 с).
- **§1.5 — авто-восстановление JIT при загрузке.** `AppPerformance.jitBootRestoreArm` (`:915-948`),
  вызов из `App.onCreate` (`App.kt:57-59`), guard `jit_boot_restore_done=<boot_tag>` ставится ДО рестарта,
  задержка 30 с, `skipped reason=pref_off|already_on|same_boot`, итог — `JIT_APPLIED … via=boot_restore`.
- **§2.1 — PLUGIN_DIAG.** Ранняя строка помечена `phase=pre_mount`; новая `md5Diag()`
  (`HvacDigitalPlugin.kt:193-212`) печатает `phase=post_mount` после `enable` и `bootInstall`.
- **§2.2 — TTL retry.** `TTL_UPLINK_RETRIES = 2`, пауза 3 с, повтор только при `reason=no_uplink`
  (`NetworkTtl.kt:158-163`, цикл `:214-232`); скрипт/правила не менялись.
- **§2.3 — текст JIT_BOOT.** `jit=off_after_reboot toggle_to_enable` вместо «применится после перезагрузки ГУ».
- **Версия 1.0.60** (`versionCode = 60`), `BuildMarkerTest` обновлён.
- **Тесты 137 → 147**: `AvasRestoreTest` (7), `JitBootTest` 4→6, `TtlDebounceTest` 3→4.

### CI

- `42669479902` (`949a31a`) — ❌ 4 FAILED (KDoc-литералы и поиск `TTL_ADD_COUNT` не в том файле).
- `42671578054` (`15f9bb5`) — ❌ **0 джобов**: partial outage GitHub Actions в 18:09–18:13 UTC
  (status.github.com «Minor/Partial System Outage»), код не при чём — повтор запустился.
- `42672009784` (`963a7fb`) — ❌ 1 FAILED (слово в KDoc считалось запрещённой операцией).
- **`42674945910` (`a155e79`) — success, 147 PASSED / 0 FAILED**, job `129353710310`,
  артефакт `13700830595` (177 146 377 Б), `BUILD SUCCESSFUL in 2m 58s`.

### Открыто

1. 🟡 AVAS: на ГУ не проверялось — первый прогон покажет `AVAS_SAVE/AVAS_RESTORE` (или честный skip).
2. 🟡 Авто-рестарт фреймворка раз за загрузку (§1.5) — новое поведение, проверяет владелец.
3. ⛔ Не делалось: мута AVAS, смена sound type, правки системы/smali, изменения TTL-правил,
   своя раскладка строки состояния.
- **`42676491595`** (`b03f999`, handoff: отчёт + WORKLOG + `SOURCE_SHA=a155e79`) — success,
  **147 PASSED / 0 FAILED**, job `129359129192`, артефакт `13700542836` (177 146 362 Б),
  `BUILD SUCCESSFUL in 3m 41s`.
- **`42678317590`** (`bdfdd96`, HEAD; только правки отчёта/WORKLOG) — success, **147 PASSED / 0 FAILED**,
  job `129365643214`, артефакт `13702710536` (177 146 364 Б).

---

## iter33p — 1.0.61 (база `c9c3e9f`, fix2)

### Сделано

- **§1 — авто-восстановление JIT при загрузке УДАЛЕНО** (решение владельца 09.11, как в референсе):
  вырезаны `jitBootRestoreArm`, `bootRestoreDecision`, `JIT_BOOT_RESTORE_*`, `route="boot_restore"`,
  `AvasRestore.Where.BOOT_RESTORE`, вызов из `App.kt`. `ctl.restart zygote` — один литерал
  (`AppPerformance.kt:1078`) в блоке `JIT_ZYGOTE`, один call-site `jitApplyWithZygote` (`:217`).
- **§2 — сеть вокруг zygote по референсу** (`tools/JitNet.kt`): сохранение `wifi/ap` до рестарта
  (`JIT_NET_PRE wifi=… ap=…`), скрипт `svc wifi disable` → drain `oif wlan0` ≤20 с → `setprop usejit`
  (`ip_rule_wait=… drained=…`), после подъёма — санация только `oif wlan0` (`JIT_NET_SANITIZE`),
  Wi-Fi до 3 попыток × 3 с, AP через `startSoftAp` (`JIT_NET_RESTORE`). `svc wifi enable` из
  watcher-скрипта убран (возврат — из приложения, с ретраями и read-back).
- **§3 — AVAS как в референсе**: `AvasRestore` переписан — режим + sound type по областям {0,1},
  восстановление по расписанию 5 с → 12 с → 30 с ×5, вердикт только по read-back, call-sites 2.
- **§4 — лог-файлы по датам** (`tools/LogFiles.kt`): `voiceapp3-DD.MM.YYYY.log` в
  `files/logs`, append в течение дня, retention 14 суток, хук в `AppLog.writeRaw`.
- **Тесты**: `AvasRestoreTest` переписан (9), `JitBootTest` (6, без boot-restore),
  новые `JitToggleChainTest` (5), `NetRestoreTest` (5), `LogFilesTest` (5), `BuildMarkerTest` → 61.

### Открыто

1. 🟡 sound type: id свойства в референсе нативный (`Politica.h(7)`) — при `reason=no_sound_prop`
   режим AVAS всё равно восстанавливается; нужен один прогон владельца с логом.
2. 🟡 AP-возврат зависит от наличия `startSoftAp` в прошивке (лог честный).

### CI

- `42827223054` (`a36a2b7`) — ❌ 160 PASSED / 5 FAILED (стражи: границы sound type, call-sites,
  порядок «сеть → watcher», ожидание `mode_now` в тесте).
- **`42828995241` (`8b6ee74`) — ✅ 164 PASSED / 0 FAILED**, job `129873731857`,
  артефакт `13771626397` (177 172 215 Б).
- **`42830831600`** (`3646901`, HEAD; docs + маркер) — ✅ 164 PASSED / 0 FAILED, job `129880075141`,
  артефакт `13771533798` (177 172 323 Б), `BUILD SUCCESSFUL in 4m 17s`.
- **`42832827628`** (`d33eaa5`, HEAD; только docs) — ✅ 164 PASSED / 0 FAILED, job `129887092945`,
  артефакт `13772668459` (177 172 241 Б).

## iter33p-fix1 (1.0.62) — закрытие 4 блокирующих замечаний REVIEW_iter33p

### Сделано

- **§1 сеть при отказах** (`AppPerformance.kt:915-937,973,984,1147-1166,1203-1209`, `JitNet.kt:75-120,275-330`):
  без подтверждённого сохранения состояние сети операция прерывается (`aborted reason=no_net_state`,
  `ToolsStep.Status.BLOCKED`) ДО `svc wifi disable`; отказ после отключения Wi-Fi компенсируется в той же
  операции (`JIT_NET_COMPENSATE`, zygote не перезапускается); возврат сети вызывается сразу после решения
  «намерение наше» и в ветке `STALE` — до чтения props и до ожидания `JIT_ZYGOTE_DONE`; сохранение
  снимается только по `allConfirmed`, иначе `keep_saved`; `request=`/`state=`/`link=` в логе разделены.
- **§2 точное удаление `ip rule`** (`JitNet.kt:199-258`): решение — `sanitizePlan` (удаление только при
  `oif == wlan0`, единственном `oif` и уникальном priority; иначе `SKIP_NOT_OIF_WLAN0|SKIP_MULTIPLE_OIF|
  SKIP_DUPLICATE_PRIORITY`), скрипт перечитывает `ip rule show` перед каждым `ip rule del <полный spec>`,
  `scope=oif_wlan0` в логе.
- **§3 доказанный id AVAS** (`scripts/derive_avas_sound_prop.py`, `AvasRestore.kt:48,51`): разбор
  `libexpolitica.so` + самопроверка на двух подряд идущих полевых id нашего кода
  (`h(0x15)=0x2140A377` = `AC_AMBIENT_TEMP`, `h(0x16)=0x2140A379` = `AC_INSIDE_TEMP`) → `h(7)=0x21407057`,
  области `{0,1}`.
- **§4 сверка по read-back + контрольные окна** (`AvasRestore.kt:121-153,290-360`): `plan` пишет только
  расхождения сохранёнными значениями; `verdict` — `ok|already_ok|unverified|failed`, нечитаемое = `unverified`;
  снимок снимается только при подтверждении И последнем окне; расписание 5 с → 12 с → 30 с ×5 на КАЖДЫЙ
  снимок (`id`), а не один раз за процесс.
- **🟡**: единый атомарный снимок `avas_snapshot` (temp+rename, `AvasRestore.kt:252-266`), «запрос ≠
  состояние» в `attemptLine`, `LogFiles.writable` + фоллбэк внутрь `filesDir/logs` (`LogFiles.kt:72-93,120-142`).
- **Тесты 164 → 174**: новые/переписанные `NetSanitizePlanTest` (7), `NetRestoreTest` (7),
  `AvasRestoreTest` (9), `LogFilesTest` (6, `unwritableDirIsDetected`), `BuildMarkerTest` → 62.

### Открыто

1. Полевой прогон JIT не выполнялся (условие ревьюера: только после повторного REVIEW и с логом).
2. Гарантии восстановления сети/AVAS нет — механизм + честные логи; см. §8 отчёта.

### CI

- `42837804148` (`8f4ceba`) — ❌ 172 PASSED / 2 FAILED (`BuildMarkerTest.versionIs61` ждал 61,
  `NetRestoreTest.abortWhenStateNotSaved` ловил слово `svc wifi disable` в комментарии) → исправлено.
- **`42840556489` (`8b24928`, HEAD; код iter33p-fix1) — ✅ 174 PASSED / 0 FAILED**,
  job `129914321552`, `BUILD SUCCESSFUL in 4m 16s`, артефакт `13775398418` (177 177 169 Б).

## iter33p-fix2 (1.0.61) — закрытие 5 блокирующих замечаний REVIEW_iter33p-fix1

Версия возвращена к 61 / `1.0.61` (владелец подтвердил: 1.0.61 не устанавливалась).

### Сделано

- **§1 поведенческие тесты сети** — логика вынесена в чистое ядро `tools/JitNetCore.kt`
  (`JitNetModel`, `JitFlow`, `JitNetCore`; ни одного `android.*`), `JitNet.kt` стал адаптером.
  Текстовые `NetRestoreTest.kt` и `AvasRestoreTest.kt` удалены; вместо них `JitNetCoreTest` (18)
  и `AvasCoreTest` (12) с фейковым окружением (префы/скрипты/Wi-Fi/часы) и сценариями гибели
  процесса между шагами.
- **§2 повтор сети** (`AppPerformance.kt:1127-1300`, `JitNetCore.kt:236-300`): ворота
  `JitFlow.gate` (нет состояния сети → `ABORT_NO_NET_STATE`, не подтвердилось намерение →
  `ABORT_INTENT_WRITE`, маркер снимается), подтверждённая запись намерения (`writeIntentConfirmed`),
  отдельное подтверждение запуска watcher-а по строке `JIT_ZYGOTE_ARMED` (`waitWatcherArmed`),
  `abortAfterNet` (гашение watcher → откат props по read-back → компенсация сети → снимок AVAS
  удаляется только если рестарт невозможен), восстановление сети на старте независимо от pending
  (`recoverNetOnStart` вызывается до чтения pending), маркер снимается только по `canClearMarker`.
- **§3 `ap0`** (`JitNetCore.kt:91-107`): read-only проба `P=/OS=/A=`; отсутствие интерфейса —
  штатное «AP выключен», `null` только при молчащем канале.
- **§4 AVAS** (`AvasCore.kt`, `AvasRestore.kt:41,89,128`): каждая попытка привязана к id своего
  снимка (`stale_task`), удаление только при `lastWindow` + «снимок мой» + подтверждение,
  монотонный `nextId()`, все обращения к Car API под одним `lock`, пустой sound-снимок → `UNVERIFIED`
  (не `already_ok`), нечитаемый режим → ничего не пишем и `UNVERIFIED`, снимок делается ПОСЛЕ ворот
  сети/намерения.
- **§5 скрипт AVAS** (`scripts/derive_avas_sound_prop.py`, `docs/agent/avas_identity.md`,
  `docs/agent/ref/**`): формат входа определяется по содержимому (обёртка / APK / отказ), печатаются
  md5+sha256 трёх уровней, `int[248]` восстанавливается мини-дизассемблером AArch64 из ветки `cmd=5`,
  проверяются dex-строки и 10 smali-улик, в конце `САМОПРОВЕРКА: OK`. Прогон на обёртке, на самом APK
  и корректный отказ на `assets/pip-overlay.apk`.
- **🟡 TTL** (`JitNetCore.kt:60-90`, `AvasRestore.kt:41`): обоснование 10 минут; сеть — при
  просрочке `wifi=1` Wi-Fi возвращается, AP не поднимается (`WIFI_ONLY`), при `wifi=0` — `DROP`;
  часы назад (`now < ts`) просрочкой не считаются.
- **🟡 «восстановлено» ≠ «интернет»** (`JitNetCore.kt:203-230`): `essentials`/`fully`/`link`/
  `sanitize_left` разделены, снятие маркера по `canClearMarker`, а не по `fullyRestored`.
- **Локальный прогон тестов без Android SDK**: `scripts/localjvm/` (заглушки + рефлексивный
  Runner) — 50 PASS / 0 FAIL на этом коде; инструкция в `scripts/localjvm/README.md`.

### Открыто

1. Полевой прогон JIT на ГУ не выполнялся — не рекомендован до зелёного CI и повторного ревью.
2. Совместимость `ip rule del <полный spec>` на целевой прошивке и приём AVAS-свойства
   подтверждаются только полем (в логах `priority=/spec=` и `getIntProperty`).

### CI

- Предыдущий зелёный: `42843273431` (`6335ed0`) — ✅ 174 PASSED / 0 FAILED, job `129923870857`,
  артефакт `13778445997`.
- По iter33p-fix2 — заполняется после push (без job-id слово «PASSED» не используется).
