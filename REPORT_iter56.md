# REPORT iter56 — владение JIT-операцией, terminal, mount, учёт TTL

**Ветка:** `fix/v2` · **Версия:** `versionCode=63` / `1.0.63` (не повышалась) ·
**Локальный прогон:** `OK (843 tests)` (последний прогон на итоговом дереве) ·
**Коммиты этой итерации:** `e83e264` (§4), `d58904b` (§5), `948e4ec` (§7/§8) ·
**SOURCE_SHA:** см. `WORKLOG.md` (итоговый кодовый коммит + handoff).

Отчёт сознательно отделяет **сделанное с доказательством** от **открытого**. Ни одна строка
ниже не заявляет поведение на ГУ: испытание владельцем в этой итерации не проводилось.

---

## 1. Что именно было дефектом (по аудиту iter55) и что стало

| № | Дефект | Как было | Как стало |
|---|---|---|---|
| 1 | Отказ претендента выдавался за конец владельца | `_refuse` писал `END rc=0 … refused=1`; `_jit_owner_dead` принимал любую строку с `END` и совпадающими `op`+`inst` | `_refuse` не пишет `END` вообще; конец доказывается `END` с **точной** identity (`op`,`inst`,`pid`,`pstart`) в terminal этого исполнения. `REFUSED` больше не участвует в выборе предмета чтения |
| 2 | Неатомарный takeover через `mv` | `mv lock.dead.$$ lock` поверх имени, которое мог занять живой владелец | захват `tmp+ln` (`_jit_take_lock`), перехват через `_jit_steal_dead` (пин inode + сверка), возврат `_jit_restore_dead` через `ln` — при занятом имени объект **сохраняется** и печатается `CL_TAKEOVER_KEPT` |
| 3 | Порядок намерений по wall-clock | сравнение `ts_ms`; при переводе часов назад «новое» проигрывало | монотонное поколение `seq` (`bumpIntentSeq` с подтверждением чтением) + единое правило `_jit_intent_order`; при отсутствии `seq` — явное правило `rule=ts_legacy`, при недоказуемости — `unprovable` (уступка, не перезапись) |
| 4 | `parseTerminal` выбирал «последний `inst` в файле» | поздний след (в т.ч. отказ повторной доставки) подменял предмет чтения, его `END rc=0` читался как наше завершение | факты берутся по **своему** исполнению (`expectInst`); `END` учитывается только при совпадении `pid` со `START`; при нескольких исполнениях без identity ни `STARTED`, ни `FINISHED` не заявляются (`note=;term_exec_ambiguous=N`) |
| 5 | Identity не была durable до отправки | `inst` жил только в памяти процесса | `JitRef.OpMark.inst` пишется в отметку **до** claim/отправки и читается после перезапуска; один `opInst` на клик (отметка → claim → гвардия → чтение terminal) |
| 6 | Два одновременных клика могли дать **ноль** side effect'ов | payload, встретив lock, занятый живой короткой командой записи claim, отказывался немедленно | ограниченное ожидание (до 2 c) занятого lock без выполнения действий; по истечении — прежний отказ `lock_held` |
| 7 | Снятие mount-слоя по префиксу пути | при невычислимом ожидаемом root `STAGE_PREFIX*` считался доказательством владения и слой снимался | слой **не снимается**, печатается `FOREIGN_MOUNT … reason=root_expected_uncomputable action=keep_layer`; префикс доказательством не считается нигде |
| 8 | Учёт «мы меняли TTL» только по созданию цепи | `TOUCHED_v4/v6` поднимался лишь при `-N rc=0`: правило в уже существующей своей цепи не считалось изменением → откат не запускался | `TOUCHED_*` поднимается при любом состоявшемся `-N`/`-A`/`-j`; откат покрывает и этот случай |

---

## 2. Матрица доказательств (§10)

Типы стенда: **JVM+sh** — тест исполняет настоящий `/bin/sh` над настоящими файлами, утилиты
подменены (`iptables`, `mount`/`umount`, таблица `mountinfo`); **JVM** — чистая логика.
Команда прогона одна: `bash /home/user/runtests.sh` (kotlinc 1.9.24, JUnit 4, изолированный tmp).
Точечно: `java -cp … org.junit.runner.JUnitCore <Class>`.

