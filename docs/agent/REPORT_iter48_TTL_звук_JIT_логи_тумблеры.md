# iter48 — TTL (владение), звук (монтирования), настройки (ревизии), JIT (zygote), логи

**Дата:** 2026-10-22 · **Ветка:** `fix/v2` · **Версия:** `versionCode=63`, `versionName=1.0.63` (НЕ повышалась)
**История:** `6a944c0` (handoff iter47) → `2791b97` (§Б) → `ed80ec2` (§В–§Е) → этот коммит (отчёт + `SOURCE_SHA` + `WORKLOG`)
**SOURCE_SHA:** `ed80ec2` — ровно тот код, который собирается в APK (`git rev-parse` в сборке не используется)
**Тесты (JVM):** `bash runtests.sh` → **`OK (470 tests)`** (было 446 в iter47, +24 в `Iter48Test`)
**Реальное ГУ:** НИЧЕГО из этого на машине не проверено. Всё ниже — JVM-тесты, bash-эмуляторы скриптов и чтение кода. Проверка на ГУ — только владелец (§ безопасный план в конце).

---

## Сводка по разделам мандата

| § | Тема | Статус | Что именно |
|---|------|--------|------------|
| А | Правила работы | ✅ | ветка `fix/v2`, без `main`/`--force`/reset; версия не менялась; тесты отдельно от сборки |
| Б | TTL: владение и поводы | ✅ (JVM + bash-эмулятор) / ❌ (ГУ) | своя цепочка `VAPP_TTL`; чужое не удаляется; поводы не теряются |
| В | Звук: монтирования | ✅ (JVM + bash-эмулятор) / ❌ (ГУ, слышимость) | владение слоем по `mountinfo`; `umount` без `\|\| true`; реестр до сходимости |
| Г | Настройки: ревизии | 🟡 | межпроцессный lock + конфликт ≠ confirmed + `unknown` до UI; UI-разметку `*State` ещё никто не читает |
| Д | JIT: zygote | 🟡 | чтение `/proc` реализовано, допуск `mark-60s` убран; признак всё равно НЕ доказывает нашу команду |
| Е | Логи | ✅ (JVM) / 🟡 (межпроцессность `LogFiles`) | `.1` больше не удаляется перед rename; spill при занятом локе; `LogFiles` уже был под файл-локом |
| Ж/З | Отчёт | ✅ | этот файл, `WORKLOG.md`, `SOURCE_SHA` |

Обозначения: ✅ сделано и проверено тем, что доступно; 🟡 сделано частично / остались ограничения, названные прямо; ❌ не проверено (нет доступа к ГУ); ⛔ сознательно не делалось.

---

## §Б — TTL: удаляем только своё, поводы не теряются

### Б.1 Что было сломано (по коду `6a944c0`)

1. `TtlScript.del_all()` перебирал **все** правила `mangle/POSTROUTING` с `ttl-set`/`hl-set` и удалял их; `sanitize()` вызывался и при включении. Принадлежность не проверялась: чужое правило снималось так же, как своё.
2. «Своё» определялось по значению `65` и совпадению интерфейса — это не доказательство владения.
3. `TtlRunner.execute()` пропускал проход по условию `curGen == lastAppliedGen && lastAppliedOk && !networkDirty`: фоновый повод, пришедший **после** успешного применения того же поколения, до транспорта не доходил.
4. Граница «последняя проверка → `unlock()`»: поток-повод видел `runLock.isLocked == true`, ставил `networkDirty = true`, а владелец цикла в этот момент уже вышел из `while` и ничего не перепроверял → повод терялся молча.
5. Исчерпание `maxPasses` просто отбрасывало непроверенное последнее намерение.
6. `AdbClient.onRootRestored` — одиночный колбэк, **никем не назначенный**: события «root готов» в production для TTL не существовало (тест его имитировал).

### Б.2 Что сделано

**Владение — по имени цепочки, а не по значению** (`TtlScript.kt:46`, `TtlScript.kt:113`)

