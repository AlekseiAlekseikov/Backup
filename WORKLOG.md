# WORKLOG — iter54 (JIT, доставка, TTL, звук, окна, логи, тумблеры)

## iter67 — A–K (отметка, яркость, хранилища, цепочка, сеть, UI, аудит команд)

Ветка `fix/v2`, `versionCode=63`/`versionName=1.0.63` без изменений, `--force` не применялся, `main` не пушился.
Полный отчёт — `docs/agent/REPORT_iter67.md`. Локально: `bash /home/user/runtests67.sh` → `OK (1222 tests)`;
`typecheck_lock.sh` → `53/53`, новых ошибок нет. `SOURCE_SHA=d5a22b7987697b2be4107e6e90cc124ca1705cc2`.
Красные прогоны ДО получены на `git worktree` от базы `93734b7` (`/home/user/wbaseB`); Gradle локально недоступен.

| Шаг | Что сделано | Проверка | Коммит |
|---|---|---|---|
| A | `WriteResult(writeAccepted, readbackOk)`, `AdbClient.execExpect`, `getprop` сравнивается с ожиданием; `writeProp` читает факт одним `getprop` | `Iter67WriteVerdictTest` (11): до — 3 падения, после — `OK` | `1ad8a2b`, `44ea55e` |
| B | `SyncState`/`syncPlan`/`BrightRestorer`: пишется только расходящееся, `setprop` с тика убран, серия `SYSFS_UNREADABLE` → один лог и запрет записи | `Iter67BrightSyncTest` (10): до — 2 падения | `0cd27fb` |
| C | `attempt`/`boot` в отметке, `ts` = время последней попытки ДО `JIT_CHAIN_SENT` в оба хранилища, `JIT_UNBLOCK … exit=` | `Iter67UnblockTest` (15): до — 1 падение + не компилируется | `002cdb4` |
| K | `PairDiag`/`classifyPair` (блокирует только `OP_DIFF`), согласование «хуже» `repair=rewrite_worse`, удаление всего набора ключей одним `commit()` | `Iter67PairTest` (13): до — 2 падения | `a0884bc`, `8446add`, `8921573` |
| E | `JIT_OP_PHASE` всегда, `JIT_OP_PROBE result=absent`, `JIT_CHAIN_LOST action=no_resend`, перечитывание terminal через 5 с | `Iter67ChainDiagTest` (5): до — 2 падения | `9abcb11` |
| J | `trap EXIT` до первого side effect, свой каталог через `pm path`+`dirname` исключён из `umount`, `UMOUNT_*` до `setprop` | `Iter67ChainScriptTest` (8): до — 4 падения | `9667c11` |
| I | флаг «сеть сняли мы», `restoreDecision` (возраст не смотрится, нет claim ≠ чужой), `uiReason`, свойства `wifiRestoreState`/`wifiRestoreNotice` | `Iter67NetRestoreTest` (11): до — 2 падения | `ccc4750`, `d5a22b7` |
| G+D | `JitUiState` + `perfJitState/Runtime/RuntimeRaw/Why/Text`, `pending_restart` сохраняет намерение и не блокирует клик, `JIT_STATE` | `Iter67UiStateTest` (11): до — 2 падения | `564c91d` |
| H | `Toggles45.staleness` с классом отметки, `JIT_MARK_INVALID … clicks=allowed`, арифметика времени в `sh` проверена | `Iter67UnblockTest` (кейсы H): до — нет строки | `636c1bd` |
| F | `CommandAudit.missingSeparators` (склейка не отправляется), `setprop/getprop` в журнале не усекаются | `Iter67CommandAuditTest` (8): до — 2 падения | `d715b68` |

Согласованы с новыми правилами: `JitIter41Test` (3 кейса), `Iter51JitOwnershipTest` и
`Iter54JitConcurrencyTest` (по 1 формулировке) — причины в отчёте, §3.


Ветка `fix/v2`. `main` не пушился, `--force` не применялся. Версия не менялась: `versionCode=63`, `versionName=1.0.63`.
Локальный прогон: `bash /home/user/runtests.sh` (kotlinc 1.9.24 + `android.jar`-стабы + JUnit 4.13.2), полный отчёт — `REPORT_iter54.md`.

## Журнал итерации

