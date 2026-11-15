# iter54 — JIT, TTL, звук замка, логи, окна и тумблеры: что реально исправлено

**Ветка:** `fix/v2` (единственная, куда шла запись) · **`--force` не применялся, `main` не пушился**
**HEAD на момент отчёта:** `789f8f6` · **Кодовый SHA итерации:** `789f8f6` (`SOURCE_SHA` указывает на него)
**versionCode 63 / versionName 1.0.63 — не менялись**
**Локальный прогон:** `bash /home/user/runtests.sh` → **`OK (806 tests)`**, ~55 с
**CI `Build APK`:** run **`43707141785`** на `789f8f6` — **success** (job `build`, шаг 11 `Gradle build (debug)` ✔, шаг 12 `Upload APK` ✔) · https://github.com/AlekseiAlekseikov/Backup/actions/runs/43707141785
Предыдущий ран `43705256662` на `d270a2d` — **failure**: `:app:compileDebugKotlin`, `ToolsBackend.kt:1046 Unresolved reference 'status'` (у `ToolsResult` нет поля `status`, сводный статус — `statusWire`). Исправлено в `789f8f6`: добавлен `ToggleIntent.Applied.ofWire(...)`, production переведён на него, добавлен тест. Раньше (iter53/ранние iter54) раны отменялись: `43701879127`, `43702393826`, `43703150481`, `43704050617`, `43704534919`, `43706748051`.

> Терминология отчёта: ✅ — исправлено и покрыто исполняемым тестом; 🟡 — исправлено, но проверка ограничена fake/JVM и/или требует ГУ; ❌ — не сделано/не доказано; ⛔ — проверено быть не может в этой среде.

---

## 0. Коммиты итерации

| Коммит | Блок | Локальный прогон после коммита |
|---|---|---|
| `8c0f9b1` | §6.1 TTL — идентичность своей цепи | `OK (757)` |
| `9f76bb1` | §4 JIT — владение экземпляром, порядок намерений, атомарный lock, гейт отправки | `OK (763)` |
| `8a54ce6` | §5 доставка — типизированный исход до потребителей | `OK (773)` |
| `78cc0a4` | §7 звук — трёхзначный журнал, идемпотентная миграция, верхний слой mount | `OK (779)` |
| `2252175` | §8 окна — `before→переход→установка→поколение` в одной секции | `OK (783)` |
| `74ebdac` | §9 логи — без content-дедупликации, `.new` под общим локом | `OK (789)` |
| `d270a2d` | §10 тумблеры — intent/apply/readback разведены | `OK (805)` (CI `43705256662` — **failure**, компиляция) |
| `789f8f6` | §10 fix — факт применения из `statusWire` (находка CI) | `OK (806)` · **CI `43707141785` success** |

---

## 1. §4 JIT — владение экземпляром, а не «opId вообще»

**Было.** `_jit_owner_dead` считал доказательством конца владельца любой `END`/`REFUSED` по тому же `opId`; параллельный претендент с тем же opId писал свой `REFUSED` и тем самым «завершал» живого владельца. Захват lock был `exists` + `mv` (не атомарно). `ClaimWrite.Failed` логировался как `action=proceed`, и payload всё равно уходил.

**Стало (production):**
- `JitOwn.kt:149` — `ClaimIdentity(operationId, instanceId, boot, pid, pstart)`; `JitOwn.kt:210` — `claimLine()`, единый формат записи владельца.
- `JitOwn.kt:297` — `_jit_owner_dead`: rc 0 = «жив ИЛИ смерть недоказуема» (перехватывать нельзя), rc 1 = доказанная смерть **именно этого экземпляра**; `JitOwn.kt:386–393` — вердикт `CL_ORDER … verdict=free|older_intent|ts_tie|newer_intent|newer_finished` до попытки захвата.
- Атомарный захват: `temp + ln` вместо `( set -C; printf > lock )` (ненадёжно на этой прошивке); отказы перечисляются причиной.
- `JitOwn.kt:438` — `ClaimWrite{Taken, Busy, Stale(newer), Failed(why)}`; `JitOwn.kt:657` — `claimGate(...)`: **`Failed` ⇒ payload не отправляется** (`claim_unproven`), `Busy` ⇒ не перехватываем, `Stale` ⇒ уступаем более новому намерению навсегда.
- `JitNetCore.kt:1998` — production консультируется с гейтом **до** отправки цепочки.

