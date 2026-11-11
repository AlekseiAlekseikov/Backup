# iter37 — финальная правка JIT, версия 1.0.61 (versionCode 61)

**Ветка:** `fix/v2` · **HEAD с кодом:** `e4f9ce6bb73b887a583ddb21b1efa3fd3e639d78` (`e4f9ce6`)
**Версия:** `versionCode=61`, `versionName="1.0.61"` — не менялись.
**CI (полный, на коммите с кодом):** run `43065985922`, job `130680937719` (`build`) — **SUCCESS**, **257 PASSED / 0 FAILED**, `BUILD SUCCESSFUL in 3m 10s`.
**CI (полный, на итоговом HEAD с документацией):** run `43067834255`, job `130687421984` (`build`) — **SUCCESS**, **257 PASSED / 0 FAILED**, `BUILD SUCCESSFUL in 4m 33s`.
**Локальный прогон (`/home/user/runtests.sh`, JVM: core + адаптеры + стабы):** `OK (142 tests)`.

> Первый прогон CI на этом же коммите (`fc5a36a`, run `43063651378`, job `130672672285`) **упал**: 15 из 17 тестов `JitPersistenceTest` падали с `RuntimeException` — в JVM-тестах `android.util.Log` является заглушкой, а логгер адаптера вызывался внутри решения. Исправлено (`JitNet.kt:36–41`: запись в лог обёрнута так, что невозможность логировать не влияет на решение), повторный прогон на `e4f9ce6` — **257/0**. Этот провал не скрыт и остаётся в истории CI.

**APK не собран здесь:** собирает и подписывает владелец в Android Studio. `SOURCE_SHA` в `app/buildinfo.properties` = `e4f9ce6` (ставится в docs-коммите; `git rev-parse` в сборке не используется). «Точно работает на ГУ» не заявляю — на устройстве ничего не запускалось.

---

## §1. Достоверный снимок Wi-Fi (✅ по коду и тестам, 🟡 по проверке на ГУ)

| Что | Где | Изменение | Тест реального пути |
|---|---|---|---|
| ✅ Неизвестное состояние ⇒ ничего не отправлять | `JitNetCore.kt:489–520` (`click`), `JitNet.kt:230` (`wifiOn`) | `wifiOn()==null` ⇒ `blocked=wifi_unknown`, `commands=0`, тумблер не трогается; `wifi=false` позже не включается (`restoreWifi`, `JitNetCore.kt:747`) | `JitOrchestratorTest.unknownWifiIsBlockedAndNothingIsWritten`, `restoreWithUnreadableWifiDoesNotSendRequest` |
| ✅ `commit=false` + значение в памяти ≠ успех | `JitNet.kt:120–163`, `JitNetCore.kt:143–158` | readback **всех трёх** ключей (`pedidoOk && autoOk && restoreOk`) **и** обязательный `commit==true`; `StoreReport.durable = committed && readback` | `JitPersistenceTest.commitFalseWithValueInMemoryIsNotConfirmed`, `JitOrchestratorTest.allCommitsFalseButValueVisibleInMemoryIsBlocked` |
| ✅ CPS/DPS без ложного `2/2` | `JitNet.kt:106–118` (`stores()`), `:120–163` | отчёт по каждому доступному хранилищу; нет хранилища ⇒ `attempted=0`; одно ⇒ `1/1` | `JitPersistenceTest.missingStoreIsNotTwoOfTwo`, `noStoreAtAllMeansNothingWasWritten`, `JitOrchestratorTest.singleAvailableStoreReportsOneOfOneNotTwoOfTwo` |
| ✅ Учёт **фактического порядка чтения** | `JitNetCore.kt:155–158` (`firstReadable`), `:539–546` | если первое по порядку чтения хранилище не подтвердило запись, после перезапуска продолжение прочитает **прежнее** значение ⇒ `snapshot_not_saved` до любых команд | `JitOrchestratorTest.staleCpsBlocksBecauseFutureReadWouldReturnOldValue`, `cleanupRemovesOnlyInStoresWhereOurWriteWasDurable`; `JitPersistenceTest.partialCommitIsNotUsableForTheFollowUp` |
| ✅ Неоднозначный конфликт блокируется **локально в JIT** | `JitNet.kt:166–196` (`readRestore`), `JitNetCore.kt:539–546` | разные значения одного ключа в CPS и DPS ⇒ `conflicts`, `blocked=snapshot_conflict`; отсутствие ключа во втором хранилище конфликтом **не** считается (чтение даст `null` = «снимка нет») | `JitPersistenceTest.divergedStoresMakeTheSnapshotAmbiguous`, `keyPresentOnlyInFirstStoreIsNotAConflict`, `JitOrchestratorTest.staleDpsAlsoBlocksBecauseSnapshotIsAmbiguous` |
| ✅ Три ключа одной записью, прежнее не удаляется, отказ cleanup виден | `JitNet.kt:120–163,197–215`, `JitNetCore.kt:547–565` | `commit` один на хранилище и на все три ключа; `perf_auto_otimizar` не удаляется никогда; `remove` вызывается **только** для `durableNames()` и логируется `cleanup=pedido:x/y/restore:x/y` | `JitPersistenceTest.autoKeyIsNeverRemovedByBlockedCleanup`, `removeTouchesOnlyRequestedStoresAndLeavesOthersIntact`, `removeFailureIsVisibleAndKeySurvivesRestart`, `JitOrchestratorTest.blockedClickDoesNotErasePreviousUserPreference`, `cleanupOnlyTouchesStoresWhereOurWriteSucceeded` |
| ✅ Legacy `wifi=…,ap=…` и битый снимок | `JitNetCore.kt:88–95,220–230` | `format=legacy_ap_ignored`, AP **не** поднимается; неразобранная строка ⇒ `Restore==null` ⇒ возврат не выполняется | `JitOrchestratorTest.brokenSnapshotIsNotExecuted`, `legacySnapshotIsMigratedAndNeverTurnsWifiOnBlindly`, `JitChainScriptTest.mountStepTouchesOnlyDataApp` |

