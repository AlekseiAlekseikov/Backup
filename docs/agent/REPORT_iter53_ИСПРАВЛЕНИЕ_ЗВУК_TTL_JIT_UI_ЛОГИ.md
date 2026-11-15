# iter53 — сквозное исправление: звук, TTL, JIT, UI, логи

**Ветка:** `fix/v2` · **versionCode 63 / versionName 1.0.63** · **`main` не пушился, `--force` не применялся**
**Код-SHA этой итерации:** `cde61c4` (последний коммит с production-кодом; плюс docs/handoff-коммит, см. §13)
**Прогон JVM-тестов:** `bash runtests.sh` → **`OK (747 tests)`** (лог `/home/user/diag/run53_57.log`)

---

## 0. Что было базой и что исправлено по блокам

| Блок | Коммит | Итог локального прогона |
|---|---|---|
| база iter52 | `01f684f` | `OK (661)` |
| §4–§5 JIT (claim/единицы/TOCTOU/takeover) | `52a3852`, `51aa7c0` | `OK (677)`, `OK (683)` |
| §6 доставка скриптов (типизированный результат) | `d5ef133` | `OK (691)` |
| §7 звук замка (сверка по всем целям, миграция журнала) | `2b2ae31` | `OK (704)` |
| §8 TTL (маркер владения, откат по факту, продолжение) | `0dad31d` | `OK (714)` |
| §9 UI (причинная свежесть снимка) | `89e8c3e` | `OK (733)` |
| §10 логи/окна (Liveness, резервирование имён, `.new`) | `cde61c4` | `OK (746)` → `OK (747)` |
| §9.2 U3-инвариант (выбор наружной не стирается) | в docs/handoff | `OK (747)` |

Тесты идут **отдельной** командой; `assembleDebug/Release` к тестам **не привязаны** (требование владельца, iter42 §6 — привязка не возвращалась).

---

## 1. §8.1 — TTL: ДОКАЗУЕМОЕ ВЛАДЕНИЕ ЦЕПОЧКОЙ

**Было (дефект).** «Своя» цепочка определялась по имени + содержимому правила. Любая чужая цепочка с тем же именем и тем же правилом (`-j TTL --ttl-set 65`) считалась нашей и **удалялась**; одновременно требование «ровно одна ссылка» превращало в `collision` любую цепочку с чужим `-j`/`-g`, из-за чего требование «не удалять чужое, но и своё назвать» не выполнялось вовсе (`TTL_REF_KEEP`/`TTL_REF_FOREIGN` не печатались).

**Стало (production, `app/src/main/java/com/example/voiceapp3/tools/TtlScript.kt`).**
- `OWN_TAG="voiceapp3:ttl:<gen>"`; поддержка `-m comment` **проверяется** пробным правилом в `VAPP_TTLPROBE` и сразу убирается: `OWN_MARKER self=… probe=ok|unsupported|unavailable`, `COMMENT_OK`, `MARK_ARGS`.
- `chain_state` → `absent | empty | ours | ours_unmarked | legacy_unmarked | collision | unknown`:
  * `ours` — в правиле есть метка владения и тело совпадает с ожидаемым;
  * `legacy_unmarked` — метки нет, тело совпадает байт в байт, **маркер поддержан** → миграция (`TTL_MIGRATE family=… from=legacy_unmarked to=marked`);
  * `ours_unmarked` — метки нет, но маркер **не поддержан** ядром (иного доказуемого вида нашего состояния не существует): правило работает, но не пересоздаётся каждый проход (`TTL_KEEP`), иначе TTL «мигал» бы на каждом сетевом событии;
  * `collision` — в цепи чужое правило: не чистим, не удаляем, `TTL_OWN … reason=chain_content_not_ours`.
- Владение **правилом** и **чужие ссылки** разделены: `ref_other_count` (всё, кроме ровно `POSTROUTING -o <uplink> -j <OUR>`) и `ref_count`. Чужие ссылки (`-j`, `-g` из других цепей) печатаются `TTL_REF_KEEP` и `TTL_REF_FOREIGN`, а цепь **не чистится и не удаляется**; OFF честно `TTL_OFF result=PARTIAL reason=referenced_elsewhere action=keep`, итог `CHECK_OWNER`/`REF_ELSEWHERE`.
- `TTL_ADD … marker=1|0` — видно, помечено ли реально созданное правило.

