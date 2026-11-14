# REPORT iter51 — звук замка, TTL, JIT, тумблеры, логи

**Ветка:** `fix/v2` · **версия:** `versionCode=63`, `versionName=1.0.63` (**не повышалась**) ·
**SOURCE_SHA:** `64ccb0f` (коммит с кодом этой итерации) · **исходный HEAD:** `a67f568`
(iter50) · **HEAD после handoff:** `64ccb0f` + коммит маркера ·
**На ГУ ничего не запускалось**, APK владелец собирает и подписывает сам.

Мандат: `uploads/ПРОМПТ_iter51_СКВОЗНОЕ_ИСПРАВЛЕНИЕ_ЗВУК_TTL_JIT_ЛОГИ.md`.
Работа велась автономно, без ГУ; физические результаты (слышимость звука, TTL на проводе,
JIT-компиляция, VHAL окон) **не приписываются**.

## 0. Уровни проверки — что именно доказано

| уровень | что это | чего НЕ доказывает |
|---|---|---|
| JVM-тесты `bash runtests.sh` | **609** тестов (`kotlinc 1.9.24` + `android.jar` + стабы `android24.jar`/`carstub.jar`, gson, junit 4.13.2) | не Android runtime, не VHAL, не ФС ГУ |
| исполнение сгенерированных скриптов (`Iter50Stand`) | настоящий `/bin/sh` + fake `mount`/`umount`/`stat`/`iptables`/`ip` | не ядро ГУ, не её mountinfo/namespace |
| поддельный ADB-канал (`Iter51DeliveryTest.FakeDevice`) | лимит 4096 после **настоящей** упаковки, чанки, обрезка записи, отказ `rm` | не реальный `adb`-протокол, не `ROOT_5555`/`ROOT_UNIX` |
| модель устройства для JIT (`Iter51JitDev`) | claim/terminal-файлы + гвардия payload | не `/system/bin/sh` ГУ, не `setprop`, не zygote |
| чтение кода (`файл:строка`) | соответствие требованиям | не поведение на машине |
| CI `Build APK` | только сборка | `testDebugUnitTest` в CI **не запускается** |

Локально **недоступны** gradle/`assembleDebug` (пустой `android-sdk`), ГУ недоступно. Число
тестов: 548 (iter50) → **609** (+61).

---

## 1. Итог по разделам мандата

| раздел | статус | где |
|---|---|---|
| §3.1 измерение payload и транспорт звука | ✅ сделано, измерено | `ScriptDelivery.kt:111`, `AdbClient.executeScript` |
| §3.1 дефект «скрипт не доставляется» воспроизведён тестом | ✅ | `Iter51DeliveryTest` (14 тестов, лимит 4096) |
| §3.2 журнал операции ДО side effect | ✅ | `LockSound.kt:980`, `LockSound.kt:1390` |
| §3.3 `applied` только по каждой цели | ✅ | `LockOp.kt:108`, `LockSound.kt:~1070` |
| §3.4 root `/`, escape, строгий `NS_OK` в shell | ✅ | `LockMountScripts.kt:111`, `:284` |
| §4 IPv6 «нет» ≠ «есть, но FAIL» | ✅ | `TtlVerify.kt:132`, `:151` |
| §4 владение цепью/ссылками, legacy `--ttl-set 65`, `-F/-X` | 🟡 сохранено из iter49–50, новых дефектов не найдено | `TtlScript.kt` (не менялся) |
| §5.1 единая транзакция intent+generation | ✅ | `Prefs2.kt` (`Save2.confirmed`) |
| §5 таблица тумблеров, `unlockSound` ≠ bind | ✅ (код + тест), таблица ниже | `Iter51TogglesTest` |
| §5.3 unknown не становится OFF в UI | ❌ **не менялось** — см. §9 | `ToolsPanel.kt` |
| §7 (в промпте §6/§7 JIT) владение неопределённой операцией | ✅ **код изменён** | `JitOwn.kt`, `JitNetCore.kt:1731/1946/2043` |
| §7 логи: `delete()` по boolean, orphan `.spill/.drain` всех PID | ✅ | `FileLogCore.kt:428`, `:475`, `:541` |
| §8.2 ротация `.new`/`.1` при рестарте | ❌ **не менялось** — см. §9 | `FileLogCore.kt:178–226` |
| §8.1 (промпт) окна: `WindowDoors.kt:576–580` | ✅ | `WindowDoors.kt:586`, `:816` |
| проверка на ГУ | ⛔ не выполнялась | — |