```
v4: mangle/VAPP_TTL   →  -j TTL --ttl-set 65        (одно правило внутри)
    POSTROUTING       →  -o <uplink> -j VAPP_TTL    (ровно один переход)
v6: mangle/VAPP_TTL   →  -j HL  --hl-set 65
```
* `drop_own()` снимает **только** переходы на `VAPP_TTL` и саму цепочку; правило вне цепочки — чужое или историческое (legacy) и **не снимается** (`TtlScript.kt:113–134`).
* Чужое/legacy только диагностируется: `TTL_FOREIGN v4|v6 spec="…"`, `TTL_LEGACY … action=keep reason=ownership_unproven` (`TtlScript.kt:102–111`).
* Проверка исходного и финального набора: `count_own_rules/count_own_jumps` до и после, `TTL_OWN removed=ok|partial jumps_left=… rules_left=…` (`TtlScript.kt:131–134`).
* `-m comment` как метка **не используется**: не гарантирован на всех ядрах.

**Найденный по ходу дефект счёта чужого (исправлен):** `FOREIGN = ALL − OWN` давал `0` при реально существующем чужом правиле (своё правило живёт внутри цепочки, а «все» считались по POSTROUTING). Из-за этого OFF объявлял `result=OK`, хотя чужие правила оставались. Теперь `FOREIGN_V4/V6 = ALL_V4/V6` (правило с `ttl-set` в POSTROUTING по определению не наше) — `TtlScript.kt:182–190`.

**Поводы** (`TtlRunner.kt`)
* Счётчик `pending` вместо флага: цикл снимает счётчик целиком и **обязан** выполнить проход по каждому поводу (`TtlRunner.kt:246`).
* Проверка «мы уже применяли это поколение» **удалена**: вход в цикл всегда означает проверку текущего состояния (`TtlRunner.kt:215–246`).
* Граница `unlock()` закрыта: после освобождения lock владелец перечитывает `pending` (`drainPending`, `TtlRunner.kt:186–212`).
* Бюджет не теряет работу: остаток исполняется продолжением (до жёсткого предела `maxPasses×4`), при достижении предела это пишется как `TTL_PASS stop reason=budget`, «применено» не заявляется (`TtlRunner.kt:284–300`).
* Повторный вход того же потока запрещён (`TTL_REENTRANT … action=mark_dirty`): иначе транспорт, вызывающий раннер изнутри прохода, вкладывался в себя (в старом коде это давало `StackOverflowError`).
* `applied` = подтверждённый транспорт **И** неизменившееся поколение **И** сохранённое намерение (`TtlRunner.kt:262–280`). Нет uplink/root → `pending`, намерение ON сохраняется.

**Реальные события** вместо таймеров
* `AdbClient`: подписка на переходы состояния канала `addRootStateListener` — вызывается только на **смене** состояния, поэтому шторма нет (`AdbClient.kt:242–250`, `AdbClient.kt:272–278`).
* `NetworkTtl.registerRootReadyHook()` регистрируется в том же месте, что и сетевой приёмник (процесс-локально, переживает lifecycle активити и недоступность канала), проход идёт на `BgWorker` (`NetworkTtl.kt:430–470`).
* `ttlRuleCounts()` теперь считает **свои** правила (цепочка + переходы), а не `ttl-set` в POSTROUTING — иначе после перехода на цепочку boot видел «правил нет» (`NetworkTtl.kt:410–424`).

### Б.3 Проверка §Б7 (bash-эмулятор iptables, `PATH=/tmp/fake/bin`)

Скрипты извлекались из `TtlScript.build()` (`@ENABLE_VALUE@`, `@OUR_CHAIN@`), прогон с раздельным состоянием v4/v6.

