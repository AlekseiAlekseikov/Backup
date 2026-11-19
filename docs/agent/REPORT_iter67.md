# iter67 — отчёт (правки A–K)

Ветка `fix/v2`. `applicationId`, `versionCode`/`versionName` (63 / 1.0.63) **не менялись**, `main` не
тронут, `--force` не использовался, форма журнала `[r=<rid>/<ts>/<try>/w=<8hex>]` не менялась.

- `SOURCE_SHA` = `d5a22b7987697b2be4107e6e90cc124ca1705cc2` (последний CODE-коммит iter67)
- Полный прогон юнит-стенда: **`OK (1222 tests)`**
- `typecheck_lock.sh`: **53 → 53**, новых ошибок нет
- Локальный Gradle недоступен (JDK 11, нет Android SDK/сети) — см. §6 «Что НЕ доказано»:
  требование «вывод Gradle с `FAILED`» заменено прогоном того же теста на коде `HEAD` (`/home/user/wbaseB`,
  `git worktree` от базы `93734b7`). Тексты assertion и строки падений приведены дословно.

## 0. Как доказан «красный прогон ДО»

Каждый `Iter67*Test` — обычный JUnit-тест, который прогоняется дважды:

1. **ДО** — на рабочем дереве базы (`git worktree add /home/user/wbaseB HEAD` от `93734b7`), куда
   скопированы **только** новые вспомогательные файлы (чтобы тест компилировался);
2. **ПОСЛЕ** — на текущем дереве с правкой.

Тесты, которые физически не компилируются на базе (новое поле/параметр), отмечены отдельно: это и
есть доказательство отсутствия механизма (компилятор: `unresolved reference`). Полный лог каждого
прогона — `/home/user/kcout/out` + вывод ниже.

## 1. Таблица правок

| Правка | Файл:строка | Тест | Красный до | Зелёный после | Что не доказано |
|---|---|---|---|---|---|
| A | `tools/WriteVerdict.kt:1–140`; `ScreenBrightness.kt:123–170`; `AdbClient.kt:477–500`; `JitNetCore.kt:3355–3390` | `Iter67WriteVerdictTest` (11) | 3 падения: `writeSystemInt больше не печатает SUCCESS по одному readback`, `execExpect объявлен в AdbClient…`, `getprop читается только через сравнение с ожиданием` | `OK (11 tests)` | поведение `Settings.System.putInt` на живой ГУ |
| B | `tools/BrightSync.kt:1–150`; `ScreenBrightness.kt:176–215` | `Iter67BrightSyncTest` (10) | 2 падения: `в теле восстановления остался writeProp`, `settings get как доказательство` | `OK (10 tests)` | реальная причина расхождения settings/sysfs |
| C | `JitNetCore.kt:411–500` (`attempt`,`boot`), `1795–1815` (`JIT_UNBLOCK`), `2115–2140` (`JIT_ATTEMPT`); `tools/JitUnblock.kt:1–120` | `Iter67UnblockTest` (15) | 1 падение: `нет обновления отметки` (+ тест полей не компилируется: `cannot find a parameter with this name: attempt`) | `OK (15 tests)` | что `ts` действительно обновляется на устройстве |
| D | `tools/JitUi.kt:1–140`; `ToolsBackend.kt:246–268`; `AppPerformance.kt:871–890` | `Iter67UiStateTest` (11) | 2 падения: `нет perfJitState`, `нет строки JIT_STATE` | `OK (11 tests)` | вид панели на экране ГУ |
| E | `JitNetCore.kt:2340–2410` (фаза/проба/потеря), `2580–2615` (`probeTerminalDelayed`), `1740` (`scheduler`) | `Iter67ChainDiagTest` (5) | 2 падения: `нет ни одной фазы`, `нет строки absent` (+ отложенный перечитыватель не компилируется) | `OK (5 tests)` | появление строк на живой ГУ |
| F | `tools/CommandAudit.kt:1–80`; `AdbClient.kt:~1975`; `LogRedact.kt:~70` | `Iter67CommandAuditTest` (8) | 2 падения: `транспорт не проверяет команду`, `команда усечена, хвост потерян …(len=144)` | `OK (8 tests)` | что ложных срабатываний нет на всех боевых командах (проверено на литерале скрипта) |
| G | `tools/JitUi.kt:95–140`; `JitNetCore.kt:3268–3300` | `Iter67UiStateTest` (кейсы G) | `нет строки JIT_STATE` | `OK` | снятие/сохранение `jit_pedido` на устройстве |
| H | `Toggles45.kt:230–255` (`staleness`); `JitNetCore.kt:1904–1925` (`JIT_MARK_INVALID`) | `Iter67UnblockTest` (кейсы H) | `нет строки JIT_MARK_INVALID` (`Toggles45.staleness` на базе не существует) | `OK (15 tests)` | происхождение `ts=-196980728` в бою |
| I | `tools/JitNetDecision.kt:1–90`; `JitNetCore.kt:2100–2125`, `2930–3000`; `JitNet.kt` (`putBool`); `ToolsBackend.kt:216–227` | `Iter67NetRestoreTest` (11) | 2 падения: `флаг не ставится`, `нет решения в пути старта` | `OK (11 tests)` | фактический возврат Wi-Fi на ГУ |
| J | `JitNetCore.kt:800–950` (`scriptBody`, `chainScript(termPath)`) | `Iter67ChainScriptTest` (8) | 4 падения: `нет строки исключения`, `нет trap EXIT`, `нет честного пропуска`, `отчёт umount … : -1 vs 323` | `OK (8 tests)` | что `zygote_pid` на ГУ меняется |
| K | `tools/JitPair.kt:1–150`; `JitNetCore.kt:670–780`, `1806–1825`; `JitNet.kt:529–610, 730` | `Iter67PairTest` (13) | 2 падения: `состояние не должно быть терминальным: AMBIGUOUS`, `expected:<1794577054082> but was:<null>` | `OK (13 tests)` | как ведёт себя реальный `SharedPreferences` при отказе диска |