---

## 2. §3 — звук замка: корневая причина найдена и измерена

### 2.1 Точные длины (измерено, не оценка)

Компиляция `LockMountScripts`/`TtlScript` инструментом `kotlinc` и печать `String.toByteArray(UTF_8).size`:

| скрипт | байт UTF-8 | строк |
|---|---|---|
| `LockMountScripts.bindScript` | **8935** | 171 |
| `restoreScript` (1 цель) | **5593** | — |
| `restoreScript` (2 цели) | **6246** | — |
| `restoreScript` (6 целей) | **8858** | — |
| `restoreScript` (12 целей) | **12794** | — |
| `TtlScript.build(on/off)` | **23035** | — |

Лимит одной команды ADB-протокола (и `AdbClient`) — **4096** байт (`ScriptDelivery.MAX_PAYLOAD`,
`AdbClient.kt:2104`). Значит на канале `ROOT_5555` прежний `AdbClient.execute(script, …)`
**не мог доставить** ни bind, ни restore, ни TTL: скрипт отклонялся ещё до отправки
(`ERR_ADB_TOO_LONG`). Это и есть причина «звук не встаёт» — не ошибка mount и не права.

### 2.2 Транспорт

Новый `ScriptDelivery.deliver(name, script, timeout, expectRoot, exec, tries, nonce)`
(`ScriptDelivery.kt:111`):

1. план по **фактической** длине UTF-8: ≤ `DIRECT_LIMIT` (3500) — прямая команда, иначе заливка;
2. **уникальное** имя `/data/local/tmp/voiceapp3_<name>_<8hex>.sh` (nonce на вызов) — коллизий с
   параллельным скриптом и с файлом прошлого запуска нет;
3. чанки base64 (`ScriptChunker.CHUNK=2500`), до 3 попыток, сверка `EXPECTED=<bytes>` **до** `sh`;
4. при несовпадении/обрыве **скрипт не запускается** (mount не выполняется вовсе);
5. `rm -f '<path>'` только своего файла; отказ удаления сообщается (`leftover=…`), а не скрывается;
6. late execution: ответ, пришедший после таймаута, не считается отменой — он попадает в журнал
   и не приписывается новой операции.

`AdbClient.executeScript` теперь всегда идёт через `ScriptDelivery.deliver` под
`synchronized(scriptUploadLock)`; журнал: `SCRIPT_UPLOAD name=… rc=… transport=… bytes=… chunks=…
size_checked=… leftover=…` и `SCRIPT_UPLOAD FAIL`.

`LockSound`: bind → `executeScript("lock_bind", …, 30000)` (`LockSound.kt:~960`), restore →
`executeScript("lock_restore", …, 30000)` (`:1246`).

### 2.3 Тесты дефекта через production-цепочку

`Iter51DeliveryTest` — fake `AdbClient` с лимитом 4096 **после** настоящей упаковки и виртуальным
`/data/local/tmp`: прямой вызов отклоняется лимитом; bind/restore доезжают байт-в-байт; постоянный
отказ чанка ⇒ **0** выполнений; обрезка записи ⇒ 3 попытки и **0** выполнений; уникальные пути;
отказ `rm` сообщается; `rc` скрипта пробрасывается; `ROOT_5555`-путь и `ROOT_UNIX`-путь покрыты
выбором транспорта; `ERR_SCRIPT_TOO_LARGE` без обращения к устройству.

### 2.4 §3.2/§3.3 — журнал и доказательство по каждой цели