| Сценарий | Результат |
|---|---|
| ON при живом чужом `-o wlan0 -j TTL --ttl-set 64` (v4) и `--hl-set 64` (v6) | своё поставлено, чужое **на месте**: `TTL_FOREIGN v4/v6`, `FOREIGN_V4=1 FOREIGN_V6=1`, `TTL_LEGACY … action=keep`, вердикт `SCOPED_OK` |
| Повторный ON | идемпотентно: `TTL_ADD_COUNT jumps_v4=1 jumps_v6=1 rules_v4=1 rules_v6=1` |
| OFF при чужом | своё снято (`TTL_OWN removed=ok`), чужое цело, `TTL_OFF … result=PARTIAL reason=foreign_rules_remain`, вердикт `OFF_OWN_FOREIGN_LEFT` |
| ON→OFF на чистой машине | `result=OK`, `OFF_OK`, состояние правил пусто |
| Нет uplink (только `p2p-p2p0-0`) | `TTL_UPLINK_REJECT … reason=carplay_iface`, `TTL_TRY variant=none`, правил не создано |

Разбор этих итогов — в чистом `TtlVerify` (см. ниже): `PARTIAL` остаётся `PARTIAL`, а не «OK» и не «FAILED».

### Б.4 Найденный и исправленный дефект разбора (важно)

`NetworkTtl.parseVerifyResult` искал ключи регулярками с **двойным экранированием** (`"\\\\s"` в Kotlin = `\\s` в регулярке, т.е. литеральный обратный слэш). Значение, стоящее в сводной строке после пробела (`TTL_V6=0`), не разбиралось → `v6 = null`, и OFF при полностью снятых правилах мог быть объявлен «не выполнен». Разбор вынесен в чистый `TtlVerify` (`TtlVerify.kt`) с правильным экранированием и покрыт тестом `onSummaryIsParsedEvenWhenKeysFollowASpace`. `NetworkTtl.parseVerifyResult` теперь только отображает вердикт (`NetworkTtl.kt:161–174`).

---

## §В — Звук: монтирования по `mountinfo`

### В.1 Что было сломано

* `LockSound.kt` определял владение слоем по **первому полю `/proc/mounts`** — это устройство файловой системы (`/dev/block/…`), а не путь bind-источника. Сравнение с `/data/local/tmp/carlock_custom*` не могло совпасть никогда: **свой слой считался чужим** (`FOREIGN_MOUNT` + `break`), а чужой мог быть принят за «похожий на наш».
* `umount … || true` закрывал провал: успех заявлялся независимо от результата.
* Реестр слоёв заполнялся только в «счастливой» ветке: если во время bind владелец выключил звук, слои уже стояли, а реестр о них не знал — снять было нечем.
* Пространство имён монтирования не проверялось: bind мог уйти в namespace, невидимый системе.

### В.2 Что сделано

* **`MountInfo.kt`** (новый, чистый, покрыт тестами): разбор `/proc/<pid>/mountinfo` — mount ID, parent ID, major:minor, **root** (для bind это путь источника), точка монтирования, ФС, источник; раскрытие октальных escape (`\040`). Классификация слоя: `OURS` (root начинается с нашего staging), `FOREIGN`, `BASE` (root `/` — обычное монтирование ФС, не слой).
* **`LockMountScripts.kt`** (новый, чистый, покрыт тестами) — скрипты bind и restore:
  * владение по `mountinfo` (`awk '$5==t {print $1"|"$4}'`), `/proc/mounts` не используется вообще;
  * `umount` без `|| true`: `rc` фиксируется, после каждой попытки **перечитывается** `mountinfo`, снятием считается только уменьшение числа слоёв; `umount -l` помечается как `UMOUNT_DETACHED … reason=lazy_table_entry_may_remain` (запись в таблице может остаться — это не «снято»);
  * на чужой верхний слой **не монтируем** (`TARGET_SKIP reason=foreign_layer_present`) — иначе затеняли бы чужую подмену;
  * проверка `/proc/self/ns/mnt` vs `/proc/1/ns/mnt`: при несовпадении `MOUNT_NS_MISMATCH`, bind не выполняется и «применено» не заявляется;
  * `MOUNTED_OK` печатается только когда `mountinfo` подтвердил root нашего staging **и** inode цели.