| Шаг | Что сделано | Проверка | Коммит |
|---|---|---|---|
| §6.1 TTL | идентичность своей цепи: `CHAIN_PREFIX`/токен установки, `chain_state absent\|empty\|ours\|unproven\|collision\|unknown`, историческое имя только для отчёта, `ROLLED_BACK=yes` только после readback | `Iter54TtlOwnershipTest` (9) + `Iter53TtlTest` | `8c0f9b1` `OK (757)` |
| §4 JIT | `ClaimIdentity(op, instance, boot, pid, pstart)`, `CL_ORDER verdict=…`, отказ претендента ≠ terminal владельца, атомарный lock (`temp + ln`), `ClaimWrite{Taken,Busy,Stale,Failed}` + `claimGate` (Failed ⇒ payload не уходит) | `Iter54JitConcurrencyTest` (6) на настоящем `/bin/sh` с барьерами | `9f76bb1` `OK (763)` |
| §5 доставка | `DeliveryOutcome{NotSent, Unknown(op, EvidenceRef), Finished}`, `outcome()`, `directScript()` в подоболочке; потребители `LockSound`/`NetworkTtl` реагируют на `Unknown` | `Iter54DeliveryTest` (10) | `8a54ce6` `OK (773)` |
| §7 звук | `JournalRead{Absent, Unknown(why), Readable}`, `ENOENT ≠ EACCES ≠ corrupt`, идемпотентная crash-safe миграция, `mi_top` = последняя строка mountinfo + сверка с `mount_id`, `AMBIGUOUS` блокирует umount, сверка по ВСЕМ целям | `Iter54SoundTest` (5) + `Iter52JournalTest` | `78cc0a4` `OK (779)` |
| §8 окна | новый `VentOwnerTx` (одно состояние, один lock, поколение); `before→переход→установка→generation` в одной секции; closed-ветка через `mutateOwners` | `Iter54WindowTest` (4): 8×25 транзакций, 200 разных поколений, 0 ошибок | `2252175` `OK (783)` |
| §9 логи | убрана content-дедупликация (`recoverPendingNew`), позиционная граница (`boundary_dup=N`), слияние `.new` под общим `<name>.lock`, при занятом локе `.new` сохраняется | `Iter54LogTest` (6) | `74ebdac` `OK (789)` |
| §10 тумблеры | `ToggleIntent` (intent по readback хранилища, applied по статусу действия), `WriteAck{intent, applied}` + `note()`, `decide()` не путает PARTIAL с отказом записи, подписи «сохранено, ждёт применения», `perfJit` показывает последнее известное, а не default | `Iter54ToggleTest` (15) | `d270a2d` `OK (805)` |
| §10 fix | сводный статус действия — `statusWire` (находка CI: `Unresolved reference 'status'`), `ToggleIntent.Applied.ofWire()` | `Iter54ToggleTest` (16) | `789f8f6` `OK (806)` · CI `43707141785` **success** |
| handoff | `REPORT_iter54.md`, `WORKLOG.md`, `SOURCE_SHA=789f8f6` | CI `43708022868` на `e4f22ec` — **success** | `e4f22ec` |

## iter61 — JIT-вход, TTL-поколение, звук, доставка

Ветка `fix/v2`, `versionCode=63` без изменений, `--force` не применялся. Полный отчёт — `docs/agent/REPORT_iter61.md`.
Локально: `bash /home/user/runtests58.sh` → `OK (1033 tests)`; `typecheck_lock.sh` → `53/53`, новых нет.
На ГУ ничего не выполнялось.