| было | стало |
|---|---|
| `applied` = «есть хотя бы один `MOUNTED_OK`» и сравнение **числа** фактов с числом целей (`MOUNT_FACT` печатается и для `unverified`) ⇒ две цели, одна не проверена → `applied=true` | `LockOp.evidence(expected, facts, targetsReadable)` (`LockOp.kt:108`): `complete` только если на каждую ожидаемую цель ровно один `VERIFIED`, без `missing/duplicates/extra`; иначе `markApplied("partial:<reason> verified=N/M")` |
| список целей читался без проверки rc: пустой список → «нечего монтировать» (тихий успех) | `targetsReadable`: rc≠0 или пусто ⇒ bind **не отправляется** (`targets_unreadable`) |
| журнала операции не было: после падения нельзя понять, что уже сделано | `locksound_op.txt` пишется и **перечитывается** ДО mount (`writeOpJournal`/`finishOpJournal`, `LockSound.kt:980/1390`), `LockOp.State{PLANNED,DONE,UNDEFINED,FAILED}`; повреждённая запись = «неизвестно», не «операции не было» |
| сходимость после OFF/смены WAV — только при успехе | сходимость выполняется и при неизвестном/частичном исходе (`genChanged || desiredOff`), `applied` для устаревшего выбора не заявляется |

`Iter51LockOpTest` (12 тестов): 1 verified + 1 unknown ⇒ не complete; дубли; лишняя цель; пустой
список; `maySendBind` без подтверждённого журнала; round-trip журнала; повреждённая запись ⇒ `null`.

### 2.5 §3.4 — namespace и корень `/`

* `restoreScript` теперь проверяет namespace **в той же shell-операции** и **до** любого `umount`:
  при `NS_OK != 1` печатается `RESTORE_BLOCKED reason=namespace_not_proven … action=none` и
  скрипт выходит (отдельная предварительная команда доказывала namespace только своего вызова);
  нечитаемая mountinfo — `RESTORE_BLOCKED reason=mountinfo_unreadable`.
* `expected_root_for`: mount point `/` больше не даёт шаблон `//*` (он не совпадал с `/data/...`);
  отдельная ветка, остаток пути берётся целиком, вложенные точки работают как раньше.
* Тесты: `Iter50MountLayerTest` +3 (NS mismatch → ни одного `umount`; нечитаемая таблица; корень как
  единственный родитель).

---

## 3. §4 — TTL: IPv6 как отдельный факт

| было | стало |
|---|---|
| для ON достаточно `VERIFY_V4=OK` + `uplink_only=yes` ⇒ `VERIFY_V6=FAIL` давал **полный OK** | `TtlVerify`: `v6PresentButFailed` (FAIL/NOT_APPLIED/UNKNOWN при живом IPv6) ⇒ вердикт **PARTIAL** с причиной про v6 (`TtlVerify.kt:132`, `:151`) |
| `SKIPPED`/`none` (IPv6 в системе нет) | остаётся OK, но в детали прямо «применено **ТОЛЬКО** IPv4» — это не полная проверка обоих семейств |
| любая другая проблема | коллизия/откат/нечитаемая таблица/нет uplink/не встал v4 — по-прежнему **FAILED** (не подменены «частичным») |

`Iter51TtlFamilyTest` (7 тестов) проверяет настоящий `TtlVerify.parse` на выводе формата
`TtlScript.build(true)`.

**Не менялось (сохранено из iter49–50, проверено тестами):** argv-разбор `-o wlan0 -j VAPP_TTL`,
владение цепочкой по содержимому, `-j`/`-g` из чужих цепей, legacy `--ttl-set 65` не считается
своим, `-F`/`-X` только при доказанном владении, порядок v4→v6 и rollback только своего,
`TTL_RESULT`/`TtlRunner.Outcome` с честным `pending/partial`.

---

## 4. §5 — prefs, транзакция и тумблеры

### 4.1 Единая транзакция (§5.1)