* **`LockSound.kt`**: факты слоёв пишутся в реестр **до** любой сходимости (`LOCKSOUND_REGISTRY write_before_converge`), реестр сливается (`mergeOwnMounts`), а не перезаписывается; `restoreSystemLockSoundBind` чистит реестр **только** по доказанным записям, staging-файлы удаляются только когда наших слоёв не осталось; снятие не подтверждено → `applied=false` с причиной, реестр сохранён.

### В.3 Проверка (bash-эмулятор: поддельные `mount`/`umount`/`stat` + файл `mountinfo`)

| Сценарий | Результат |
|---|---|
| Чистая цель | `MOUNTED_OK` с `mid` и `inode`, readback по mountinfo |
| Чужой слой на цели | `FOREIGN_MOUNT` + `TARGET_SKIP`, на цель **не монтируем**, чужой слой цел |
| Наш старый слой (другой staging) | `UMOUNTED_OK` → новый bind |
| `umount` отказывает (busy) | `UMOUNT_FAILED`/`UMOUNT_DETACHED` по факту, `TARGET_SKIP own_layers_not_removed`, `MOUNTED_OK` не печатается |
| Restore: свой слой | `STOCK_OK`, слой снят |
| Restore: чужой сверху | `STOCK_FOREIGN_LEFT`, наш слой под чужим остаётся — так и заявлено |

Синтаксис обоих скриптов дополнительно проверен `bash -n` (OK).

**Ограничение, названное прямо:** наш слой, оказавшийся **под** чужим, снять нельзя, не тронув чужой. Он остаётся до перезагрузки, и это честно попадает в журнал (`STOCK_FOREIGN_LEFT`) и в реестр. Слышимость звука не проверяется приложением — только владельцем.

---

## §Г — Настройки: ревизии, tombstone, «неизвестно» до UI

| Было | Стало | Файл |
|---|---|---|
| `nextRev` = max+1 без какой-либо межпроцессной сериализации (два процесса могли записать разные значения с одинаковым номером) | `ProcLock` (файловый lock ОС + JVM-защита от `OverlappingFileLockException`) вокруг **всего** read-modify-write; не взяли lock → `lock_…` и запись **не** «подтверждена» | `ProcLock.kt`, `Prefs2.kt:118–128`, `Prefs2.kt:157` |
| конфликт равных ревизий мог вернуться как `confirmed` | при конфликте `confirmed=false`, причина `conflict_same_rev` | `Prefs2.kt:170–186` |
| `removeEx` подтверждал удаление по ревизии | подтверждение = нет значения **и** tombstone **и** наша ревизия | `Prefs2.kt:196–232` |
| `getBoolean` молча сводил Conflict/Unknown к `default` | добавлен `getBooleanOrNull`; `getBoolean` пишет `PREFS_UNKNOWN …` (не выдаёт default за решение владельца) | `Prefs2.kt:289–315` |
| `ToolsBackend.state()` отдавал выдуманное значение | для каждого тумблера `<key>State` = `on|off|unknown` + массив `prefsUnknown` + запись в журнал | `ToolsBackend.kt:157–190` |

🟡 **Осталось:** JS/нативная разметка `ToolsPanel` пока читает `key` (совместимость), поле `*State` уже публикуется, но ни один потребитель его ещё не отображает. Это следующий шаг, если владелец захочет видеть «неизвестно» в интерфейсе.
🟡 `ProcLock` защищает только наших писателей: чужое приложение наш lock-файл не знает. Убийство процесса между записью копии №1 и №2 остаётся возможным — для этого работают ревизии и согласование при чтении.

### Таблица тумблеров — только по `ToolsCatalog` / `ToolsPanel` / `ToolsBackend.Toggle`