| # | Свойство | Тест | Файлы prod | Стенд | Старое поведение | Новое | Результат |
|---|---|---|---|---|---|---|---|
| 1 | Отказ не пишет `END` и не выдаётся за завершение | `Iter52JitOwnTest.guardDoesNotCreateLockForRefusedPayload` + `supersededOpRefusesEvenWithoutLock` | `JitOwn.kt` | JVM+sh | `END rc=0 … refused=1` в terminal | строки `END` нет, есть только `REFUSED` | PASS |
| 2 | Конец владельца — только по точной identity | `Iter52JitOwnTest.ownerEndInTerminalProvesCompletionAndAllowsTakeover` + `endOfAnotherInstanceOrOfAnotherOpDoesNotProveDeath` | `JitOwn.kt` | JVM+sh | любой `END` с `op`+`inst` | нужны `op`,`inst`,`pid`,`pstart` (и `boot`) | PASS |
| 3 | Живой владелец не объявляется мёртвым чужим `END` | `Iter56JitTest.endLineOfAnotherShellIsNotTheEndOfTheLiveHolder` | `JitOwn.kt` | JVM+sh | `CL_STATUS=taken` (перехват живого lock) | `CL_STATUS=busy`, lock на месте | PASS |
| 4 | Устаревшее намерение не перезаписывает новое | `Iter54JitConcurrencyTest.lateClaimOfOlderIntentNeverCancelsNewerIntent` | `JitOwn.kt` | JVM+sh | claim перезаписывался | `CL_STATUS=stale`, `verdict=older_intent` | PASS |
| 5 | Порядок не зависит от перевода часов назад | `Iter56JitTest.newerIntentWinsEvenWhenTheClockWentBackwards` | `JitOwn.kt`, `JitNetCore.kt` | JVM+sh | старое намерение выигрывало | побеждает большее `seq`, `rule=seq` | PASS |
| 6 | Два настоящих shell одновременно → ровно один side effect | `Iter54JitConcurrencyTest.twoRealShellsAtTheSameMomentProduceExactlyOneSideEffect` | `JitOwn.kt` | JVM+sh (2 процесса, барьер) | 1/50 прогонов давало **0** эффектов (отказ из-за короткого претендента) | 0 отказов на 40 прогонах после правки | PASS |
| 7 | Перехват не теряет и не подменяет объект | `Iter56JitTest.takeoverNeverLosesOrReplacesTheObjectItDidNotProveDead` | `JitOwn.kt` | JVM+sh | `mv` поверх живого lock | `proof=owner_dead_exact_inst`, временных `.dead.`/`.pin.` не остаётся | PASS |
| 8 | Возврат на занятое имя не перезаписывает новый lock | `Iter56JitTest.restoreOfAMovedForeignLockNeverOverwritesTheNewOne` | `JitOwn.kt` | JVM+sh | `mv` затирал живой lock | `CL_TAKEOVER_KEPT`, оба объекта целы | PASS |
| 9 | Terminal не читается как «наше завершение» по чужому исполнению | `Iter56JitTest.replayRefusalOfTheSameInstanceNeverFreesTheLiveOwner` + `claimLineCarriesGenerationAndIdentity` + `Iter51JitOwnershipTest.terminalParsingDistinguishesEveryProvablePhase` | `JitOwn.kt`, `JitNetCore.kt` | JVM | `lastInst` подменял предмет | `inst_unproven_legacy` / `term_no_own_execution`, без ложного `FINISHED` | PASS |
| 10 | Identity исполнения переживает перезапуск процесса | `JitOrchestratorTest` (probe по отметке), `JitPersistenceTest` | `JitNetCore.kt` | JVM | `inst` терялся | `OpMark.inst` в отметке, читается обратно | PASS |
| 11 | Claim с новым полем `seq` читается клиентом | `Iter53ClaimTest.realClaimWrittenByClaimCommandIsReadAsPresent` | `JitOwn.kt` | JVM+sh | жёсткий regex → «claim испорчен» | разбор по полям, `CLAIM_STATUS=present` | PASS |
| 12 | Слой с похожим префиксом не снимается | `Iter56MountJitTest.layerWithPrefixLikeRootIsNeverTreatedAsOurs` | `LockMountScripts.kt` | JVM+sh (fake mount/umount) | — (инвариант) | `FOREIGN_MOUNT … ownership_not_proven`, umount не вызывался | PASS |
| 13 | Невычислимый ожидаемый root ⇒ слой сохраняется | `Iter56MountJitTest.unprovableExpectedRootKeepsTheLayerInsteadOfGuessing` | `LockMountScripts.kt` | JVM+sh | слой снимался по `STAGE_PREFIX*` | `reason=root_expected_uncomputable action=keep_layer`; таблица не изменилась | PASS; **на старом коде падает** (проверено откатом файла) |
| 14 | `-N` в существующую свою цепь не считается «чужим» | `Iter55TtlTest.existingForeignChainWithOurNameIsNeverAppendedTo` (и семейство §6.2) | `TtlScript.kt` | JVM+sh (fake iptables) | дописывание в чужую цепь | `chain_exists_not_ours` → стоп | PASS |
| 15 | Учёт каждого успешного `-A`/`-N` | — | `TtlScript.kt` | — | `TOUCHED_*` только по `-N` | `TOUCHED_*` по `-N`/`-A`/`-j` | **теста нет** (см. §4, строка «открыто») |