`Prefs2.Save2.confirmed` требовал `save.confirmed && genSave.confirmed`, а `gen` — обе части **в
одной копии**. Состояние `confirmed=true, gen=-1` было достижимо: intent подтверждён в одной копии,
поколение «нашлось» в другой. Теперь `confirmed = gen > 0 && …` — **подтверждённая транзакция без
реального поколения невозможна**. Тесты: `Iter50Prefs2Test` +2 (в т.ч. «разные части выглядят
верными в разных копиях» через `FakePrefs.skipKeys`).

### 4.2 Таблица реальных тумблеров (`ToolsBackend.Toggle`, проверено кодом)

| UI-ключ | pref-константа | storage | действие / readback | зависимости | уровень |
|---|---|---|---|---|---|
| `imm` | `Prefs.IMMERSIVE` (on) | CPS | системный UI, чтение prefs | — | код |
| `wake` | `Prefs.LAUNCH_ON_WAKE` | CPS | prefs + сервис | — | код |
| `cb` | `Prefs.CLIMATE_BAR` | CPS | оверлей | — | код |
| `proj` | `Prefs.PROJECTION_BETA` | CPS | prefs | CarPlay-плагин не трогается | код |
| `follow` | `Prefs.THEME_FOLLOW_HU` | CPS | тема | `Choice.gu` | код |
| `remember` | `ScreenBrightness.PREF_REMEMBER` | CPS | чтение яркости | — | код |
| `notifBatt`/`notifTemp`/`notifWifi` | `StatusIcons.PREF_*` | CPS | строка состояния | — | код |
| `hvacBatt` | `StatusIcons.PREF_HVAC_BATT` | CPS | hvac_digital_* | **скрывает** «Цифровую наружную» по дизайну референса | код + прежние итерации |
| `hvacTemp`/`hvacExtTemp` | `StatusIcons.PREF_HVAC_*` | CPS | hvac_digital_* | выбор наружной **не стирается** (🟡 см. §9) | код |
| `lockSound` | `LockSound.PREF_LOCK_SOUND_ENABLED` | CPS + DPS-mirror | bind/mount + журнал операции | только этот тумблер монтирует | тесты iter49–51 |
| `unlockSound` | `LockSound.PREF_UNLOCK_SOUND_ENABLED` | CPS | только prefs, **без bind/mount** | — | `Iter51TogglesTest` |
| `windowDriverOnDoor`/`windowOppositeOnDoor` | `WindowDoors.PREF_WINDOW_*` | CPS | VHAL-команда + readback | поколение владельцев | тесты iter50–51 |
| `ttl65` | `NetworkTtl.PREF_TTL_65_ACTIVE` | CPS+DPS (транзакция) | iptables + `TTL_RESULT` | uplink обязателен | исполнение скриптов |
| `perfVideo` | `AppPerformance.PREF_PERF_VIDEO` | CPS | `cmd package compile -m …` (**не read-only**) | — | код |
| `perfJit` | `AppPerformance.PREF_AUTO_OPTIMIZE` | CPS+DPS | JIT-цепочка (см. §5) | только по клику | тесты iter51 |

`unlockSound` ≠ bind замка доказано тестом: разные pref-ключи, в `setUnlockSoundEnabled` нет
`lock_bind`/`executeScript`/`bindScript`/`mount`, есть `putBoolean` + `getBoolean`.

---

## 5. §7 (JIT) — владение неопределённой операцией (код изменён)

### 5.1 Что было не так

`undefined` ставился при потерянном ответе, а «выходом из неизвестности» считались старт zygote
после отметки или истечение окна. Ни то, ни другое не относится к **самой команде**, и сама
команда не отменялась: доставленный, но ещё не выполненный payload мог выполниться позже — уже
после нового клика. Свидетельства о завершении не было вовсе.

### 5.2 Что реализовано (`JitOwn.kt`, `JitNetCore.kt`)

