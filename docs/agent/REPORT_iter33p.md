# REPORT iter33p (1.0.61) — ТРЕТИЙ ЗАХОД, ПРАВКА 2: 10 проблем §0.0 мандата

**Ветка:** `fix/v2` (без `--force`, `main` не тронут). **Версия:** `versionCode=61`,
`versionName="1.0.61"` (APK собирает и подписывает владелец).

> **Почему правка 2.** Правка 1 (`aed18fb`) скопировала последовательность `j9`→`la`→`e2`→`bb/r7`,
> но признаком завершения нашего скрипта в ней оставалось **выключенное радио**. Мандат
> `PROMPT_iter33p_3й_заход_ИСПРАВЛЕНИЕ_1.0.61.md` §0.0 показал 10 мест, где это даёт ложный
> результат: сеть мог выключить другой shell, а `svc wifi disable` — вообще ПЕРВАЯ команда
> скрипта, поэтому «сеть выключена» ничего не говорит о том, дошёл ли скрипт до `setprop` и
> `ctl.restart zygote`.
>
> Правка 2 меняет признак завершения на единственный доказуемый — **ответ транспорта на НАШ
> вызов** (`shell=REPLIED`). Всё, что не доказано, останавливается в `HOLD_UNSAFE_PHASE`: ноль
> записей в хранилища, ноль сетевых действий, решение за владельцем. Механика референса
> (`j9`→`la`→`e2`→`bb/r7`) не менялась; скрипт по-прежнему совпадает с DEX символ в символ.
>
> Документ заменяет прежний отчёт целиком; §0.4 сохранён как история правки 1 (`aed18fb`) с
> пометкой, что номера строк там относятся к тому коммиту.

---

## 0. Публикация и CI

| Что | Значение |
|---|---|
| база (до работы) | `e8f5e23` (`fix/v2`, handoff второго захода) |
| код этой итерации | **`aed18fb`** (`aed18fb38769109c53a42e96aef16de7ea396063`) |
| CI по коду | run **`42954771103`**, job **`130307151164`** — **SUCCESS**, `BUILD SUCCESSFUL in 4m 27s` |
| тесты в CI | **186 PASSED / 0 FAILED** (`grep -c ' PASSED$'` = 186, `grep -c ' FAILED$'` = 0 по полному логу) |
| artifact | `app-debug`, id `13826483630`, 177 171 113 Б |
| handoff | **`297f1ef`** — только `docs/agent/**` + `app/buildinfo.properties` (`SOURCE_SHA=aed18fb`) |
| CI по handoff правки 1 | run **`42956152521`**, job **`130311632733`** — **SUCCESS**, `BUILD SUCCESSFUL in 2m 57s`, **186 PASSED / 0 FAILED**, artifact `13827676157` |
| база правки 2 | **`6436be8`** (было в CI: run `42957218012`, job `130315052654`, **186 / 0**) |
| **код правки 2** | **`61bfddd`** (`61bfddd0c55352156c34a55706c2811ae07e9634`) — только JIT-файлы, UI-строка и тесты |
| handoff правки 2 | `25ff688` — только `docs/agent/**` + `app/buildinfo.properties` |
| **правка компиляции** | **`1d64e54`** — в `ToolsPanel.addOneShot` осталась ссылка на локальную `jitConfirmed` (лишняя вставка при патче): CI `42960987191` по `25ff688` упал на `:app:compileDebugKotlin`, `e: ToolsPanel.kt:923:49 Unresolved reference 'jitConfirmed'`. Строка убрана, поведение не менялось |
| **CI по коду правки 2** | run **`42962463984`**, job **`130331961735`** (SHA `1d64e54`) — **SUCCESS**, `BUILD SUCCESSFUL in 3m 5s` |
| тесты в CI | **210 PASSED / 0 FAILED** (`grep -c 'Z .* PASSED$'` = 210, `FAILED$` = 0 по полному логу джобы); из них JIT/лог — те же 95, что локально (§0.1) |
| artifact | `app-debug`, id `13829668249`, 177 178 359 Б |
| `SOURCE_SHA` | **`1d64e54`** — последний коммит кода, прошедший CI (§0.3) |
| локальный прогон | **`OK (95 tests)`** @ `61bfddd`/`1d64e54` — JIT/лог-подмножество на стабах и настоящем `/bin/sh` (§7) |

`SKIPPED` в логе — только Gradle-задачи (`checkKotlinGradlePluginConfigurationErrors`,
`kspDebugUnitTestKotlin`), ни одного пропущенного теста.

### 0.1 Разбивка JIT-тестов в CI (из полного лога той же джобы)

| класс | в CI правки 2 | локально | было (правка 1) | что нового |
|---|---|---|---|---|
| `JitOrchestratorTest` | **23** | 23 | 20 | `lostChainNeverRestoresEvenWhenNetworkIsDown`, `bothRadiosOffWaitsForProvenCompletion`, `bothRadiosOffWithRepliedChainIsDoneAndNextClickAllowed` |
| `JitRefTest` | **14** | 14 | 13 | `startDecisionRequiresProvenShellCompletion`, `shellFieldRoundTripsAndOldSnapshotIsUnknown` |
| `JitLifecycleTest` | **7** | 7 | — | барьеры на каждом шаге цепочки (см. §4) |
| `JitStoreConsistencyTest` | **8** | 8 | — | CPS/DPS: `commit=false`, расхождение, приоритет, одно/оба хранилища, удаление ключа |
| `JitUiStateTest` | **5** | 5 | — | тумблер = подтверждённый факт, новых кнопок нет |
| `JitChainScriptTest` | 8 | 8 | 8 | без изменений |
| `JitStartZeroWritesTest` | 5 | 5 | 5 | без изменений |
| `JitLogsCancelTest` | 5 | 5 | 5 | `restoreOnlyTurnsNetworkOn` переписан на `runInline=1`; `HOLD` убран из чёрного списка |
| `AvasIndependenceTest` | 5 | 5 | 5 | без изменений |
| `JitBootBoundaryTest` | 4 | 4 | 4 | без изменений |
| `JitNoPersistTest` | 3 | 3 | 3 | без изменений |
| `JitRulesReadOnlyTest` | 2 | 2 | 2 | без изменений |
| `LogFilesTest` (§9, лог по датам) | 6 | 6 | 6 | без изменений |
| **итого JIT/лог** | **95** | **95** | 71 | +24 |
| **весь набор в CI** | **210** | — | 186 | +24 |

Полный набор проекта (Android-модуль) исполняется только в CI — локально нет Android SDK.
Поэтому здесь и далее «95» — это JIT/лог-подмножество, а не весь набор: цифры CI по коду
`61bfddd` указаны в §0 после прогона и **не** подменяются локальными.

Остальные 115 PASSED — тесты других подсистем (TTL, климат, статус-иконки, плагин, reconnect и
т. д.), они не менялись.

### 0.2 Что именно в коммите `61bfddd`

| файл | изменение |
|---|---|
| `tools/JitNetCore.kt` | `JitRef.Shell {UNKNOWN, REPLIED, LOST}` — единственный доказанный признак завершения; `startDecision` (`HOLD_UNSAFE_PHASE` вместо решения по сети); `clickGate` (блок и при `wifi=0,ap=0` этой загрузки); `JitRef.Request` (invoked/accepted/detail); `confirmedState(fact, op)`; `verifyApplied` → `settled = definitive && want==true && shell==REPLIED && phase!=ARMED`; `restoreNetwork` возвращает `Request`, guard `wifiOn()==null ⇒ wifi_state_unknown`; `writeOp(before)` — запись только по актуальному снимку; `opGate`/`opLock` — одна операция; `publishForTest` для теста позднего писателя |
| `tools/JitNet.kt` | `judgeWrite`/`WriteOutcome`: `ok` / `readback_ok_commit_false` / `readback_mismatch` / `priority_read_mismatch` / `no_store`; публикация ключа только при совпадении обратного чтения; `AndroidStore.putStringConfirmed`/`putBoolConfirmed` через этот вердикт; `AndroidNetState.setWifi/startAp` → `JitRef.Request` (в т.ч. отказ `false`, `void`/`null` и исключение рефлексии) |
| `tools/AppPerformance.kt` | `jitConfirmedState` = `JitRef.confirmedState(LAST_JIT_FACT, readJitOp())`; `refreshJitRuntimeAsync(..., onDone)` — обновление виджета после фонового чтения; `JIT_START_RESULT` с честными `stdout`/`status`; `BUSY` в `why` |
| `tools/ToolsBackend.kt` | `perfJitConfirmed` — факт из кэша, а не preference |
| `ui/ToolsPanel.kt` | строка «Сейчас на устройстве: …» (+ фаза/скрипт/сеть операции), обновляется по `onDone`; новых кнопок нет |
| тесты | +`JitLifecycleTest` (7), +`JitStoreConsistencyTest` (8), +`JitUiStateTest` (5), +`JitLifecycleShell`; переписаны `JitOrchestratorTest` (23), `JitRefTest` (14), `JitLogsCancelTest`, `JitFake` |