## 2. Что именно изменилось в поведении (цитаты до/после)

**A.** Было:
```kotlin
val ok = Settings.System.putInt(ctx.contentResolver, key, value)
val verified = got == value
if (verified) AppLog.i(TAG, "… → SUCCESS")            // печаталось даже при ok=false
WriteResult(ok && verified, got, …)
```
Стало: `WriteResult(writeAccepted, readbackOk, …)`, `label()` ∈ {`SUCCESS`, `PARTIAL write=… readback=…`,
`FAILED`}; `AdbClient.execExpect(cmd, expect)` возвращает оба факта и пишет
`EXEC_EXPECT rc=… expect=«…» got=«…» ok=… → …`. `getprop dalvik.vm.usejit` сравнивается с ожидаемым.

**B.** Было: `writeSystemInt(...); writeSysfs(lvl); writeProp(lvl)` при любом расхождении (1593 записи
за 8 ч 20 мин). Стало: `syncState(settings, sysfs, want)` → пишется только расходящееся; при `IN_SYNC`
и `SYSFS_UNREADABLE` — ноль записей; после 3 подряд `SYSFS_UNREADABLE` одна строка
`BRIGHT_SYSFS_UNREADABLE path=… errno=… streak=3 action=no_writes` и запись в узел запрещена до
перезапуска процесса. `setprop` из тика убран.

**C.** Отметка получила `attempt` и `boot`; `ts` = время ПОСЛЕДНЕЙ попытки, пишется
`store.updateMark(sentMark)` **до** `JIT_CHAIN_SENT` (в оба хранилища). Каждое решение о разблокировке —
строка `JIT_UNBLOCK op=… age_ms=… limit_ms=… boot_same=… exit=… clicks=… snapshot_applied=no`.