**Тесты** (`Iter53TtlTest`): `legacyUnmarkedChainIsMigratedNotBlindlyDeleted`, `ourChainReferencedByAnotherChainIsKeptAndReferenceNamed`, `gotoFromPostroutingIsForeignReferenceAndBlocksDeletion`, `quotedSpecIsRefusedInsteadOfBlindDelete`, `readableFailureIsUnknownNotAbsent`. Стенд — настоящий `/bin/sh` + подставные `iptables`/`ip6tables` (`Iter50Stand.kt`), argv сверяется по каждому аргументу.

## 2. §8.2 — TTL: ОТКАТ ПО ФАКТУ ИЗМЕНЕНИЯ (T2)

**Было.** При `TTL_STOP=1` (v6 отказала после того, как v4 уже применена) значения `rc_*` обнулялись, блок отката пропускался целиком → **v4 оставалась изменённой**, хотя итог назывался отказом.

**Стало.** `TOUCHED_v4/TOUCHED_v6` фиксируют РЕАЛЬНОЕ изменение таблицы; откат решается по ним: `TTL_TRY action=stop reason=partial_after_stop v4=… v6=…`, затем снятие **обеих** семей в порядке «точный переход → `-F` → `-X`», `TTL_ROLLBACK family=v4|v6 jump_del_rc=… chain_flush_rc=… chain_del_rc=…`.
**Тест:** `v6FailureRollsBackV4AndLeavesNothingHalfApplied` — после отказа v6 таблица v4 пуста.

## 3. §8.3 — TTL: РЕАЛЬНОЕ ПРОДОЛЖЕНИЕ ПОСЛЕ БЮДЖЕТА (T3)

**Было.** `TTL_PASS stop reason=budget … action=pending_until_next_trigger` — обещание «продолжим на следующем поводе», которого в стабильной сети могло не быть вовсе.

**Стало (`TtlRunner.kt`).** `armContinuation(source)` + внедряемый `continuationScheduler`; `continuationArmed` (AtomicBoolean) даёт **coalescing**: пока задача запланирована, новые поводы не создают вторую; `CONTINUATION_DELAY_MS = 3_000`; лог `TTL_PASS stop … action=pending_continuation continuation=…` либо честный `action=pending_no_scheduler`, когда планировщик не внедрён; `continuationsRun` — счётчик реально исполненных продолжений.
**Тесты:** `budgetSchedulesRealContinuationAndCoalesces` (ровно одна задача, она реально доходит до транспорта), `budgetWithoutSchedulerIsReportedHonestly`, `budgetStopNeverClaimsApplied` (ни в одной строке бюджета нет `confirmed=true`).

## 4. §9.1 — UI: ПРИЧИННАЯ СВЕЖЕСТЬ СНИМКА (U1)

**Было (iter52 §8.2).** «Два несогласных снимка ⇒ показываем факт». Количество ответов не доказывает, что снимок видел нашу запись: два ответа, посчитанные ДО клика, новее от количества не становятся.

**Стало (production).**
```
click(key, value, clickId) → ToolsBackend.applyFromUi(key, value, clickId)
        → WriteAck{key, clickId, ok, rev = writeRevision после ЗАВЕРШЕНИЯ записи}
снимок: rev (ревизия ДО чтения состояния) + $acks{key: {clickId, ok, rev}}
ToolsStateCausality.decide: KEEP_CLICK | CLEAR_CONFIRMED | SNAPSHOT_CONTRADICTS_WRITE |
                            WRITE_FAILED | UNCONFIRMED | APPLY_SNAPSHOT
```
- `snapshotRev < ack.rev` или подтверждения нет → **держим выбор владельца** (`TOGGLE_HOLD … action=keep_click`), сколько бы снимков ни пришло.
- `snapshotRev >= ack.rev` + `ok` + значение совпало → `TOGGLE_CONFIRMED`.
- `snapshotRev >= ack.rev` + значение другое → `TOGGLE_CONFLICT` (это **факт**, а не гонка), подпись «не применено (сохранено другое значение)».
- `ack.ok=false` → `TOGGLE_WRITE_FAILED`, подпись «не применено (запись не удалась)» — без ложного успеха.
- нет подтверждения дольше `UNCONFIRMED_AFTER_MS=8000` → `TOGGLE_UNCONFIRMED`, подпись «запись не подтверждена».
- `unknown` в снимке не отменяет клик ни в одной ветке.
- Ревизия завершения записи растёт **отдельно** от ревизии начала (`writeRevision.incrementAndGet()` в начале `set()` и в `applyFromUi(…, clickId)` после), а снимок берёт ревизию **до** чтения — иначе гонка могла бы «подтвердить» устаревшие значения.
- WebView-путь без `clickId` ничего не подтверждает (`clickId = -1`).

