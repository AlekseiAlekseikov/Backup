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
