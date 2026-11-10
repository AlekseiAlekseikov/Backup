# REPORT iter33p-fix4 — ответ на REVIEW iter33p-fix3

**Ветка:** `fix/v2`. **Версия:** `versionCode=61`, `versionName="1.0.61"` (не менялась).
**SHA кода:** см. §0.1 — заполняется сразу после push (в этом отчёте заранее указан только
предыдущий подтверждённый CI, см. §0.2).

---

## 0. Про передачу и CI — честно

### 0.1 Что произошло с публикацией fix3

Код fix3 **не попал в `fix/v2`**: ревьюер прав, ветка по GitHub API указывала на `25eef00` (fix2).
Причина техническая и моя: в этой сессии песочница дважды откатывалась к старому снимку, git-токен
жил только в URL remote-а и терялся вместе с `.git`, а `git push` без токена не проходит
(`could not read Username for 'https://github.com'`). Коммит fix3 существовал только локально и был
потерян при откате. Поэтому:

- **в fix/v2 сейчас лежит fix2 (`25eef00`), а не fix3/fix4** — независимая проверка заявленного
  кода до push невозможна, и я это не заявляю как проверенное;
- fix3 и fix4 собраны в один набор изменений и будут опубликованы одним коммитом;
- **нужен git-токен** (только в remote URL, в отчёт/доки не пишется). Без него публикации не будет,
  и это единственная причина, по которой вердикт ревью «нельзя проверить» пока остаётся в силе.

### 0.2 CI

- Последний **подтверждённый** CI: `42858741744` (`25eef00`), job `129978799323`,
  **193 PASSED / 0 FAILED**. Он относится к **fix2** и не является CI исправлений fix3/fix4 —
  именно так, как указано в ревью.
- CI по коду fix3+fix4: **ещё нет** (нет push). После публикации будет добавлен run/job и
  фактическое число `PASSED`/`FAILED`.
- Локальный прогон (не заменяет Android CI): **63 PASS / 0 FAIL**, см. §7.

---

## 1. Блокирующее по существу: продолжение операции после zygote

**Замечание ревью:** если после успешного JIT-рестарта Wi-Fi возвращается только со второго клика,
это блокирующее нарушение исходного требования — интернет должен возвращаться в рамках той же
операции.

**Исправлено именно так.** Введено явное решение о том, что делать с сетью при запуске процесса —
`JitFlow.bootNetAction(intentVerified, phaseProven)` (`JitNetCore.kt:440`):

| Условия | Действие |
|---|---|
| намерение наше и от ТЕКУЩЕЙ загрузки **и** в файле итога есть строка `ARMED / RESTARTING / DONE / ABORTED op=<id>` для id из намерения | `CONTINUE_OPERATION` — возвращаем сохранённые Wi-Fi/AP и санацию |
| нет намерения, либо намерение от другой загрузки, либо фазы нет (ни одной строки нашей операции) | `NONE_COLD` — **ноль сетевых действий**, маркер только читается для лога |

Реализация в оркестраторе — `AppPerformance.netOnProcessStart` (`AppPerformance.kt:930`),
вызывается в начале `jitPendingVerify` (`:984`), т.е. на старте процесса:

```
JIT_NET_CONTINUE reason=post_zygote op=op-… phase_proven=yes (продолжение операции JIT)
JIT_NET_START result=RESTORED|KEPT|EXPIRED|NONE via=post_zygote op=… state=wifi=1,ap=0
   … либо, если продолжение не доказано:
JIT_NET_PENDING marker=wifi=1,ap=0 policy=full op=… intent_verified=false phase_proven=false action=none_cold_start
```

Порядок: рестарт zygote убивает наш процесс, поэтому «после подъёма» физически = следующий запуск
процесса. Это **продолжение той же операции** (её id, её сохранённое состояние, её файл итога), а не
самостоятельная boot-функция: второго клика не требуется, повторного `ctl.restart` нет, JIT не
применяется.

**Обязательный тест из ревью — есть:** `JitNetCoreTest.zygoteContinuationRestoresWifiWithoutSecondClick`
(`app/src/test/java/com/example/voiceapp3/tools/JitNetCoreTest.kt`):

1. операция: `saveBeforeRestart()` → `wifi=1` сохранён и подтверждён;
2. `runPreRestart("true")` = `svc wifi disable` → Wi-Fi выключен, `link=down`;
3. zygote выполнен: файл итога = `ARMED op=…` + `RESTARTING op=…` + `DONE op=… zygote_restart=ok`;
4. **новый процесс**: новый экземпляр `JitNetCore` на тех же префах (маркер — на диске), состояние
   «железа» сохранено в объекте-стенде;