**Проверено только тестами:** `JitPersistenceTest` (17 тестов) гоняет **настоящий** `JitNet.AndroidStore` на модели `SharedPreferences` с отдельными памятью и «диском» (`JitFake.Prefs`), включая «новый процесс», который читает только «диск». Это **не** Android-интеграция: реальный `SharedPreferences` ГУ, `Prefs2` и поведение ФС устройства не проверялись.

## §2. Старая задача не стирает новый снимок (✅ по коду, 🟡 по полю)

| Что | Где | Изменение | Тест |
|---|---|---|---|
| ✅ Снимок снимается до планирования, после задачи не удаляется | `JitNetCore.kt:629–716` (`onProcessStart`) | `remove()` только **до** `postDelayed`; в задаче повторного `remove` нет — данные нового клика не стираются | `JitOrchestratorTest.oldTaskDoesNotDeleteNewerSnapshotWithSameValue`, `oldTaskDoesNotDeleteNewerSnapshotWithDifferentValue` |
| ✅ Одинаковые значения — не идентификация | там же | после задачи снимок не трактуется как «мой»; удаления по совпадению значения нет вообще | те же тесты (в «одинаковом» варианте Wi-Fi остаётся включённым, поэтому снимок `wifi=1` совпадает со старым) |
| ✅ Отказ удаления обрабатывается честно | `JitNetCore.kt:657–676` | `still_present=true` + `removal_unconfirmed=true`, повторного действия в процессе нет; после смерти процесса старт может вернуть Wi-Fi ещё раз — сказано прямо (без watchdog) | `JitOrchestratorTest.duplicateWithUnconfirmedRemovalStillSchedulesOnce` |
| 🟡 Смерть процесса в 15 с | `JitRef.RESTORE_DELAY_MS=15_000`, `JitNetCore.kt:686–700` | это риск **референса**, отдельного хранилища «в процессе» не вводится; в отчёте не выдаётся за решённое | `JitOrchestratorTest.snapshotRemovedSynchronouslyAndRestoreScheduled15s`, `deathBeforeScheduledTaskLeavesNetworkUntouched` |
| ✅ Частичный отказ планировщика | `JitNetCore.kt:695–705` | `scheduler_failed`, задача снята с учёта, снимок не восстанавливается (ограничение референса, сказано в логе) | `JitOrchestratorTest.schedulerFailureIsReportedHonestly` |