**Тесты** (`Iter54JitConcurrencyTest.kt`, 6): `twoRealShellsAtTheSameMomentProduceExactlyOneSideEffect`, `refusalOfAnotherInstanceDoesNotBecomeTerminalOfTheFirst`, `lateClaimOfOlderIntentNeverCancelsNewerIntent`, `olderIntentTakesOverOnlyAfterNewerProvedFinished`, `unconfirmedClaimStopsThePayloadAndKeepsIntentPending`, `productionConsultsTheGateBeforeSendingTheChain`. Исполняется **настоящий `/bin/sh`** с временными файлами и барьерами; zygote не запускается (по требованию мандата).

**Честная граница:** 🟡 PID reuse и недоступный `/proc` моделируются аргументами, а не реальным ядром Android; убийство zygote-процесса на живом ГУ не воспроизводилось.

---

## 2. §5 доставка скриптов — исход не теряется по пути

**Было.** `Pair(ok, out)` терял `state`, `operationId`, evidence path и признак «могло исполниться позже»; `LockSound`/`NetworkTtl` получали `Pair` и не могли отличить `RUN_UNKNOWN` от отказа.

**Стало:** `ScriptDelivery.kt:149` — `DeliveryOutcome{NotSent(why), Unknown(operationId, EvidenceRef), Finished(operationId, exitCode, output)}`; `:125` — `EvidenceRef`; `:178` — `outcome(result, operationId)`; `directScript()` оборачивает скрипт в подоболочку, чтобы `exit N` не съедал маркер.
Потребители: `NetworkTtl.kt:357–366` (`TTL_DELIVERY … action=stop|pending`), `LockSound.kt:1046–1075` (`NotSent` → не считать применённым, `Unknown` → сохранить свидетельство и сверять, `Finished` → факт).

**Тесты** (`Iter54DeliveryTest.kt`, 10): потеря stdout после START, поздний `.done`, загрузка той же длины но с другим содержимым, отсутствие `cksum`/`md5sum`, `.started`/`.done` одной попытки, честный `NotSent` при отказе чанка, `cksum` назван проверкой целостности, **а не криптодоказательством**.

**Граница:** 🟡 реальный `adb`-туннель в тестах не поднимался (запрещён инвариантом); доставка проверяется на fake-device, повторяющем протокол.

---

## 3. §6 TTL — своя цепь доказывается идентичностью, а не совпадением

**Было (главный дефект iter53).** Немаркированная цепь с совпадающим именем и телом считалась «нашей» и **удалялась**.

**Стало:**
- `TtlOwnership.kt:33` — `CHAIN_PREFIX = "VAPP_TTL_"`, `:75` — имя цепи из токена установки (уникальное на установку), `:92` — `load`, `:121` — `ensure` (создание реестра с подтверждённой записью и readback).
- `TtlScript.kt:223` — `chain_state` → `absent|empty|ours|unproven|collision|unknown`; `:60–77` — историческое имя участвует **только в отчёте** (`legacy`), а не в присвоении.
- `ROLLED_BACK=yes` печатается только после readback фактического отсутствия правила/цепи.

**Тесты** (`Iter54TtlOwnershipTest.kt`, 9 + `Iter53TtlTest`): `sameNameChainWithoutOurMarkerIsNeverDeclaimed`, `unknownRegistryNeverCreatesANewIdentity`, `conflictingCopiesAreUnknownNotAFreshToken`, `twoInstallationsDoNotShareChainName`, `unknownRegistryTouchesNothingAtAll`, `ownChainWithTokenIsAdoptedAndLegacyChainIsOnlyReported`, `markerOfDefaultIsNotProofOfOwnership`, `registryUnknownScriptStillPassesShellSyntaxCheck`, `registryIsCreatedOnceWithConfirmedWriteAndReadback`; `foreignReferenceBlocksDeletion`, `v6FailureRollsBackV4…`.

**Граница:** 🟡 стенд — настоящий `/bin/sh` с подставными `iptables`/`ip6tables` (argv сверяется по каждому аргументу), но не ядро ГУ. «ON переживает reboot/update» — по коду (перечитывание реестра после загрузки) и тестам реестра; **на живом ГУ не проверялось** (⛔, перезагрузка не выполнялась). Нет утверждения, что TTL ломал интернет — причина не доказана.

---

## 4. §7 звук замка — журнал трёхзначный, перенос идемпотентный, верхний слой по таблице