### 0.3 `SOURCE_SHA`

`app/buildinfo.properties` → `SOURCE_SHA=1d64e54` — последний коммит КОДА, прошедший CI
(`42962463984`, 210/0). `git rev-parse` в сборке не используется: значение читает
`app/build.gradle.kts` и кладёт в `BuildConfig.GIT_COMMIT`. Коммит `61bfddd` — та же логика,
`1d64e54` отличается только удалением одной ошибочной строки в `ToolsPanel.addOneShot`.

### 0.4 История: замечания REVIEW к `78a3248` (правка 1, `aed18fb`)

> Номера строк в таблице ниже относятся к `aed18fb`. В `61bfddd` часть кода переписана —
> актуальные ссылки в §0.5 и §3. Таблица сохранена как история, чтобы было видно, что именно
> проверялось в прошлый раз.

| № | замечание REVIEW к `78a3248` | что сделано | код | тест |
|---|---|---|---|---|
| 1 | «признак выполнения» включал совпадение `getprop`, хотя свойство могло остаться от прошлой операции | признак — только сеть: Wi-Fi/AP, включённые в снимке, сейчас выключены | `JitNetCore.kt:635` | `JitOrchestratorTest.timeoutWithQueuedShellDoesNotTriggerEarlyRestore` |
| 2 | «одна попытка на процесс» скрывала продолжение операции | ограничения нет; на каждом старте решение по фактам; добавлен `startGate` (single-flight), чтобы параллельные `onCreate`/`onStartCommand` не вернули сеть дважды | `JitNetCore.kt:534` | `concurrentProcessStartsDoNotRestoreTwice`, `secondStartInSameProcessRetriesRestore` |
| 3 | pref `perf_auto_otimizar` писался до попытки применить → отказ выглядел как «включено» | ключ пишет ядро, ПОСЛЕ подтверждённого снимка; при отказе в UI честный `BLOCKED` | `AppPerformance.kt:190`, `JitNetCore.kt:455` | `snapshotIsNotAcceptedWithoutReadback`, `partialStoreWritesAreDetected` |
| 4 | сверка при `unknown` снимала намерение и переписывала снимок | `unknown` не снимает намерение; для чужой/неопознанной загрузки сверка только читает (`persist=false`) | `JitNetCore.kt:761` | `verifyOutcomes`, `previousBootStartWritesNothingAndKeepsSnapshot` |
| 5 | `parseProp` считал `unknown` вывод с переводом строки → сверка не завершилась бы никогда | многострочность проверяется на обрезанном значении; `rc≠0`/`ERR_`/`a\nb` → `UNKNOWN` | `JitNetCore.kt:144` | `JitRefTest.onlyTrueAndFalseAreFacts` |
| 6 | `JitOp.click` не возвращал `ChainRun` (`run` всегда `null`) — исход терялся | возвращается реальный `ChainRun` (ok/`channel_lost`) | `JitNetCore.kt:413` | `JitChainScriptTest.orderIsReferenceOrder`, `JitOrchestratorTest.*` |
| 7 | `AndroidNetState.setWifi` не компилировался (`w.isWifiEnabled = on` как выражение) | вызов `setWifiEnabled(on)`, результат — «запрос принят», а не состояние | `JitNet.kt:134` | CI `:app:compileDebugKotlin` + `restoreNetwork` тесты |
| 8 | `AvasRestore`-ветка: `reportLegacyAvasSnapshot` вызывалась, но не существовала | функция восстановлена: только чтение файла-остатка и строка лога | `AppPerformance.kt:774` | `AvasIndependenceTest.leftoverAvasSnapshotIsNotApplied` |
| 9 | `JitBootMark.sameLoad` (±60 с) — недоказанный признак «той же загрузки» | удалён; JIT сравнивает только `boot_id`-теги (`BootMatch`), `epoch…` ⇒ `UNKNOWN` ⇒ ничего не делаем | `JitNetCore.kt:261`, `JitBootMark.kt` | `unknownBootTagBlocksRestore`, `JitBootMark` покрыт CI-сборкой |
| 10 | мёртвые константы `JIT_STATE_ON_LINE`/`OFF_LINE` (обещали `false` заранее) | удалены; UI показывает факт из кэша (`true|false|unknown`) | `ToolsBackend.kt:190`, `ToolsPanel.kt:1126` | `JitBootBoundaryTest.*` |
| 11 | скрипт расходился с DEX на один символ (`$(($_n + 1))` вместо `$((_n + 1))`) | приведён символ в символ; тест фиксирует литерал из smali и длину 392 | `JitNetCore.kt:105` | `JitRefTest.verbatimReferenceLiteralFromSmali` |

---

### 0.5 Десять проблем §0.0 мандата правки 2 → что именно изменено

Формулировки — из мандата (`PROMPT_iter33p_3й_заход_ИСПРАВЛЕНИЕ_1.0.61.md`, §0.0). Для каждой
строки: чем закрыто, где в коде и каким тестом доказано. «Ноль записей» везде означает, что тест
считает ВСЕ записи хранилища (`JitFake.Store.writes`) и все сетевые запросы
(`JitFake.Net.calls`), а не только ожидаемый ключ.

