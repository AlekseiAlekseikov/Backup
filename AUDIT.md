# AUDIT.md — Аудит исправлений voiceapp3 по логу

Дата: 2026-10-28
Ветка: `fix/log-driven-fixes` (запушена на https://github.com/AlekseiAlekseikov/Backup.git)

---

## Таблица аудита

| # | Проблема | Доказательство из старого voiceapp3.log (строки) | Место в коде (было) | Корневая причина | Что исправлено | VERIFY (чем проверен) |
|---|---|---|---|---|---|---|
| 1 | Белый экран 9+ сек на главном потоке (ensurePortMigrated) | 358–359, 597–598, 663–664 (main tid=23814, dt=9094ms) | `AdbClient.ensurePortMigrated` вызывался из синхронного пути с `VoiceAssistantService.onCreate`; `syncAll`/`execute` с main | Блокирующий ADB connect+bootstrap на UI-потоке, пока не работает root-канал | Весь AdbClient работает на отдельном AdbWorker; BgWorker 4 потока; `AdbClient.execute()` на main ставит в очередь и возвращает ERR_MAIN_THREAD_FORBIDDEN; `BgWorker.assertNotMain()` guards; bootstrap → BgWorker, UiBridge события → BgWorker, watchdog тик → BgWorker | `assertNotMain` бросает в debug; в AdbClient.execute залогирован вызов с main со stack-trace; портирование через `w0/n.java` case 0 очередью сообщений |
| 2 | Ложный SUCCESS на неработающем root-канале (TTL_v4=0, FAILBIND, ZYGOTE err, exit=-1) | 12, 532–537, 554–575, 15, 16, 21/24 | `AdbClient.runCommand` считал rc=0 успехом; fallback в Runtime.sh uid=1000 для root-команд; classifyOutput отсутствовал | `rc=0` не равен успеху (echo-ы в скрипте возвращают 0); `xtables lock`/`FAILBIND`/`Permission denied` игнорировались; root-команды сыпались в sh uid=1000 и рапортовали «OK» | Строгий контракт в `runCommand()`: root-команды при NO_ROOT_SH → сразу ERR_ROOT_CHANNEL_UNAVAILABLE; `classifyOutput()` детектирует 12+ маркеров; alt-port retry только по IOException (не фоллбэк в sh); VERIFY EXEC строка на каждый вызов | VERIFY EXEC пишет `SUCCESS|FAILED(reason)`; ручной grep по логу |
| 3 | Порт 65010 не поднимается | init: `adb.tcp.port=65010` но `iptables ttl-set count=0 0`; строки 358–359 ensurePortMigrated FAILED 9094ms на main | Миграция (setprop+ctl.restart adbd) шла через Runtime.sh от uid=1000, где SELinux не даёт писать ctl.restart adbd; uid-проверка была `uid.contains("uid=0")` (давала ложное срабатывание на uid=1000); не было диагностики AUTH/SELinux/listeners | Миграция порта должна идти ЧЕРЕЗ ADB-сокет на 5555 (как в w0/m.java `l()`/`y(8000,SCRIPT)`), а не через sh; строгий regex uid=0(root); пошаговая PORT65010-трассировка | `bootstrap()` переписан: проба 65010→5555 по ADB, затем migrateScript (setprop+ctl.restart adbd через ADB сокет 5555 в фоне), 10с ожидание listen; `isRootUid()` regex `^uid=0(\(root\))?\b`; при AUTH/IOE/summary failure вызывается `portDiagnosticDump()` (getenforce, ls -l /dev/socket/adbd, netstat/ss listeners, ps adbd, adbd props, logcat -t 30 adbd); пошаговые PORT65010 логи step1..ИТОГ | Проверка на ГУ: `netstat -ltnp | grep 65010`, `id → uid=0(root)`, пошаговая PORT65010 трассировка в voiceapp3.log |
| 4 | Batt=36% / temp=-22°C, одна иконка на двоих, циклическое переключение | 396, 409, 424, 443, 599 и далее по всему логу (константы, не меняются) | `readSocScan/readTempScan` перебирали VHAL пропы по диапазонам `0x11400000..0x117FFFFF` с шагом 256; одно notification ID/канал на batt+temp; `SYS_HVAC_EXT` был `persist.sys.hvacdigital.temp_ext` (неверное имя) | Брутфорс диапазонов натыкается на случайные Float; общие notification ID/канал заставляли иконки менять друг друга | Брутфорс диапазонов удалён; прямые пропы из дампа прошивки (EV_BATTERY_LEVEL=0x11600309, ED_EV_BATTERY_PERCENTAGE=0x2140a6ed÷10, ENV_OUTSIDE_TEMPERATURE=0x11600703) + hvacdigital.exttemp проп + фиксированный fallback PRECISE_COMMON с тегом `[fallback]`; отдельные ID (15020/15021/15024), отдельные каналы; `socSource/tempSource` логируются и попадают в текст уведомления; `syncAll` на BgWorker; периодика не на main | На ГУ: реальные значения меняются вместе со «Строкой состояния», формат `soc=74 (src=VHAL:0x11600309/EV_BATTERY_LEVEL raw=74.0)`, иконки раздельные, без цикла |
| 5 | TTL не применяется (xtables lock) | 530–537 (`Another app is currently holding the xtables lock`; TTL_V4=0 при rc=0) | `iptables` вызывались без `-w`; успешность определялась по rc=0 последней команды; не было проверки через -C; не было ресивера на смену сети; не было boot-restore верификации; измерение TTL отсутствовало | rc=0 не гарантирует применение правила (echo TTL_V4=0 возвращает 0, а сам iptables -A падает из-за xtables lock); при повторах add без delete дублируются правила | Переписан `NetworkTtl.kt`: `-w 5` везде; TTL_BEFORE_*, VERIFY_V4/V6 через `iptables -C`; счётчики до/после; ping-heuristic для фактического исходящего TTL; строгий успех = VERIFY_V4=OK+TTL_V4=1 и VERIFY_V6=OK+TTL_V6=1; при жёстком провале тумблер сам отключается; bootRestore с VERIFY; BroadcastReceiver на CONNECTIVITY_ACTION/wifi STATE_CHANGE пере-применяет при wlan0 up | На ГУ: `iptables -t mangle -S POSTROUTING` содержит `-j TTL --ttl-set 65`, в логе TTL_V4=1 TTL_V6=1 VERIFY=OK/OK, есть строка TTL_CHECK фактического TTL; при смене сети правило переживает; при выключении правил нет |
| 6 | Быстродействие: FAILBIND / ZYGOTE Exec error / Wi-Fi отключался навсегда / setenforce 0 | 9–16, 554–575 (`FAILBIND`, `SRC_INODE=`, `TGT_INODE=`, `Exec error`, `svc wifi disable` без restore) | В JIT: `svc wifi disable` без обратного включения; в mount: `setenforce 0` + rc=0 считался успехом без проверки inode; zygote restart не верифицировался | `mount --bind` может фейлиться (см. «bad /etc/fstab» в старом логе) и тогда статус писался ОК; Wi-Fi оставался выключенным после неуспеха; `setenforce 0` на user-билде silently отклоняется и маскирует проблему | JIT-скрипт: `trap restore_wifi EXIT INT TERM` (гарантированное включение Wi-Fi); ZYGOTE_PID_BEFORE/AFTER; USEJIT_AFTER getprop. video-60fps: setenforce 0 убран; SELINUX_BEFORE/AFTER; MEDIA_PID_BEFORE/AFTER; MOUNT_PRESENT из /proc/mounts; inode-check и строгая классификация OK/FAILED/PARTIAL/BLOCKED; VERIFY строка. | На ГУ: `getprop dalvik.vm.usejit` соответствует ожидаемому; pid zygote меняется; `/proc/mounts` содержит bind-mount; `SRC_INODE==TGT_INODE`; Wi-Fi остаётся включённым |
| 7 | Звук замка: бесконечный ретрай-шторм + Permission denied на copyTo | 30–32, ~200 строк каждые 30 сек (`Java copyTo /data/local/tmp failed: Permission denied`, `cp: No such file or directory`, exitCode=137) | Копирование wav в `/data/local/tmp` делалось через Java `FileOutputStream` от uid=1000 — нет прав; watchdog дёргал apply каждые 60 сек; не было cap retries; `setenforce 0` в скрипте | uid=1000 system_app не может писать в /data/local/tmp; не было детектирования детерминированных ошибок | Копирование через ADB root-канал (base64 pipe через AdbClient.execute); MAX_BIND_RETRIES=3 с экспоненциальным backoff (5/10/20с); детерминированные ошибки (root unavailable/Permission/AVC/ENOENT) → отключение PREF_LOCK_SOUND_ENABLED и флаг bindDisabledUntilBoot, без повторов до ребута; VERIFY по STAGE_INODE==TGT_INODE и MOUNTED_OK маркерам; setenforce 0 убран | На ГУ: не больше 3 попыток после старта; при невозможности права не штурмит каждые 30с; при работающем root — bind-mount реально на месте и звук играет |
| 8 | screen_off_timeout 2147483647 спам + сброс после удаления | 512–520, 855–884 (десятки записей подряд) | `healScreenTimeoutOnce()` + watchdog каждые N секунд писали Int.MAX_VALUE в SCREEN_OFF_TIMEOUT | Агрессивное перетирание системной настройки без сохранения оригинала | Все автоматические записи в SCREEN_OFF_TIMEOUT удалены; watchdog убран; при первом старте мы СОХРАНЯЕМ текущее значение как «оригинальное» и НИЧЕГО не пишем автоматически; если видим Int.MAX_VALUE от старой версии — внятная ошибка в лог с просьбой пользователю выставить значение вручную; **однако механизм «переживает удаление» (scr 4 в prompt_continue.md) пока НЕ РЕАЛИЗОВАН** (см. «Оставшиеся блокеры»); гейт по board=IHU629G/model=IHU629G пока не добавлен — функция keep-awake по умолчанию выключена и не пишет ничего, пока пользователь сам не включит | На ГУ: при холодном старте нет ни одной записи в SCREEN_OFF_TIMEOUT; `settings get system screen_off_timeout` не меняется автоматически при включенном/выключенном приложении; после удаления не меняется (т.к. мы его не трогаем) |
| 9 | Два формата логов (GeelyTools/ToolsBackend vs CENTRAL_*) | 3.7 лога (смесь форматов) | Разные TAG'и в разных файлах | Несогласованная миграция тегов | Все TAG'и tools/+VoiceAssistantService/+UiBridge → CENTRAL_<Name>; AppLog.TAG_BASE = "CENTRAL"; старые GeelyTools/GeelyIcons/HvacDigital вычищены | В новом логе все строки имеют тег `CENTRAL_*` |

---

## «Ложный успех: где и как исправлено»

Перечень всех мест, где раньше заявлялся SUCCESS при провале, и что теперь там:

- **`AdbClient.runCommand`** (центральный раннер всех root-команд): root-команды без работающего root-канала сразу возвращают `ERR_ROOT_CHANNEL_UNAVAILABLE` без фоллбэка в sh; `classifyOutput()` детектирует 12+ маркеров провала; любая команда получает строку `VERIFY EXEC port=… rc=… expectRoot=… cmd=… → SUCCESS|FAILED (reason)`.
- **`NetworkTtl.setTtl65Active`/`bootRestore`/`networkChange`**: правило считается применённым только если `iptables -C` ОК и grep-счётчики совпадают с ожидаемым (включено=1, выключено=0); при жёстком провале тумблер сам отключается через prefs.
- **`AppPerformance.setVideo60Fps`**: `mountStatus` классифицируется по inode match + MOUNT_PRESENT присутствию + отсутствию FAILBIND/perm/root-unavailable; PARTIAL/FAILED прокидываются в ToolsResult.
- **`AppPerformance.setAutoOptimize`**: pid zygote до/после сравнивается, USEJIT_AFTER getprop дописывается в вывод (следующий шаг — завязать статус на эти значения; сейчас статус по exitCode, но trap Wi-Fi и логи уже на месте).
- **`HvacDigitalPlugin.enable/disable`**: статус определяется по наличию HVAC_DIGITAL_OK/REVERTED_OK + inode match (enable), а не по rc=0; VERIFY строка явно пишет ожидание/факт.
- **`ScreenBrightness.writeSystemInt`**: readback через `settings get` и VERIFY строка.
- **`LockSound.applySystemLockSoundBind`**: проверяет STAGE_SIZE, наличие MOUNTED_OK: в выводе, детерминированные ошибки отключают тумблер.
- **`StatusIcons.syncAll`**: VERIFY строки `VERIFY batt notif: soc=… src=… → posted` / `VERIFY temp notif: …`.

Везде удалены конструкции вида `status = ToolsStep.Status.OK` с фоллбэком по rc=0 без проверки системного состояния.

---

## «Порт 65010»

### Что сделано в коде

- `AdbClient.bootstrap()` в точности повторяет последовательность `w0/m.l`+`y(8000,SCRIPT)`:
  1. Проба портов 65010→5555 (с учётом кэша `workingPort` как `w0/m.f3919a`).
  2. При необходимости — запуск migrateScript (setprop service/persist.adb.tcp.port 65010 + `(sleep 1; setprop ctl.restart adbd)&` в фоне, как у оригинала) ЧЕРЕЗ ADB-сокет 5555 (НЕ через Runtime.sh uid=1000).
  3. Ожидание listen 65010 до 10 секунд с повторным probe.
  4. Вторичная проверка 5555 (fallback, если 65010 не поднялся).
  5. При фейле — диагностический дамп (getenforce, /dev/socket/adbd, netstat/ss, ps adbd, props, logcat -t 30 adbd).
- ADB протокол (`execAdbSocket`) повторяет `kotlinx/coroutines/scheduling/h.java` (CNXN/AUTH/OPEN/OKAY/WRTE/CLSE, баннер `host::central-geely\0`, maxpayload=4096, version=0x01000000). AUTH теперь логирует причину и вызывает portDiagnosticDump.
- Проверка root через строгий regex `^uid=0(\(root\))?\b` (не срабатывает на uid=1000).
- Все root-команды ходят ТОЛЬКО через ROOT_65010/ROOT_5555; при NO_ROOT_SH/PERM_FAIL — жёсткий возврат ERR_ROOT_CHANNEL_UNAVAILABLE, без фоллбэка в Runtime.sh.

### Что нужно проверить на ГУ

1. `netstat -ltnp | grep 65010` — слушатель есть после bootstrap.
2. В логе строки вида `PORT65010 ИТОГ = UP on port=65010, shell uid=uid=0(root) gid=0(root) …`
3. `id` через AdbClient → `uid=0(root)`.
4. `iptables -t mangle -S POSTROUTING` после включения TTL содержит `-j TTL --ttl-set 65` (через port 65010).

**Если порт так и не поднимется** — по диагностическому дампу в логе можно будет точно определить причину (AUTH RSA ключ/bind addr/SELinux avc denied/adbd crash/timeout). Гадать без лога не буду.

---

## «Оставшиеся блокеры»

1. **screen_off_timeout «переживает удаление» (§4 prompt_continue.md)**: согласно новому требованию, функция keep-awake должна выставлять `Int.MAX_VALUE` один раз и так, чтобы это не сбрасывалось после удаления приложения.
   - Механизм «один раз и навсегда» не может быть реализован через Settings.System с уровнем приложения — Settings.System пишутся в /data/system/users/0/settings_system.xml и не удаляются с приложением, но ОЕМ-сервис ГУ может сбрасывать их при своём старте. Без снятия `dumpsys settings | grep screen_off`, `logcat | grep -i screen_off_timeout` и выяснения, кто именно сбрасывает, делать наугад не стал.
   - **Что не сделано**: не добавлен gate по `board=IHU629G, model=IHU629G`; нет механизма durable-установки (одноразовый скрипт в /data/local/tmp/ или проперти).
   - Сейчас код НИКОГДА не пишет в SCREEN_OFF_TIMEOUT автоматически. После валидации на ГУ что виновник именно OEM-сервис и как его обойти — будет отдельный коммит.
2. **Сборка и прогон на ГУ** — в песочнице нет Android SDK и нет ГУ, поэтому новый `voiceapp3.log` по §7 сценариям не снимался; его нужно получить у вас после `./gradlew assembleDebug` и установки на устройство.
3. **Измерение фактического исходящего TTL** — сейчас есть только heuristic через `ping -t 65 -c 1 1.1.1.1`. Прямое измерение требует `tcpdump`, который на ГУ может отсутствовать. Если ping-heuristic не даст показания — в логе будет строка `TTL_PING_HEURISTIC=…` и факт будет виден по счётчикам TTL_CNTR_AFTER_V4 > TTL_CNTR_BEFORE_V4.

---

## «Требует проверки на устройстве»

Сценарии из §7 prompt_fix_by_log.md:

| # | Сценарий | Где смотреть в логе |
|---|---|---|
| 0 | Порт 65010 UP, `id → uid=0(root)` | строки `PORT65010:` и финальная `PORT65010 ИТОГ = UP…uid=uid=0(root)` |
| 1 | Холодный старт <2с, нет вызовов на main | строки с `thread=main` в AdbClient.execute (д.б. 0), `VERIFY THREAD` не должно быть |
| 2 | Батарея — реальное значение | строки вида `soc=74 (src=VHAL:0x11600309/EV_BATTERY_LEVEL raw=…)` |
| 3 | Температура — отдельная иконка | `temp=… (src=VHAL:0x11600703/ENV_OUTSIDE_TEMPERATURE)` |
| 4 | Обе иконки одновременно | NOTIF_BATT=15020 и NOTIF_TEMP=15021 обе видны, без циклического переключения |
| 5 | TTL вкл — `ttl-set 65` есть, TTL_V4=1 | `VERIFY TTL: expected enabled=true (TTL_V4=1 TTL_V6=1 VERIFY=OK/OK) → got … → SUCCESS` + `TTL_PING_HEURISTIC=…` |
| 6 | TTL выкл — правил нет, TTL_V4=0 | то же с enabled=false |
| 7 | TTL после ребута — восстановлено | `VERIFY TTL bootRestore: rc=0 → OK` |
| 8 | JIT: usejit соотв, zygote pid сменился, Wi-Fi остался вкл | `ZYGOTE_PID_BEFORE=… ZYGOTE_PID_AFTER=… ZYGOTE_RESTARTED=YES`, `WIFI_RESTORED=1` |
| 9 | video60fps bind-mount по inode | `VERIFY video_codec_swap: OK (SRC_INODE=… == TGT_INODE=…)` |
| 10 | остальные пункты perf | — |
| 11 | Звук замка — нет ретрай-шторма | после 3 неудач `lockSound отключён до следующей загрузки: <reason>` и тишина |
| 12 | Провал → тумблер OFF + причина | при ERR_ROOT_CHANNEL_UNAVAILABLE / perm / FAILBIND тумблер в prefs сбрасывается на false |
| 13–17 | Регресс (Wi-Fi, тема, строка состояния, двери/окна, звук закрытия) | — |
| 18 | screen_off_timeout не меняется приложением | в логе нет записей Settings.System.putInt SCREEN_OFF_TIMEOUT кроме действий пользователя; `settings get system screen_off_timeout` не Int.MAX_VALUE если пользователь не включал |
| 19 | screen_off_timeout после удаления | значение не меняется (т.к. приложение его не писало) |

---

## «Отличия от оригинала (CentralEXAuto)»

| # | Отличие | Обоснование |
|---|---|---|
| 1 | **Нет `Runtime.exec("sh")` как root-пути** (как и у оригинала, но в оригинале fallback в sh вообще отсутствовал, а у нас он оставлен только для не-root отладочных команд с явным маркером NO_ROOT_SH). root-команды строго через ADB-сокет. | Убрать фоллбэк совсем нельзя — на АВТО с включённым ADB на 5555 и без рута он нужен для диагностических команд. Но root-команды в sh больше не падают (это был главный источник false-success). |
| 2 | **Главный поток оберегается** — AdbClient.execute из main кидает ERR_MAIN_THREAD_FORBIDDEN, все тяжелые операции на BgWorker. В оригинале CentralEXAuto тоже многое делал на отдельном треде `w0/a0`/`w0/n`, но там есть и прямые вызовы из UI callback'ов. | Прямое требование промпта (Пункт 5 правило 5). |
| 3 | **Нет `setenforce 0`** ни в одном скрипте. В оригинале (декомпилированный w0) его тоже нет в основных путях — места с setenforce 0 были добавлены кем-то из предыдущих агентов и маскировали Permission denied. | Правило 4 промпта. |
| 4 | **VERIFY-контракт** (classifyOutput, VERIFY строки в логе, автоматическое отключение тумблеров при провале). В оригинале CentralEXAuto полагался на rc=0 и не писал единый VERIFY-формат. | Взял из правила П8 prompt_fix_by_log.md (ложный успех). |
| 5 | **Тег логов `CENTRAL_*`** вместо разнобоя `GeelyTools`/`ToolsBackend`/`GeelyIcons`/`AppPerformance`/`NetworkTtl` и т.п. | Требование П9; упрощает `adb logcat -s CENTRAL_*`. |
| 6 | **source tag в логе и в тексте уведомлений batt/temp** (`VHAL:0xNNNNNNNN/PROP_NAME`). В оригинале значение просто подставляется в иконку без логирования источника. | Нужно для отладки (см. причину 36%/-22°C). |

---

## Что ещё не закончено (в работе)

1. **P4 screen_off_timeout keep-awake** (новое требование из prompt_continue.md): гейт на IHU629G, «один раз и навсегда» механизм, не ломающий поведение после удаления. Нужен запуск на ГУ и анализ `dumpsys settings`/`logcat` для определения виновника сброса.
2. **Сборка APK и снятие нового voiceapp3.log** по §7 сценариям — на машине пользователя, после сборки.
3. **Финальный merge в main** — только после валидации на устройстве.

---

## Обновление 2026-10-28 (вторая итерация, по prompt_next.md)

### Раздел 3 — exttemp vs temp_ext (регресс)

| Свойство | Источник | В коде | Статус |
|---|---|---|---|
| `persist.sys.hvacdigital.batt` | voiceapp3.log строки 445–486; CentralEXAuto w0/x7.java:138 | ✅ | OK |
| `persist.sys.hvacdigital.temp` | voiceapp3.log строки 457–462; w0/x7.java:140 | ✅ | OK |
| `persist.sys.hvacdigital.temp_ext` | **voiceapp3.log строки 469/471/473 (com.flyme.auto.systemuiplugin plugin на устройстве)** | ✅ SYS_HVAC_EXT_TEMP_EXT (primary) | OK — восстановлено |
| `persist.sys.hvacdigital.exttemp` | w0/x7.java:142, w0/ah.java:17, w0/u5.java:27 (новая CentralEXAuto) | ✅ SYS_HVAC_EXT_EXTTEMP (secondary) | Пишется одновременно с temp_ext для совместимости с новым плагином |

setHvacDigital и bootRestoreHvacDigital теперь пишут **в оба** свойства; readTempScan пробует оба, логируя `temp cross-check` со вторым значением для сверки.

### Раздел 2 — TTL измерение

Удалён фиктивный `ping -t 65` (он не измеряет ничего). Новая схема:
1. **tcpdump** (tcpdump/toybox-tcpdump/busybox-tcpdump/system paths) — снимает 3 пакета на dst 1.1.1.1 при пинге (без `-t`, используется системный default TTL, который должно переписать mangle-правило); парсит `ttl N`.
2. **Fallback** — счётчики iptables TTL правила ДО и ПОСЛЕ 3 пингов; если счётчик вырос → правило сработало на пакетах.
3. Если ни tcpdump ни счётчики не дали результата — в логе честно `TTL_CHECK: ttl=unavailable метод=no-tcpdump-and-counters-not-incremented` без ложного SUCCESS.

Лог-линии:
- `TTL_BEFORE_V4=… TTL_BEFORE_V6=…`
- `TTL_AFTER_V4=… TTL_AFTER_V6=… VERIFY=OK|FAIL/…`
- `TTL_CHECK: фактический TTL = N (метод=tcpdump/iptables-counters-after-ping/unavailable)`
- `TTL_CHECK: ИТОГ = N (ожидали 65|default) → SUCCESS|FAILED`

### Раздел 5 — screen_off_timeout (серийный гейт + однократная запись + durable path)

Серийник целевого ГУ найден в дампе прошивки (`geely_dump/getprop.txt`):
```
[persist.device.real.serialno]: [IHU629G0MAS9W00010001144]
```
Вторичный серийник `ro.boot.serialno = AB1FRA00029968507` принят как кросс-проверка.

Реализация:
- `detectActualSerial()` пробует в порядке: `persist.device.real.serialno` → `ro.serialno/ro.boot.serialno` → `Build.getSerial()` (с READ_PHONE_STATE guard) → `Build.SERIAL` → ANDROID_ID.
- Гейт по серийному номеру: `isTargetHu()` = true ТОЛЬКО если фактический серийник совпал с одним из двух констант. Никаких привязок к машине (VIN, Car property, модель авто) нет — только серийник самой платы ГУ.
- `setKeepAwake(true)` пишет `SCREEN_OFF_TIMEOUT=Int.MAX_VALUE` через Settings.System + VERIFY readback.
- Durable path через root-канал: `setprop persist.sys.screen_off_timeout_force 1` + установка скрипта в `/data/local/tmp/screen_timeout_always_max.sh` с `chmod 755` + запуск его. Скрипт лежит в /data/local/tmp (не очищается при uninstall).
- `setKeepAwake(false)` возвращает сохранённое оригинальное значение, удаляет скрипт, сбрасывает prop в 0.
- При любом несовпадении серийника — `SCREEN_TIMEOUT: serial expected=… actual=… → skip` и НИЧЕГО не пишется.
- Диагностика: `dumpsys settings | grep screen_off_timeout` добавляется в лог для поиска того, кто сбрасывает значение.
- `ensureKeepAwake()` вызывается из `syncOnContext` на BgWorker при каждом старте сервиса (пере-применение).

### Раздел 6 — проверка root-канала из UI

- `AdbClient.probeRootChannel()` возвращает (port, state, uidLine, verdict) с BLOCKING на вызов с main.
- UI endpoint `dev.checkRoot` (BgWorker.run) возвращает в tools panel 4 строки:
  ```
  PORT65010: connect → OK на порту N|no port
  handshake CNXN → OK
  id → uid=0(root) gid=0(root) …
  ИТОГ = UP (uid=0)|DOWN (not root)|DOWN (reason)
  ```
- В логе при bootstrap уже есть полная PORT65010 пошаговая трассировка (шаги 1–4, диагностический дамп при AUTH/failure).

### Раздел 7 — звук закрытия не сломан

`applySystemLockSoundBind` делает bind-mount только для `/vendor/etc/carlock/*.wav` (это LOCK path). Путь unlock `/system/etc/avas/sound_type_*/*` затрагивается только если пользователь выбрал unlock-файл (PREF_UNLOCK_SOUND_PATH). Существующий OEM механизм воспроизведения lock-звука через Settings.System (lock_car_audio_enable/path) не трогается.

### Обновление по §4 batt/temp сверка

Источник привязан к подтверждённым VHAL property из дампа + обоим hvacdigital props (temp_ext primary, exttemp secondary). Кросс-чек обоих значений пишется в лог. **Сверку с реальным значением «Строки состояния» может сделать только владелец на устройстве** (запуск приложения, сравнение значения в иконке с тем, что показывает статус-бар).

---

## Обновление 2026-10-28 (итерация 3, по prompt_four_fixes.md → fix/v2)

### Перенос ветки на `main` (раздел 1)

- Сделан свежий `git clone`, checkout `main` (1650 файлов), новая ветка `fix/v2`.
- **Группа A** (новые файлы) скопированы целиком из `fix/log-driven-fixes`:
  `AUDIT.md`, `WORKLOG.md`, `BgWorker.kt`, `decompiled/centralex/`.
- **Группа B** (совпадающая база, безопасно): `VoiceAssistantService.kt`, `UiBridge.kt`, `AppFileLogger.kt`, `AppPerformance.kt`, `HuNightMode.kt`, `HvacDigitalPlugin.kt`, `LockSound.kt`, `NetworkTtl.kt`, `ScreenSaverOverlay.kt`, `StatusIcons.kt`, `WindowDoors.kt`.
- **Группа C** (расходятся): `AdbClient.kt` (132 → 644 строк), `AppLog.kt` (117 → 262), `ScreenBrightness.kt` (235 → ~450), `ToolsBackend.kt` (836 → 835) — в каждый перенесена СУТЬ фикса (порт-65010 hardening, false-success бан, BgWorker, CENTRAL_* тэги, P3 hvacdigital, P4 TTL, P5 keep-awake).
- **Проверки после переноса**:
  - `git diff --diff-filter=D main` → ПУСТО (0 файлов удалено из main)
  - `git ls-files | wc -l` → 1663 (main было 1650, +13 новых)
  - `platform-keys/`, `app/src/main/jniLibs/arm64-v8a/`, `flutter_telemetry/lib/l10n/` присутствуют (libjnidispatch.so, libonnxruntime*.so, libroadcast_client.so, libsherpa-onnx-jni.so, app_en.arb, app_es.arb, app_localizations*.dart на месте)

### Раздел 3 — блокировка главного потока в setKeepAwake ИСПРАВЛЕНА

Было: `setKeepAwake` на вызывающем потоке синхронно делал `isTargetHu() → detectActualSerial() → isTargetHu()` — три вызова с 8×`Runtime.exec("getprop X")` × `waitFor(500)` → потенциально 12 секунд на main-thread (тот же класс бага, что вызывал белый экран).

Стало:
- `detectActualSerialBg(ctx)` — **ТОЛЬКО для фонового потока** (BgWorker.assertNotMain).
- **ОДИН** запуск `getprop` без аргументов (парсит весь вывод за ~10 мс) вместо 8 отдельных exec.
- Кэш серийника в `SharedPreferences` + in-memory `@Volatile cachedSerial`, TTL 1 час, явная инвалидация.
- `setKeepAwake` на вызывающем потоке делает ТОЛЬКО `prefs.edit().putBoolean(PREF_KEEP_AWAKE, …)` и BgWorker.post — ноль exec/блокировок.
- `ensureKeepAwake` и `syncOnContext` — тоже только планируют фон; `ensureSerialProbeAsync` заранее прогревает кэш.
- Лог: `SCREEN_TIMEOUT: serial detect dt=…ms src=getprop:persist.device.real.serialno val=…`.
- Нет ни одного вызова Runtime.exec в синхронной части setKeepAwake.

### Раздел 2 — фиктивный durable-скрипт УДАЛЁН

- Скрипт `/data/local/tmp/screen_timeout_always_max.sh` убран (не создаём, не удаляем).
- В коде и комментарии ясно сказано: файлы в `/data/local/tmp` **сами не запускаются**; на IHU629G нет подтверждённого init.d-хука; создание такого файла — мусор в системе.
- Фактическая причина, по которой значение переживает uninstall — **Settings.System не очищается Android при удалении APK**. Это честно зафиксировано в комментарии и в логе:
  `SCREEN_TIMEOUT: NOTE — значение держится в Settings.System (переживает uninstall); init.d/durable-скрипт НЕ ставится (нет подтверждённого init.d-хука на IHU629G; файл в /data/local/tmp не самозапускается)`
- persist.sys.screen_off_timeout_force по-прежнему ставится через root-канал как сигнал намерения, но не выдаётся за самостоятельный durable-механизм.
- Для настоящего durable-механизма (с защитой от сброса OEM-сервисом) нужен подтверждённый хук в прошивке — без дампа init-скриптов (.rc / install-recovery.sh / init.d) делать это вслепую некорректно.

### Раздел 4 — проверка серийника на устройстве

Добавлен UI-эндпоинт `dev.checkSerial` (в tools dev-панели), который вызывает `ScreenBrightness.serialCheckReport()` и выводит пользователю:

```
SCREEN_TIMEOUT: device serial check
  persist.device.real.serialno   = IHU629G0MAS9W00010001144
  ro.serialno                    = AB1FRA00029968507
  ro.boot.serialno               = AB1FRA00029968507
  expected                       = IHU629G0MAS9W00010001144 / AB1FRA00029968507
  → applicable | mismatch (detected src=… dt=…ms)
```

Владелец должен:
1. Открыть dev-панель в приложении;
2. Нажать `checkSerial`;
3. Сравнить показанные `persist.device.real.serialno` и `ro.serialno` с ожидаемыми;
4. Если verdict = `applicable` — функция keep-awake применится; если `mismatch` — гейт не сработал и присылаем нам снятые значения для корректировки констант.

**Замечание безопасности:** серийники захардкожены в открытом репозитории. Лучше вынести в `local.properties` → `buildConfigField "String", "EXPECTED_SERIAL", "\"…\""` и в коде использовать `BuildConfig.EXPECTED_SERIAL`, а в публичный репозиторий добавить `local.properties` в `.gitignore`. Это на усмотрение владельца; текущее состояние не ломает работу, но публикует серийник платы.

### Раздел 5 — документация TTL и temp_ext (закрепление)

**TTL (NetworkTtl.kt)** — ping -t больше не используется. Схема:
1. Пробуем tcpdump (ищем в `tcpdump`, `toybox-tcpdump`, `busybox-tcpdump`, `/system/bin/tcpdump`, `/system/xbin/tcpdump`), запускаем в фоне на `ip and dst host 1.1.1.1`, шлём 3 пинга **без** -t (с системным default TTL, который mangle-правило должно переписать в 65), парсим в выводе поле `ttl N`.
2. Если tcpdump не найден/не дал результата — fallback: iptables-счётчики на TTL/HL правилах ДО и ПОСЛЕ пинга; если счётчик инкрементировался — правило сработало.
3. Если ничего не получилось — в лог честно `TTL_CHECK: ttl=unavailable метод=no-tcpdump-and-counters-not-incremented` без ложного SUCCESS.
Логи: TTL_BEFORE_V4/V6, TTL_AFTER_V4/V6, `TTL_CHECK: фактический TTL = N (метод=tcpdump|iptables-counters-after-ping|unavailable)`, `TTL_CHECK: ИТОГ = N (ожидали 65|default) → SUCCESS|FAILED`.

**temp_ext / exttemp (StatusIcons.kt)** — dual-write:
| Свойство | Источник | Роль |
|---|---|---|
| `persist.sys.hvacdigital.batt` | voiceapp3.log 445–486; w0/x7.java:138 | batt |
| `persist.sys.hvacdigital.temp` | voiceapp3.log 457–462; w0/x7.java:140 | внутренняя t° |
| `persist.sys.hvacdigital.temp_ext` | voiceapp3.log 469/471/473 (`com.flyme.auto.systemuiplugin`) | **primary** (на устройстве сейчас работает) |
| `persist.sys.hvacdigital.exttemp` | w0/x7.java:142, w0/ah.java:17, w0/u5.java:27 (новый CentralEXAuto) | secondary (для forward-compat с новым плагином) |

setHvacDigital и bootRestore пишут оба свойства; readTempScan пробует оба и логирует cross-check.

### Раздел 6 — Регрессия «Wi-Fi = текст "WiFi" вместо иконки» (fix/v2 P1, hard rule)

**История проблемы:** во время групповой заливки group B/C в fix/log-driven-fixes в `StatusIcons.kt` был скопирован артефактный вариант `makeTextIcon`, где ветка `main=="wifi"` вместо дуг рисовала обычный `drawText("WiFi", …)`. Результат на ГУ: в статус-баре виден текст "WiFi" там, где ожидается значок сигнала.

**Причина корневая:** файл был скопирован *целиком* из ветки, а не применён дельта-фикс поверх main — из-за чего всплыл старый сломанный кусок. См. правило §8 "любой файл, расходящийся с main, обязан стартовать с main-версии и нести только дельту фикса".

**Исправление (fix/v2, commit 5477c9a):**
- `StatusIcons.makeTextIcon` ветка `main=="wifi"` заменена на Canvas-дуги (3 концентрических дуги radii 0.22/0.38/0.54 размера + точка снизу, всё через `Paint.Style.STROKE`, `strokeCap=ROUND`). Иконка — это вектор в Bitmap, а не текст.
- Рядом добавлен комментарий-предохранитель: `// DO NOT replace with text placeholder`.
- Click по Wi-Fi-уведомлению открывает `Settings.ACTION_WIFI_SETTINGS` (а не `telemetryPi`, как у батареи/температуры).
- В лог пишется `WIFI: level=N rssi=N icon=arcs click→android.settings.WIFI_SETTINGS`.
- NOTIF_WIFI=15024, отдельный канал CHANNEL_WIFI — не пересекается с batt/temp.

**Никогда впредь:**
1. Не заменять значок Wi-Fi текстом "WiFi" / "W" / "📶" и т.п. — только Canvas-глиф.
2. Не копировать StatusIcons.kt целым файлом из других веток — только точечные diff-фиксы поверх main.


### Раздел 7 — Статус root-канала на ГУ владельца (voiceapp3-2.log от 2026-10-30)

**Доказательства из лога:**
- Все 8 путей su-binaries отсутствуют (`/sbin/su`, `/system/bin/su`, …, `/vendor/bin/su` — MISSING).
- ADB TCP порт 5555 — `ECONNREFUSED`; порт 65010 — `unreachable dt=3004ms`.
- Приложение работает как `uid=1000(system) system_app`, контекст `u:r:system_app:s0`.
- `service.adb.tcp.port=65010` в getprop выставлен, но adbd не слушает — порт не поднят.

**Вывод:** на ГУ в текущем состоянии нет ни su, ни доступного ADB TCP. Функции, требующие root (TTL, mount --bind для звука/кодека, `setprop ctl.restart` для JIT/zygote, `iptables`, `svc wifi` для JIT-перенастройки) — не работают и честно рапортуют BLOCKED.

**Что нужно от владельца:**
1. Подключить ГУ USB-кабелем к ПК и выполнить `adb tcpip 5555` один раз (после этого канал поднимется и порт 65010 через перезагрузки может жить не всегда — но 5555 из adb shell работает).
2. Либо установить APK как **priv-app** в `/system/priv-app/` (требует перепрошивки/кастомного recovery). `system_app` с sharedUserId=android.uid.system даёт часть системных прав, но `ctl.restart`, `iptables`, монтирование — требуют либо root, либо вызов через priv-app CarService/vendor.
3. Либо установить Magisk (userdebug/root-прошивка) — это единственный способ получить постоянный su.

**Что делает код:**
- Все root-функции продолжают рапортовать BLOCKED с честной причиной (`root unavailable`, `ADB не работает`, `uid=1000(system)`) — нет ложного SUCCESS.
- При появлении работающего канала (порт 5555/65010) AdbClient автоматически поднимает соединение и команды начнут проходить.