## §3.1. Параллельные клики (✅)

`JitNetCore.kt:489–507` — слот `CLICK_SLOT` (атомарный `compareAndSet`), `finally` освобождает его (`:595`). Второй клик получает `blocked=busy`, **никаких** команд не отправляет и исходный снимок не перезаписывает состоянием после `svc wifi disable`. Сообщение — в существующей строке инструмента (`AppPerformance.kt:1003–1007`, `1019–1023`), новых кнопок нет.
**Таймаут ≠ завершённость:** при `rc=137 ERR_ADB_TIMEOUT` команда могла остаться в очереди транспорта (`AdbClient.execute`), выставляется `chain_undefined` (`JitNetCore.kt:583–592`), намерение не снимается сверкой (`:904–907`).
Тесты: `JitOrchestratorTest.parallelSecondClickIsRejectedAndOriginalSnapshotKept`, `parallelClicksWithOppositeValuesOnlyOneChainStarts`, `timeoutKeepsIntentUntilOutcomeIsDefinite`, `definiteReplyClearsUndefinedState` (барьеры `JitFake.BlockingShell`, позднее исполнение, противоположные значения `want`).

## §3.2. Сверка не удаляет новое намерение (✅)

`JitNetCore.kt:831–915` (`verifyApplied`): до shell-чтения фиксируются `gen0` и наличие `jit_pedido`; под `INTENT_LOCK` перед удалением проверяются **слот клика** и **поколение**; удаление — настоящее `remove(KEY_PEDIDO)` (`:911`), не запись `false`. Пропуски: `click_in_flight`, `pedido_changed`, `chain_undefined`, `property_unreadable` — снимок не снимается.
`getprop` совпал ≠ рестарт zygote: сверка пишет `restart=not_claimed`, вердикт только `ok/not_applied/cache_missing/unknown` (`JitRef.verify`, `JitNetCore.kt:332`).
Панель вызывает сверку при открытии (`ToolsPanel.kt:1178–1186`), watchdog — только при наличии намерения (`AppPerformance.kt:1086–1092`).
Тесты: `verifySkippedWhileOurClickIsInFlight`, `newSameRequestDuringReadIsNotErased`, `clickStartingDuringVerifyBlocksRemoval`, `verifyOkRemovesIntentByRealRemoval`, `verifyNotAppliedWarnsAndRemoves`, `verifyUnknownPropertyKeepsIntent`, `verifyWithoutIntentWritesNothing`, `clickStartingDuringVerifyBlocksRemoval`.

## §3.3. Антидубль — на одну задачу, не на процесс (✅)

`JitNetCore.kt:636–650`: проверка `scheduledTask != null` выполняется **до** разбора снимка (снимок мог быть уже снят первым вызовом) ⇒ `reason=duplicate`; после выполнения задачи (`:715–719`) флаг снимается, поэтому **новый** снимок в том же процессе снова планируется. Ошибки планировщика/выполнения обрабатываются (`:695–705`, `:696–700`).
Тесты: `duplicatePreventionIsPerTaskNotPerProcess`, `duplicateWithUnconfirmedRemovalStillSchedulesOnce`.
🟡 Запуск после рестарта zygote не гарантируется (`START_STICKY`/boot broadcast — не доказательство) — в логах и тексте UI это не заявляется.

## §4. UI (✅ по коду, 🟡 по экрану ГУ)