| Шаг | Что сделано | Проверка | Коммит |
|---|---|---|---|
| §3 JIT | снимок `for f in c.* p.*` заменён протоколом НОМЕРА `n.<k>`: захват через noclobber + readback, ожидание меньших номеров поимённо, освобождение только по доказанной смерти (`JitOwn.kt:553`, `:572`, `:595`); тесты iter59/iter60 приведены к новому протоколу без ослабления инвариантов | `Iter61JitSlotRaceTest` (8) падает на iter60 и зелёный на iter61; `Iter60JitTicketRaceTest` (9), `Iter59JitAdminTest` | `be669ba` |
| §4 TTL | отсутствие следов ⇒ «неизвестно» (fence держится), снятие только по durable-поколению (`exit 90`), миграция legacy ДО fence, `JournalState` вместо `null→пусто`, своя фаза у каждой retry, `markAttempt(): Boolean` блокирует send, `desire_lag` планируется | `Iter61TtlFenceTest` (10) + всё TTL-семейство (107 в одном прогоне) | `be669ba` |
| §5 звук | дефект воспроизведён зондом (`previous_restore_unresolved restores=0` на production-значениях); `StartedMark{PRESENT,ABSENT,UNKNOWN}` + доказательство из журнала (`restore_prepared`/`restore_not_sent`), durable-фаза `restore_handoff` ДО транспорта | `Iter61SoundEvidenceTest` (9) по цепочке `evidenceCommand → parseEvidence → reconStep → recoveryGate`; `Iter60SoundAttemptTest` (8) на production-свидетельстве | `d27eae1` |
| §3.4 JIT-клик | счётчики: один клик = ровно одна payload-команда, ноль прямых вызовов Wi-Fi, второй клик `previous_unfinished`; `ctl.restart zygote` последним, санация `ip rule oif wlan0` в той же команде; LWW по `seq` при переводе часов назад (`_jit_intent_order`) | `Iter61JitClickChainTest` (8) | `4624f0f` |
| §6 доставка | коллизия имени не подменяет nonce (`State.COLLISION`), четыре факта (payload rc / transport rc / stdout-маркер / `.done`) различимы, identity в журнале до send | `Iter61DeliveryFactsTest` (6) | `4624f0f` |
| §7/§8 | §7 — только стенд (🟡), ГУ ⛔; отчёт `docs/agent/REPORT_iter61.md`, CI на точном HEAD | `OK (1033)` | см. отчёт |

## Инцидент среды

Песочница откатила `/home/user/w` (пропали `.git` и `app/src`), а также `toolchains/kotlinc/lib`.
Восстановлено: `git clone -b fix/v2` (все коммиты были запушены) + `kotlin-compiler-1.9.24` заново.
После восстановления прогнан полный набор: `OK (805 tests)` на `d270a2d`, затем `OK (806 tests)` на `789f8f6` — цифры выше подтверждены на восстановленном дереве.

## Открытые долги

- Локально `assembleDebug/Release` не собирались (нет Gradle-кэша SDK в песочнице). CI собирает **debug** на `789f8f6` — success (`43707141785`); **release** на этом HEAD не проверялся.
- Живой Android/UI-рендер (Robolectric/instrumentation) не исполнялся — проверены production-модели и JSON снимка, не listener/render.
- На реальном ГУ не проверялись: VHAL окон, zygote-переходы, mount верхнего слоя, выживание TTL после reboot/update, плагин SystemUI.
- Межпроцессность логов — потоки + реальный `FileLock`, без `fork`.

---

# iter56 — владение, terminal, mount, учёт TTL

Ветка `fix/v2`. `main` не пушился, `--force` не применялся. Версия не менялась: `versionCode=63`,
`versionName=1.0.63`. Подробный отчёт — `REPORT_iter56.md`.

## Журнал итерации

| Шаг | Что сделано | Проверка | Коммит |
|---|---|---|---|
| §4.1 отказ ≠ конец | `_refuse` не пишет `END`; `_jit_owner_dead` требует `END` с точной identity (`op`+`inst`+`pid`+`pstart`, при наличии `boot`), `REFUSED` не участвует | `Iter52JitOwnTest` (`guardDoesNotCreateLockForRefusedPayload`, `endOfAnotherInstanceOrOfAnotherOpDoesNotProveDeath`), `Iter56JitTest.replayRefusalOfTheSameInstanceNeverFreesTheLiveOwner` | `e83e264` |
| §4.2 атомарный захват | `tmp`+`ln` (`_jit_take_lock`), перехват `_jit_steal_dead` (пин inode + сверка), возврат `_jit_restore_dead` через `ln`, `CL_TAKEOVER_KEPT` при занятом имени | `Iter56JitTest.takeoverNeverLosesOrReplacesTheObjectItDidNotProveDead`, `restoreOfAMovedForeignLockNeverOverwritesTheNewOne` | `e83e264` |
| §4.3 порядок намерений | `seq` (монотонное поколение, `bumpIntentSeq` + подтверждение чтением) и единое правило `_jit_intent_order` (`seq` → `seq_owner_ended` → `ts_legacy` → `unprovable`); ранняя и подlock-проверки — один хелпер | `Iter54JitConcurrencyTest.lateClaimOfOlderIntentNeverCancelsNewerIntent`, `Iter56JitTest.newerIntentWinsEvenWhenTheClockWentBackwards`, `equalGenerationsAreATie…`, `legacyClaimWithoutSeq…` | `e83e264` |
| §4.3 чтение claim | формат проверяется по полям (`validClaimValue`): новое поле `seq` не делает валидный claim «испорченным» | `Iter53ClaimTest.realClaimWrittenByClaimCommandIsReadAsPresent` | `e83e264` |
| §5 terminal = исполнение | убран выбор «последнего `inst`»; факты только своего исполнения (`expectInst`), `END` учитывается при совпадении `pid` со `START`, при нескольких исполнениях без identity — `started`/`finished` не заявляются (`term_exec_ambiguous`), легаси-файл помечается `inst_unproven_legacy` | `Iter56JitTest`, `Iter51JitOwnershipTest.terminalParsingDistinguishesEveryProvablePhase` | `d58904b` |
| §5 durable identity | `JitRef.OpMark.inst` пишется в отметку ДО claim/отправки и читается после перезапуска; один `opInst` на клик (отметка → claim → гвардия → чтение terminal) | `JitOrchestratorTest`, `JitPersistenceTest` | `d58904b` |
| §5 гонка lock | ограниченное ожидание (до 2 c) занятого коротким претендентом lock: два одновременных клика больше не дают НОЛЬ side effect'ов | `Iter54JitConcurrencyTest.twoRealShellsAtTheSameMomentProduceExactlyOneSideEffect` (0/40 прогонов после правки; 1/50 до неё) | `d58904b` |
| §7 mount | снятие слоя по литеральному префиксу запрещено: при невычислимом ожидаемом root слой сохраняется (`reason=root_expected_uncomputable action=keep_layer`) | `Iter56MountJitTest` (2 теста; на старом коде второй падает — проверено откатом файла) | `948e4ec` |
| §8 TTL | `TOUCHED_v4/v6` поднимается при любом состоявшемся `-N`/`-A`/`-j`, а не только при создании цепи ⇒ откат покрывает правило, добавленное в существующую свою цепь | семейство `Iter55TtlTest`/`Iter54TtlOwnershipTest` (отдельного теста под reuse-путь не добавлено — см. отчёт §3) | `948e4ec` |