**Стало:**
- `LockOp.kt:139` — `sealed interface JournalRead { Absent / Unknown(why) / Readable(value) }`, `:161` — `readJournal`: `ENOENT ≠ EACCES ≠ corrupt` (отдельные причины в `wire()`).
- `LockSound.kt:1616–1621` — `LOCKSOUND_JOURNAL …`; `:1651` — `reconcileLocked`: при `Unknown` **ничего не снимается** (`action=stop`), журнал сохраняется.
- Миграция старого журнала — идемпотентная (`alreadyThere → rename_only`), crash-safe по порядку «записать → проверить → удалить».
- `LockMountScripts.kt:75` — `mi_top()` = **последняя строка mountinfo** (реальный верхний слой), `:171` — `MOUNT_ORDER method=mountinfo_order_with_mount_id_corroboration top_guaranteed=only_if_order_and_id_agree`; при расхождении порядка и `mount_id` печатается `AMBIGUOUS||` и **не выполняется ни один umount**.

**Тесты** (`Iter54SoundTest.kt` 5, `Iter52JournalTest`): `missingJournalIsAbsentButUnreadableJournalIsUnknown`, `emptyJournalIsReadableAndCorruptLinesAreCounted`, `journalAppendIsPerOperationAndLastStateWins`, `reconcileRequiresEveryTargetNotJustOne` (после OFF проверяются ВСЕ цели), `mountTopLayerUsesTableOrderWithIdCorroboration`, `disagreeingMountIdAndFileOrderIsUnprovenAndTouchesNothing`.

**Граница:** 🟡 монтирования реального верхнего слоя на ГУ не выполнялись — по инварианту запрещено трогать AP ГУ/`connadaptor_modo`; проверяется разбор реального `mountinfo` и argv.

---

## 5. §8 окна — одна критическая секция

**Было.** `before` читался, затем отдельно мутировались owners, затем отдельно снимался `generation`; closed-ветка мутировала набор напрямую; между шагами терялись конкурентные события.

**Стало:**
- `VentOwnerTx.kt` (`:19` `ClosePlan`, `:29` один `lock`, `:38` `generation()`, `:42` `snapshotWithGen()`, `:52` `mutate`, `:74` `planCloseInstall`) — чтение `before`, вычисление перехода, установка и снятие поколения в **одной** секции.
- `WindowDoors.kt:106` — единственный `ventTx`; `:183` — `planCloseInstall` делегирует; `:560` `closeVentWindow` использует план целиком; closed-ветка идёт через `mutateOwners` (прямых `synchronized(ventOwners)` не осталось).
- `WindowDoors.kt:90` — `mainHandler by lazy` (иначе JVM-тест падал на `Looper` Stub).

**Тесты** (`Iter54WindowTest.kt`, 4): 8 потоков × 25 транзакций → ровно 200 РАЗНЫХ поколений, ошибок 0, запись окна не потеряна; две двери на общем окне не теряют владельца B; `ventRollbackOwners` не стирает новое событие и распознаёт ABA по поколению.

**Граница:** ⛔ настоящие VHAL-события окон не подавались; проверена модель состояния и его сериализация.

---

## 6. §9 логи — одинаковые записи легальны, `.new` под общим локом

**Было.** `recoverPendingNew` выбрасывал строки, уже встречавшиеся в активном файле (content-дедупликация) — реальные события терялись молча, потому что одинаковые записи в журнале **легальны**. Слияние `.new` шло без лока, хотя append/ротация — под `<name>.lock`.

**Стало** (`FileLogCore.kt:168` `recoverPendingNew`, `:212` `mergePendingLocked`):
- content-дедупликация удалена;
- граница определяется **позиционно**: сравнивается только последняя запись активного файла и первая запись `.new` (исторический случай двойной записи при отказе fsync), в отчёте `boundary_dup=N`; неполный хвост активного файла границей не считается;
- слияние идёт под **тем же** межпроцессным `<name>.lock` + JVM-локом, что append и ротация; при занятом локе — `new_locked_kept:<why>` и `.new` **сохраняется** как свидетельство;
- `.new` удаляется только после переноса всех записей и проверки удаления.

**Тесты** (`Iter54LogTest.kt`, 6): одинаковые записи не дедуплицируются; пропускается только позиционный граничный дубль; неполный хвост; чужой лок (реальный `FileLock`) держит `.new`; два «процесса» восстанавливают 20 записей **без дублей**; append сохраняет одинаковые строки.