| Что | Где | Изменение |
|---|---|---|
| ✅ Поколение относительно **самого события клика** | `ToolsPanel.kt:999` (`AppPerformance.markJitClick()` в обработчике нажатия), `AppPerformance.kt:913–916` | поколение растёт синхронно с нажатием, а не позже в фоне; устаревший ответ пропускается целиком (`ToolsPanel.kt:1199–1204`, лог `JIT_UI stale_fact skipped generation_changed`) |
| ✅ Программное обновление не вызывает обработчик | `ToolsPanel.kt:1220–1231` | `setOnCheckedChangeListener(null)` → установка → возврат слушателя; настройка не пишется |
| ✅ `unknown` честно | `ToolsPanel.kt:1223, 1232–1243` | «· состояние неизвестно», текст «неизвестно (getprop не прочитан: причина)»; кнопок и красного текста нет |
| ✅ `setWifiEnabled(true)==false` ≠ «запрос принят» | `JitNetCore.kt:393–400` (`setWifiOutcome`: `ACCEPTED/REFUSED/UNKNOWN/EXCEPTION`), `:747–800` (`restoreWifi`) | в логе и в `WifiRestoreReport` раздельно `requested/outcome/stateAfter/address`, `internet=not_checked`; `isWifiEnabled`/наличие адреса не выдаются за интернет |
| ✅ Сохранены проверка cache по `w0/sa`/`j5.d`, `null` без строк, формула `r7`, настоящее удаление намерения | `JitRef.cacheExecutableFromMaps` (в `JitNetCore.kt`), `JitNetCore.kt:332–345, 911` | без изменений по семантике; `maps=null` ⇒ `unknown`, а не «JIT включён» |

Тесты: `JitUiStateTest.*` (в т.ч. `panelProgrammaticUpdateDoesNotFireClickListener`, `panelRunsVerificationOnOpenAndUpdatesBothWidgets`), `JitOrchestratorTest.restoreAcceptedIsNotProofOfInternet`, `restoreRefusedIsNotCalledAccepted`, `restoreUnknownReturnValueIsUnknownNotAccepted`, `restoreExceptionIsReportedAsException`, `restoreSkipsWhenAlreadyOnOrWasOff` — четыре исхода и запрет включать то, что было выключено.
⚠️ `ToolsPanel.kt`/`AppPerformance.kt`/`VoiceAssistantService.kt` локально **не исполняются** (нужен Android): их корректность подтверждена только компиляцией в CI и текстовыми проверками `JitUiStateTest` — это не прогон на устройстве.

## §5. `umount` + одноразовая read-only диагностика (✅ сбор, ⛔ обратимость)

Шаг `umount` **включён** (`chainScript`, `mount_step=present` в каждой отправке). Обратимость на ГУ **не доказана** — ни безопасность, ни поломка не заявляются.

Сбор (`tools/JitMountDiag.kt`, новый файл; `JitNet.kt:300–330`; вызов `VoiceAssistantService.kt:273`):

- запуск в фоне через `BgWorker` с `assertNotMain`, задержка 20 с после `attachContext`, **без** ожидания канала в рабочем потоке (`AdbClient.isChannelReady()`, `AdbClient.kt:2127`); канал не готов ⇒ `status=root_unavailable`, повторов нет;
- **не чаще одного раза за процесс** (`AtomicBoolean`, `JitNet.kt:325`), без кнопок, без связи с кликом JIT, worker не удерживается;
- тот же транспорт `JitRef.JitShell` (`AndroidShell` → `AdbClient.execute`), поэтому права/mount namespace те же, что у JIT-цепочки — и это сказано в логе (`namespace ... подтверждается только одинаковым транспортным контекстом`);
- разделы: `id` + `readlink /proc/self/ns/mnt` + `readlink /proc/1/ns/mnt` + `/proc/self/uid_map`; строки `/proc/mounts` по условию референса `' /data/app/'` (без цикла `umount`); `mountinfo` для `self` и `/proc/1` с исходными строками; `findmnt` не используется;
- метки `JIT_MOUNT_DIAG_BEGIN` / `_SECTION` / `_DATA` / `_END`, счётчики строк и частей, `parts=i/n`, время, код возврата отдельно от фильтрации, отметка `truncated` (`wc -l` ≠ числу полученных строк), статусы `ok/empty/permission_denied/root_unavailable/timeout/truncated/unknown` по разделам;
- в начале лога предупреждение, что пути `/data/app/` содержат имена приложений владельца (в публичный отчёт их не копируем). **Данных с ГУ пока нет** — лог появится только после обычного запуска приложения владельцем.