## Прогоны

- `bash /home/user/runtests.sh` → `OK (841 tests)` после `d58904b`, `OK (843 tests)` после `948e4ec`.
- Прогон против старого кода (файл `LockMountScripts.kt` возвращён к `HEAD`): `Tests run: 843, Failures: 1` — падает ровно `Iter56MountJitTest.unprovableExpectedRootKeepsTheLayerInsteadOfGuessing`.

## Инциденты итерации

- Обрыв файла при in-place правке `JitOwn.kt` (срез в python-скрипте отрезал хвост ~250 строк).
  Восстановлено: хвост взят из `git show HEAD:…/JitOwn.kt` и к нему заново применены правки
  iter56 (`_refuse` без `END`, `_jit_restore_dead` вместо `mv`, полный `END` в `trap`).
  После восстановления — полный прогон, `OK`.
- `twoRealShellsAtTheSameMomentProduceExactlyOneSideEffect` был плавающим (1 сбой на ~50 прогонов);
  причина найдена и устранена ограниченным ожиданием lock, подтверждено 40 прогонами без сбоев.

## Открытые долги (iter56)

- **§6 звук (`LockSound.kt`)** — не трогался: `PLANNED` без durable evidence до отправки,
  no-target/corrupt не должны давать Ready, `DONE` не должен закрываться снимком mount,
  поздний restore опасен.
- **§8 `NetworkTtl`** — типизированные исходы вместо `Pair` не вводились.
- **§9 окна** — `WindowDoors.kt` не менялся (все writers, физическое действие после плана).
- **§9 логи** — `renameTo` уже проверяется (`FileLogCore.kt:256,381,403,783,825`), правки не требовалось.
- На ГУ ничего не испытывалось; JIT-компиляция и слышимость WAV не измерялись.

---

# iter57 — план звука (зафиксирован до правок, по решению владельца)

## Состояния звука (то, что МОЖНО утверждать)

```text
подготовка (prepare)        → известны nonce, путь скрипта и путь СВИДЕТЕЛЬСТВА (<path>.done)
PLANNED   + ev=<path>       → записано и подтверждено чтением ДО отправки (сбой записи ⇒ отправки нет)
NOT_SENT                    → доказано: ни один байт скрипта не исполнялся  ⇒ retry безопасен
UNKNOWN(bind)               → ответ потерян, МОГ стартовать: запись НЕ закрывается, новый bind НЕ идёт
STARTED (в свидетельстве)   → конкретное исполнение начало работу; terminal нет ⇒ поздний shell возможен
FINISHED(rc) + verdict      → terminal ДОКАЗАН → только теперь допустим переход в DONE / stock
EFFECT_VERIFIED             → по каждой цели ровно один VERIFIED-факт слоя (iter51/iter56 без изменений)
```