**Где:** `ui/ToolsPanel.kt:163–235` (разбор снимка и решения по каждому ключу), `ui/ToolsPanel.kt:1130–1190` (отрисовка/клик), `ui/UiBridge.kt:855–925` (`Snapshot`, `attachCausality`, `rev`, `$acks`), `ui/UiBridge.kt:486–520` (`clickId` из `settings.change`), `tools/ToolsBackend.kt:1009–1040` (запись + ack), `tools/ToolsStateCausality.kt` (модель, `ToolsSnapshotFold` — разбор настоящего JSON, его зовёт панель).

**Тесты:** `Iter53UiTest` (14 кейсов: снимок до клика многократно, unknown, отказ записи, обратный порядок ответов, быстрый ON/OFF, соседние ключи не меняются от чужого клика, холодный старт/process death, каждый реальный ключ отдельной строкой, инварианты bridge/панели) + переписанный `Iter52TogglesUiTest` (причинная модель вместо счётчика).

## 5. §9.2 — все реальные переключатели панели (отдельной строкой)

Писатель у всех один — `ToolsBackend.set(toggle, value) → Prefs2.putBooleanEx(appContext, toggle.pref, value)`; читатель снимка — `ToolsBackend.state()` через `Prefs2.readBoolean` (согласование CPS/DPS) с трёхзначным `<key>State`; позиция в UI — `Toggles45.switchPosition(triState, lastKnown, def)` (`unknown` ≠ OFF, последнее известное — `Prefs2 tools_lastknown_<key>`).

| UI key | storage (pref) | default | side effect | истинность при unknown | тесты |
|---|---|---|---|---|---|
| `imm` Полный экран | `app_immersive_mode_enabled` | true | оконные флаги (`applyImmersiveSetting` + readback) | «не подтверждено» | `Iter45/46`, `Iter51TogglesTest` |
| `wake` Запуск при пробуждении | `app_launch_on_wake_enabled` | false | приёмник пробуждения | «не прочитано» | `ToggleNoLieTest` |
| `cb` Полоса климата | `app_climate_bar_enabled` | false | панель климата | «не прочитано» | `ToggleNoLieTest` |
| `proj` CarPlay / Android Auto | `app_projection_beta_enabled` | false | `setProjectionBetaSetting` + `projectionBetaState()` | PARTIAL-шаг, не «успех» | `JitUiStateTest`, `StatusIconContractTest` |
| `follow` Следовать теме ГУ | `app_theme_follow_hu_enabled` | true | тема шелла | «не прочитано» | `Toggles45Test` |
| `remember` Запоминать яркость по фарам | `ScreenBrightness.PREF_REMEMBER` | false | восстановление яркости | «не прочитано» | `Iter50TogglePositionTest` |
| `notifBatt` Батарея | `StatusIcons.PREF_BATTERY` | false | строка состояния | «не прочитано» | `StatusIconContractTest` |
| `notifTemp` Наружная t° | `StatusIcons.PREF_EXT_TEMP` | false | строка состояния | «не прочитано» | `StatusIconContractTest` |
| `notifWifi` Wi‑Fi | `StatusIcons.PREF_WIFI` | false | строка состояния | «не прочитано» | `StatusIconContractTest` |
| `hvacBatt` Процент заряда | `hvacdigital_batt` | false | `setprop persist.sys.hvacdigital.batt` | «не прочитано» | `FlagVerifyTest`, `ToggleNoLieTest` |
| `hvacTemp` Цифровой климат | `hvacdigital_temp` | false | пишется **ФЛАГ** `0|1`, не уставка | «не прочитано» | `HvacVerdictTest` |
| `hvacExtTemp` Цифровая наружная | `hvacdigital_temp_ext` | false | `setprop persist.sys.hvacdigital.exttemp` | «не прочитано» | `Iter53UiTest` (U3-инвариант) |
| `lockSound` Звук замка | `LockSound.PREF_LOCK_SOUND_ENABLED` | false | монтирование/сверка целей (§7) | «не прочитано» | `Iter53SoundTest` |
| `windowDriverOnDoor` Стекло водителя | `WindowDoors.PREF_WINDOW_DRIVER_ON_DOOR` | false | VHAL `setIntProperty` | «не прочитано» | `VentOwnersTest`, `Iter51VentRollbackTest` |
| `windowOppositeOnDoor` Противоположное | `WindowDoors.PREF_WINDOW_OPPOSITE_ON_DOOR` | false | VHAL | «не прочитано» | те же |
| `ttl65` Фиксация TTL 65 | `NetworkTtl.PREF_TTL_65_ACTIVE` | false | iptables/ip6tables (§8) | «не прочитано» | `Iter50TtlArgvTest`, `Iter52TtlTest`, `Iter53TtlTest` |
| `perfVideo` Оптимизация видео | `AppPerformance.PREF_PERF_VIDEO` | false | props/OVR | «не прочитано» | `JitUiStateTest` |
| `perfJit` JIT | `AppPerformance.PREF_PERF_AUTO_OPTIMIZE` | false | цепочка JIT (§4–§5) | «состояние неизвестно» | `JitIter*`, `Iter51JitOwnershipTest` |
| `unlockSound` Звук открытия дверей | `LockSound.PREF_UNLOCK_SOUND_ENABLED` | false | отдельный путь звука открытия | «не прочитано» | `Iter53SoundTest` |
| `gu` Тема ГУ (выбор) | `ui_head_unit_theme_mode` | light | тема плагина/шелла | «не прочитано» | `Toggles45Test` |
| `theme` Тема приложения (выбор) | `TelemetryPrefsMirror.KEY_SHELL_THEME` | sepia | палитра | «не прочитано» | `Toggles45Test` |