5. `JitFlow.bootNetAction(true, phaseProven) == CONTINUE_OPERATION`;
6. `restorePendingInChain("post_zygote")` → `Recovery.RESTORED`, `wifi == true`, маркер снят,
   в логе `JIT_NET_RESTORE why=chain:post_zygote` и `JIT_NET_CHAIN done context=post_zygote`.

**Как моделируются границы процессов** (ревью просило уточнить): память процесса = экземпляр класса
(`JitNetCore`, счётчики, лог), «диск» = `FakePrefs` (маркер `jit_net_saved`), «устройство» = объект
`Harness` (Wi-Fi, `ap0`, `link`, ответы скриптов). Новый процесс = **новый экземпляр** ядра на тех же
префах и том же `Harness`; старый экземпляр после «смерти» не используется. Ограничение: реальная
смерть процесса и реальный `ctl.restart` так не проверяются — только переходы логики.

Остальные обязательные сценарии:

| Сценарий | Тест | Ожидание |
|---|---|---|
| обычный холодный старт без операции | `coldStartDoesNoNetworkActions` | `NONE_COLD`, 0 вызовов `setWifiEnabled`, 0 скриптов |
| прерывание ДО zygote (намерение есть, фазы нет) | `interruptionBeforeZygoteLeavesNetworkAlone` | `NONE_COLD`, сеть не тронута |
| stale/чужой маркер (другой `op`, другая загрузка) | `foreignOperationResultDoesNotProvePhase` | `NONE_COLD` при `intentVerified=false` и при чужом op |
| отмена рестарта (рестарта не было, но сеть могла остаться выключенной) | `abortedOperationStillContinuesForCompensation` | `CONTINUE_OPERATION`, `CANCELLED` |
| отказ возврата после zygote | `failedPostZygoteRestoreKeepsMarker` | `KEPT`, маркер на диске, повтор — при следующей операции JIT |
| обычная перезагрузка ГУ | `foreignOperationResultDoesNotProvePhase` + `JitBootMark.pendingDecision=STALE` | `NONE_COLD` |

---

## 2. 🟡 Файл отмены: связь с id операции и гонка

**Замечание:** в приведённом фрагменте watcher проверял только существование общего файла, а не
содержимое; гонка между проверкой и `setprop ctl.restart` не обработана.

**Исправлено** (`JitNetModel.watcherScript`, `JitNetCore.kt:145`):

```sh
echo "JIT_ZYGOTE_ARMED op=$OP want=$V delay=${DELAY:-8} pid=$$" > $OUT      # 1) до sleep
sleep ${DELAY:-8}
if [ -f $CANCEL ] && grep -q "op=$OP" $CANCEL 2>/dev/null; then             # 2) СОДЕРЖИМОЕ
  echo "JIT_ZYGOTE_ABORTED op=$OP reason=cancel_file" >> $OUT
  exit 0
fi
ZP0=$(pidof zygote 2>/dev/null); SS0=$(pidof system_server 2>/dev/null)
echo "JIT_ZYGOTE_RESTARTING op=$OP" >> $OUT                                 # 3) точка невозврата
setprop ctl.restart zygote
… ожидание подъёма ≤180 с …
echo "JIT_ZYGOTE_DONE op=$OP runtime=$RT persist=$PS want=$V zygote_restart=ok waited_s=$i" >> $OUT
```

- файл с чужим `op=` **отменой не считается** — проверено исполнением скрипта:
  `JitWatcherScriptTest.cancelFileWithForeignOpDoesNotCancel` (рестарт выполнился),
  `cancelFileWithOurOpPreventsRestart` (рестарт не выполнялся, есть `ABORTED op=<наш id>`);
- гонка «отмена пришла между проверкой файла и рестартом» больше не додумывается: строка
  `JIT_ZYGOTE_RESTARTING op=<id>` пишется непосредственно перед `setprop`. Разбор
  (`JitFlow.cancelOutcome`, `JitNetCore.kt:403`) даёт `CANCELLED` только при `ABORTED` нашего id,
  `RESTARTED` при `RESTARTING`/`DONE` нашего id, иначе `UNKNOWN`;
- `UNKNOWN` не считается успехом: `restartStillPossible` = true, маркер сети и снимок AVAS
  сохраняются, в логе `restart_possible=yes`;
- асинхронные окна отмены (10 × 1 с) и текст `JIT_WATCH_CANCEL op=… outcome=…`.

---

## 3. 🟡 `canClearMarker`: исходные состояния Wi-Fi OFF и AP-only

**Замечание:** требование `link=up` всегда — неверно для случая, когда Wi-Fi был выключен до операции.