Ключевые правила, которые входят в код:
1. `ev` (путь свидетельства) известен и записан **до** отправки; отправляется ровно подготовленный
   nonce (прямой путь тоже оставляет device-side `.done`).
2. Нет целей/stage, нет свидетельства, свидетельство не читается, `.done` пуст/бит → `UNDEFINED`
   + конфликт; **Ready не возвращается**, новый bind/слепой umount не запускается.
3. Снимок stock/успешный restore **не закрывает** операцию, пока её terminal не доказан
   (`late_shell_unproven`). Restore тоже типизирован: `Unknown` restore держит fencing (новый bind
   запрещён), `rev=` пишется в журнал.
4. `mi_top` и снятие слоя: только по identity источника + всех целей (iter56 сохранён), чужие не
   трогаются, `umount -l` без повторного подтверждения стоком не считается.
5. Неразрешимый по доказательствам случай (легаси-запись без свидетельства) остаётся конфликтом и
   имеет ЯВНЫЙ путь разрешения владельцем (`owner_resolved_unprovable`), а не «ждём вечно».

## Стенд

`LockOp.Reconciler` — production-решатель (его вызывает `LockSound.reconcileLocked`), с
инъецируемыми портами `evidence()`/`restore()`/`journal()`. Тесты гоняют РЕАЛЬНЫЙ журнал в файлах,
реальные `BindOp` и тот же код, подменяя только устройство. Стенд записи/чтения — настоящая ФС.
Границы: JVM + fake device, не ГУ.

---

# iter57 §6–§10: что сделано и чем проверено (итог итерации)

Порядок §4 был: звук (готов, `f06e00a`) → остаточные дефекты JIT → TTL ownership и поздняя доставка
→ логи/окна/UI → тесты, сборки, CI, отчёт.

## §7.1 JIT: старое намерение не побеждает новое (`def5aef`)
`seq` сравнивается только как номер намерения; `newer_intent` — лишь при `me_seq > seen_seq`;
`END` более нового порядок не меняет; без `seq` — `rule=ts_legacy`; нечего сравнивать —
`unprovable`. Удалены `seq_owner_ended` и `_jit_intent_ended` (они объявляли «намерение кончилось»
по факту чужого завершения).

## §7.3 JIT: takeover без окна потери (`def5aef`)
Атомарное создание lock (`temp`+`ln`), смена объекта только под `<lock>.admin`, `_jit_lock_acquire`
→ 0/1/2, перехват — только при точном совпадении с доказанно мёртвой строкой, payload пишет
`ppid`/`ppstart`, `trap` не чистит `<lock>.dead.*`.

## §8 TTL (`4454d04`)
`OWNMARK=${TTL_STATE_DIR}/voiceapp3_ttl_own_<gen>` — device-side маркер создания; `NAMED_OWN`
требует `ownmark_ok` (имя установки владением больше не считается). Стоп
`reason=no_safe_identity` заменён на `TTL_IDENTITY action=create_only`: создание своей цепи
разрешено, дописывание в существующую немаркированную — нет. Единственный безусловный стоп по
имени — историческое имя цепи (`legacy_name_not_owned`). Маркер снимается вместе с цепью.

## §7.2 JIT: атомарная выдача `seq` (`958fdea`)
Файл-счётчик + `FileChannel.lock` (fcntl) + `rename`/`fsync`; префы — вторая копия; часы в номере
не участвуют; `epoch` — случайный токен для лога; переполнение → `null` (порядок `ts_legacy`),
а не отрицательное число; JIT не блокируется навсегда. Лог: `issue=… src=file_lock`.

## §9 Логи (`01e847a`)
Свидетельство `.new` называет полный путь и исходный каталог (`at=`, `origin=`, `moved=`); отказ
`rename` не выдаётся за перенос; `LogFiles.lastDirChoice` называет `external` /
`internal_fallback` с причиной.

## §10 Окна (`40f292b`)
`VentOwnerTx.read` отдаёт снимок (был mutable-set escape), `ownersOf` удалён; откат вынесен в
единственную точку `WindowDoors.rollbackAfterFailedClose`; часы `awakeClock` — чтобы объект вообще
загружался в JVM. `NetworkTtl.parseVerifyResult` → типизированный `VerifyResult` (`cf6373a`).