Числовые настройки (яркость `bright`, время заставки `rest`, «не гасить ГУ») идут иными ключами и в клик-модель `clickId` не входят (другой путь — слайдер/кнопка); их состояние читается тем же снимком отдельными ключами.

**U3 (заряд OFF скрывает наружную, выбор сохраняется).** В приложении выбор наружной **не стирается**: запись тумблера меняет только свой pref (`Prefs2.putBooleanEx(appContext, toggle.pref, value)`), сброса `hvacdigital_temp_ext` при выключении заряда нет ни в backend, ни в панели (проверяется `Iter53UiTest.batteryOffKeepsTheSavedOutsideTemperatureChoice`). Скрытие наружной в слоте — правило **плагина** (Flyme SystemUI), визуально на ГУ **не проверялось** (⛔, см. §12 U3).

## 6. §10.1 — ЛОГИ: ТРИ СОСТОЯНИЯ ЖИВОСТИ (L1)

`FileLogCore.kt:474–545`:
- `enum class Liveness { LIVE_SAME_PROCESS, DEAD_OR_DIFFERENT_PROCESS, UNKNOWN }`;
- доступность `/proc` доказывается чтением `self/stat` (`procAccessible()`), а не наличием каталога;
- `DEAD` — только доказательство: другой `boot_id`, другой `starttime` (PID переиспользован) или отсутствие процесса при **доступном** `/proc`;
- `readStartTime()==null` больше **не** значит «жив»: это `UNKNOWN`;
- старый числовой тег без идентичности: смерть по `starttime` недоказуема (в теге его нет) → `UNKNOWN`, если процесс существует; молчание > 30 минут ≠ смерть;
- `UNKNOWN` не забирается, но и не теряется: файл остаётся, факт пишется в индекс `<журнал>.orphans` (`ts=… pid=… liveness=unknown action=defer reason=proc_unavailable|owner_unproven`) и пересканируется при каждом слиянии — как только смерть доказана, строки забираются штатным путём.

**Тесты:** `Iter53LogTest` (`unreadableProcIsUnknownNotAliveNotDead`, `identityProvesBothDirections`, `unknownOwnerIsDeferredToIndexNotLost`, `deferredFileIsRecoveredAsSoonAsDeathIsProven`), обновлённые `Iter51LogOrphanTest.silentOwnerIsNotDeclaredDeadByAge`/`reusedPidWithDifferentStartTimeIsDeadAndRecovered`, `Iter52LogIdentityTest`.