**D/G.** `JitUiState{ON,OFF,ON_NOT_APPLIED,PENDING_RESTART,UNKNOWN}`; `perfJit` — только намерение, рядом
`perfJitState`, `perfJitRuntime`, `perfJitRuntimeRaw`, `perfJitWhy`, `perfJitText`. `pending_restart`
(`prop=true`, `cache=false`) **не** снимает `jit_pedido` и не блокирует клик; в журнал —
`JIT_STATE pedido=… prop=… cache=… result=pending_restart why=zygote_not_restarted`.

**E.** `JIT_OP_PHASE` печатается всегда; проба отсутствия —
`JIT_OP_PROBE op=… result=absent file=… attempt=after_payload`; потеря канала —
`JIT_CHAIN_LOST op=… cause=… payload_maybe_running=1 commands_sent=1 action=no_resend`; одно
отложенное перечитывание terminal через 5 с (`attempt=delayed`).

**F.** `CommandAudit.missingSeparators` ловит `… 2>/dev/null setprop …`; транспортные вызовы с
потерянным разделителем **не отправляются** (`ERR_CMD_MISSING_SEPARATOR`), в журнал идёт
`CMD_SUSPECT missing_sep=… where=«…» action=no_send`. `setprop`/`getprop` в журнале не усекаются до 120.

**H.** `Toggles45.staleness(...) → Pair<Boolean, MarkVerdict>`; любой класс кроме `OK` даёт
`JIT_MARK_INVALID … exit=mark_invalid_<класс> clicks=allowed snapshot_applied=no`. Арифметика
миллисекунд в `sh` не используется: `ts_ms` — конкатенация в Kotlin (проверено тестом).

**I.** Флаг «сеть сняли МЫ» (`jit_we_disarmed_wifi`) ставится до отправки цепочки и снимается только
после подтверждённого включения. `restoreDecision`: `we_disarmed` не смотрит на возраст, отсутствие
claim ≠ чужой claim. Строка `JIT_RESTORE_DECISION op=… restore=… why=… chain_resolved=…`. Владельцу —
свойства `wifiRestoreState` (`denied`/`unread`) и `wifiRestoreNotice`, без красного. `uiReason`
различает `not_sent` и `sent_no_reply`.

**J.** Скрипт: `trap EXIT` до первого side effect (пишет в `/data/local/tmp`), свой каталог через
`pm path com.example.voiceapp3` → `dirname` исключается (`UMOUNT_SKIP self=…`), при неизвестном пути
`umount` не выполняется (`UMOUNT_SKIPPED reason=self_unknown`), `UMOUNT_*` пишутся до `setprop`.
Порядок референса сохранён.

**K.** `PairDiag{SAME,ONE_EMPTY,BOTH_EMPTY,TS_DIFF,STATE_DIFF,OP_DIFF,INVALID_ONE}`: блокирует только
`OP_DIFF`; `TS_DIFF`/`STATE_DIFF` дают копию «хуже» + `repair=rewrite_worse` и строку `JIT_PAIR`; одна
невалидная копия → `used=cps|dps`. Очистка операции удаляет **весь** набор ключей одним `commit()` на
хранилище с проверкой чтением.

## 3. Тесты, которые пришлось согласовать с новыми правилами (и почему)

| Тест | Что изменено | Основание |
|---|---|---|
| `JitIter41Test.markStateConflictIsUncertaintyNotPending` | ожидание `AMBIGUOUS` → `UNDEFINED` + `diag=state_diff`, `repair=rewrite_worse` | §K: расхождение копий ОДНОЙ операции больше не терминально |
| `JitIter41Test.markStateConflictBlocksClickAndCleanup` | `previous_ambiguous` → `chain_undefined`; задача возврата ставится | §K + §I.3 (возврат сети не зависит от исхода) |
| `JitIter41Test` (2 кейса `scheduled`) | причина старта `stored_undefined` → `scheduled` | возврат сети отделён от разрешения операции |
| `Iter51JitOwnershipTest` | сверка литерала референса — с вариантом без добавок §J | §J меняет отправляемый скрипт намеренно |
| `Iter54JitConcurrencyTest` | поиск вызова `JitRef.chainScript(...)` без строки целиком | вызов стал многострочным (§J передаёт путь terminal) |