| № | проблема §0.0 | закрытие | код | тест |
|---|---|---|---|---|
| 1 | выключенный Wi-Fi/AP не признак завершения: сеть мог выключить другой shell, `svc wifi disable` — первая команда скрипта, задержка возврата может истечь | признак завершения один — `Shell.REPLIED` (транспорт ответил на наш `exec`). `wifi_now`/`ap_now` пишутся в лог с пометкой «только наблюдение, НЕ признак завершения». Нет ответа ⇒ `HOLD_UNSAFE_PHASE` | `JitNetCore.kt:238` (`Shell`), `:361-376` (`startDecision`), `:755-761` (лог) | `JitLifecycleTest.foreignWifiOffIsNotEvidence`, `JitOrchestratorTest.lostChainNeverRestoresEvenWhenNetworkIsDown`, `JitOrchestratorTest.timeoutWithQueuedShellDoesNotTriggerEarlyRestore` |
| 2 | оба радио off — не доказательство: `DONE` при timeout/потере канала; второй клик; `DONE` до проверки загрузки; запись на чужой/неизвестной загрузке | `NOTHING_TO_RESTORE` только при `REPLIED`; `clickGate` не пускает второй клик при `ARMED/SENT` этой загрузки даже при `wifi=0,ap=0`; на чужой/неизвестной загрузке — ноль записей | `JitNetCore.kt:366-371`, `:389-397`, `:776-796` | `JitLifecycleTest.bothRadiosWereOffDoesNotBecomeDoneByItself`, `JitOrchestratorTest.bothRadiosOffWaitsForProvenCompletion`, `bothRadiosOffWithRepliedChainIsDoneAndNextClickAllowed`, `JitStartZeroWritesTest.previousBootStartWritesNothingAndKeepsSnapshot` |
| 3 | CPS/DPS атомарность не доказана | `judgeWrite` пишет оба хранилища и **читает оба обратно**; `ok` только при совпадении; `commit=false` при совпавшем чтении — предупреждение; расхождение/`no_store` ⇒ снимок не принят, скрипт не отправляется. Семантика `Prefs2` не тронута | `JitNet.kt:64-122` (`WriteOutcome`,`judgeWrite`), `:141-175` (`putStringConfirmed`/`putBoolConfirmed`) | `JitStoreConsistencyTest` (8 тестов: ok, `commit=false`, расхождение, приоритет, одно хранилище, оба, удаление ключа, сквозная запись снимка), `JitOrchestratorTest.snapshotIsNotAcceptedWithoutReadback`, `partialStoreWritesAreDetected` |
| 4 | гонки старт↔клик↔возврат: старая копия `op` в `SENT`/`DONE`; `startGate` пишет сверку параллельно; восстановление теряет `pedidoChecked` | `opGate` — одна операция на приложение (`BUSY`/`JIT_BUSY`); все записи идут через `writeOp(before)`: если снимок изменил другой писатель, запись отбрасывается и это видно в логе; `RESTORED` пишется по ПЕРЕЧИТАННОМУ снимку | `JitNetCore.kt:498-523` (`opLock`,`opGate`,`writeOp`), `:767-781`, `:986-1000` | `JitLifecycleTest.staleWriterCannotOverwriteNewSnapshot`, `clickAndStartNeverOverlap`, `JitOrchestratorTest.concurrentClicksStartExactlyOneOperation`, `concurrentProcessStartsDoNotRestoreTwice` |
| 5 | тумблер отдавал preference, а не подтверждённый факт | `JitRef.confirmedState`: `on` только при `getprop=true`; `off` только при `false` + `Phase.DONE` + `want=false`; иначе «состояние неизвестно». Виджет обновляется после фонового чтения (`onDone`); новых кнопок нет | `JitNetCore.kt:197-202`, `ToolsBackend.kt:201`, `ToolsPanel.kt:1139-1168` | `JitUiStateTest` (5: `onOnlyWhenRuntimeIsTrue`, `offOnlyWithProvenOperation`, `unknownIsShownHonestly`, `panelHasNoNewButtonsAndUpdatesWidget`, `refreshCallbackIsCalledOnce`) |
| 6 | `RESTORED` ≠ безопасное завершение и мог навсегда заблокировать клики | `RESTORED` ставится только при подтверждённом чтении состояния радио; `DONE` из `RESTORED` — только через сверку (`definitive && want==true && REPLIED`). Не доказано ⇒ `HOLD_UNSAFE_PHASE`, ноль записей; перезагрузка ГУ лечением не считается | `JitNetCore.kt:774-781`, `:994-1030` | `JitOrchestratorTest.apiRefusalIsNotReportedAsRestored`, `restoreNotRepeatedAfterSuccess`, `secondStartInSameProcessRetriesRestore` |
| 7 | AP: результат `startSoftAp` игнорировался (`issued=true` без исключения), guard не срабатывал при `wifiOn()==null` | `JitRef.Request(invoked, accepted, detail)`; рефлексия: `false` ⇒ `rejected`, `void`/`null` ⇒ «вызван, не подтверждён», исключение ⇒ `notInvoked`; `wifiOn()==null` ⇒ `wifi_state_unknown`, запрос AP НЕ отправляется. В логе раздельно `request=` / `state_after=` / `confirmed=` / `reason=` | `JitNetCore.kt:99-105`, `:855-905`, `JitNet.kt:224-262` | `JitOrchestratorTest.apAndWifiAppliedLateAreStillConfirmed`, `apiRefusalIsNotReportedAsRestored`, `JitLogsCancelTest.logSeparatesStateRequestAndErrors` |
| 8 | сверка до выполнения считалась результатом; намерение стиралось по случайному `getprop` | `settled` требует `want==true` + `shell==REPLIED` + фаза не `ARMED`; `unknown`/`rc≠0`/`ERR_`/пусто/cache `null` ⇒ `unknown` и намерение СОХРАНЯЕТСЯ; результат обеих записей проверяется и логируется (`saved_pedido=`, `saved_op=`) | `JitNetCore.kt:181-196` (`verify`), `:994-1030` | `JitOrchestratorTest.verifyOutcomes`, `wantFalseWithBrokenChannelIsNotSuccess`, `JitRefTest.verifyNeverCallsUnknownSuccess` |
| 9 | запуск после zygote не доказан | не заявляется ни `BOOT_COMPLETED`, ни рестарт: в логе `restart=not_claimed`; продолжение привязано к старту ПРОЦЕССА (`.BootReceiver`, `VoiceAssistantService.onCreate`, `App.jitBootProbe`), скрытых сервисов и boot-фильтров не добавлено | `AppPerformance.kt:805-853`, `JitNet.kt:314-329` | `JitBootBoundaryTest.restartOnlyFromClickPath`, `noBootBackfillAnywhere`, `JitRefTest.chainOutcomeDoesNotClaimRestart` |
| 10 | `MOUNT_STEP_ENABLED=false` ⇒ не точная копия; нельзя утверждать, что lazy umount даёт `ENOENT` и лечится только reboot | шаг реализован символ в символ и выключен; в отчёте он помечен ⛔ и разобран как недоказанный; владельцу даны короткие read-only команды (mountinfo/namespace root-транспорта), массовый unmount не запускался | `JitNetCore.kt:86`, `:123-155` | `JitChainScriptTest.mountStepTouchesOnlyDataAppWhenEnabled`, `JitRefTest.mountStepOnEqualsReferenceVerbatim` |

Итог по §0.0: **8 закрыто (✅), 1 остаётся вопросом владельца (🟡 — монтирования, ⛔ по исполнению),
1 недоказуемо в принципе (запуск после zygote — заявлять нельзя, поэтому не заявляется).**

---

## 1. Механика референса — что именно скопировано

Проверено по `CentralEXAuto_v3.3.9.apk` (md5 `c3f1664125953b9851bf4483b265135f` из ZIP
`8a65c6c29749c64d4e335c57490a347a`); декомпиляция jadx 1.4.7 + baksmali 2.5.2, выписки —
`docs/agent/ref/centralexauto_v339_jit_chain.md`.

### 1.1 Клик: `w0/j9.onClick`

```java
wifi = wm.isWifiEnabled();  ap = ap0 != null && ap0.isUp() && ap0.getInetAddresses().hasMoreElements();
prefs.edit().putBoolean("jit_pedido", target).putBoolean("perf_auto_otimizar", target)
     .putString("jit_restaurar", "wifi=" + wifi + ",ap=" + ap).commit();   // ДО любых изменений
pool.execute(new la(activity, target, progress));
```

Наша копия: `JitOp.prepare` (`JitNetCore.kt:619`) — чтение состояния (`wifiOn`/`apUp`, `null` ⇒
`REFUSED_STATE_UNKNOWN`, ноль команд), одна запись снимка с подтверждением обратным чтением,
затем ключи референса `jit_pedido`/`perf_auto_otimizar`/`jit_restaurar` (`JitRef.KEY_*`), затем
скрипт. Отличие — «снимок одним значением» `jit_op` (§3).

### 1.2 Скрипт: `w0/la.run` — символ в символ

`JitRef.chainScript` (`JitNetCore.kt:143`) генерирует ровно литерал DEX (`w0/la.smali:326`+`:342`,
подстановка `la.smali:153`); при `mountStep = true` длина 392 символа и **байтовое равенство**
проверено независимо (см. §4):

```
svc wifi disable
_n=0
while [ $_n -lt 20 ]; do
  [ "$(ip rule show | grep -c 'oif wlan0')" = "0" ] && break
  sleep 1; _n=$((_n + 1))
done
for _m in $(grep ' /data/app/' /proc/mounts | awk '{print $2}'); do   ← шаг §1.1, ВЫКЛЮЧЕН
  umount -l "$_m" 2>/dev/null
done
setprop dalvik.vm.usejit <true|false>
setprop ctl.restart zygote          ← последняя команда
```

Таймаут транспорта `CHAIN_TIMEOUT_MS = 40_000` (`JitNetCore.kt:68`), как `H1(40000, …)`.

### 1.3 Возврат после подъёма фреймворка — `w0/e2.run`

```java
if (s != null && Pattern.compile("^wifi=([01]),ap=([01])$").matcher(s).matches()) {
    prefs.edit().remove("jit_restaurar").apply();
    handler.postDelayed(new e2(restauro, service, 0), 15000L);
}
// e2: if (ap) service.U("pós-JIT", false);                        // AP ПЕРВЫМ
//     if (wifi && !wm.isWifiEnabled()) wm.setWifiEnabled(true);   // затем Wi-Fi
```

Наша копия — `JitOp.onProcessStart` + `JitOp.restoreNetwork` (`JitNetCore.kt:712`, `:845`): та же
регулярка (`JitRef.parseRestore`), пауза `RESTORE_DELAY_MS = 15_000` (`:65`), **AP первым**, затем
Wi-Fi; AP ждётся до 20 с шагом 1 с (`AP_POLL_*`, как `U`), Wi-Fi — до 10 с шагом 0.5 с (наше
отличие, §3).