## §7.4 JIT сквозной путь (`abf9f81`)
Ручной ON/OFF через настоящую `JitOp.click`: один payload, `ctl.restart zygote` последней командой,
возврат Wi‑Fi после рестарта фреймворка, вне клика — ни одной команды, без AP/AVAS/плагина.

## Прогон
`bash /home/user/runtests.sh` → `OK (890 tests)`. Стенд: JVM + fake shell (+ настоящий `/bin/sh` и
подставные `iptables` для TTL). Release локально не собран: Gradle требует JVM 17, доступна 11.

---

# ITER58 — восстановленная итерация (база `026e2f4`)

## Почему «восстановленная»
Песочница потеряла `.git` и `app/src` рабочего дерева; коммиты iter58 существовали только как
запись. Итерация повторена на свежем клоне `fix/v2` (`026e2f4`): правки применены теми же
скриптами (`patch_jitown*.py`, `patch_sound*.py`, `patch_ttl58.py`), тесты написаны заново.
**SHAs отличаются от прежней записи** — воспроизвести их невозможно (хэш зависит от содержимого).

## §2.1 JIT-lock: реальный маршрут
```
клик → JitOp.click → claimCmd (shell)
  _jit_admin_enter   (атомарное set -C создание <lock>.admin; снятие — только при _jit_death_proven)
  _jit_take_lock     (tmp + ln: имя появляется сразу с содержимым)
  запись claim (tmp + mv, readback = taken)
  _jit_release       (под admin, повторное чтение, удаляется ТОЛЬКО своя строка)
  _lock_self_mark    (определена ДО вызова; дописывает ppid/ppstart payload)
guard payload:
  _jit_death_proven → _jit_may_takeover (0 можно / 1 нельзя / 2 payload мог начать)
```
Полярность одна: `rc 0` = смерть ДОКАЗАНА. `REFUSED` доказательством не является.

## §3.1/§3.2 Звук
```
тумблер → LockSound.applySystemLockSoundBind → BgWorker → reconcileLocked(allowRestore)
  LockOp.reconStep(op, ports):
    no_targets → previous_restore_unresolved → prepareRestore (пустой ⇒ нет отправки)
    → ports.restore → not_sent / outcome_unknown → bindLate → verdict
  агрегатор: LockOp.recoveryGate(unresolved, conflict, lateShell, readOnlyPass)
    Conflict > Pending(unresolved:…) > Pending(read_only_pass) > null (можно сходиться)
  null ⇒ bind/OFF-отметка; иначе — ранний return gate, side effect НЕТ
```

## §4 Delivery
`directScript`: `_VRC` снимается ОДИН раз сразу после payload; `.done` получает код payload,
внешний код совпадает; отказ записи честно `written=0`.

## §5 TTL
`-N` → маркер `…_own_<gen>` (шапка один раз + строки `v4=created`/`v6=created`, чтение обратно)
→ при отказе: `action=stop reason=ownmark_not_persisted` + снятие созданной цепи.
`ownmark_ok` требует совпадения цепи И поколения И существования цепи.
OFF снимает только строку своего семейства; файл — когда семей не осталось.

## Прогон
`bash /home/user/runtests58.sh` (клон `/tmp/iter58`) → **`OK (921 tests)`**.
База `026e2f4` тем же стендом → `OK (890 tests)`.

## Исправление после первого CI (коммит `7ddee6b`)
CI на `c95f7aa` упал ДВУМЯ ошибками компиляции в `LockSound.kt` — их не видит локальный стенд:
`evalLog` (такого символа в файле нет) и `if (applied)`, где `applySystemLockSoundBind` возвращает
`Unit` (работа планируется в `BgWorker`). Исправлено: запись в `AppLog.e`; результат bind больше не
заявляется — `Pending("bind_scheduled")`, готовность объявит проход, который прочитает факт.
Страж: `bash /home/user/typecheck_lock.sh` (весь пакет `tools` против стаба `android.jar`,
сравнение с базой `026e2f4`) — на сломанной версии падает с теми же двумя ошибками.

# iter59 — звук/JIT/TTL/доставка (ветка `fix/v2`, база `446b343`)

## §3 звук
`LockOp.reconStep` переписан: terminal BIND → terminal прежней попытки → (read-only: наблюдение) →
подготовка identity → отправка → исход → сверка целей. `ReconPorts.restore(op, attempt)` отправляет
ровно записанный путь; `readOnly()`/`observe(op)` дают читающему проходу закрыть операцию по факту.
`LockSound.appendOp` подтверждает запись `LockOp.sameIdentity` (все поля), а не `state`+`gen`.
`LockMountScripts.observeScript` — чисто читающий скрипт (`STOCK_OK`/`OWN_PROOF`/`FOREIGN_MOUNT`/
`STOCK_UNPROVEN`), ни `mount`, ни `umount`, ни записи файлов.
Осознанно изменено поведение iter58: restore при недоказанном terminal bind НЕ отправляется.