**Исправлено** (`RestoreResult`, `JitNetCore.kt:250–310`): требования зависят от того, что было
сохранено:

```kotlin
val linkRequired: Boolean get() = wifiWasRequested          // сохранено wifi=1
val sanitizeRequired: Boolean get() = wifiWasRequested
private val linkOk = !linkRequired || link == "up"
private val sanitizeOk = !sanitizeRequired || (sanitizeLine && sanitizeLeft == 0)
val fullyRestored = essentialsConfirmed && linkOk && sanitizeOk
val canClearMarker = fullyRestored
```

Тесты `restoreResultSeparatesEssentialsFromInternet`:

| Сохранено | Подтверждено | Маркер |
|---|---|---|
| `wifi=1` | `link=up`, `left=0` | снимается |
| `wifi=1` | `link=down` | **остаётся** |
| `wifi=1` | `left=2` / нет строки санации | **остаётся** |
| `wifi=0` | `link=down`, санации нет | **снимается** (сеть вернулась в исходное состояние; Wi-Fi не включаем) |
| `wifi=0, ap=1` (AP-only) | AP подтверждён, `wlan0` без IPv4 | **снимается** |
| `wifi=1, ap=1` | AP не подтверждён | **остаётся** |

Бесконечного ложного retry нет: при `wifi=0` маркер снимается сразу, сеть не трогается (тест
`failedPostZygoteRestoreKeepsMarker` показывает обратное — «не подтвердилось → маркер жив → повтор
при следующей операции JIT», а не циклические попытки на старте).

---

## 4. 🟡 Частичное исполнение shell, потеря ответа, AVAS, `ip rule`

- **Потеря ответа / частичное исполнение.** Уже в fix3: `propsMaybeTouched = true` сразу после
  отправки скрипта; откат attempt-ится всегда, успех — по **точному** разбору read-back
  (`tag("rt")`/`tag("pr")`, `JitNetCore.kt:520`), а не по подстроке (`want_rt=false` больше не
  выглядит как `rt=false`). Статусы: `props_rollback=ok|failed|skipped`, `cancel=CANCELLED|RESTARTED|UNKNOWN`,
  `net_marker=cleared|kept`. Тест `propsRollbackIsVerified` (4 случая, включая ловушку подстроки).
- **Гонки AVAS.** `AvasCore.scheduleRestore` целиком под тем же `synchronized(lock)`, что и `save`,
  плюс перепроверка снимка перед удалением (`AvasCore.kt:101–120`, лог `cleared=yes|no`).
  Тест `AvasCoreTest.expiredScheduleDoesNotDropForeignSnapshot` (сохранение «между проверкой и
  удалением» → новый снимок остаётся).
- **Удаление `ip rule`.** Только по полному `spec` и только при однозначности (один `oif`, ровно
  `wlan0`, уникальный priority); при неоднозначности — отказ с `reason=`. Абсолютной защиты не
  заявляю: в логе пишется наблюдаемый остаток `sanitize_left=<n>`, совместимость `ip rule del <spec>`
  с `ip` на целевой прошивке подтверждается только полем.
- **«Автономный маркер возврата».** Такого больше нет: маркер не запускает действия ни на обычном
  старте (`NONE_COLD`, §1), ни по просрочке (`reason=expired`, без сетевых действий).

---

## 5. Проверка заявленного в fix3 (для независимого ревью после публикации)

Чтобы пункты было легко проверить, вот точные места:

| Что | Где |
|---|---|
| `ARMED` до задержки и до рестарта | `JitNetCore.kt:145` (`watcherScript`), шаг 1 |
| строки с `op=<id>` | там же: `ARMED`, `ABORTED`, `RESTARTING`, `DONE` |
| отмена по содержимому файла | там же, шаг 2 (`grep -q "op=$OP"`) |
| точка невозврата | там же, шаг 3 (`JIT_ZYGOTE_RESTARTING`), затем `setprop ctl.restart zygote` |
| очистка старого результата | `AppPerformance.kt:1402` (`resetWatcherResult`), вызов `:1170` |
| сверка по id | `JitNetCore.kt:380–420` (`armedFor/doneFor/abortedFor/restartingFor/cancelOutcome`) |
| решение «сеть при старте» | `JitNetCore.kt:440` (`bootNetAction`), `AppPerformance.kt:930` |
| возврат как продолжение операции | `JitNetCore.kt:601` (`restorePendingInChain`), вызовы `AppPerformance.kt:939`, `:1163` |
| нет `pkill`/`pgrep`/`kill` | отсутствуют во всех `tools/*.kt` (grep) |
| откат props по read-back | `JitNetCore.kt:505–530` |
| маркер только при полном подтверждении | `JitNetCore.kt:250–310` |