1. **claim** `/data/local/tmp/voiceapp3_jit.claim` (`op=<id>;ts=…;want=…`) — пишется короткой
   командой перед отправкой payload (`JIT_CLAIM op=… taken/verified`, `JitNetCore.kt:1946`).
2. **terminal** `/data/local/tmp/voiceapp3_jit_<id>.term` — `START` пишет начало (до первого side
   effect), `trap EXIT` пишет `END rc=N` (срабатывает и после `setprop ctl.restart zygote`:
   убивается zygote, не наш shell).
3. **Гвардия в самом payload** (`JitOwn.guard`, вставляется в отправляемый скрипт): если claim на
   устройстве принадлежит другой операции — `echo JIT_REFUSED …; exit 0`, **до** `svc wifi
   disable`/`setprop`/`ctl.restart`. Позднее исполнение устаревшего payload становится
   ДОКАЗАННЫМ no-op, а не надеждой. Литерал референса при этом не изменён (проверено тестом).
4. **Фаза операции**: `created → claimed → sent → started → finished/refused/unknown`
   (`JitOwn.classify`), в журнале `JIT_OP_PHASE … — <что доказано>`; ответ транспорта исходом не
   считается.
5. **Выход из неизвестности**: короткий read-only запрос claim+terminal (`JIT_OP_PROBE`) перед
   решением о блокировке; `END`/`JIT_REFUSED` по **тому же id** ⇒ `JIT_OP_RESOLVED evidence=terminal`
   (`:1731`) и клик разрешается — **без** reboot, **без** ожидания окна, **без** повторного
   `ctl.restart zygote`. Старт zygote, новый PID, boot id, Wi-Fi/IP доказательством не считаются.
6. **Бессрочного запрета нет**: `JitOwn.clickDecision` разрешает клик и честно называет остаточный
   риск (`old_payload_running` / `old_payload_maybe_queued`), прямо говоря, что уже начавшийся
   рестарт новым claim'ом не отменяется.
7. **`JIT_REFUSED` ⇒ честный откат**: настройки возвращаются по доказанной принадлежности
   (`planRestore`/`restorePrevious`), свои ключи снимаются (`removeOwned`), возврат Wi-Fi не
   планируется (сеть не менялась), в журнале `restart=not_happened internet=not_checked`.
8. **Восстановление Wi-Fi** привязано к владельцу claim (`JitOwn.mayRestoreWifi`): чужой/новый
   выбор не откатывается.

### 5.3 Остаточный риск (назван, не спрятан)

* гвардия защищает от **позднего старта**, но не отменяет уже начавшийся payload — исход остаётся
  «идёт» (`STARTED`), и это блокирует новый клик до `END`;
* если на устройстве не удалось ни записать claim, ни прочитать terminal, исход остаётся
  `UNKNOWN` — это по-прежнему «не доказано ни выполнено, ни не выполнено»;
* ни одно из наблюдений (claim/terminal/`getprop`) **не доказывает JIT-компиляцию**.

### 5.4 Путь к рабочему JIT (не запрет)

Проверяемый порядок: клик → claim → payload (гвардия + `setprop dalvik.vm.usejit` + `ctl.restart
zygote`) → prompt `END rc` → `verifyApplied` (`getprop` + `/proc/self/maps` по `jit-cache`).
Разделение честное: `END rc=0` доказывает **завершение команды**, `getprop=true` — только
свойство, `maps` с `r-xp` на `jit-cache` — наблюдение карты **одного** процесса. Компиляция
считается неизвестной, пока нет проверяемого признака целевого Android. Автоматического JIT на
boot нет: цепочка запускается только кликом (`ctl.restart zygote` — только внутри неё).

---

## 6. §7 (логи)