## §5 JIT
Общий `<lock>.admin` → каталог `<lock>.adm` с уникальными записями `p.<inst>.<pid>` (temp+`ln`) и
входом по билетам; чужое снимается только по своему имени и только после доказанной смерти.
`_lock_self_mark` подтверждает результат чтением; `guard` делает `_refuse self_mark_failed` до
опасного действия. Разбор записей — без внешних процессов (секция ~в 3 раза быстрее).

## §6 TTL
`ownmark_ok v4|v6` + `NAMED_OWN_V4/V6` + `chain_state` по инструменту ⇒ чужая v6 не становится
своей. Отказ маркера v6 симметричен v4 (`chain_delete_rc` учитывается). `TtlAttempt`: durable
identity до отправки + правило повтора; `Pair`-retry удалён, `Unknown` не повторяется.

## §7 доставка
`Result.transportRc/payloadRc/rcFromDone`; потребители берут код payload. Коллизия имени: noclobber
+ новый nonce, занятое имя не перезаписывается.

## Прогон
`bash /home/user/runtests58.sh` → **`OK (954 tests)`** (+33 новых).
`bash /home/user/typecheck_lock.sh /home/user/w59` → 0 новых ошибок (база `026e2f4`).
⛔ Release-сборка локально невозможна (Gradle 9 → JVM 17). ⛔ UI/ГУ/VHAL/интернет — только владелец.
❌ Не сделано в этом проходе: `JitNet` seq/epoch, UI-корень визуального OFF, `FileLogCore`,
`VentOwnerTx`.

---

# iter60 (2026-10-22) — рабочий JIT, durable TTL, звук по фазам, delivery без подмены identity

База `5c092d7`, `SOURCE_SHA=53a4f9c`, версия 63/1.0.63 без повышения. Полный отчёт —
`docs/agent/REPORT_iter60.md`.

## §3 JIT admin (P0)
Гонка одинаковых ticket воспроизведена (участник останавливается между выбором ticket и
публикацией). Протокол: сначала публикуется фаза выбора `c.<inst>.<pid>`, затем ticket; ждущий
не входит при живом `c.*` ⇒ два владельца невозможны. Identity (`inst`+`pid`+`pstart`) при
повторном входе, глубина вложенности, снятие чужой записи только по своему имени и после
доказанной смерти, legacy `<lock>.admin` мигрируется со сверкой. Regression
`Iter60JitTicketRaceTest` (9): на коде iter59 падают 5, на iter60 — 9/9.

## §4 TTL (P0)
`TtlState` (новый): append-only журнал попыток с фазами `PREPARED/MAY_HAVE_RUN/TERMINAL/PROVEN_NOT_SENT`,
fence до сверки прошлой попытки, закрытие ровно по ЕЁ terminal, отдельное durable намерение
владельца (поколение), миграция iter59 как «неизвестно». `NetworkTtl`: persistDesire → fence →
recordAttempt → send → durable фаза; `TTL_DESIRE_LAG` вместо ложного успеха. 13 тестов.

## §5 звук (P0)
`NotSent` с непустым `restoreEv` больше не даёт `previous_restore_unresolved` (это был бессрочный
запрет восстановления). Crash до send (`restore_prepared` без следов) восстанавливается, crash
после send (`started=1`/`outcome_unknown`) блокирует. Reread ограничен (`REREAD_MAX_PER_OP=3`),
дедуплицирован, флаг снимается в `finally`. `finishOpJournal` → `Boolean`; без durable DONE
операция не закрывается. 8 тестов (5 падают на iter59).

## §6 доставка
При `EXISTS` nonce внутри отправки НЕ меняется: `State.COLLISION` → `NotSent`, новую identity
готовит вызывающий. Коды различены: payload / transport / stdout-маркер / `.done`. 4 теста,
2 старых теста обновлены под новый контракт.

## §7 UI/окна/логи
`VentOwnerTx.mutate` больше не отдаёт живые коллекции (mutable-set escape закрыт, 800 конкурентных
мутаций). `FileLogCore` проверен (renameTo/fsync/свидетельство). Таблица ключей настроек — в отчёте.
❌ Плагин статус-бара, док по скриншоту, климат — не делались; VHAL/стекло/логи на ГУ не проверялись.