Проверка «тест ловит дефект» выполнена для строки 13: файл `LockMountScripts.kt` возвращён к
`HEAD`, прогон → `Tests run: 843, Failures: 1` именно на этом тесте; после возврата правки — `OK`.

---

## 3. Что НЕ сделано и почему это названо открытым

1. **§6 звук (`LockSound.kt`, 1946 строк).** Не трогал в этой итерации. Остаются дефекты из
   мандата: `PLANNED` без durable evidence до отправки; no-target/corrupt не должны давать
   Ready; `DONE` не должен закрываться снимком mount; поздний restore по-прежнему опасен.
   Это не «мелочь на потом»: правка требует переписать связку «план → отправка → сверка → DONE»
   и покрыть её стендом с fake `mount`/`umount`. Делать это «на бегу» в конце итерации — значит
   отдать владельцу непроверенную звуковую цепочку.
2. **§8 `NetworkTtl`.** Учёт `-A`/`-N` в скрипте исправлен, но типизированные исходы вместо
   `Pair` (`ttlRuleCounts(): Pair<Int, Int>`, `parseVerifyResult(...): Pair<Status, String>`)
   не вводились: смена типа затрагивает всех вызывающих и требует отдельного прогона UiState.
3. **§9 окна.** `WindowDoors.kt` не менялся: проверка «все writers + физическое действие после
   плана» не выполнена.
4. **§9 логи.** `renameTo` уже проверяется (`FileLogCore.kt:256,381,403,783,825`; при отказе —
   `notes`/возврат данных в spill). Отдельной правки не потребовалось, отдельного теста тоже
   не добавлялось.
5. **Испытание на ГУ.** Не проводилось. Ни один пункт отчёта не утверждает поведение на машине.

---

## 4. Доказательства и их границы

* Все прогоны — **JVM + настоящий `/bin/sh`** на песочнице; shell-утилиты подменены.
  Это доказывает логику скриптов и файловые протоколы, но **не** доказывает поведение
  `/system/bin/sh`, toybox, SELinux и mount-namespace на ГУ.
* `Iter56JitTest` исполняет сгенерированные production-скрипты (`JitOwn.claimCmd/guard`) как есть;
  подделка только во «внешнем мире» (файлы, таблица mountinfo, fake-утилиты).
* Ничего не заявляется про JIT-компиляцию, про слышимость WAV и про фактический возврат Wi-Fi:
  эти вещи измеряются только на устройстве.

## 5. Изменённые файлы

```
app/src/main/java/com/example/voiceapp3/tools/JitOwn.kt
app/src/main/java/com/example/voiceapp3/tools/JitNetCore.kt
app/src/main/java/com/example/voiceapp3/tools/JitNet.kt
app/src/main/java/com/example/voiceapp3/tools/LockMountScripts.kt
app/src/main/java/com/example/voiceapp3/tools/TtlScript.kt
app/src/test/java/com/example/voiceapp3/tools/Iter56JitTest.kt        (новый, 10 тестов)
app/src/test/java/com/example/voiceapp3/tools/Iter56MountJitTest.kt   (новый, 2 теста)
app/src/test/java/com/example/voiceapp3/tools/Iter51JitDev.kt
app/src/test/java/com/example/voiceapp3/tools/Iter52JitOwnTest.kt
app/src/test/java/com/example/voiceapp3/tools/JitIter41Test.kt
```

Новых классов-обёрток, скрывающих старый путь, не вводилось: production идёт по тем же
функциям (`claimCmd`, `guard`, `parseTerminal`, `drop_own_layers`), изменено их поведение.
