# REPORT iter55 — производственные цепочки: JIT, TTL, звук, логи, окна, UI

Дата: 2026-10-22. Ветка: **`fix/v2`** (только она; `main` не пушился, `--force` не использовался).

## 0. Состояние кода

| Что | Значение |
|---|---|
| HEAD (кодовый, итог) | `4f3ea25` — тесты terminal-identity прямой отправки (§5.1) |
| Кодовые коммиты итерации | `ec2210e` (§4 JIT), `7e982dd` (§8 логи), `89d7f8c` (§6 TTL), `87d5b59` (§5 доставка + §7 звук + §9 UI-состояние), `5e33994` (§9 зависимость строк), `4f3ea25` (§5.1 тесты) |
| `SOURCE_SHA` в `app/buildinfo.properties` | обновлён handoff-коммитом на `4f3ea25` (см. `git diff --name-only <SOURCE_SHA>..HEAD` в сообщении handoff) |
| Версия | `versionCode = 63`, `versionName 1.0.63` (`app/build.gradle.kts:78`), Gradle без `git rev-parse` |
| Рабочее дерево | чистое (всё закоммичено и запушено в `origin/fix/v2`) |
| Локальный прогон JVM | `bash /home/user/runtests.sh` → **`OK (832 tests)`** |
| CI на точный кодовый HEAD | run `43721642690` (`4f3ea25`) — **success** (`Gradle build (debug)` success, `Upload APK` success); CI-тесты в этом workflow не запускаются, `assembleRelease` тесты не запускает |
| CI ранее в итерации | `43719835880` (`5e33994`) success, `43718595968` (`87d5b59`) success |

**Единицы доказательств в отчёте разделены:** «прочитано по исходнику» / «JVM-тест локального
harness (kotlinc 1.9.24, подставные iptables/mount/…)» / «shell-стенд с fake-утилитами» /
«CI debug-сборка (компиляция production)» / «проверено владельцем на ГУ» — последнего в этой
итерации **нет ни по одному пункту**.

---

## 1. §4 JIT — ✅ (кроме проверки на ГУ)

**Дефект до.** Terminal отказа претендента считался terminal владельца; перехват брошенного lock
шёл как `read → prove dead → mv → take` без проверки результата `mv` (TOCTOU); запись claim могла
перезаписать более новое намерение, потому что решение опиралось на `System.currentTimeMillis()`.

**Исправление (production).**
- `JitOwn.kt:339` `_jit_steal_dead` — перехват только того объекта, который проверен; результат
  `mv` проверяется, при замене объекта перехват отказывает (`JitOwn.kt:427`
  `CL_TAKEOVER proof=refused reason=object_replaced`).
- `JitOwn.kt:421`, `:785` `_jit_steal_dead` вызывается из claim под одним lock; логи
  `CL_ORDER_UNDER_LOCK` (`JitOwn.kt:450`), `CL_TAKEOVER proof=owner_dead_exact_inst`
  (`JitOwn.kt:425`), `JIT_LOCK_TAKEOVER` (`:792`).
- `JitOwn.kt:769`–`772` — ранняя сверка `inst` из claim ДО взятия lock (`instance_mismatch`),
  поэтому отказ другого экземпляра не становится terminal владельца.
- Решение о последнем намерении и запись claim — в одной критической секции (`JitOwn.kt:376`
  `claimCmd`), без ветки `newer_finished`.

**Тест, падающий до исправления:** `Iter55JitTest` (5):
`refusalOfAnotherInstanceNeverFreesTheLiveLockForLaterClaimants`,
`concurrentTakeoversOfAnAbandonedLockNeverStealTheFreshLock`,
`claimThatReadTheStateBeforeTheLockYieldsAfterANewerIntentIsWritten`,
`corruptOrUnattributableLockIsNeverTreatedAsDead`,
`terminalOfAnotherInstanceIsNotTheTerminalOfTheLiveOwner`.
Исполняется: shell-стенд (`Iter50Stand`) с подставными утилитами, два «процесса» внутри JVM через
реальные вызовы сгенерированного скрипта. Компилируются `JitOwn.kt`, `JitNetCore.kt` и др.

**Что остаётся непроверенным.** Живой путь ГУ: ручной клик → `ctl.restart zygote` → восстановление
Wi‑Fi `Interceptor` → readback. На устройстве **не выполнялось** (`setenforce 0`, `pm install`,
туннели запрещены) → 🟡 по §4.4.

---