## §0 гигиена
`REPORT_iter54..59.md` → `docs/agent/` (`git mv`). Новые агентские `.md` только в `docs/agent/`.

## Прогон и честные поправки
`bash /home/user/runtests58.sh` → **`OK (992 tests)`**. `typecheck_lock.sh` → **53 → 53, новых нет**
(до исправления пути к kotlinc скрипт писал ложные «0/0» — это исправлено в iter60).
⛔ ГУ не проверялась: JIT на toybox, Wi‑Fi/интернет, TTL/iptables, слышимость звука, VHAL, release.

## iter62 (§A механизмы + §B функции владельца)
§A: JIT — один владелец через атомарный `ln` (`<lock>.own/o`), номера больше нет; TTL — поколение проверяется перед КАЖДОЙ мутацией, публикуется до отправки, `SUPERSEDED` только по доказательству; звук — подготовка восстанавливается из журнала, handoff-Unknown получает ровно один безопасный повтор и закрытие по наблюдаемому стоку.
§A регресс, найденный прогоном: скрипт без `guardGen` падал на `_gen_ok: not found` ⇒ TTL не работал вообще (починено no-op функцией). §B: пустое значение тумблера больше не «OFF», зависимая строка считается по подтверждённому состоянию и выбору владельца, граничная запись журнала не дублируется, SSID/MAC/IP маскируются, `ClosePlan` отдаёт копию карты.
Прогон: `runtests62.sh` → **OK (1072 tests)**; `typecheck_lock.sh` → 53 = 53. ⛔ ГУ не проверялась (нет устройства/adb): toybox, `su`, `iptables`, `mount --bind`, статус-бар, слышимость, VHAL; локальная Gradle-сборка невозможна (JDK 11, нет SDK).

## iter63 (P0 владельца + четыре P1)
Журнал: обе граничные записи сохраняются, дубли устраняются по `rid` события (не по тексту) — `LogRecordId`,
`FileLogCore.appendStamped`. Тумблеры: подтверждение сверяется по КЛЮЧУ (`sameKey`), панель восстанавливает
`acks` — падающий тест на «клик одного меняет другие» есть. JIT: уборка мёртвых `t.*` при входе. TTL: три
состояния файла поколения (`INIT/GEN_OK/ADOPT/UNWRITABLE/SUPERSEDED`), путь и значение в `TTL_APPLY_RESULT`,
guard у всех 27 мутаций (пробы `-m comment`, v6, откаты, `del_jump`/`drop_family`). `LogRedact`: время суток
не маскируется, IPv6 при любом числе групп, IPv4 вместе с портом. `WindowDoors`: `BLOCKED`/`FAILED` с причиной
вместо безусловного `OK`. README — необратимость после `uninstall`.
Прогон: `runtests63.sh` → **OK (1115 tests)**; `typecheck_lock.sh` → 53 = 53. ⛔ ГУ не проверялась (нет устройства/adb):
шаг 1 §5 не исполнен; локальная Gradle-сборка невозможна (JDK 11, нет SDK/сети).

## iter64 (одна правка: идентичность события журнала)
`rid` больше не счётчик процесса: `rid` = время + хеш писателя (`EventKeyGen`), ключ события — пара (rid, writer),
суффикс ` [r=…/…/…/w=…]`; старый суффикс iter63 читается. Одно правило приёма записей (`LogRecordId.addNew`) для
`.new`, spill и work-файлов; строки прежних версий переносятся все. Проверено и двумя настоящими java-процессами.
R1/R2/R6/R7 падали на `6ca7e18` (потеря записи) — проходят. Прогон: **1128 тестов**, typecheck 53 = 53. ⛔ ГУ не проверялась.

## iter65 (последняя итерация в песочнице)
Двухпроцессный тест стал конкурентным: оба процесса стартуют до ожидания, `Iter64TwoProcessMain` принимает `maxBytes`
и печатает `start`/`end`; проверяются пересечение интервалов, «строк = вызовов», уникальность ключей, целостность
формата, две метки `w=` и факт ротации. `randomTag()` — 64 бита из SecureRandom + pid + nanoTime (на iter64 метка была
не-hex, и строка с ней не считалась «с писателем»). Прогон: **1130 тестов**, typecheck 53 = 53. ⛔ ГУ: шаги Ш1–Ш6 не исполнялись.