## 7. §10.2 — ИМЕНА, LOCK, `.new/.1` (L2)

- **Резервирование имени work-файла** (`uniqueWork`, `FileLogCore.kt:478–500`): `createNewFile()` (до 64 попыток) вместо вычисления строки — совпадение с файлом прошлой инкарнации больше не приводит к молчаливой перезаписи при `renameTo`. `reserveWorkForTest` — тестовый доступ.
- **Lock:** файл лока никогда не удаляется; его существование проверяется **после** захвата (`lock_file_vanished_after_acquire`), т.к. проверка «до» ничего не гарантирует (другой inode).
- **`.new/.1` по точкам падения:** (а) `.new` записан, активный ещё не переименован — следующая ротация дописывает в непустой `.new` без дублей (iter48–50, сохранено); (б) активный переименован в `.1`, `.new` не повышен — активного нет, `.new` повышается на следующей записи; (в) **закрытая в iter53 дыра**: `.new` записан, активный жив, ротации больше не было — `recoverPendingNew` переносит строки построчно с дедупликацией по хвосту активного файла и снимает `.new` только после успешного переноса. Восстановление выполняется в контексте `drainSpill` (восстановление журнала), а не в горячем пути записи — семантика горячей ротации iter48–50 не менялась.
**Тесты:** `Iter53LogTest` (`workNamesAreReservedAtomicallyAndNeverOverwrite`, `reservedWorkFileIsCreatedBeforeRename`, `lockFileIsStableAndNeverDeleted`, `newPendingWithLiveActiveIsMergedWithoutDuplicates`, `alreadyWrittenLinesAreNotDuplicated`, `missingActivePromotesNew`, `previousPartIsNotLostWhenRotationIsInterrupted`).

## 8. §10.3 — ОКНА (W1): проверено, изменений не потребовалось

Одна критическая секция и CAS по `generation` уже реализованы в iter50/iter51: `ventRollbackOwners(now, ownersAfter, before, ventGen.get(), genAtInstall)` и `ventLateReadbackMayDropOwners(now, ownersAtAttempt, ventGen.get(), genAtAttempt)` вызываются **внутри** `mutateOwners` (одним lock с мутацией), снимок «набор + поколение» берётся одним lock (`ownersSnapshotWithGen`), ABA (A→B→A) отсекается поколением, поздний readback не снимает живую запись, команда движения не повторяется (`physical_failure=not_claimed action=none`). Покрыто `Iter51VentRollbackTest` (`abaWithSameContentButNewerGenerationIsNotRolledBack`, `rollbackDoesNotEraseOwnerAddedMeanwhile`, `productionRollbackGoesThroughSingleMutationPoint`) и `VentOwnersTest`. Изменений в iter53 здесь **нет** — это ревизия, а не правка.

---

## 9. Матрица приёмки §12 (полная)

Обозначения: ✅ — проверено в этой среде (JVM-тесты на настоящем `/bin/sh`, подставные `iptables`, подставной `/proc`); 🟡 — проверено частично (код/инвариант, но не живая среда); ⛔ — без ГУ/устройства непроверяемо; ❌ — не сделано.