## 2. §5 Доставка — ✅ (JVM + чтение), с оговоркой про upload-supervision

**Дефект до.** 1) `State.RUN_SENT` (ответ транспорта есть) превращался в `Finished` для **всех**
транспортов: пустой/обрезанный ответ без `ERR_ADB_*` объявлялся успешным выполнением скрипта.
2) Маркер завершения не был привязан к попытке: строка `VAPP_DIRECT_DONE rc=…` из stdout самого
payload (эхо, текст ошибки) принималась за terminal.

**Исправление (production).**
- `ScriptDelivery.kt:279` — `directScript` печатает `VAPP_DIRECT_STARTED_<nonce>` и
  `VAPP_DIRECT_DONE_<nonce> rc=$?` (в подоболочке, поэтому `exit N` payload'а не съедает маркер).
- `ScriptDelivery.kt:293` `directResult(name, script, rc, out, nonce)` — `RUN_CONFIRMED` **только
  при ровно одном** маркере этой попытки; иначе `RUN_UNKNOWN` (`contentKind=direct_no_terminal`
  / `direct_none`, `mayHaveRun=true`).
- `ScriptDelivery.kt:183` — `outcome()` больше не даёт `Finished` из `RUN_SENT`:
  `Unknown(why="run_sent_terminal_not_proven")`.
- Вызывающие передают один nonce в скрипт и в разбор: `ScriptDelivery.deliver` (:446) и
  `AdbClient.kt:588` (`executeScriptDetailed`).

**Тест, падающий до исправления:** `Iter55DeliveryTest` (5):
`markerPrintedByPayloadIsNotTerminal`, `twoMarkersOfTheSameAttemptAreNotProof`,
`exactlyOneOwnMarkerIsTerminalAndCarriesItsRc`, `directScriptFramesBothMarkersWithTheAttemptNonce`,
`runSentWithoutTerminalIsUnknownNotFinished`. Компилируются `ScriptDelivery.kt`,
`ScriptChunker.kt`. Старый код на этих данных давал `RUN_CONFIRMED`/`Finished`.
Существующее ожидание в `Iter54DeliveryTest` («прямой `RUN_SENT` = finished») **исправлено**, а не
ослаблено: теперь там требуется `Unknown`.

**Durable evidence (§5.2).** `LockOp.BindOp.evidence` (`LockOp.kt:115`) пишется в журнал;
путь сохраняется при `Unknown` (`LockSound.kt` — ветка `bind_outcome_unknown`), читается
read-only через `ScriptDelivery.evidenceCommand` (`ScriptDelivery.kt:416`) в
`LockSound.readEvidence` (`LockSound.kt:1708`) до мутаций.

**Что остаётся 🟡.** Upload-ветка (`deliver`, :446) по-прежнему опирается на `.started`/`.done`
конкретного пути и **не удаляет** свидетельства при недоказанном прекращении позднего shell
(`.started`/`.done` остаются) — это поведение iter53 сохранено; отдельного теста на «поздний
upload-shell спустя N секунд» нет, есть только разбор свидетельства. `cksum`/`md5` — не
криптодоказательство, `rc` конвейера проверяется (`SCRIPT_CONTENT_OK`), но подмена на устройстве
не моделировалась.

---

## 3. §6 TTL — ✅ (JVM shell-стенд + чтение)

**Дефект до.** Своя цепь определялась по `-m comment` при неподтверждённой поддержке; откат
объявлялся по коду возврата без перечитывания; `-N` проверялся без rc; `unproven` не попадал в
`NOT_OWNED`, поэтому чужая немаркированная цепь могла быть дополнена/удалена.

**Исправление (production).** `TtlScript.kt`
- `:144`–`:151` `TTL_IDENTITY comment_supported=… registry_unknown=… named_ownership=…`;
- `:263` состояние `ours_named` (владение по уникальному имени установки, без `-m comment`);
- `:363` `TTL_OWN family=v4|v6 state=ours_named|unproven action=keep` — состояние каждой семьи
  называется даже когда мутаций нет;
- `:462` отказ **до любых изменений** таблицы: `TTL_TRY action=stop reason=no_safe_identity`;
- `:517`, `:546` проверка rc `-N` и identity перед `-A`, отказ
  `chain_exists_not_ours`;
- `:610` откат только по фактическому readback (`RB_V4`/`RB_JUMPS`/`RB_V4_RC`), `ROLLED_BACK=yes`
  невозможен без чтения.

**Тест, падающий до исправления:** `Iter55TtlTest` (6):
`withoutCommentSupportNamedOwnershipMakesOnAndOffReversible`,
`withoutAnyProvenIdentityTheTableIsNotTouchedAtAll`, `unknownRegistryMeansNoTableChangeEvenWithUniqueNames`,
`failedChainDeleteIsNotReportedAsRolledBack`, `existingForeignChainWithOurNameIsNeverAppendedTo`,
`unreadableRollbackReadbackIsNeverProofOfRollback`. Стенд `Iter50Stand` дополнен ручками
`failAdd`, `failChainDelete`, `failComment` (подставные `iptables`/`ip6tables`). Результат:
`OK (832 tests)`, `Iter53TtlTest.legacyUnmarkedChainIsNeitherAdoptedNorChanged` остаётся зелёным
(утверждение не ослаблено).

**Что остаётся непроверенным.** Реальные `iptables` ГУ/Vendor-таблица: 🟡/⛔. Причина «TTL сломал
интернет» **не заявляется** — не доказана.

---

## 4. §7 Звук — ✅ (JVM + чтение production), ГУ не проверялось

**Дефект до.** `reconcilePendingOps` возвращал `Boolean`, и production-код **игнорировал** его
(`runCatching { … }` без чтения результата): несовместимый bind уходил даже при нечитаемом или
повреждённом журнале и при недоказанном исходе предыдущей операции — старый shell мог
выполниться позже и наложить свой слой поверх нового. `migrateLegacyOp` возвращал `Unit`, ошибки
чтения старого журнала терялись. `broken>0` не мешал объявить «незавершённых операций нет».

**Исправление (production).**
- `LockOp.kt:77` `SoundRecovery{Ready,Pending,Conflict}` (+`wire()`/`mayBind`);
- `LockSound.kt:913` `lastReconcilePendingReason`; `LockSound.kt:1741` `reconcilePendingOps`
  возвращает вердикт; шлюз **перед** bind реально его учитывает: `LockSound.kt:950`
  `LOCKSOUND_BIND DEFERRED` (Pending), `:958` `LOCKSOUND_BIND BLOCKED` (Conflict) — отправки нет,
  намерение владельца сохранено, причина названа;
- `LockSound.kt:1597` `migrateLegacyOp(...): String?` — `legacy_unreadable` /
  `legacy_migration_unconfirmed` → `readOpJournal` отдаёт `Unknown`, а не «пустой журнал»;
- `broken>0` и `JournalRead.Unknown` → `Conflict` (`journal_corrupt:broken=N`,
  `journal_unknown:…`);
- `LockSound.kt:1708` `readEvidence` + `LockOp.kt:48` `parseEvidence` — read-only разрешение
  `Unknown` по `<path>`/`.started`/`.done`; `rc=N` в `.done` = доказанный terminal; скрипт на
  устройстве без terminal ⇒ `LOCKSOUND_RECONCILE action=stop late_shell_possible=…`
  (`LockSound.kt:1840`) ⇒ bind не отправляется;
- `LockOp.BindOp.evidence` (`LockOp.kt:115`) — свидетельство durable в журнале (переживает
  рестарт процесса).

**Тест, падающий до исправления:** `Iter55SoundTest` (7):
`evidencePathSurvivesJournalRoundTrip`, `oldJournalLineWithoutEvidenceStillReads`,
`evidenceParseSeparatesProvenTerminalFromEmptiness`, `onlyReadyAllowsNewBind`,
`brokenJournalLineIsNotAllClear`, `unknownAppliedIsItsOwnStateNotPendingOrOff`,
`dependencyHidesRowButKeepsOwnerChoice`. Компилируются `LockOp.kt`, `ScriptDelivery.kt`,
`Toggles45.kt`. **`LockSound.kt` harness НЕ компилирует** — его правки подтверждены чтением и
CI debug-сборкой (run `43719835880`, success), но не JVM-тестом.

**Последнее намерение.** OFF и смена WAV увеличивают поколение (`bindGen`), поэтому поздний bind
старого поколения отменяется (`LOCKSOUND_BIND_CANCELLED`). Верхний слой по-прежнему определяется
по фактическому `/proc/self/mountinfo` (`root` = наш staging), а не по max ID/порядку/префиксу
(`LockMountScripts.bindScript`/`restoreScript`, проверено чтением и `Iter50MountLayerTest`).

**Остаётся 🟡/⛔.** Реальное восстановление звука после потери root-канала и после стоянки на ГУ не
проверялось.

---

## 5. §8 Логи — ✅ (JVM)

**Дефект до.** Одинаковые граничные записи в `active` и `pending` склеивались в одну при
`recover()`; ошибка `fd.sync()` подавлялась; append/rotation/recovery не были одной
межпроцессной дисциплиной.

**Исправление.** `FileLogCore.kt:210`–`:260`: переносятся ВСЕ записи `.new`, совпадение только
сообщается (`boundary_same=1`), неслитая запись остаётся отдельным файлом
(`new_merge_unsynced_kept_as=<name>`, `:257`), ошибка sync видима в отчёте.

**Тест:** `Iter53LogTest`, `Iter54LogTest` (переписаны под новую семантику, утверждения не
ослаблены) + `Iter51LogOrphanTest`. Исполняется: реальная файловая система в JVM, два
«процесса» (независимые `FileOutputStream`).

**Остаётся 🟡.** Межпроцессный сценарий на Android (два процесса приложения одновременно) не
исполнялся — только два файловых дескриптора в одном процессе.

---

## 6. §9 Окна и UI — окна ✅ (JVM, iter54, не менялись), UI 🟡

**Окна.** `VentOwnerTx.planCloseInstall` (`VentOwnerTx.kt:74`) сохранён: `before → переход →
установка → поколение` — одна критическая секция; все мутации идут через `mutate`/`mutateOwners`
(поколение двигается всегда), поздний readback снимает владельцев только при неизменных
содержимом И поколении (`ventLateReadbackMayDropOwners`), откат — только пока набор равен нашему
(`ventRollbackOwners`). `WindowDoors.kt` — обёртки под тем же lock (`snapshotWithGen`,
`ownersSnapshotWithGen`). Тесты: `Iter54WindowTest`, `Iter50VentTest`, `VentOwnersTest`,
`Iter51VentRollbackTest`. В этой итерации дефекта не найдено, код не менялся.

**UI.** Исправлено: `Toggles45.kt:29` `APPLIED_UNKNOWN` + `LockSound.markUnknown`
(`LockSound.kt:71`) — недоказанный исход звука больше не выглядит ни как выключено, ни как
«ждёт готовности»; `ToolsPanel.kt:1268` печатает нейтрально «состояние неизвестно · настройка
сохранена · причина: …». Зависимость строк: `Toggles45.kt:38` `dependentVisible` (скрываем
только при подтверждённом OFF; неизвестность не прячет) и `ToolsPanel.kt:1129`
`applyBattDependency` — «наружная температура» скрывается вместе с «процентом заряда», выбор
владельца не стирается (`UI_DEPENDENCY … pref_kept=yes`).

**Почему 🟡, а не ✅.** `ToolsPanel.kt`/`ToolsBackend.kt`/`UiBridge.kt` harness не компилирует;
визуальное поведение на живом UI (reorder, быстрые клики, холодный старт) не проверялось — только
чтение кода + CI-сборка + модельные тесты чистых функций (`Toggles45`, `ToolsStateCausality`).

---

## 7. Матрица §10 (14 пунктов)

Команда для всех JVM-строк: `bash /home/user/runtests.sh` (kotlinc 1.9.24, `android.jar`/`carstub`,
JUnit, подставные утилиты). Точечный прогон:
`java -Djava.io.tmpdir=/home/user/tmp/jvm -cp "/home/user/kcout/out:<jars>" org.junit.runner.JUnitCore <class>`.

| № | Проверка | Тест | Что исполнялось | Старый код отличается? | Статус |
|---|---|---|---|---|---|
| 1 | A держит lock, B отказал, C/D входят до конца A | `Iter55JitTest.refusalOfAnotherInstanceNeverFreesTheLiveLockForLaterClaimants` | shell-стенд, реальные скрипты, 3 претендента | да: отказ B освобождал lock | ✅ |
| 2 | Конкурентные takeover брошенного lock, новый lock не удаляется | `Iter55JitTest.concurrentTakeoversOfAnAbandonedLockNeverStealTheFreshLock` | shell-стенд, барьер между проверкой и заменой | да: TOCTOU `mv` | ✅ |
| 3 | Старый claim прочитал до lock, новый завершился | `Iter55JitTest.claimThatReadTheStateBeforeTheLockYieldsAfterANewerIntentIsWritten` | shell-стенд + `CL_ORDER_UNDER_LOCK` | да: `newer_finished` | ✅ |
| 4 | Timeout/late execution, две генерации, скачок часов, restart | `Iter55JitTest.corruptOrUnattributableLockIsNeverTreatedAsDead`, `terminalOfAnotherInstanceIsNotTheTerminalOfTheLiveOwner` | shell-стенд, точная identity `inst/pid/pstart/boot` | да | ✅ (restart процесса — только эмуляция новым `inst`) |
| 5 | JIT ON/OFF, zygote только по клику, boot без JIT, Wi‑Fi отдельно | `JitIter39..44`, `JitBootBoundaryTest`, `AvasIndependenceTest`, `Iter54JitConcurrencyTest` | JVM-модель + чтение кода | частично | 🟡 **на ГУ не проверялось** |
| 6 | Direct без DONE, поддельный marker, upload timeout, durable evidence | `Iter55DeliveryTest` (5), `Iter54DeliveryTest`, `Iter52DeliveryTest`, `Iter53DeliveryTest` | JVM, реальные функции `directScript`/`directResult`/`outcome` | да: `RUN_SENT`→`Finished`, маркер из payload | ✅ (upload-поздний shell — 🟡) |
| 7 | TTL comment unsupported ON→OFF, чужая цепь, rc каждого `-N/-A/-D/-F/-X` | `Iter55TtlTest.withoutCommentSupportNamedOwnershipMakesOnAndOffReversible`, `existingForeignChainWithOurNameIsNeverAppendedTo` | shell-стенд с fake `iptables`/`ip6tables`, `argv` логируется | да | ✅ |
| 8 | `-X` failure не даёт ложный `ROLLED_BACK`, v4/v6 readback unknown | `Iter55TtlTest.failedChainDeleteIsNotReportedAsRolledBack`, `unreadableRollbackReadbackIsNeverProofOfRollback`, `unknownRegistryMeansNoTableChangeEvenWithUniqueNames` | shell-стенд (`failChainDelete`, `failRead`, `registryUnknown`) | да | ✅ |
| 9 | Звук: читаемый/отсутствующий/нечитаемый/битый/legacy журнал, bind не делается | `Iter55SoundTest.brokenJournalLineIsNotAllClear`, `onlyReadyAllowsNewBind`, `oldJournalLineWithoutEvidenceStillReads`, `Iter54SoundTest` | JVM (`LockOp`), `LockSound` — чтение + CI | да | ✅ по модели; сам `LockSound` — 🟡 |
| 10 | Звук: delayed bind после OFF/смены WAV, restart/evidence, чужой mount | `Iter55SoundTest.evidencePathSurvivesJournalRoundTrip`, `evidenceParseSeparatesProvenTerminalFromEmptiness`, `Iter50MountLayerTest`, `Iter53SoundTest` | JVM `LockOp`, shell-стенд mountinfo | да | 🟡 (реальный mount на ГУ не проверялся) |
| 11 | Логи: независимые одинаковые граничные записи, sync failure, multiprocess | `Iter53LogTest`, `Iter54LogTest`, `Iter51LogOrphanTest` | JVM файловая система, два дескриптора | да | ✅ (два процесса Android — 🟡) |
| 12 | Окна: все writers под одним lock, ABA, физическое изменение между планом и действием | `Iter54WindowTest`, `Iter50VentTest`, `VentOwnersTest`, `Iter51VentRollbackTest` | JVM (`VentOwnerTx`), реальные параллельные вызовы | нет изменений этой итерации | ✅ |
| 13 | UI: сохранённые значения, pending vs applied, два клика, cold start | `Iter55SoundTest.unknownAppliedIsItsOwnStateNotPendingOrOff`, `dependencyHidesRowButKeepsOwnerChoice`, `Iter54ToggleTest`, `Iter53UiTest`, `Toggles45Test` | JVM чистые функции | да для новых состояний | 🟡 живой UI не проверялся |
| 14 | 1.0.63 / SOURCE_SHA / release без unit / CI на точный HEAD | — | CI run `43721642690` (HEAD `4f3ea25`), success; `assembleRelease` тесты не запускает | — | ✅ debug-сборка на точном HEAD; JVM-тесты CI не запускает (только локально `OK (832 tests)`) |

### Модули, которые harness НЕ компилирует
`LockSound.kt`, `AdbClient.kt`, `ToolsBackend.kt`, `ToolsPanel.kt`, `UiBridge.kt`,
`NetworkTtl.kt`, `AppPerformance.kt`, `VoiceAssistantService.kt`, `MainActivity.kt`, весь пакет
`ui/` и Flutter-мост. Их правки этой итерации (`LockSound.kt`, `AdbClient.kt`, `ToolsPanel.kt`)
подтверждены: чтением кода + успешной CI debug-сборкой. JVM-тест на `LockSound` невозможен без
Android-рантайма (`Handler`/`SystemClock`/`AdbClient`).

### Недоступные зависимости
Нет полного `android.jar` (только stub без `android.media.AudioAttributes`) → локальная
типизация `LockSound.kt` невозможна; нет Gradle-кэша/Android SDK → `assembleDebug/Release`
локально не запускаются; Robolectric/instrumentation недоступны.

---

## 8. §11 Безопасный план read-only диагностики для владельца

Только чтение, без `setenforce 0`, `pm install`, перезагрузок и туннелей. Команды выполняет
владелец сам; я на ГУ ничего не выполнял.

1. **Сохранение/отображение тумблеров.** Открыть панель, отметить какие тумблеры включены,
   перезапустить приложение (не ГУ), открыть снова. Ожидаемо: сохранённое ON не превращается в
   OFF; при неизвестном применении строка «состояние неизвестно · настройка сохранена».
   Маркеры: `LOCK_SOUND_STATE desired=… applied=… reason=«…»`, `UI_DEPENDENCY owner=hvacBatt …`.
2. **TTL без uplink / после раздачи.** Не трогая раздачу: `ip -4 rule show`, `ip -6 rule show`,
   `ip -4 route show table all` (read-only). Маркеры: `TTL_IDENTITY …`, `TTL_OWN family=… state=…`,
   `TTL_TRY action=stop reason=no_safe_identity|chain_exists_not_ours`, `TTL_APPLY gen=…`,
   `TTL_ROLLBACK_VERIFY …`. Если печатается `no_safe_identity` — это отказ до изменений, а не
   поломка.
3. **CarPlay независимо.** Достаточно факта работы CarPlay без включения TTL; маркер
   независимости — отсутствие в логе TTL-мутаций при активном CarPlay.
4. **Звук ON/OFF и после стоянки.** Клик OFF → ON, затем стоянка. Маркеры:
   `LOCKSOUND_BIND_OUTCOME … outcome=not_sent|unknown|finished`, `LOCKSOUND_BIND DEFERRED|BLOCKED
   … reason=…`, `LOCKSOUND_EVIDENCE op=… started=… done=… terminal=…`,
   `LOCKSOUND_RECONCILE … action=none|defer|stop`.
5. **Ручной JIT — только по решению владельца** (единственная цепочка, где допустим
   `ctl.restart zygote`, и то лишь как следствие ручного клика). Маркеры: `CL_ORDER_UNDER_LOCK`,
   `CL_TAKEOVER proof=owner_dead_exact_inst|refused`, `JIT_LOCK_TAKEOVER`, далее — реальная
   загрузка страницы/DNS (`INTERNET_*`), а не «Wi‑Fi ассоциирован = интернет есть».
6. Логи из `getExternalFilesDir(null)/logs/` (fallback `filesDir/logs/`) сохранять целиком до
   любых действий; IP/MAC/SSID/токены не публиковать.

Риски: любая запись через root-канал меняет состояние ГУ; поэтому шаги 1–4 — только чтение, а
шаг 5 — по явному согласию владельца.

---

## 9. Честные незакрытые риски

- Живой путь ГУ не прогонялся ни по одному блоку (нет разрешения на опасные операции); всё
  «✅» относится к JVM/shell-стенду/чтению кода/CI-сборке.
- Надёжно определить «ручное изменение Wi‑Fi владельцем» и принадлежность старого mount вне
  свидетельства (`.started`/`.done` + mountinfo root) по-прежнему нельзя: при отсутствии
  свидетельства сохраняется `Unknown`/`Conflict`, а не догадка.
- `LockSound.kt`/`AdbClient.kt`/`ToolsPanel.kt` не покрыты JVM-тестами (только компиляция CI).
- CI проверяет только КОМПИЛЯЦИЮ: workflow не запускает тесты, поэтому `OK (832 tests)` —
  локальный результат harness, а не CI. На итоговом `4f3ea25` CI success означает «production и
  тестовый файл компилируются», а не «тесты прошли в CI».
- `Iter52DeliveryTest`/`Iter53DeliveryTest` по-прежнему проверяют upload-инварианты; сценарий
  «поздний upload-shell через минуту после `Unknown`» моделируется только разбором свидетельства.