**Как служба запускается и получает данные ИМЕННО этой операции — гипотеза, а не факт (§0.0 п.9):** `ctl.restart zygote` даёт
новую загрузку фреймворка ⇒ система поднимает приложение ⇒ `.BootReceiver` (BOOT_COMPLETED /
LOCKED_BOOT_COMPLETED / QUICKBOOT_POWERON) и `VoiceAssistantService.onCreate`/`onStartCommand` →
`AppPerformance.checkPendingZygoteVerify` → `jitPendingVerify` → `JitNet.onProcessStart`; плюс
`AppPerformance.jitBootProbe` из `App.kt`. Данные операции — префы (`jit_op` + три ключа
референса) и тег загрузки: они лежат на диске, поэтому переживают смерть процесса. А вот ЧТО
именно система вызовет после `ctl.restart zygote` и когда — не доказано ни референсом, ни нами:
гарантированный `BOOT_COMPLETED`, сроки подъёма и порядок `onCreate` не заявляются (§3, строка 13).

**Что здесь НЕ доказано и не заявляется:** что наш `onCreate` будет вызван ровно через 15 000 мс.
15000 мс — это литерал `0x3a98` в `CentralWatchdogService.onCreate` (smali); мы выдерживаем паузу
сами внутри продолжения, а не «надеемся, что сервис поднимется вовремя». Если старт позже — сеть
вернётся позже.

**Граница AVAS (§1.3 мандата, п.8 §0.1).** В JIT-цепочке референса AVAS не участвует:
`w0/j9.onClick` пишет только три префа Wi-Fi/AP + `jit_*`, `w0/la.run` содержит только
`svc`/`ip`/`umount`/`setprop`, `w0/e2.run` — только `U()` (AP) и `setWifiEnabled(true)`;
`w0/bb`/`w0/r7` читают `getprop` и `maps`. Mute-состояние и тип звука живут отдельно
(`w0/qa`, `w0/t2`, `w0/f2`, `w0/r1`; `CentralWatchdogService.z()/A()`, планировщики `H0()`/`I0()`
с 12 000 мс и отдельный wake/CarService-путь с 5 000 мс). Поэтому сохранение/восстановление AVAS
вокруг рестарта zygote для точного JIT **надо убрать**: оно (а) не входит в разрешённую
последовательность, (б) добавляет записи в систему, которых в референсе нет, (в) его собственные
таймеры (`5/12/30 с`) смешиваются с `15 с` возврата сети и делают лог нечитаемым. У нас AVAS-код
удалён целиком, независимые функции звука не тронуты (§5).

### 1.4 Сверка: `w0/bb` + `w0/r7`

```java
getprop = "getprop dalvik.vm.usejit" → trim() == "true"
maps    = есть ли "dalvik-jit-code-cache" в /proc/self/maps  (ошибка → null)
ok = (pedido == getprop) && (!pedido || maps != Boolean.FALSE);
prefs.edit().remove("jit_pedido").apply();
```

Наша копия — `JitRef.verify` (`JitNetCore.kt:181`) + `JitOp.verifyApplied` (`:964`): та же формула,
но `rc≠0`/`ERR_`/пусто/неожиданное значение → `UNKNOWN` (а не «не применено»), и `UNKNOWN` не
снимает намерение. Отличие — §3.

---

## 2. Что удалено как «наше, не референса» (§1.2 мандата)

| удалено | где было | чем подтверждается, что нет |
|---|---|---|
| `persist.dalvik.vm.usejit`, `persist.sys.dalvik.vm.usejit`, `dalvik.vm.usejitprofiles` (запись) | `AppPerformance.kt` | `JitNoPersistTest.noPersistWrites`, `chainHasSinglePropertyWrite` |
| `bootRestoreJit` + повтор через 30 с, `jitPersistBackfill` | `AppPerformance`, `VoiceAssistantService` | `JitBootBoundaryTest.noBootBackfillAnywhere`, `JitStartZeroWritesTest.noOwnMechanismsInStartPath` |
| watcher, файл-отмена, `sleep 8`, журнал шагов, TTL операции, окно 60 с, PID zygote, `CONTINUE/COMPENSATE/HOLD` | `JitNetCore.kt` | `JitStartZeroWritesTest.noOwnMechanismsInStartPath`, `JitLogsCancelTest.noCancelOrCompensationMechanism` |
| санация/удаление `ip rule` (`del`, `flush`, `ndc`, `settings`, `route`) | `JitNet.kt` | `JitRulesReadOnlyTest` (код + реальное исполнение скрипта) |
| файлы-маркеры `jit_verified`/`jit_pending_verify`, `jitProps()`, `lastJitInitKey` | `AppPerformance.kt` | `verifyJitOnly` — только чтение (`AppPerformance.kt:947`) |
| допуск ±60 с «та же загрузка» (`JitBootMark.sameLoad`) | `JitBootMark.kt` | `JitOrchestratorTest.unknownBootTagBlocksRestore` |
| `AvasCore.kt`, `AvasRestore.kt` целиком | `tools/` | `AvasIndependenceTest.avasMechanismIsGone` |
| мёртвые строки-обещания `JIT_STATE_ON_LINE`/`OFF_LINE` | `AppPerformance.kt` | `JitBootBoundaryTest.startPathIsReadOnly` |

### 2.1 Остатки старых версий на ГУ (только чтение)

`AppPerformance.jitBootProbe` (`:729`) читает `persist.dalvik.vm.usejit`,
`persist.sys.dalvik.vm.usejit`, `dalvik.vm.usejitprofiles` и печатает
`JIT_BOOT_LEGACY … action=none reason=leftover_from_previous_versions need=owner_decision`.
Ничего не очищает и не перезаписывает. Read-only способ проверки владельцем:

```
adb shell getprop persist.dalvik.vm.usejit
adb shell getprop persist.sys.dalvik.vm.usejit
adb shell getprop dalvik.vm.usejitprofiles
```

### 2.2 Остаточный снимок AVAS прошлых версий

Файл `filesDir/avas_snapshot` **не применяется и не удаляется**: `reportLegacyAvasSnapshot`
(`AppPerformance.kt:774`) пишет только `AVAS_LEGACY snapshot=present|none … action=none write=none
need=owner_decision`. Поведение при старом снимке: ничего — JIT его не читает, AVAS-механизма в
сборке нет.

---

## 3. Таблица соответствия: референс → фактический код → совпадение → тест

Формат — по §4 мандата: для каждого элемента видно, ЧТО делает референс, ЧТО делает наш код,
совпадает ли это, где смотреть и чем доказано. «Неизвестно» — не отговорка, а состояние, которое
нельзя проверить без исполнения на ГУ; такие строки помечены и вынесены в §9.