`ToolsCatalog.kt` в дереве **отсутствует** (в iter46 он упоминался — в коде его нет). Источники ниже: `ToolsBackend.kt:68–105` (enum `Toggle`), `ui/ToolsPanel.kt` (`addToggle`). Пересчёт: 20 ключей в панели, 14 объявлены в enum; остальные (уведомления/строка состояния/`imm`,`wake`,`cb`,`proj`,`follow`,`remember`) управляются другими путями — так и записано.

| Ключ UI | Preference | Default | Где объявлен | Факт применения |
|---|---|---|---|---|
| `lockSound` | `lock_sound_enabled` | false | `Toggle.LOCK_SOUND` | `lockSoundApplied` = applied/pending/off (§В) |
| `unlockSound` | `unlock_car_audio_enable` | false | `Toggle.UNLOCK_SOUND` | только preference (свой плеер) |
| `ttl65` | `ttl_65_ativo` | false | `Toggle.TTL_65` | `ttl65Applied` = applied/pending/off (§Б) |
| `windowDriverOnDoor` | `PREF_WINDOW_DRIVER_ON_DOOR` | false | `Toggle.WINDOW_DRIVER_ON_DOOR` | окно/двери |
| `windowOppositeOnDoor` | `PREF_WINDOW_OPPOSITE_ON_DOOR` | false | `Toggle.WINDOW_OPPOSITE_ON_DOOR` | окно/двери |
| `perfVideo` | `PREF_PERF_VIDEO` | false | `Toggle.PERF_VIDEO` | через `AppPerformance` |
| `perfJit` | `PREF_PERF_AUTO_OPTIMIZE` | false | `Toggle.PERF_JIT` | `perfJitConfirmed` (§Д) |
| `remember` | `PREF_REMEMBER` | false | `Toggle.REMEMBER_BRIGHTNESS` | яркость |
| `notifBatt` / `notifTemp` / `notifWifi` | `PREF_BATTERY` / `PREF_EXT_TEMP` / `PREF_WIFI` | false | `Toggle.NOTIF_*` | уведомления |
| `hvacBatt` / `hvacTemp` / `hvacExtTemp` | `PREF_HVAC_*` | false | `Toggle.HVAC_DIGITAL_*` | `hvac_digital_*` |
| `imm`, `wake`, `cb`, `proj`, `follow` | `Prefs.*` | 1,0,0,0,1 | `Toggle.*` | UI/шелл |

---

## §Д — JIT: время старта zygote

| Было (iter47) | Стало |
|---|---|
| `JitNetCore.JitNetState.zygoteStartMs()` — метод интерфейса с default `null`; в `JitNet.kt` **переопределения не было**, `/proc` не читался. Отчёт iter47 утверждал обратное — это было завышение | `ZygoteStart.kt` (новый): pid по `cmdline` (`zygote`/`zygote64`), поле 22 `stat` (`starttime`), `btime = now − uptime`; `JitNet.AndroidNetState.zygoteStartMs()` реально это вызывает (`JitNet.kt:569–580`) |
| доказательство: `zs >= ts − 60_000` — засчитывало старт **до** команды | строго `zs > ts`, без допуска, плюс возраст отметки ≤ `STALE_MARK_MS` (`JitNetCore.kt:1490–1522`) |
| формулировка в журнале: «команда доставлена и выполнена» | `JIT_UNDEFINED_RESOLVED … evidence=zygote_started_after_mark` + прямо сказано: нашу команду это НЕ доказывает, JIT-компиляцию не доказывает; используется только для снятия блокировки нового клика владельца (`JitNetCore.kt:1638–1655`) |

🟡 Признак остаётся слабым по своей природе: zygote мог перезапуститься сам/упасть; pid может быть переиспользован; команда могла быть отброшена. Ничего из этого не заявляется как успех JIT.
⛔ Автоматического повтора JIT после boot **нет** (мандат): `ctl.restart zygote` — только внутри явного клика.
⛔ AP ГУ, `startSoftAp`, `ap0`, `CarplayMonitor`, `connadaptor_modo`, AVAS — не переносились и не трогались.