---

## 6. Порядок действий (как в коде)

**Клик по тумблеру** — `AppPerformance.jitApplyWithZygote` (`AppPerformance.kt:1150`):

| # | Действие | Строка |
|---|---|---|
| 0 | `JitNet.restorePendingInChain("pre")` — хвост прошлой операции | `:1163` |
| 1 | `JitNet.saveBeforeRestart()` — чтение Wi-Fi/AP + подтверждённая запись маркера | `:1166` |
| 2 | `opId` + `writeIntentConfirmed(want, opId)` + ворота `JitFlow.gate` | `:1170–1172` |
| 3 | `resetWatcherResult()` — `rm -f` итога/отмены + проверка чтением | `:1192` |
| 4 | `AvasRestore.saveBeforeRestart()` + `jitProfiles()` | `:1218` |
| 5 | `JitNet.runPreRestart()`: `svc wifi disable` → слив `oif wlan0` ≤20 с → `setprop` | `:1230` |
| 6 | заливка скрипта watcher-а + `WSIZE` | `:1243` |
| 7 | `nohup sh <watcher> <op> <want> 8 5 &` + `waitWatcherArmed(op)` | `:1296` |
| 8 | при отказе: `cancelWatcherAndVerify(op)` → `abortAfterNet(...)` | `:1300–1325` |
| 9 | `ToolsStep.PARTIAL` + лог `… net_return=post_zygote op=<id> verify=next_start` | `:1330` |
| 10 | **после подъёма процесса**: `netOnProcessStart` → `CONTINUE_OPERATION` → возврат сети | `:930`, `:984` |

Разрешённый набор команд не расширен: `svc wifi disable`, `ip rule … oif wlan0`,
`setprop dalvik.vm.usejit*`, `setprop ctl.restart zygote` (внутри watcher-а по клику),
`setWifiEnabled`/`startSoftAp` (возврат к сохранённому), работа с **собственными** файлами в
`/data/local/tmp` (`jit_zygote_watch.sh`, `jit_zygote_result.txt`, `jit_zygote_cancel`).

---

## 7. Локальный прогон тестов

`scripts/localjvm/` (без Android SDK, заглушки `android.*`/`org.junit.*`; в Gradle не входит):

```
JitNetCoreTest (26)      PASS 26 / FAIL 0
AvasCoreTest (13)        PASS 13 / FAIL 0
JitWatcherScriptTest (4) PASS 4  / FAIL 0   ← исполняет НАСТОЯЩИЙ sh-скрипт watcher-а
AvasContractTest (5)     PASS 5  / FAIL 0
JitToggleChainTest (5)   PASS 5  / FAIL 0
NetSanitizePlanTest (7)  PASS 7  / FAIL 0
BuildMarkerTest (3)      PASS 3  / FAIL 0
ИТОГ: PASS=63 FAIL=0
```

Компиляцией (заглушки Android) проверено, что `AppPerformance.kt`, `JitNetCore.kt`, `JitNet.kt`,
`AvasCore.kt`, `AvasRestore.kt` не имеют ошибок компиляции.

Поймано локальным прогоном и исправлено до публикации:
1. `hasLine` склеивал строки, читаемые через `tr '\n' '|'` → старый `ARMED+DONE` мог подтвердить
   новую операцию;
2. `rollbackProps` сравнивал `rt=false` подстрокой → совпадало с `want_rt=false`;
3. в новом `RestoreResult` отсутствовало требование канала для `wifi=1` (проверено тестом);
4. `AvasCoreTest` не компилировался (`SnapshotStore` vs `MemStore`).

---

## 8. Ограничения (без них отчёт был бы нечестным)

- Полевого прогона JIT нет; `ctl.restart zygote`, `svc wifi disable`, `startSoftAp` на ГУ не
  проверялись.
- Продолжение после zygote опирается на файл итога: если скрипт не успел записать ни одной строки
  (`ARMED` пишется сразу, но процесс/каталог могут быть недоступны), фаза не доказана → сеть на
  старте не трогается, маркер остаётся до следующей операции JIT. Это безопасный, но не
  автоматический исход — он виден в логе `JIT_NET_PENDING … phase_proven=false`.
- Отмена возможна только до строки `RESTARTING`; после неё честный статус — `RESTARTED`.
- После `UNKNOWN`/`RESTARTED` снимок AVAS сохраняется и будет обработан по обычному расписанию
  `AvasRestore` (это существующий механизм, не новый).
- `SOURCE_SHA` обновится handoff-коммитом; `git rev-parse` в сборке не используется.