| № | элемент референса | фактический код | совпадение / отличие / неизвестно | файл:строка | тест или недоказанное |
|---|---|---|---|---|---|
| 1 | **клик** `w0/j9.onClick`: читает `wifi`/`ap`, пишет 3 префа одним `commit()` (результат не проверяется), затем `pool.execute(la)` | `JitOp.prepare` + `JitOp.click`: чтение состояния (`null` ⇒ `REFUSED_STATE_UNKNOWN`, ноль команд), запись снимка с подтверждением обратным чтением, ключи `jit_pedido`/`perf_auto_otimizar`/`jit_restaurar`, затем скрипт | **совпадает** по порядку и значениям; **отличие** — `jit_op` одним значением и проверка результата записи (§3.1) | `JitNetCore.kt:619-686`, `:524-560` | `JitOrchestratorTest.clickSavesSnapshotThenRunsReferenceChain`, `unknownNetworkStateMeansNoChain` |
| 2 | **снимок** `jit_pedido`/`jit_restaurar` = `wifi=<0/1>,ap=<0/1>` | те же ключи + `jit_op` (`Net`/`Phase`/`Shell`/`bootTag`/`want`/`checked`), формат `wifi=…,ap=…` — та же регулярка `^wifi=([01]),ap=([01])$` | **совпадает** по ключам и формату строки; **отличие** — ссылка на загрузку и фаза (иначе снимок прошлой загрузки применялся бы к чужому решению) | `JitNetCore.kt:88`, `:245-274`, `:275-313` | `JitRefTest.parseRestoreAcceptsOnlyReferenceFormat`, `opPayloadRoundTripAndStrictness` |
| 3 | **скрипт** `w0/la.run`: `svc wifi disable` → слив ≤20 с → `umount -l /data/app/` → `setprop usejit` → `setprop ctl.restart zygote` | `JitRef.chainScript` (392 символа при `mountStep=true`) | **совпадает символ в символ** (проверено сравнением с литералом из `w0/la.smali:326,342`); таймаут 40 000 мс как `H1(40000,…)` | `JitNetCore.kt:123-155`, `:68` | `JitRefTest.verbatimReferenceLiteralFromSmali`, `scriptShape`; `JitChainScriptTest.orderIsReferenceOrder` (реальный `/bin/sh`) |
| 4 | **монтирования** (шаг §1.1 внутри того же скрипта) | реализован, `MOUNT_STEP_ENABLED=false` | **отличие**: не выполняется ⇒ **нельзя называть копию полной**; обратимость и последствия для чужих APK не доказаны (⛔, §3.2) | `JitNetCore.kt:86`, `:143` | `JitChainScriptTest.mountStepTouchesOnlyDataAppWhenEnabled`; на ГУ не проверялось |
| 5 | **продолжение** `CentralWatchdogService.onCreate` → `postDelayed(e2, 15000)` при совпадении регулярки, без метки загрузки | `JitOp.onProcessStart`: `startDecision` по `bootMatch` + `Shell`; пауза `RESTORE_DELAY_MS=15_000`; снимок НЕ удаляется, а становится историей (`DONE`/`RESTORED`) | **совпадает** по 15 000 мс (литерал `0x3a98`) и «AP первым»; **отличие** — решение принимается только при доказанном завершении скрипта, иначе `HOLD_UNSAFE_PHASE`; **неизвестно** — что наш `onCreate` будет вызван ровно через 15 с (не заявляется) | `JitNetCore.kt:361-376`, `:712-820`; `AppPerformance.kt:805-853` | `JitLifecycleTest.disableThenClientDeathThenLateZygoteRestoresNothing`, `JitOrchestratorTest.repliedChainThenRestoreOnNextStart`, `previousBootSnapshotIsNeitherAppliedNorDeleted` |
| 6 | **AP** `e2`: `service.U("pós-JIT", false)` → рефлексия `startSoftAp(null)`, результат не смотрится, latch 25 с, опрос `v()` ≤20 000 мс шагом 1 с | `restoreNetwork`: guard `wifiOn()==true` (как `U`), `startAp()` возвращает `Request(invoked, accepted, detail)`, опрос `apUp()` 20 с × 1 с | **совпадает** по порядку, вызову и окну ожидания; **отличие** — результат вызова различается (`invoked`/`accepted`), состояние подтверждается только чтением `isUp()`+адреса; `wifiOn()==null` ⇒ `wifi_state_unknown` без запроса (в референсе `!isWifiEnabled()` при ошибке дал бы `true`) | `JitNetCore.kt:845-910`, `JitNet.kt:240-262` | `JitOrchestratorTest.apAndWifiAppliedLateAreStillConfirmed`, `apiRefusalIsNotReportedAsRestored` |
| 7 | **Wi-Fi** `e2`: `if (wifi && !wm.isWifiEnabled()) setWifiEnabled(true)`, ожидания нет | `setWifi(true)` + опрос `wifiOn()` 10 000 мс шагом 500 мс | **отличие (осознанное)**: «запрошено» ≠ «включено», иначе UI/лог показывали бы успех при отказе; при отказе — `request_rejected_still_off_10s`, снимок не завершён | `JitNetCore.kt:911-935`, `:78-79` | `restoreOnlyTurnsNetworkOn`, `JitOrchestratorTest.apiRefusalIsNotReportedAsRestored` |
| 8 | **порядок возврата**: AP первым, затем Wi-Fi | `restoreNetwork` — AP-блок, затем Wi-Fi-блок | **совпадает** | `JitNetCore.kt:845-937` | `JitChainScriptTest` не участвует; `JitOrchestratorTest.repliedChainThenRestoreOnNextStart` |
| 9 | **readback/сверка** `w0/bb`+`w0/r7`: `getprop` `trim()=="true"`, `maps` содержит `dalvik-jit-code-cache`, `ok=(pedido==getprop)&&(!pedido||maps!=false)`, `jit_pedido` снимается ВСЕГДА | `JitRef.verify` (та же формула) + `verifyApplied`: `rc≠0`/`ERR_`/многострочно/пусто ⇒ `UNKNOWN`; намерение снимается только при `definitive && want==true && REPLIED && phase!=ARMED`; запись через `judgeWrite` с обратным чтением обоих хранилищ | **совпадает** по формуле; **отличие** — `unknown` не считается успехом и не снимает намерение; **отличие** — публикация ключа только после подтверждённого чтения | `JitNetCore.kt:181-196`, `:964-1030`; `JitNet.kt:64-122` | `JitOrchestratorTest.verifyOutcomes`, `wantFalseWithBrokenChannelIsNotSuccess`, `JitStoreConsistencyTest` (8) |
| 10 | **UI** референса: прогресс `H1(45000)` в шторке | строка «Сейчас на устройстве: …» под существующим тумблером: `on`/`off`/«неизвестно» + фаза/скрипт/сеть операции; обновляется по завершении фонового чтения | **отличие**: прогресса нет (процесс умирает на `ctl.restart`), вместо него — подтверждённый факт или честное «неизвестно»; **новых кнопок и тумблеров нет** | `ToolsPanel.kt:1139-1168`, `ToolsBackend.kt:201`, `JitNetCore.kt:197-202` | `JitUiStateTest` (5), `JitBootBoundaryTest.startPathIsReadOnly` |
| 11 | **ошибки/транспорт**: `H1` даёт `rc`/вывод; референс исход не различает | `chainOutcome`: `REPLY_RECEIVED` при `rc==0` и выводе без `ERR_`, иначе `CHANNEL_LOST`; `rc=137`/`ERR_ADB_TIMEOUT` — потеря канала, а НЕ «выполнено»; задание в очереди транспорта не отменяется | **отличие**: разделены «ответ получен» / «канал потерян» / «состояние неизвестно»; в логе отдельные поля, в т.ч. `restart=not_claimed` | `JitNetCore.kt:400-410`, `AppPerformance.kt:840-860` | `JitRefTest.chainOutcomeDoesNotClaimRestart`, `JitLogsCancelTest.lostChannelIsLoggedAsErrorNotSuccess`, `unknownFactIsLoggedAsUnknown` |
| 12 | **AVAS**: в `j9`/`la`/`e2`/`bb`/`r7` НЕ участвует | `AvasCore.kt`/`AvasRestore.kt` удалены; JIT-код не содержит `Avas*`, `audioserver`, `avas_*`; остаток `filesDir/avas_snapshot` только фиксируется строкой лога | **совпадает** (в референсе связи нет ⇒ у нас тоже) | `AppPerformance.kt:774-790` | `AvasIndependenceTest` (5) |
| 13 | **запуск после zygote**: `ctl.restart zygote` — последняя команда; как именно поднимется наше приложение, референс не доказывает | продолжение — на старте ПРОЦЕССА (`.BootReceiver`, `VoiceAssistantService.onCreate`, `App.jitBootProbe`), данные — на диске | **неизвестно**: гарантированный `BOOT_COMPLETED`/рестарт не заявляется; скрытых сервисов, boot-фильтров и своих команд не добавлено | `AppPerformance.kt:805-853`, `JitNet.kt:314-329` | `JitBootBoundaryTest.restartOnlyFromClickPath`, `noBootBackfillAnywhere` |
| 14 | **логи**: у референса только `Log` в logcat | `AppLog` → дневной файл; строки `JIT_*`; состояние/запрос/ошибка/`unknown` — разными полями, `internet=not_checked` | **отличие** (требование владельца, §9 мандата) | §6 | `LogFilesTest` (6), `JitLogsCancelTest.logSeparatesStateRequestAndErrors` |

### 3.1 Как референс узнаёт «ту же загрузку» — и почему так нельзя