| ID | Проверка | Что сделано и чем доказано | Статус |
|---|---|---|---|
| **J1** | `readCmd` → stdout → production claim parser → Wi-Fi guard | `JitOwn.parseClaim/classify` + `claimAllowsRestore` на пути `JitNet → runScheduledRestore → restoreWifi` (iter52 §7, сохранено); чужие/unknown claim не разрешают restore (`CS_STATUS`, `CLAIM_VALUE`) | ✅ (JVM), живой ADB ⛔ |
| **J2** | владелец после клика меняет Wi‑Fi | «неизвестность честна»: `internet=not_checked`, поздние снимки не перетирают клик (§9.1), собственный claim разрешает обычное безопасное продолжение | ✅ (JVM) / живой ⛔ |
| **J3** | старый payload прочитал claim, потом новый выполнился | TOCTOU закрыт: claim пишется и перечитывается **под тем же** device-lock (`51aa7c0`), старый payload не запускает устаревший side effect (`Iter52JitOwnTest`) | ✅ (JVM) |
| **J4** | старая операция STARTED > 180 с | takeover **только** по доказанной смерти владельца (`pid+pstart+boot` или свой `END`); время доказательством не является (`52a3852`) | ✅ (JVM) |
| **J5** | завершение/отказ/потеря stdout/crash/старые единицы | `ts_ms`/`ts_s` с проверкой диапазона (`946_684_800_000..4_102_444_800_000`, `0`=неизвестно), фазовая модель и путь следующего ручного клика (`restoreDecision`, `parseTerminal` по `op=<id>`) | ✅ (JVM) |
| **D1** | потеря ответа до/после запуска | `State = NOT_SENT/UPLOAD_INCOMPLETE/RUN_SENT/RUN_CONFIRMED/RUN_UNKNOWN/TOO_LARGE`; при `RUN_UNKNOWN` evidence (`.sh`/`.done`) не уничтожается (`d5ef133`, `Iter53DeliveryTest`) | ✅ (JVM) |
| **D2** | отказ `rm`, одинаковая длина/другие байты, нет `cksum` | `SCRIPT_BYTES=`+`SCRIPT_CONTENT_KIND=`, сверка `cksum`→`md5sum`→`od|awk`, нет инструментов → `exit 97`; «непроверенный скрипт не выдаётся за проверенный» | ✅ (JVM) |
| **S1** | две цели, подтверждена одна, rc ошибочный/вывод обрезан | сверка по КАЖДОЙ ожидаемой цели: `STOCK_RESTORED` только при ровно одном `STOCK_OK` на цель без missing/extra/дублей/`UMOUNT_DETACHED`; иначе `UNPROVEN` (`2b2ae31`) | ✅ (JVM) |
| **S2** | старый `locksound_op.txt`, crash на миграции | идемпотентная crash-safe миграция (append+verify → rename), `JournalRead{NotFound,Parsed,Unknown}`; повреждение = неизвестность, а не «операций не было» | ✅ (JVM) |
| **S3** | mount после timeout / OFF или новый WAV в полёте | reconciliation по всем целям + rc; чужое не снимается (`FOREIGN_LEFT`), `OFF` без root → pending → retry по root-ready | ✅ (JVM) |
| **S4** | неизвестный верх, несортированная таблица, чужой слой, namespace/read error | ownership mount, порядок операций, отказ от `umount` без доказательства владения, `UNPROVEN` вместо ложного успеха | ✅ (JVM) |
| **T1** | одноимённая чужая цепь / ссылка `-j`/`-g` | маркер владения + `TTL_REF_KEEP`, чужая цепь = `collision` (не трогаем), `-g`/`-j` из других цепей не дают удалить цепь | ✅ (JVM+shell) |
| **T2** | v4 применился, v6 ошибка/обрезанный вывод | `TOUCHED_*` + `partial_after_stop` + откат обеих семей, конкретный остаток в `TTL_ROLLBACK`/`TTL_CHECK` | ✅ (JVM+shell) |
| **T3** | выбор у границы бюджета runner | планировщик продолжения + coalescing, `pending_no_scheduler` без планировщика; случайного нового события не требуется | ✅ (JVM) |
| **U1** | два старых snapshot и unknown после клика | причинная модель `clickId`+`rev`+`$acks`; количество ответов ничего не доказывает (§9.1) | ✅ (JVM) |
| **U2** | все реальные тумблеры, холодный старт/один клик | таблица §5 (21 ключ отдельными строками), `decideAll` по каждому ключу, соседние ключи не меняются (`oneClickDoesNotChangeNeighbourKeys`) | ✅ (JVM) / живой рендер ⛔ |
| **U3** | заряд OFF, цифровая наружная была ON | запись меняет только свой pref, сброса наружной нет (`batteryOffKeepsTheSavedOutsideTemperatureChoice`); скрытие в слоте — правило плагина | 🟡 (код) / ГУ ⛔ |
| **L1** | старый тихий живой PID / новый умерший / недоступный proc | `Liveness` три состояния, отложенные в `<журнал>.orphans`, доказанный orphan восстанавливается | ✅ (JVM+подставной `/proc`) |
| **L2** | collision work, два процесса, crash rotation/read error | атомарное резервирование имени, стабильный lock с проверкой после захвата, восстановление `.new/.1` во всех точках; пределы дублей названы (`dup_skipped=`) | ✅ (JVM; межпроцессная корректность на f2fs/ext4 ГУ ⛔) |
| **W1** | событие между before/install/snapshot и ABA | CAS по generation в одной критической секции (iter50/51, ревизия iter53), новый владелец не стирается, повторной команды движения нет | ✅ (JVM) / VHAL ⛔ |
| **V1** | `SOURCE_SHA` и CI | `SOURCE_SHA` в `app/buildinfo.properties` = код-SHA (см. §13), Gradle без `git rev-parse`; тесты и сборка — разными шагами | 🟡 (CI на финальном SHA — см. §13) |