---

## §Е — Логи

| Дефект | Исправление | Файл |
|---|---|---|
| перед `renameTo` удалялся `.1` → отказ следующего шага терял предыдущую часть | `.1` не удаляется: `Files.move(REPLACE_EXISTING, ATOMIC_MOVE)` с фоллбэком без предудаления; при отказе строка **дописывается** в активный файл, причина `rotate_rename_failed:appended_to_active` | `FileLogCore.kt:183–232` |
| `.new` мог обнуляться (`setLength(0)`) — терялись данные прошлой неудачной ротации | дописывание в существующий `.new` | `FileLogCore.kt:186–196` |
| при занятом JVM-локе строка **терялась** (`jvm_lock_busy`) | строка уходит в spill этого процесса; причина `jvm_lock_busy:spilled` / `:spill_failed` | `FileLogCore.kt:74–86`, `280–300` |
| гонка чтения/удаления spill | слияние под локом spill через `.drain`; при неудаче данные **возвращаются** в spill | `FileLogCore.kt:310–360` |
| `LogFiles` «без межпроцессной защиты» | уже под файл-локом каталога (`dir/.logfiles.lock`, `FileChannel.lock`) с честным `LOGFILES_LOCK_UNAVAILABLE` при отказе | `LogFiles.kt:457–492` |

🟡 `WindowDoors.kt:550–562`: сравнение owners ≠ обнаружение ABA — **не менялось** в этой итерации (не входило в приоритет §Е beyond logs); записано как открытый пункт.
🟡 Абсолютная сохранность при отключении питания не обещается: `fsync` снижает риск, но не даёт гарантии.

---

## §Ж — Команды и результаты (все — в этой сессии)

```
git clone --depth 60 -b fix/v2 …        → HEAD 6a944c0, дерево чистое
bash runtests.sh                        → OK (446 tests)      # до правок
bash runtests.sh                        → OK (446 tests)      # после §Б-кода (TtlRunner переписан)
bash runtests.sh                        → OK (460 tests)      # + Iter48Test (§Б)
bash runtests.sh                        → OK (465 tests)      # + MountInfo/LockMountScripts
bash runtests.sh                        → OK (470 tests)      # + §Д/§Е тесты (итог)
bash -n /tmp/ttl_on.sh /tmp/ttl_off.sh  → SYNTAX_OK
bash -n /tmp/bind.sh /tmp/restore.sh    → SYNTAX_OK
эмулятор iptables (5 сценариев §Б7)     → чужое сохраняется, OFF=PARTIAL при чужом
эмулятор mount/umount/mountinfo (6 сцен.) → чужой слой цел, umount без || true
kotlinc по затронутым файлам            → нет ошибок, кроме «unresolved reference» (нет androidx/onnx)
```

Уровни доказательности:
* **JVM-тесты** (`Iter48Test`, 24 теста) — реальный код `TtlScript`, `TtlVerify`, `TtlRunner`, `MountInfo`, `LockMountScripts`, `ZygoteStart`, `FileLogCore`;
* **bash-эмуляторы** — поведение shell-скриптов на поддельных `iptables`/`mount`/`umount`/`stat`;
* **Android/ГУ** — ❌ не проверялось. Сборка APK — CI (`Build APK`), владелец собирает и подписывает сам.

---

## §З — Гоночные сценарии (что чем закрыто)