В `CentralWatchdogService.onCreate` проверяется только формат `jit_restaurar`; никакой метки
загрузки нет. Значит, снимок, оставшийся от прошлого клика (например, приложение не поднялось),
будет применён при следующем старте — даже если это другая загрузка и водитель уже включал Wi-Fi
руками. Поэтому у нас в снимке есть тег загрузки, и применяется он только при доказанном
совпадении `boot_id`. `epoch…`-теги (когда `/proc/sys/kernel/random/boot_id` недоступен) считаются
`UNKNOWN`: допуск по часам ничего не доказывает (NTP, ручная правка), поэтому ±60 с удалён (§0.4 п.9).

### 3.2 Шаг монтирований §1.1 — анализ read-only и запрос решения (⛔ не выполнено)

Команда референса: `grep ' /data/app/' /proc/mounts | awk '{print $2}'` → для каждой найденной
точки `umount -l "<точка>"`.

Что это значит технически (анализ по механике Linux и по составу `/proc/mounts` на Android 9,
без запуска чего-либо на ГУ):

1. **Что там смонтировано.** В `/data/app/` живут каталоги установленных APK; на Android 9
   встречаются варианты: (а) обычный путь без отдельного монтирования — тогда в `/proc/mounts`
   строки нет и шаг ничего не делает; (б) bind-mount каталога/файла APK (обновления, `adb install`
   поверх), (в) loop/`dm-verity`-образы. Строка `/data/app/` в `/proc/mounts` — признак (б)/(в).
2. **Что делает `umount -l`.** Ленивое снятие: точка исчезает из дерева сразу, ресурсы
   освобождаются, когда закроется последний пользователь. Для уже запущенных процессов обычно
   ничего не рвётся (их файловые дескрипторы живы), но новый `open()`/`mmap()` по снятому пути,
   по механике Linux, попадает в уже отсоединённое дерево и обычно даёт `ENOENT`. Это вывод из
   механики VFS, а НЕ измерение на ГУ: полем `ENOENT` не подтверждён и за факт не выдаётся.
3. **Последствия для ЧУЖИХ приложений.** Если точка — APK другого приложения (в том числе
   системного), то после снятия его код нельзя заново отобразить в память: первый же холодный
   старт/перезапуск процесса = падение или «приложение не установлено». Это ровно тот класс
   необратимых правок, который запрещён уточнением 1.0.61.
4. **Bind-mount на изолированных данных** (`/data/user/0/...`, `ce`/`de` storage): снятие точки с
   APK не затрагивает данные, но и не помогает: JIT-кэш (`dalvik-jit-code-cache`) лежит не там, а
   в `/data/dalvik-cache`, который шаг не трогает.
5. **Обратимость.** Штатного «вернуть как было» в рамках нашей программы нет: обратное
   `mount` требует знать исходные параметры (source, fstype, opts) и права, которых у нас нет;
   после `umount -l` вернуть точку штатными средствами приложения нельзя, а перезагрузка ГУ как
   «лечение» запрещена и лечением не считается. Значит, требование «после выключения функций/удаления приложения система должна
   возвращаться в сток» этим шагом нарушается.
6. **На ГУ проверять запрещено** (мандат: исследование — только чтение, массовый unmount не
   запускать), поэтому состав `/data/app/` в `/proc/mounts` полем не подтверждён.

**Вывод:** обратимость и безопасность для чужих приложений **не доказаны**, поэтому шаг не
исполняется. Он реализован символ в символ, включается одной константой
(`JitRef.MOUNT_STEP_ENABLED`) после решения владельца; тест
`JitChainScriptTest.mountStepTouchesOnlyDataAppWhenEnabled` доказывает, что при включении снимаются
только точки `/data/app/`, шаг стоит до `setprop`, и появляется ровно 3 строки.

**Как проверить это, ничего не меняя (§0.0 п.10).** `/proc/mounts` описывает namespace ТОГО
процесса, который его читает, поэтому `cat /proc/mounts` через транспорт и `grep` внутри нашего
скрипта могут видеть РАЗНЫЕ деревья. Читать надо mountinfo root-транспорта и сравнивать namespace
с namespace PID 1 — всё только на чтение, без единого `umount`:

```
adb shell 'echo pid=$$; readlink /proc/self/ns/mnt; readlink /proc/1/ns/mnt'
adb shell 'grep -c " /data/app" /proc/self/mountinfo'
adb shell 'grep " /data/app" /proc/self/mountinfo | head -n 5'
adb shell 'ls -l /proc/self/ns/mnt /proc/1/ns/mnt'
```

Если `readlink` для `self` и `1` совпадают, mountinfo транспорта применим к системе; если нет —
вывод скрипта ничего не говорит о том, что видят zygote и приложения, и шаг тем более нельзя
включать. Команды read-only, массовых `umount` в них нет.

**Что нужно от владельца:** (а) вывод указанных выше четырёх команд (есть ли вообще точки
`/data/app/` и тот ли это namespace), (б) решение «выполнять / не выполнять»; без него шаг
остаётся выключенным (`MOUNT_STEP_ENABLED=false`).

---

## 4. Тесты (§4 мандата)

Локально: **95 / 95 PASS** на настоящем `/bin/sh` с подставными
`svc`/`ip`/`setprop`/`getprop`/`sleep`/`umount` (§7). Это JIT/лог-подмножество; полный набор
проекта исполняется только в CI (§0).

Барьеры этой правки (§4 мандата: «disable → задержка → гибель/старт процесса → ранний возврат →
поздний zygote; сеть выключена другим действием, команда в очереди; оба радио off; timeout без
отмены; поздняя команда после нового клика») реализованы на управляемом устройстве
`JitLifecycleShell.kt`: `answering` (ответит ли транспорт), `queued` (задание осталось в очереди),
`accepted` (что вернул API), `deviceSteps` (журнал реальных шагов устройства), `stepWifiDisable()`,
`foreignAction()`, `getprop`/`getpropRc`/`cache`.

| класс | тестов | что покрывает |
|---|---|---|
| `JitOrchestratorTest` | 23 | цепочка целиком: клик → снимок → транспорт (с очередью) → старт процесса → возврат AP→Wi-Fi → сверка. Потеря канала при выключенной сети (`lostChainNeverRestoresEvenWhenNetworkIsDown`), оба радио off без доказательства (`bothRadiosOffWaitsForProvenCompletion`) и с доказательством (`bothRadiosOffWithRepliedChainIsDoneAndNextClickAllowed`), чужая загрузка, `epoch`-тег, повреждённый снимок, отложенное появление AP/Wi-Fi, отказ API, повтор после неудачи, отсутствие повторного возврата, `verify` ok/cache_missing/unknown/not_applied, одновременные клики и одновременные старты |
| `JitLifecycleTest` | 7 | барьеры по шагам: `disableThenClientDeathThenLateZygoteRestoresNothing` (смерть клиента после `svc wifi disable`, поздний zygote — ноль сети и записей), `foreignWifiOffIsNotEvidence` (Wi-Fi выключил кто-то другой — не признак), `bothRadiosWereOffDoesNotBecomeDoneByItself`, `timeoutDoesNotCancelAndBlocksEverything` (timeout транспорта не отменяет задание ⇒ ноль действий), `staleWriterCannotOverwriteNewSnapshot` (поздний писатель), `clickAndStartNeverOverlap`, `repeatedStartIsAllowedButWritesNothingWithoutProof` |
| `JitStoreConsistencyTest` | 8 | CPS/DPS: обе записи + обратное чтение; `commit=false` при совпавшем чтении; расхождение; расхождение приоритетного чтения; недоступно ОДНО хранилище (подтверждается другим); недоступны ОБА (`no_store`); `null` ⇒ ключа нет в обоих; сквозная запись снимка через то же правило |
| `JitUiStateTest` | 5 | `on` только при `getprop=true`; `off` только при `false` + `DONE` + `want=false`; иначе «неизвестно»; в панели нет новых кнопок, а виджет обновляется по `onDone`; колбэк вызывается один раз |
| `JitRefTest` | 14 | формат `jit_restaurar`; литерал из DEX символ в символ (392); первая/последняя команда, окно 20 с; `parseProp`; формула `bb/r7`; `bootMatch`; `startDecision` требует `shell=REPLIED`; `Shell` переживает сериализацию, старая запись без поля ⇒ `UNKNOWN`; `clickGate`; `chainOutcome` не заявляет рестарт |
| `JitChainScriptTest` | 8 | реальный `sh`: порядок команд, ранний выход при 0 правилах, исчерпание 20 с, `false`-ветка, только `/data/app/` при включённом шаге, отказ `setprop` не обрывает цепочку, ноль запрещённых литералов |
| `JitStartZeroWritesTest` | 5 | обычный старт и «через 30 с»: ноль записей, ноль команд, ноль сетевых запросов; остаток `jit_pedido=true` не переписывается; чужая загрузка — ноль записей и снимок цел; в стартовом коде нет рестарта/`persist`/своих механизмов |
| `JitLogsCancelTest` | 5 | в логе раздельно состояние, запрос, ошибка и `unknown`; восстановление только включает (ни одного `false`-запроса); механизма отмены/компенсации нет |
| `AvasIndependenceTest` | 5 | AVAS-механизм удалён; JIT-код не ссылается на AVAS; в цепочке нет AVAS/audio-команд; остаток `avas_snapshot` только фиксируется; звук не тронут |
| `JitBootBoundaryTest` | 4 | нет `bootRestoreJit`/`jitPersistBackfill`/повтора 30 с/записи `persist.*`; `ctl.restart` ровно один раз и только в тексте скрипта; старт — только чтение; остатки persist только читаются |
| `JitNoPersistTest` | 3 | нет записи `persist.*`/`usejitprofiles`; в скрипте одно `setprop`; старые значения только читаются |
| `JitRulesReadOnlyTest` | 2 | `ip rule` только считается (код + исполнение): ни `del`, ни `flush`, ни `ndc`, ни `settings` |
| `LogFilesTest` | 6 | имя `voiceapp3-DD.MM.YYYY.log`, локальная дата, append, retention 14 сут. только своих файлов, чужие имена не трогаются, ошибка записи не ломает приложение |
| **итого** | **95** | |