---

## 10. §11 — AVAS, плагин, климат

- **AVAS независим от JIT:** `AvasIndependenceTest` (86 строк) в прогоне, зелёный; в §4–§10 AVAS не затрагивался, возврат сохранённых mode/sound type происходит только в ранее согласованных сценариях; ничего не мутится, `audioserver` и звуковые файлы не трогаются.
- **Процесс плагина не убивается:** новых `kill`/`force-stop` для `com.flyme.auto.systemuiplugin` нет (grep по `kill|force-stop` в связке с плагином — пусто).
- **Цифровой климат:** в `persist.sys.hvacdigital.temp` уходит **ФЛАГ** `0|1`, не уставка; `+12°` — заглушка. Наружная: `AC_AMBIENT_TEMP` (0x2140A377, area 0) → `(raw−80)/2`, fallback `ENV_OUTSIDE` (`OutsideTemp.kt`).
- **Не переносилось:** AP ГУ, `CarplayMonitor`, `connadaptor_modo` — изменений нет.

---

## 11. §13 — порядок работ, тесты, CI

1. **База и исходные ошибки** зафиксированы до правок (лог падений iter52/iter53: `run53_24…27`, `ttl.log`); там, где регрессию нельзя было воспроизвести локально (Android-зависимые файлы), это сказано прямо (§12 «⛔»).
2. **Production-изменения по блокам** с локальными тестами после каждого блока (см. таблицу §0).
3. **Вторая независимая самопроверка:** каждый новый helper найден через `rg` вместе со всеми production-вызовами —
   - `ToolsStateCausality.decide` → единственный вызов в `ToolsPanel.onToolsState` (через `ToolsSnapshotFold.decideAll`) + тесты;
   - `recoverPendingNew` → `drainSpill` + тесты; в горячем `writeUnderLock` вызова **нет** (специально);
   - `livenessOf` → `orphanFilesOf` (и совместимый `pidLooksAlive`);
   - `uniqueWork` → `mergeWork`/orphan-путь; `reserveWorkForTest` — только тест;
   - `armContinuation` → ветка `reason=budget` в `drainLoop`;
   - `TTL_MIGRATE`/`ours_unmarked`/`KEEP_V4|V6` → `chain_state` + `drop_family` + apply-путь.
   Таймауты и «воображаемое позднее исполнение» проверены тестами на обратный порядок ответов и на поздний readback окон.
4. **Тесты отдельно:** `bash runtests.sh` → `OK (747 tests)`. **`assembleRelease` отдельно:** в этой среде нет Android SDK (в `toolchains/android-sdk` только `LICENSE/NOTICE/README`), JDK 17 — заглушки, поэтому Gradle-сборка локально **не выполнялась**; сборку компилирует CI `Build APK` (`assembleDebug`) на точном финальном SHA — см. §13. Заявить локальный `assembleRelease` было бы неправдой.
5. **CI:** workflow `Build APK` (`.github/workflows/build.yml`) на push в `fix/**`: шаги Flutter AAR → Android SDK → `./gradlew --no-daemon --stacktrace assembleDebug` → upload APK. **Шага тестов в workflow нет** (у токена нет scope `workflow`, добавлять его в этом задании запрещено), поэтому авторитетное число тестов — это локальный прогон выше, а CI доказывает **компиляцию** всех production-файлов (в т.ч. `ToolsPanel.kt`/`UiBridge.kt`, которые локальный JVM-стенд не компилирует).
6. **Отчёт и WORKLOG** — этот файл и `docs/agent/WORKLOG.md`.
7. **Неудачные/непроверенные сценарии** перечислены явно: §12 (⛔/🟡) и §13 ниже.
8. **Безопасная проверка владельцем** — §14 ниже.