Ни одна из правок не отменяла сериализацию кликов: второй payload по-прежнему не отправляется
(`DropShell`: `payloadCmds().size == 1`).

## 4. Возражения

1. **§A: `getprop` «только через `execExpect`».** Буквальное требование — читать значение только при
   известном ожидаемом. Диагностическое чтение «что там сейчас» ожидания не имеет по определению;
   поэтому `execExpect` используется там, где ожидание есть (`writeProp`, проверка исхода записи), а
   `readProp` возвращает `PropFact.expectOk=false` и пишет `expect_source=none`, когда ожидания нет.
   Молчаливой замены нет: факт «ожидания не было» назван в журнале.
2. **§B: «`settings get` под root запрещён как доказательство».** В репозитории на пути яркости его и
   не было (доказательство — `Settings.System.getInt`); тест фиксирует это как инвариант, а не как
   исправление. Пункт задания выполнен «по факту», без переписывания кода.
3. **§C/§H: `boot id`.** `JitRefTest.noOwnMechanismsLeftInCore` прямо запрещает ядру JIT содержать
   `boot_id` (историческое правило: boot id не доказывает ни выполнения, ни успеха). Компромисс:
   чтение и сравнение вынесены в `JitUnblock.currentBootId()`, отметка лишь хранит строку `boot=…`, а
   смысл ограничен ровно одним — «окно закрыто сменой загрузки». Иначе пришлось бы удалить
   существующую защиту, что мандат запрещает.
4. **§K: `AMBIGUOUS` при разных `op`.** Оставлен блокирующим (как требует задание), хотя это
   единственный путь к «вечной» блокировке — теперь он требует реально разных операций в копиях, а не
   расхождения времени/состояния.

## 5. Что НЕ доказано

- ⛔ **Ничего не проверено на живой ГУ** в этой итерации: команд на устройстве не выполнялось.
  Не проверено: смена `zygote_pid`, появление `UMOUNT_SKIP`/`END` в terminal-файле, ноль записей
  яркости при `IN_SYNC`, отсутствие `JIT_PEDIDO kept` после очистки, возврат Wi-Fi по флагу.
- ⛔ **Gradle-сборка не запускалась** (JDK 11, нет Android SDK/сети). `:app:assembleRelease` и
  `testDebugUnitTest` на Android не проверены; CI-прогон в этой итерации **ещё не выполнялся**.
  `typecheck_lock.sh` (53→53) покрывает только пакет `tools` против стабов `android.jar`.
- ⛔ Строки `BRIGHT_SYNC`/`JIT_STATE`/`JIT_OP_PHASE`/`JIT_UNBLOCK` на устройстве не снимались.
- ⛔ Вид панели (`perfJitState`, `wifiRestoreState`) на экране ГУ не проверялся: UI-слой не запускался.
- ⛔ Флейк `Iter54JitConcurrencyTest` в этой итерации не воспроизвёлся; отдельной задачей не заводился.
- ⛔ Реальные два процесса APK, `ctl.restart zygote`, слышимость, статус-бар, VHAL — вне зоны проверки.

## 6. Порядок выполнения (по Части 4)

A → B → C+H+K → E+J → I → G+D → F. Каждая правка — отдельный коммит (см. §7), с красным прогоном до
и зелёным после; полный прогон — в конце (дважды: `OK (1220)`, затем `OK (1222)` после тестов H/K/I).

## 7. Коммиты