**Граница:** 🟡 «два процесса» моделируются потоками с отдельными экземплярами и реальным файловым локом; настоящий `fork` двух процессов приложения не запускался.

---

## 7. §10 тумблеры и UI — намерение ≠ применение ≠ readback

**Было.** Подтверждением записи считался `ToolsResult.ok` **общего действия**. TTL ON, сохранённый и ожидающий uplink (PARTIAL), показывался как «запись не удалась»; `perfJit` при неизвестном getprop подставлял `default` и выглядел как факт.

**Стало:**
- `ToggleIntent.kt:28` — новый чистый модуль: `Intent{WRITTEN, WRITE_FAILED, UNKNOWN}` считается по **readback хранилища** (`Prefs2.getBooleanOrNull`: конфликт/неизвестность = `null` ⇒ `UNKNOWN`, **не** `false`) плюс отчёт записи; `Applied{APPLIED, PENDING, FAILED, UNKNOWN}` — по статусу действия (`PARTIAL`/`BLOCKED` ⇒ `PENDING`, а не отказ); нейтральные подписи; `confirmsChoice()`.
- `ToolsBackend.kt:1018` `applyFromUi(...)`, `:1040` `persistReported(res.steps)`, `:1049` лог `TOGGLE_INTENT key=… click_id=… want=… stored=… intent=… applied=… action=… note=…` (applied берётся из `statusWire`), `:1074` `persistReported` — отчёт шагов сохранения.
- `ToolsStateCausality.kt:66` — `WriteAck` несёт `intent`/`applied` (старые снимки читаются по `ok`), `toJson`/`readAcks` — оба факта, `note()`; `decide()` возвращает `WRITE_FAILED` только по факту отказа записи, `UNKNOWN` держит выбор и через бюджет честно называется неподтверждённым.
- `ToolsPanel.kt:1643` — `applyNotes`; в снимке (`:250–262`) заполняются из `$acks`; подпись (`:1170–1173`) — «сохранено, ждёт применения» / «сохранено, применение не удалось» (нейтрально, без красного); новый клик снимает подпись прошлого намерения; `perfJit` при `unknown` показывает последнее известное (`:1153`), а не `default`.
- Модель `clickId` iter53 сохранена целиком (`ToolsPanel.kt:1174` создаёт, `UiBridge.kt:496–515` доводит до backend, ack возвращается в снимке).

**Тесты** (`Iter54ToggleTest.kt`, 15): матрица intent/applied; **TTL ON с ожидающим uplink даёт `CLEAR_CONFIRMED`**, а не `WRITE_FAILED`; неизвестное чтение ≠ `false`; поздний ack старого клика не снимает новый выбор; частичный снимок не выдумывает OFF; решение по одному ключу не трогает соседние; round-trip ack через настоящий JSON снимка.

**Граница:** 🟡 **живой рендер не проверялся.** Robolectric/instrumentation в песочнице отсутствуют, `ToolsPanel`/`UiBridge`/`ToolsBackend` локальный harness не компилирует (Android-зависимости). Модельный JSON — не эквивалент живого рендера; это сказано прямо, а не замаскировано зелёным тестом. Отдельно: `hvacExtTemp` при OFF «Процента заряда» на панели не скрывается (скрытие по дизайну референса реализуется в плагине SystemUI) — 🟡 сохранённое значение не стирается, но визуальную зависимость на ГУ я не проверял.

---

## 8. Матрица испытаний (§11) — имя теста, что исполняется, команда, результат

Команда для всех строк: `bash /home/user/runtests.sh` (kotlinc 1.9.24 + `android.jar`-стабы + JUnit 4.13.2; всё — **JVM**, кроме помеченных ⛔).