### Ограничения среды (честно)
- Нет ГУ/устройства: слышимость звука, реальный интернет/CarPlay, TTL на проводе, JIT-компиляция и `ctl.restart zygote`, VHAL окон, рендер WebView/панели, слот «заряд/наружная» в SystemUI-плагине — **не проверялись**.
- Локальный JVM-стенд не компилирует Android-зависимые файлы (`ToolsPanel.kt`, `UiBridge.kt`, `ToolsBackend.kt`, `LockSound.kt`, `JitNet.kt`, `WindowDoors.kt`, `AdbClient.kt`): их проверяет только CI.
- Межпроцессная корректность lock/rotation проверена на JVM-потоках и настоящей ФС; поведение f2fs/ext4 ГУ при одновременной записи двух процессов — **не доказано**.

---

## 12. Точные SHA / версия / порядок коммитов

```
base (iter52)        01f684f
§4                   52a3852
§5                   51aa7c0
§6                   d5ef133
§7                   2b2ae31
§8                   0dad31d
§9                   89e8c3e
§10                  cde61c4
handoff (docs+marker) см. коммит с этим отчётом и SOURCE_SHA=<код-SHA>
```
`versionCode = 63`, `versionName = "1.0.63"`, `app/buildinfo.properties: SOURCE_SHA=<код-SHA>` (Gradle читает маркер, `git rev-parse` не используется).

---

## 13. Что осталось непроверенным и что делать дальше

| Что | Причина | Следующий шаг |
|---|---|---|
| CI на финальном SHA | запускается push'ем handoff-коммита | проверить run `Build APK` на этом SHA, job `build`, фактический шаг `Gradle build (debug)` |
| `assembleRelease` локально | нет Android SDK/JDK 17 в песочнице | выполнять у владельца/в CI; тесты к сборке не привязывать |
| TTL на проводе | нужен ГУ и `tcpdump` | после включения тумблера — `TTL_CHECK real=…` и замер на проводе |
| Слышимость звука замка/открытия | нужен ГУ и ухо | включить оба тумблера, послушать, снять лог `STOCK_OK:` |
| JIT-компиляция, `ctl.restart zygote` | нужен рут и ручной клик | один ручной клик тумблера JIT после проверки сети, полные датированные логи |
| U3 слот «заряд/наружная» | визуально только на ГУ | после OFF заряда убедиться, что наружная скрыта, а выбор (`hvacdigital_temp_ext=1`) остался в prefs |
| Рендер/слушатели панели | нет Robolectric/устройства в песочнице | проверка `ToolsSnapshotFold` + инварианты кода; визуальный клик — на ГУ |

---

## 14. Безопасная проверка владельцем (после исправлений)

Порядок безопасный, без reboot-лечения и **без повторного JIT как средства починки сети**:

1. **Логи:** открыть вкладку «Инструменты», затем снять полные датированные логи (`getExternalFilesDir(null)/logs/`). Ничего не включать.
2. **Интернет/CarPlay как есть:** проверить исходный интернет и CarPlay (телефонная раздача ≠ AP ГУ; независимый CarPlay не ломаем).
3. **TTL:** включить «Фиксация TTL 65» один раз, дождаться `TTL_ADD … marker=…` и `TTL_CHECK … → SCOPED_OK/SCOPED_V4_ONLY`; при `REF_ELSEWHERE`/`CHECK_OWNER` — не повторять клики, а снять лог (там будет конкретная причина). Проверка TTL на проводе — только если есть возможность замера.
4. **Звук:** включить «Звук блокировки замка» и «Звук открытия дверей», открыть/закрыть дверь и **послушать**; при отсутствии звука снять лог с `STOCK_OK:<цель>` / `TTL_…`-подобными маркерами сверки.
5. **JIT:** один ручной клик тумблера JIT в спокойной сети; посмотреть в логе `JIT_*`/`CLAIM_*`. Если сеть уже не работает — **не** «лечить» её повторным JIT; сначала лог.
6. Прислать датированные логи: по ним видно, какая именно проверка не дошла до факта (правило/цель/цепь/запись), без догадок.

Решение об испытании принимает владелец. Результаты стенда **не** выдаются за результаты ГУ.