```
d5a22b7 iter67: I — отказ возврата сети виден владельцу свойством wifiRestoreState/Notice (без красного)
8921573 iter67: K — тест очистки набора ключей (намерение снимается, поколение не откатывается)
8446add iter67: K — очистка операции удаляет весь набор ключей одним commit на хранилище с проверкой чтением
636c1bd iter67: H — Toggles45.staleness с классом отметки, строка JIT_MARK_INVALID с clicks=allowed, арифметика времени в sh проверена
44ea55e iter67: A — writeProp читает факт одним getprop (устранена ошибка типа String? в CI-пути)
d715b68 iter67: F — CommandAudit.missingSeparators (не отправлять склейку), setprop/getprop в журнале не усекаются
564c91d iter67: G и D — pending_restart сохраняет jit_pedido и не блокирует клик, JIT_STATE строкой, JitUiState с perfJitState/perfJitWhy
ccc4750 iter67: I — флаг «сеть сняли мы», restoreDecision (we_disarmed не смотрит на возраст, нет claim ≠ чужой), uiReason not_sent/sent_no_reply, снятие флага только после подтверждения
9667c11 iter67: J — trap EXIT до первого side effect, исключение точки установки своего APK из umount, UMOUNT_* до setprop
9abcb11 iter67: E — JIT_OP_PHASE всегда, JIT_OP_PROBE result=absent, JIT_CHAIN_LOST без повторной отправки, отложенное перечитывание terminal через 5 с
a0884bc iter67: K — согласованное чтение пары (PairDiag/OP_DIFF блокирует, TS/STATE_DIFF не блокируют навсегда) и удаление в оба хранилища с проверкой
002cdb4 iter67: C — attempt/boot в отметке, ts = время последней попытки до JIT_CHAIN_SENT, JIT_UNBLOCK с exit= на каждое решение
0cd27fb iter67: B — яркость пишет только расходящееся (SyncState/BrightRestorer), setprop убран с тика, series SYSFS_UNREADABLE
1ad8a2b iter67: A — WriteResult(writeAccepted, readbackOk) и AdbClient.execExpect: SUCCESS только при двух доказательствах
```

## 7.1 Пуш

`git push origin fix/v2` из этой песочницы **не выполнен**: удалённый репозиторий — приватный
`https://github.com/AlekseiAlekseikov/Backup.git`, в окружении нет ни креденшелов, ни токена
(`fatal: could not read Username for 'https://github.com': No such device or address`). Поэтому:

- все коммиты лежат в `/home/user/w67` (ветка `fix/v2`, HEAD `2993caf` — после этого коммита менялся только отчёт);
- готовый пакет для переноса: `/home/user/iter67_fix_v2_93734b7..4a00687.bundle` (90 КБ, от базы
  `93734b7`). Применение у себя:
  `git fetch /path/iter67_fix_v2_93734b7..4a00687.bundle fix/v2:fix/v2` (или `git bundle unbundle`).

Ни `--force`, ни `main` не затрагивались.

## 8. Короткий итог для владельца

1. Яркость больше не пишет `settings`/`sysfs`/`setprop` каждые 2–3 секунды: пишется только то, что
   реально расходится, а если узел не читается — ничего и одна строка о причине.
2. После клика JIT сеть возвращается даже если скрипт умер: флаг «сеть сняли мы» отменяет и пятиминутный
   лимит, и отсутствие `claim`; на панели появится строка, если сеть вернуть не удалось.
3. Скрипт больше не размонтирует каталог, из которого работает сам APK, и пишет `trap`/`UMOUNT_*` до
   `setprop` — цепочка перестаёт обрываться молча.
4. В журнале появляются `JIT_OP_PHASE`, `JIT_OP_PROBE result=…`, `JIT_CHAIN_LOST`, `JIT_UNBLOCK … exit=`,
   `JIT_MARK_INVALID`, `JIT_STATE`, `BRIGHT_SYNC` — по ним видно, на каком шаге оборвалось.
5. Вечных блокировок из-за расхождения двух копий настроек и из-за мусорного времени отметки больше
   нет: такие случаи называют себя строкой и разрешают клик.
6. Тумблер различает «включено», «включено, не применено», «включено, ждёт перезапуска фреймворка»,
   «неизвестно» — красного нет, новых кнопок нет.
7. Версия не менялась; для установки — сборка из `fix/v2`, `SOURCE_SHA=d5a22b7…`.