Отдельно проверено независимо от кода (Python-сравнение с литералом из `w0/la.smali:326,342`):
`chainScript(true, mountStep = true) == литерал DEX` (392 символа), false-вариант отличается только
значением свойства.

### 4.1 Удалённые тесты и эквивалентное покрытие

| удалён | тестов | почему | эквивалент |
|---|---|---|---|
| `JitChainEndToEndTest` | 14 | проверял прежнее ядро (`continueAfterBoot`, свой журнал), API которого больше нет | `JitOrchestratorTest` (20) — те же сценарии на `JitOp` + очередь транспорта |
| `JitBootTest` | 5 | опирался на `JitBootMark.sameLoad`/файл-намерение | `JitStartZeroWritesTest` (5) + `JitBootBoundaryTest` (4) |
| `JitBootMarkTest` | 4 | проверял удалённый допуск ±60 с | `JitOrchestratorTest.unknownBootTagBlocksRestore` + `previousBootSnapshotIsNeitherAppliedNorDeleted` |
| `JitTogglePathTest` | 4 | статические проверки прежней цепочки | `JitBootBoundaryTest` (4) + `JitNoPersistTest` (3) |
| `JitNetCoreTest`, `JitToggleChainTest`, `AvasCoreTest`, `AvasContractTest`, `JitPersistTest`, `JitWatcherScriptTest`, `NetSanitizePlanTest` | удалены раньше (первый заход) | проверяли код, которого больше нет | `JitRefTest`, `JitOrchestratorTest`, `JitRulesReadOnlyTest`, `AvasIndependenceTest` |

Ни один тест не удалён «потому что красный».

---

## 5. AVAS — отдельные функции, не часть JIT (§2 мандата, п.8 §0.1)

**По коду референса:** mute — `w0/qa`/`w0/t2`/`w0/f2`, выбор типа звука — `w0/r1`; вызовы из
`CentralWatchdogService.z()`/`A()`; планировщики `H0()`/`I0()` — **12000 мс**, отдельный
wake/CarService-путь — **5000 мс**. В `w0/j9`, `w0/la`, `w0/e2`, `w0/bb`, `w0/r7` нет ни одного
обращения к AVAS (проверено по smali: в `la` только `svc`/`ip`/`umount`/`setprop`).

**У нас:** `AvasCore.kt`/`AvasRestore.kt` удалены; JIT-код (`JitNetCore.kt`, `JitNet.kt`) не
содержит ни `Avas*`, ни `audioserver`, ни `0x21407057`, ни `avas_*`
(`AvasIndependenceTest.jitCodeDoesNotTouchAvas`); журнал вызовов реального скрипта содержит только
`svc`/`ip`/`setprop`/`umount` (`chainHasNoAvasOrAudioCommands`). Новых кнопок, мута, правок
`audioserver` и звуковых файлов нет. Независимые функции звука (`tools/LockSound.kt`) не менялись.

---

## 6. Логи

`AppLog` → `LogFiles`: `getExternalFilesDir(null)/logs/voiceapp3-DD.MM.YYYY.log`, fallback
`filesDir/logs/`, локальная дата, append, retention 14 суток только своих файлов, ошибка записи не
роняет приложение (`LogFilesTest`, 6 тестов). JIT-строки этой итерации:
`JIT_APPLIED_REQUEST`, `JIT_SNAPSHOT` (+ `nothing_to_restore`), `JIT_REFS`, `JIT_PHASE`,
`JIT_CHAIN_SENT`, `JIT_CHAIN_RETURN`, `JIT_CLICK`, `JIT_START`/`JIT_START_RESULT`, `JIT_NET_CONT`,
`JIT_NET_RESTORE` (`request=` / `state_before=` / `state_after=`), `JIT_APPLIED`,
`JIT_PEDIDO kept|cleared`, `JIT_NOT_APPLIED`, `JIT_PROBE`, `JIT_BOOT`, `JIT_BOOT_LEGACY`,
`JIT_STORE`, `AVAS_LEGACY`. Состояние, запрос, ошибка и `unknown` — разными полями; слово
`internet=` всегда `not_checked` (подтверждается состояние радио, а не наличие сети).

---

## 7. Локальный прогон

```
=== compile ===  (без ошибок)
=== run ===      OK (95 tests)
```

Как повторить: `bash /home/user/runtests.sh` (kotlinc 1.9.24 + `android.jar` + junit 4.13.2;
скрипт сам переходит в рабочую копию `W=/tmp/w9`, иначе source-сканирующие тесты не находят
`app/src/main/java`, в сборку не входит, каталог вне `app/src/**`).

Это **не** прогон на ГУ: `WifiManager`, `startSoftAp`, `ctl.restart zygote` подменены; Android-модуль
локально не собирается (нет Android SDK) — его проверяет CI (`:app:compileDebugKotlin`). Дополнительно
локально проверено: `JitNetCore.kt`+`JitNet.kt` типизируются против настоящего `android.jar`
(стабы `AppLog`/`AdbClient`/`Prefs2`/`BgWorker` — `/home/user/scripts/localjvm/Stubs.kt`), а
`ToolsPanel.kt`/`AppPerformance.kt`/`ToolsBackend.kt` компилируются без ошибок в собственном коде
(остаются только ошибки разрешения `R`/классов проекта при одиночной компиляции — их закрывает CI).

## 8. Чек-лист

### 8.1 Одиннадцать пунктов §0.1 мандата restart-2

| № | требование | статус | доказательство |
|---|---|---|---|
| 1 | гипотеза не выдаётся за гарантию; продолжение — по фактам | ✅ | `JitNetCore.kt:361`, `:732`; `JitOrchestratorTest.timeoutWithQueuedShellDoesNotTriggerEarlyRestore`, `JitLifecycleTest.foreignWifiOffIsNotEvidence` |
| 2 | копия и ограничения различимы; всё, что не из референса, помечено | ✅ | §3 (таблица 11 отличий), §0.4 |
| 3 | сеть не возвращается и снимок не снимается по одному boot ID | ✅ | `JitNetCore.kt:361` (`startDecision`: только `bootMatch` + `Shell.REPLIED`, иначе `HOLD_UNSAFE_PHASE`), `JitStartZeroWritesTest` |
| 4 | на ГУ ничего не выполнялось | ✅ | все прогоны локальные/CI; полевое подтверждение не заявляется |
| 5 | при невыполнимости безопасно — остановиться и запросить решение | ✅ | §3.2 (монтирования), §9 |
| 6 | монтирования не запрещены, но необратимые правки запрещены | ✅ | шаг реализован и выключен (§3.2) |
| 7 | AVAS не часть JIT; старый снимок обычным стартом не применяется | ✅ | §5, §2.2, `AvasIndependenceTest` (5) |
| 8 | граница AVAS объяснена кодом в §1.3 | ✅ | §1.3 «Граница AVAS», §5 |
| 9 | анализ монтирований read-only, вывод + запрос решения | 🟡 | §3.2: анализ есть, проверка на ГУ запрещена → нужно решение владельца |
| 10 | таблица «референс vs реализация» | ✅ | §3 |
| 11 | тесты §4 мандата, удалённые перечислены | ✅ | §4, §4.1 |