| было | стало |
|---|---|
| `runCatching { f.delete() }.isSuccess` | `FileLogCore.deleteVerified(f)` (`:475`+): истина только по **boolean** `delete()`; «файла нет» ≠ «не удалилось»; неудача попадает в заметку (`work_delete_failed`, `empty_spill_kept`, `lockfile_kept`) |
| восстанавливался только spill **своего** pid | `orphanFilesOf(dir, name)` (`:428`) находит `<log>.<pid>.spill` и `.drain.<pid>-N` **всех наших** pid; живые (свежая запись + `/proc/<pid>`) и чужие журналы не трогаются; PID reuse учтён окном свежести 30 мин; переименование в уникальное имя, слияние под межпроцессным lock, `orphans_recovered=N` |
| уникальные work-имена | сохранено из iter50 (`.drain.<pid>-<seq>`), проверено тестами |

`Iter51LogOrphanTest` (7 тестов, реальные файлы, подменяемые `/proc` и часы).

---

## 7. §8.1 (промпт) — окна: откат владельцев

`WindowDoors.kt:576–580` (код iter50) при неподтверждённом readback переписывал набор владельцев
напрямую — `synchronized(ventOwners) { set.clear(); set.addAll(before) }` — **в обход**
`mutateOwners`: поколение не росло, и владелец, добавленный другой дверью за время команды VHAL,
молча стирался (окно оставалось без владельца). Теперь откат идёт через `mutateOwners` и только
если набор равен тому, что записали мы (`ventRollbackOwners`, `:816`); в журнал добавлено
`rolled_back=…`. `Iter51VentRollbackTest` (5 тестов, включая «новый владелец не стирается»).

AVAS/status bar/плагин не трогались.

---

## 8. Безопасный план испытания на ГУ (один JIT-клик, reboot — не лечение)

Порядок рассчитан на один заход; между шагами ничего не «дожимается».

1. **Перед всем:** выгрузить с ГУ `locksound_op.txt` и `/data/local/tmp/voiceapp3_jit*.{claim,term}`
   (если есть) — это исходное состояние. Записать `getprop dalvik.vm.usejit`.
2. **Звук замка (проверка §3):** включить «Звук блокировки замка» → в журнале ожидается
   `SCRIPT_UPLOAD name=lock_bind transport=upload bytes=8935 size_checked=1`, затем
   `MOUNT_FACT…state=verified` **по каждой** цели и `applied` только при полном наборе. Выключить →
   `SCRIPT_UPLOAD name=lock_restore …`, `UMOUNTED_OK` по каждой цели. Если `applied=partial:` —
   смотреть `missing/duplicates/unverified`, повтор не «дожимать», а разбирать по журналу.
3. **TTL:** включить при подключённом uplink → `TTL_RESULT`. Полный `OK` — только если оба
   семейства проверены; `PARTIAL` с `VERIFY_V6=FAIL` означает «v4 стоит, v6 нет» (это и есть
   честный результат, а не сбой интерфейса).
4. **JIT — ровно ОДИН клик.** После клика в журнале должны быть по порядку: `JIT_CLAIM op=…
   verified=1`, `JIT_CHAIN_SENT`, `JIT_OP_PHASE op=… phase=finished` (или `started`/`unknown`).
   * `phase=finished` ⇒ исход команды известен, `JIT_OP_PROBE` покажет `END rc=…`;
   * `phase=unknown` ⇒ **не жать второй раз «чтобы сработало»** и **не перезагружать** ГУ как
     лечение: следующий клик разрешён (бессрочного запрета нет), но он сначала прочитает
     terminal и честно назовёт риск (`old_payload_maybe_queued`). Повторный клик запускает новую
     опасную цепочку (`svc wifi disable`, `umount -l`, `ctl.restart zygote`);
   * `JIT_REFUSED` ⇒ payload не выполнялся, настройки возвращены — это доказанный no-op, сеть и
     свойства не менялись.
5. **После JIT:** дождаться возврата Wi-Fi по снимку (15 с) и сверить
   `JIT_NET_RESTORE … completion=`; `internet=not_checked` означает «не проверялось», а не «нет
   интернета». CarPlay/AP-плагин не трогаются.
6. **Что считать успехом проверки на ГУ:** `size_checked=1` + `verified` по всем целям (звук);
   слышимый звук (только после реального прослушивания); `END rc` по JIT. Что **не** считается:
   «в журнале нет ошибок», «Wi-Fi вернулся», «`getprop` = true», «`dex` в maps».