| Гонка | Защита | Чем проверено |
|---|---|---|
| клик ON, затем OFF во время выполнения | второй проход по последнему намерению; снимаются только свои правила | `Iter47Test.offDuringOnIsFollowedUp…`, JVM |
| два клика одновременно | `gate.tryLock` — параллельных shell-операций нет | `concurrentClicksNeverRunShellInParallel` |
| сеть вернулась во время прохода | счётчик поводов + перечитывание после `unlock` | `triggerOnTheExitBoundaryOfTheCycleIsNotLost` |
| повод после успешного применения того же поколения | проверка «уже применено» удалена | `backgroundTriggerAfterSuccessfulSameGenerationStillVerifies` |
| шторм намерений | бюджет + предел, «применено» не заявляется | `intentChurnIsBoundedAndKeepsLastIntent` |
| два процесса пишут настройку | `ProcLock` (файловый lock) + ревизии + конфликт ≠ confirmed | код + `Prefs2`-путь, JVM-проверка частичная 🟡 |
| bind и OFF одновременно | реестр пишется до сходимости, `convergeAfterBind` один проход | код, JVM-проверка 🟡 (нужен доступ к mount) |
| чужой слой на цели | `mountinfo`-классификация, не монтируем и не снимаем | bash-эмулятор |
| занятый lock лога | spill процесса + слияние под локом | `busyJvmLockDoesNotLoseTheLine` |

---

## Что нужно от владельца (безопасный план проверки)

Ни одна из команд ниже не меняет сеть/систему «на всякий случай»; всё — чтение или ровно то, что делает тумблер.

1. **TTL, чужое правило.** До включения: `iptables -t mangle -S POSTROUTING | grep -E 'ttl-set|hl-set'` (только чтение). Включить тумблер. После: та же команда — список чужих правил должен совпасть, плюс появиться `-j VAPP_TTL`. В журнале искать `TTL_FOREIGN`, `TTL_LEGACY … action=keep`, `TTL_OWN removed=…`.
2. **TTL, выключение.** Выключить тумблер. Ожидаемо: `TTL_OFF … result=OK`, если чужих правил нет, или `result=PARTIAL reason=foreign_rules_remain`, если они есть (это не ошибка). `iptables -t mangle -S VAPP_TTL` → «no chain» (цепочки нет).
3. **TTL, отсутствие uplink.** Включить TTL без интернета (например, без клиентского Wi-Fi): ожидаемо `TTL_TRY variant=none reason=no_uplink`, тумблер остаётся включённым (`pending`), Wi-Fi приложение не включает.
4. **Звук.** Включить кастомный звук. Проверить `cat /proc/self/mountinfo | grep carlock` (чтение) — в строке на цель `root` = `/data/local/tmp/carlock_custom…`, а не устройство. Выключить — запись должна исчезнуть; в журнале `LOCKSOUND_UNMOUNT_RESULT proven=…` (не `unproven`).
5. **Звук, чужой слой.** Если на цели уже есть чужой bind — ожидаемо `TARGET_SKIP reason=foreign_layer_present`, звук не применяется, чужой слой цел.
6. **JIT.** Клик тумблера (только вручную). В журнале `JIT_UNDEFINED_RESOLVED … evidence=zygote_started_after_mark` (если zygote перезапустился) — это **не** подтверждение JIT-компиляции; смотреть `perfJitConfirmed`.
7. **Настройки.** Значение тумблера после перезапуска не должно «само» меняться; при `prefsUnknown` в состоянии — тумблер помечается unknown (пока только в JSON).

Ограничения, которые я НЕ преодолел и не заявляю: слышимость звука, реальный TTL в пакетах, поведение на ГУ, межпроцессная защита от чужих приложений, сохранность при отключении питания.

---

## Открытые пункты на следующую итерацию

1. `WindowDoors.kt:550–562` — обнаружение ABA (сравнение owners — не обнаружение).
2. UI: читать `<key>State`/`prefsUnknown` в `ToolsPanel`, чтобы «неизвестно» было видно без журнала.
3. `TtlRunner`: единый межпроцессный исполнитель (сейчас сериализация только внутри JVM — так и заявлено).
4. `LockSound`: слой под чужим слоем снимается только перезагрузкой (нужен ли владельцу явный отчёт в UI).
5. `AppFileLogger.kt:141–143` — оставшаяся гонка чтения/удаления на стороне вызывающего: теперь защищена в `FileLogCore.drainSpill`, но сторона вызывающего не перепроверена.