### 8.2 Требования исходного мандата (1.0.61)

| № | требование | статус | доказательство |
|---|---|---|---|
| 1.1 | снимок Wi-Fi/AP до изменений + ключи референса | ✅ | `JitNetCore.kt:619`, `JitOrchestratorTest.clickSavesSnapshotThenRunsReferenceChain` |
| 1.2 | один скрипт: `svc wifi disable` → слив ≤20 с → `usejit` → zygote последней | ✅ | `JitNetCore.kt:143`, `JitRefTest.verbatimReferenceLiteralFromSmali` (392 симв.), `JitChainScriptTest.orderIsReferenceOrder` |
| 1.3 | шаг монтирований §1.1 | ⛔ | реализован символ в символ, выключен: обратимость не доказана (§3.2) |
| 2 | возврат — продолжение той же операции, AP→Wi-Fi, 15 с | ✅ | `JitNetCore.kt:712`, `:845`; `JitOrchestratorTest.repliedChainThenRestoreOnNextStart` |
| 2.1 | объяснить запуск службы и получение данных операции | ✅ | §1.3 |
| 2.2 | тайминг — по инструкциям, недоказанное не за факт | ✅ | §1.3 (15000 = `0x3a98`; «`onCreate` вовремя» не утверждается) |
| 3 | сверка `getprop` + `dalvik-jit-code-cache`, нет данных ⇒ `unknown` | ✅ | `JitNetCore.kt:181`, `:964`; `JitRefTest.onlyTrueAndFalseAreFacts` |
| 4 | обычный старт/ребут не применяют JIT и не рестартуют zygote | ✅ | `JitStartZeroWritesTest` (5) |
| 5 | §1.1 исследование обратимости | 🟡 | §3.2 |
| 6 | §1.2 убрать своё (persist, watcher, отмена, `sleep 8`, санация, boot-backfill, `bootRestoreJit`, `jitPersistBackfill`) | ✅ | §2, `JitNoPersistTest`, `JitStartZeroWritesTest.noOwnMechanismsInStartPath` |
| 6.1 | старые persist-значения не менять, дать read-only способ | ✅ | §2.1 |
| 6.2 | не копировать `ip rule del` | ✅ | `JitRulesReadOnlyTest` |
| 7 | жизненный цикл: одна операция, второй клик не перезаписывает, чужая загрузка ⇒ ноль записей | ✅ | `JitNetCore.kt:391`, `JitLifecycleTest.clickAndStartNeverOverlap`, `JitOrchestratorTest.concurrentClicksStartExactlyOneOperation` |
| 7.1 | `ARMED`/«отправлено»/отсутствие журнала/60 с/таймаут — не доказательства | ✅ | `JitRef.chainOutcome` (`JitNetCore.kt:400`), `restart=not_claimed`, `JitLogsCancelTest.lostChannelIsLoggedAsErrorNotSuccess` |
| 7.2 | `setWifiEnabled(true)`/`startSoftAp` ≠ интернет; в логах раздельно | ✅ | `JitNetCore.kt:845` (`request=`/`state_before=`/`state_after=`/`confirmed=`/`internet=not_checked`), §6 |
| 8 | AVAS — отдельные функции, убрать привязку к JIT | ✅ | §5 |
| 8.1 | поведение при старом снимке AVAS | ✅ | §2.2 |
| 9 | логи (путь, fallback, retention 14 сут.) | ✅ | §6, `LogFilesTest` (6) |
| 10 | не менять плагин/слоты/климат/батарею/TTL/CarPlay/рут-reconnect | ✅ | в `aed18fb` нет файлов этих подсистем (`git show --stat aed18fb`) |
| 11 | §4 тесты: вся цепочка, iso-стенд, одновременные клики, потеря транспорта, boot/30 с | ✅ | §4 |
| 11.1 | удалённые тесты перечислены с эквивалентом | ✅ | §4.1 |
| 12 | публикация + CI актуального SHA + run/job/PASSED/FAILED | ✅ | §0: `42954771103` / `130307151164` / 186 / 0 |
| 12.1 | полевой успех не заявлять | ✅ | на ГУ ничего не запускалось |

---

### 8.3 Десять проблем §0.0 третьего захода (мандат правки 2)

| № | требование §0.0 | статус | доказательство |
|---|---|---|---|
| 1 | выключенный Wi-Fi/AP — не признак завершения | ✅ | `JitNetCore.kt:361` (`Shell.REPLIED` — единственный признак), `JitLifecycleTest.foreignWifiOffIsNotEvidence` |
| 2 | оба радио off — не доказательство; ноль ВСЕХ записей и сетевых действий | ✅ | `JitNetCore.kt:366-397`, `:776-796`; `bothRadiosOffWaitsForProvenCompletion`, `bothRadiosWasOffDoesNotBecomeDoneByItself` |
| 3 | CPS/DPS атомарность доказана на обеих сторонах, `commit=false`, исключение, недоступность, расхождение, смерть между записями | ✅ | `JitNet.kt:64-122`; `JitStoreConsistencyTest` (8) |
| 4 | гонки старт↔клик↔возврат, включая поздний shell; проверка содержимого снимка | ✅ | `JitNetCore.kt:498-523`, `:986-1000`; `JitLifecycleTest.staleWriterCannotOverwriteNewSnapshot`, `clickAndStartNeverOverlap` |
| 5 | тумблер = подтверждённый факт; нет подтверждения ⇒ `unknown`, без новых кнопок | ✅ | `JitNetCore.kt:197`, `ToolsPanel.kt:1139`; `JitUiStateTest` (5) |
| 6 | `RESTORED` ≠ безопасное завершение; честный блок вместо фиктивного успеха | ✅ | `JitNetCore.kt:774-781`, `:994-1030`; `apiRefusalIsNotReportedAsRestored` |
| 7 | AP: разделять `invoked/accepted/confirmed/unknown`; guard при `wifiOn()==null` | ✅ | `JitRef.Request`, `JitNetCore.kt:855-905`; `apAndWifiAppliedLateAreStillConfirmed` |
| 8 | сверка до выполнения — не результат; `unknown` не снимает намерение | ✅ | `JitNetCore.kt:994-1030`; `verifyOutcomes`, `wantFalseWithBrokenChannelIsNotSuccess` |
| 9 | запуск после zygote не доказан — не заявлять `BOOT_COMPLETED`, не добавлять сервисы/фильтры | ✅ | `restart=not_claimed`; `JitBootBoundaryTest.noBootBackfillAnywhere` |
| 10 | `MOUNT_STEP_ENABLED=false` ⇒ не точная копия; read-only команды; без массовых unmount | ⛔ / 🟡 | §3.2: шаг выключен и честно назван недоказанным; команды read-only даны; исполнение — по решению владельца |
| — | таблица `референс → код → совпадение/отличие/неизвестно → файл:строка → тест` | ✅ | §3 (14 строк), §0.5 |
| — | честная остановка при конфликте с точной копией | ✅ | §3.2 (⛔ шаг монтирований), §9 |

## 9. Что осталось за владельцем

1. **§1.1 монтирования** — решение по `umount -l` (шаг готов, выключен, §3.2). Полезная проверка
   только чтением — `mountinfo` root-транспорта и сравнение namespace с PID 1 (четыре команды в
   §3.2); `cat /proc/mounts` сам по себе ничего не доказывает, потому что показывает namespace
   читающего процесса.
2. **Остатки persist-свойств JIT** прежних версий — чистить или оставить (сейчас только читаем, §2.1).
3. **Остаточный снимок AVAS** (`filesDir/avas_snapshot`) — удалять или оставить (сейчас не трогаем, §2.2).
4. **`ACTION_BOOT_HU`** — нужен ли отдельный фильтр, как в референсе (сейчас нет; продолжение
   привязано к старту процесса, не к конкретному boot-действию).
5. **Полевой прогон JIT** — до нового REVIEW владелец JIT на ГУ не запускает; всё подтверждённое
   здесь — локально и в CI, полевого успеха нет.