| # | Требование §11 | Тест(ы) | Что реально исполняется | Результат |
|---|---|---|---|---|
| 1 | A держит lock, B отказ, C/D не входят, после A — ровно один | `Iter54JitConcurrencyTest.twoRealShellsAtTheSameMomentProduceExactlyOneSideEffect`, `refusalOfAnotherInstanceDoesNotBecomeTerminalOfTheFirst` | настоящий `/bin/sh`, временные файлы, барьеры; fake side effect вместо zygote | ✅ |
| 2 | claim delayed/late, новый клик, старый payload поздно | `lateClaimOfOlderIntentNeverCancelsNewerIntent`, `unconfirmedClaimStopsThePayloadAndKeepsIntentPending` | `claimGate` production + скрипт гвардии | ✅ |
| 2b | takeover только по доказанной смерти | `olderIntentTakesOverOnlyAfterNewerProvedFinished` | `_jit_owner_dead`, `CL_ORDER` | ✅ |
| 3 | ON/OFF, zygote только из клика, нет boot-JIT | `JitBootBoundaryTest`, `JitStartZeroWritesTest`, `JitNoPersistTest`, `AvasIndependenceTest` | разбор production-скрипта/цепочек | ✅ (модель), ⛔ zygote на ГУ |
| 4 | delivery upload/run unknown, поздний `.done`, recovery по evidence | `Iter54DeliveryTest` (10) | fake-device по протоколу `readCmd/claimCmd` | 🟡 |
| 5 | TTL: чужой идентичный unmarked не меняется; своя marked ставится/снимается; чужие ссылки не тронуты | `Iter54TtlOwnershipTest` (9), `Iter53TtlTest.legacyUnmarkedChainIsNeitherAdoptedNorChanged` | настоящий `/bin/sh` + подставные `iptables`, argv-сверка | ✅ |
| 6 | нет uplink → появился; v4/v6 ошибки; подтверждённый rollback | `v6FailureRollsBackV4AndLeavesNothingHalfApplied`, `budgetSchedulesRealContinuationAndCoalesces` | скрипт + планировщик продолжения (`TtlRunner.kt:148`) | ✅ (fake) |
| 7 | TTL + CarPlay независимость | `AvasIndependenceTest`, `JitIter*Test` (CarPlay не затрагивается) | отсутствие команд CarPlay в цепочках | 🟡 fake; ⛔ ГУ |
| 8 | Звук: отсутствующий/нечитаемый/битый журнал, crash при миграции | `Iter54SoundTest` (5), `Iter52JournalTest` | `JournalRead`, файлы на диске | ✅ |
| 9 | Звук: отложенный bind, OFF, новый WAV, чужой верхний mount, все цели | `reconcileRequiresEveryTargetNotJustOne`, `mountTopLayerUsesTableOrderWithIdCorroboration`, `Iter53SoundTest` | разбор `mountinfo` + argv | 🟡 (fake mount-таблица) |
| 10 | Логи: одинаковые события, multiprocess+rotation+recovery | `Iter54LogTest` (6), `Iter53LogTest` (10) | реальные файлы, реальный `FileLock`, барьеры | ✅ (JVM), 🟡 (не `fork`) |
| 11 | Окна: событие в каждом межшаговом окне, ABA | `Iter54WindowTest` (4) | 8 потоков × 25 транзакций на production-классе `VentOwnerTx` | ✅ (модель), ⛔ VHAL |
| 12 | UI: самостоятельные ключи, быстрые клики, cold start, intent/apply раздельно | `Iter54ToggleTest` (15), `Iter53UiTest` (13), `Iter52TogglesUiTest` | production `ToolsSnapshotFold`/`ToolsStateCausality` на реальном JSON снимка | 🟡 модель; ⛔ живой рендер |
| 13 | Строка состояния, климат, видимость наружной температуры | `Iter53UiTest.batteryOffKeepsTheSavedOutsideTemperatureChoice`, `HvacDigitalPropsTest`, `FlagVerifyTest` | модель + разбор production-пути | 🟡 (плагин не запускался) |
| 14 | `versionCode=63`, `1.0.63`, `SOURCE_SHA`, release без unit-тестов | `BuildMarkerTest`, `app/build.gradle.kts:78–79`, `app/buildinfo.properties` | разбор файлов сборки | ✅ |
| 15 | JVM tests, сборки, CI на точном HEAD | CI run `43707141785` @ `789f8f6` | `Gradle build (debug)` + `Upload APK` в CI (JDK 21, Android SDK, Flutter AAR) | ✅ CI success; ⛔ `assembleRelease` локально не исполнялся |

**Что локальный harness не компилирует** (Android-зависимости, проверка только по тексту): `ToolsBackend.kt`, `ToolsPanel.kt`, `UiBridge.kt`, `NetworkTtl.kt`, `LockSound.kt`, `JitNetCore.kt`, `AdbClient.kt`, `WindowDoors.kt`. Инварианты для них проверены чтением кода и тестами чистых модулей, которые они вызывают — **не** зелёной компиляцией.

---

## 9. Версия, маркер, передача