Тесты (`JitMountDiagTest`, 13): `commandsAreReadOnlyAndFour` (проверяются **фактически отправленные строки**: нет `umount`, `setprop`, `svc`, `kill`, `reboot`, `ip rule del`, `ndc`, `settings`, записи в файлы), `oneShotPerProcessIsEnforced`, `adapterRunsOffMainThreadAndWithoutWaiting`, `goodReadProducesOkSectionsAndFilteredLines`, `emptyResultOfSuccessfulReadIsEmptyNotDenied`, `permissionDeniedIsNotTreatedAsEmpty`, `timeoutIsReportedAsTimeout`, `unknownFailureIsUnknownNotOk`, `shortOutputIsMarkedTruncated`, `longOutputIsSplitIntoNumberedParts`, `filtersOnlyTheSameConditionAsReference`, `chunksNeverExceedLimit`, `collectionDoesNotDependOnFindmnt`.
⚠️ Транспорт в тестах подставной (`ScriptedShell`); на устройстве сбор не запускался. Пробных `umount`/zygote/reboot не выполнялось, кнопки JIT не нажимались.

---

## Что нельзя подтвердить без ГУ

1. Реальное поведение `Prefs2.cps/dps` на ГУ (в тестах — модель `SharedPreferences`), в т.ч. «после перезапуска прочитается то же».
2. Реальный `WifiManager`: значения `isWifiEnabled`/`setWifiEnabled` и фактическое состояние сети после `svc wifi disable`; наличие/отсутствие интернета не проверялось и не заявляется (`internet=not_checked`).
3. Ответ транспорта `AdbClient` при рестарте zygote: оборвётся ли соединение, останется ли команда в очереди (описано как неопределённость, не как факт).
4. Запустится ли приложение после рестарта zygote, и сработает ли возврат сети через 15 с.
5. Обратимость `umount -l` для `/data/app/` — нужен датированный лог `JIT_MOUNT_DIAG_*` с ГУ.
6. Как выглядит и ведёт себя панель инструментов на экране ГУ (поколение, подписи, тумблер) — проверено только чтением кода и текстовыми тестами.

**Фейки ≠ Android-интеграция ≠ поле.** Все JVM-тесты работают на подставном shell/сети/хранилище; `JitPersistenceTest` — на модели `SharedPreferences` (память/«диск»/«новый процесс»), но тоже без Android. Ни один тест не запускался на ГУ. Непроведённых тестов с неизвестной причиной нет: всё, что не выполнялось, не выполнялось потому, что требует устройства (см. список выше).

## Файлы iteration 37

Код: `tools/JitNetCore.kt` (переписан, API v2), `tools/JitNet.kt` (адаптеры + одноразовая диагностика), `tools/JitMountDiag.kt` (**новый**), `tools/AppPerformance.kt` (поколение, `markJitClick`, `Hint`, `jitApplyWithZygote`), `ui/ToolsPanel.kt` (`gen0`, `markJitClick`, пропуск устаревшего ответа), `tools/AdbClient.kt:2127` (`isChannelReady`), `VoiceAssistantService.kt:273`, `tools/JitBootMark.kt` (правка комментария).
Тесты: `JitPersistenceTest.kt` (**новый**, 17), `JitMountDiagTest.kt` (**новый**, 13), `JitOrchestratorTest.kt` (42), `JitFake.kt` (+`Prefs`), а также сверены с v2: `JitRefTest`, `JitChainScriptTest`, `JitUiStateTest`, `JitNoPersistTest`, `JitStartZeroWritesTest`, `JitLogsCancelTest` (добавлен сброс состояния ядра в `@Before`, чтобы порядок классов не влиял).

## Артефакты и индексы

- Код: `e4f9ce6bb73b887a583ddb21b1efa3fd3e639d78`, `SOURCE_SHA=e4f9ce6`.
- Итоговый HEAD ветки `fix/v2` (код + этот отчёт + WORKLOG + `SOURCE_SHA`): `d1afe1ec087aeacc7da1d81116ddb8c90634ecf3`.
- CI run `43065985922` / job `130680937719`: **PASSED 257 / FAILED 0**; предыдущий прогон `43063651378` / `130672672285` — FAILED (15 падений, причина устранена).
- Локально: `OK (142 tests)` (`/home/user/runtests.sh`).
- Артефакты вне `build/out/dist/target`: этот отчёт `/home/user/REPORT_iter37_jit_1.0.61.md`, копия `docs/agent/REPORT_iter37_jit_1.0.61.md`, блок в `docs/agent/WORKLOG.md`.