---

## 9. Чего нет в этой итерации (честно)

| пункт | статус | причина |
|---|---|---|
| §5.3 трёхзначное состояние **всех** тумблеров в UI (unknown не становится OFF) | ❌ не менялось | UI-мост `ToolsPanel.kt` вне локального стенда (Android/WebView): правку нельзя проверить ни тестом, ни сборкой локально; в CI тесты не идут. Прежнее поведение iter50 сохранено, новых обещаний не добавлено |
| §5 зависимость «Процент заряда» → скрывает «Цифровую наружную» по production-пути status bar | 🟡 не проверено сквозным тестом | требует `StatusIcons`/плагина статус-бара и Android runtime; сохранение самого выбора наружной температуры не менялось |
| §8.2 ротация: восстановление `.new`/`.1` при рестарте, порядок promote | ❌ не менялось | дефект из iter50-отчёта остаётся открытым; чтобы не выдавать «сделано» за сделанное, пункт оставлен открытым |
| §6.3 hidden-toggle связки, `ToolsPanel` unknown | ❌ | то же, что §5.3 |
| проверка на ГУ, реальный `adb`-протокол (`ROOT_5555`/`ROOT_UNIX`), слышимость, TTL на проводе, JIT-компиляция, VHAL | ⛔ | среды нет |

---

## 10. Проверки и артефакты

| что | команда | результат |
|---|---|---|
| JVM-тесты | `bash runtests.sh` | **OK (609 tests)** |
| компиляция production-кода | тот же прогон (`kotlinc 1.9.24`) | без ошибок |
| CI `Build APK` | run `43628779760` на `64ccb0f` | **success** (~17 мин); тесты в CI не запускаются |
| CI (первый заход) | run `43627831587` на `aad13b2` | **failure**: `LockSound.kt:975 Unresolved reference 'Stage0'` — исправлено в `64ccb0f`; это единственная проверка компиляции `LockSound.kt`/`AdbClient.kt`, локальный стенд их не собирает (см. §10.1) |

Коммиты итерации (в порядке применения):

```
5486689  §3.1–§3.3 доставка скриптов звука, доказательство по каждой цели, журнал
4e81248  §3.4 барьер namespace + корень '/'
768a8ae  §4.3(1) IPv6 partial + §5.1(1) confirmed без поколения невозможен
1d0c69f  §7 логи: delete по boolean, orphan .spill/.drain всех pid
747a65b  §8.1 окна: откат через mutateOwners
147abde  §7(JIT): claim/terminal/гвардия/выход из неизвестности
23273b8  §5 инварианты тумблеров
bce779d  отчёт + WORKLOG
aad13b2  SOURCE_SHA=23273b8
64ccb0f  fix: Stage0 внутри LockSound (найдено CI) -> SOURCE_SHA=64ccb0f
```

### 10.1 Пробел в локальной проверке (важно)

Локальный стенд компилирует **не все** production-файлы: `LockSound.kt` и `AdbClient.kt` требуют
Android `Context`/`AudioAttributes`/`Settings` и в стенд не входят. Именно поэтому опечатка
`LockOp.Stage0` (класс объявлен внутри `LockSound`) дошла до CI и уронила сборку `aad13b2`.
Единственный барьер для этих двух файлов — CI `Build APK`; для остальных изменённых файлов
(`ScriptDelivery`, `LockOp`, `JitOwn`, `JitNetCore`, `FileLogCore`, `WindowDoors`, `TtlVerify`,
`Prefs2`, `LockMountScripts`) барьер — локальный прогон 609 тестов.

**Владельцу:** файл `REVIEW_iter50_*.md` в загрузках отсутствует (в `/home/user/uploads` есть
только `REVIEW_iter33p*`, `review_iter7/9/22/26.md`) — независимый аудит iter50 не прочитан и не
учитывался; пришлите его, если он должен быть разобран.