- `versionCode = 63`, `versionName = "1.0.63"` (`app/build.gradle.kts:78–79`) — не менялись.
- `SOURCE_SHA` в `app/buildinfo.properties` = `789f8f6` (кодовый коммит итерации); Gradle читает файл, `git rev-parse` в сборке не используется (`app/build.gradle.kts:52,61`).
- `assembleRelease` не зависит от unit-тестов (прогон — отдельной командой `runtests.sh`).
- Изменения между `9f57f5b` (маркер iter53) и `789f8f6`: `8c0f9b1`, `9f76bb1`, `8a54ce6`, `78cc0a4`, `2252175`, `74ebdac`, `d270a2d`, `789f8f6` + этот handoff-коммит.

---

## 10. Честные лог-маркеры (без чувствительных значений)

```
CL_ORDER seen_op=<op> seen_ts_ms=<ms> verdict=free|older_intent|ts_tie|newer_intent|newer_finished
CLAIM_WRITE result=taken|busy|stale|failed reason=<why>
JIT_SEND gate=claimed|op_in_flight|superseded_by_newer_intent|claim_unproven action=proceed|stop
TTL_DELIVERY op=<op> state=unknown evidence=<id> source=<why> action=stop
TTL_PASS continuation source=<why> action=scheduled delay_ms=3000
chain_state=<absent|empty|ours|unproven|collision|unknown> TTL_ROLLBACK rc=… readback=absent
LOCKSOUND_JOURNAL read=<absent|unknown:why|readable ops=N> action=none|stop
MOUNT_ORDER method=mountinfo_order_with_mount_id_corroboration top_guaranteed=only_if_order_and_id_agree
LOG_RECOVER new_merged=N boundary_dup=N deleted=true|false new_locked_kept:<why>
TOGGLE_INTENT key=<key> click_id=<id> want=<b> stored=<b|null> intent=<written|write_failed|unknown> applied=<applied|pending|failed|unknown> note=…
```
Логи пишутся в `getExternalFilesDir(null)/logs/` с fallback `filesDir/logs/`, с разбивкой по датам; `/data/local/tmp/logs/` не используется.

---

## 11. Безопасный план проверки владельцем (на машине, без «лечения» перезагрузкой)

1. **Снять исходное состояние, ничего не меняя:** экспорт логов приложения (`logs/`), `getprop dalvik.vm.usejit`, `settings`/prefs-снимок панели, вывод read-only TTL-сверки (`TTL_VERIFY`/`chain_state`), `mountinfo` по точкам звука. Секреты (SSID/MAC/IP) не публиковать.
2. **TTL:** включить ON при отсутствии uplink — ожидать `сохранено, ждёт применения` (не «выключено»), затем при появлении uplink — `applied`. Проверить, что чужие цепи не изменились (`iptables -S` до/после).
3. **Звук:** OFF → все цели проверены (`LOCKSOUND_RECONCILE … action=…`), чужой верхний mount не снят (`MOUNT_ORDER … top_guaranteed=`).
4. **Тумблеры:** клик по одному ключу не меняет соседние; unknown показывается нейтрально; `TOGGLE_INTENT … note=` соответствует тому, что видно.
5. **JIT — только по решению владельца и только ручным кликом** (`ctl.restart zygote` живёт исключительно в цепочке клика; boot-restore запрещён). Наблюдать: `CLAIM_WRITE`/`JIT_SEND gate=…`, факт интернета проверять **загружаемой страницей/DNS**, а не «association/IP». Повторный JIT-клик **не** является ремонтом сети; перезагрузка ГУ лечением не предлагается.
6. Если интернет пропал — сначала снять логи и `getprop`/`iptables -S`/маршруты, и только потом решать. **Причина не доказана**, поэтому вина TTL не утверждается.

---

## 12. Незакрытые долги (не размечены ✅)

- ⛔ **`assembleRelease` локально не собирался** (нет Gradle-кэша SDK в песочнице); CI собирает **debug** (`build` → `Gradle build (debug)`), release-вариант на этом HEAD не проверялся.
- 🟡 **Живой Android/UI-рендер** (Robolectric/instrumentation) не исполнялся — модель и JSON проверены, listener/render нет.
- ⛔ **Реальное ГУ:** VHAL-события окон, zygote-переходы, mount верхнего слоя, reboot/update-выживание TTL, поведение плагина SystemUI (`hvacExtTemp` при OFF заряда).
- 🟡 **Межпроцессность логов** проверена потоками + реальным `FileLock`, без `fork` двух процессов.
- 🟡 **JIT:** PID reuse/недоступный `/proc` — по аргументам fake-стенда, не на ядре Android.
