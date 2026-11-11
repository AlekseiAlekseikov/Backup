# REPORT iter34 (1.0.61) — JIT как в референсе: ядро переписано с нуля по smali

Мандат: `ПРОМПТ_ТОЧНАЯ_КОПИЯ_JIT_РЕФЕРЕНСА_1.0.61.md`.
Ветка **`fix/v2`**. Базовый HEAD на начало работы: **`460f3b5`** (код — `1ec62f1`).
Коммиты этой итерации: **`e0479a1`** (перенос ядра), **`9de9dce`** (шаг `umount -l` включён).

Референс: ZIP `CentralEXAuto` md5 **`8a65c6c29749c64d4e335c57490a347a`**, внешний
`CentralEXAuto_v3.3.9.apk` md5 **`c3f1664125953b9851bf4483b265135f`** (разбор — baksmali 2.5.2 по
APK, не `assets/pip-overlay.apk`).

---

## 0. Публикация и CI

| что | значение |
|---|---|
| ветка / HEAD | `fix/v2` / **`9de9dce`** (код итерации) |
| docs-коммит | **`b93eea8`** (отчёт + `WORKLOG.md` + `SOURCE_SHA`); run **`42996265714`**, job `130441021795` — `success`, `PASSED` 194 / `FAILED` 0, artifact `13844292942` (177 161 997 Б) |
| run / job | **`42994299986`** / **`130434558159`** |
| результат job | **`success`** (09:20:27 → 09:31:04 UTC) |
| `PASSED` / `FAILED` из полного лога | **194 / 0** (`grep -a 'PASSED'` = 194 строки; строк `FAILED` — 0; `BUILD SUCCESSFUL`) |
| артефакт | `app-debug`, id `13843339117`, 177 161 925 Б |
| `SOURCE_SHA` в `app/buildinfo.properties` | **`9de9dce`** |

Разбивка `PASSED` по классам (полный лог той же джобы): `JitOrchestratorTest` 24,
`JitRefTest` 12, `JitChainScriptTest` 8, `LogFilesTest` 6, `JitUiStateTest` 5,
`JitStartZeroWritesTest` 5, `JitLogsCancelTest` 5, `AvasIndependenceTest` 5,
`JitBootBoundaryTest` 4, `JitNoPersistTest` 3, `JitRulesReadOnlyTest` 2 + прочие
(`Ttl*`, `Hvac*`, `Mount*`, `Temp*`, `PluginAsset`, `BuildMarker`, `BootGate`, …).

Локально (`/home/user/runtests.sh`, kotlinc 1.9.24 + `android.jar`, 13 файлов JIT/AVAS/логов):
**`OK (79 tests)`**, 0 падений. Локальный прогон — это тесты на **подставных** командах;
испытаний на ГУ в этой итерации **не было** (владелец до нового REVIEW JIT не запускает).

---

## 1. Что сделано (одной страницей)

Ядро JIT **перезаписано целиком** под реальный код референса, а не доработано «поверх»:

| файл | было | стало |
|---|---|---|
| `tools/JitNetCore.kt` | ~1260 строк: свои фазы, `HOLD_*`, `Op`/`decodeOp`, `KEY_OP`, `ClickResult`, `StartDecision`, `Shell`, watcher, TTL, PID | 575 строк: `JitRef` (константы референса, литерал скрипта, разбор, сверка, итог) + `JitOp` (клик → продолжение → возврат сети → AP → сверка) |
| `tools/JitNet.kt` | старый адаптер под прежний API | `AndroidShell` / `AndroidStore` / `AndroidNetState` / `AndroidWaiter` + публичные `read/contains/usejitFact/codeCacheVisible/click/onProcessStart/verifyApplied` |
| `tools/AppPerformance.kt` | `readJitOp`, `jitApplyWithZygote` со своей машиной состояний, `verifyJitOnly` со снимками | тонкие обёртки: `jitPendingVerify` (= `CentralWatchdogService.onCreate`), `jitApplyWithZygote` (= `w0/j9.onClick`), `jitRuntimeFact`/`jitConfirmedState` (= `w0/bb`/`w0/r7`) |
| `ui/ToolsPanel.kt` | строка «операция: …» из `readJitOp` | только факт `getprop`: `on` / `off` / «состояние неизвестно» |

Удалено (и это проверяется тестами по исходнику, а не на слово): `HOLD_*`, фазы,
журнал фаз, `Op`/`decodeOp`, `KEY_OP`, `ClickResult`, `StartDecision`, второй watcher,
файл отмены, `sleep 8`, boot-backfill/автоприменение JIT, метка загрузки, TTL снимков,
PID-проверки, `jit_verified`/`jit_pending_verify` как отдельные ключи, «санация» `ip rule`.
Скрытого флага «шаг монтирований выключен» **больше нет**: `MOUNT_STEP`/`MOUNT_STEP_REASON`
удалены, `chainScript(want) == referenceScript(want)`, в логе `mount_step=present`.

---

## 2. Таблица: референс → фактический путь → совпадение → тест

| № | референс (`smali`/DEX) | фактический путь (наш код) | вердикт | тест |
|---|---|---|---|---|
| 1 | `w0/j9.smali` `onClick`, `pswitch_2b` (вызов из `w0/b9.smali:1206`, `S0`, ids `0x7f09011f/0x7f09011e`): `WifiManager.isWifiEnabled()`, `NetworkInterface.getByName("ap0")`→`isUp() && getInetAddresses().hasMoreElements()`; оба блока в `catchall_48`/`catchall_64` ⇒ `false` | `JitNetCore.kt:297 JitOp.click`; `JitNet.kt:154 AndroidNetState.wifiOn`, `:156 apUp` | ✅ совпадает (исключение = `false`) | `JitOrchestratorTest.clickReadErrorBecomesFalseAsInReference`, `…clickSavesThreeKeysInOneCommitBeforeScript` |
| 2 | один `Editor`: `putBoolean(jit_pedido, want)`, `putBoolean(perf_auto_otimizar, want)`, `putString(jit_restaurar, "wifi=w,ap=a")`, `commit()`; результат `commit()` **не проверяется** | `JitNet.kt:85 AndroidStore.putSnapshot` (`JitNetCore.kt:297` вызывает до отправки) | 🟡 **отличие**: у приложения два хранилища (`Prefs2.cps`/`dps`) — пишем в оба, на каждое один `Editor` + один `commit()`; ключи, порядок и «результат не гейтит отправку» — как в референсе (лог `saved=…`) | `clickSavesThreeKeysInOneCommitBeforeScript`, `commitResultDoesNotStopChain` |
| 3 | `pool.execute(new la(...))` сразу после `commit()`, второй клик ничем не блокируется | `JitNetCore.kt:319` (`shell.exec(chainScript(want), 40 000)`), `JitNet.kt:199 JitNet.click` | ✅ совпадает | `clickSendsReferenceScriptVerbatim`, `secondClickOverwritesSnapshotAsInReference` |
| 4 | `w0/la.smali` `pswitch_10e`: `svc wifi disable` → ≤20×1 с до `ip rule show \| grep -c 'oif wlan0'` = 0 → `for _m in $(grep ' /data/app/' /proc/mounts \| awk '{print $2}'); do umount -l "$_m" 2>/dev/null; done` → `setprop dalvik.vm.usejit true\|false` → `setprop ctl.restart zygote` (последняя) | `JitNetCore.kt:157 scriptBody`, `:174 referenceScript`, `:177 chainScript` | 🟡 литерал совпадает **символ в символ** (392 симв., тест), но **поле не исполнялось**; `umount` — ⛔ см. §3 | `JitRefTest.chainScriptIsReferenceLiteralByteForByte`, `JitChainScriptTest.orderIsReferenceOrder`, `…mountStepTouchesOnlyDataApp` |
| 5 | `H1(0x9c40 = 40000, …)` — таймаут транспорта; затем `postDelayed(ua, 0xafc8 = 45000)` — индикатор в UI | `JitRef.CHAIN_TIMEOUT_MS = 40_000`, индикатор — строка лога/текст панели | ✅ к сети не относится, ничего не доказывает | `JitLogsCancelTest.lostChannelIsLoggedAsErrorNotSuccess` |
| 6 | `CentralWatchdogService.onCreate` (`smali` 0x7b9…0x811): `getString("jit_restaurar", null)` → `Pattern.compile("^wifi=([01]),ap=([01])$").matcher(s).matches()` → `new oe(w,a)` → `remove(...).apply()` (**до** выполнения) → `postDelayed(new e2(...), 0x3a98 = 15000)` | `JitNetCore.kt:361 JitOp.onProcessStart` (`JitRef.parseRestore:149` = `matchEntire`, без `trim`); точка входа — `AppPerformance.jitPendingVerify:820` из `VoiceAssistantService.onCreate:270` и `onStartCommand:349` | 🟡 **структурное отличие точки входа**: у нас нет класса `CentralWatchdogService`, продолжение вешает наш foreground-сервис на **каждом** старте (в референсе — `onCreate` сервиса); последовательность «совпало → снять → 15 000 мс → `e2`» идентична | `unparsedSnapshotIsLeftUntouched`, `snapshotIsRemovedBeforeDelayAndDelayIs15s`, `restartOfProcessDoesNotRestoreTwice` |
| 7 | `w0/e2.smali` case 0/1: `if (ap) U("pós-JIT", false);` затем `if (wifi && !wm.isWifiEnabled()) wm.setWifiEnabled(true);`; исключение обрывает весь возврат | `JitNetCore.kt:404 JitOp.restoreNetwork` | ✅ порядок AP→Wi-Fi, только включение, «уже включено — не трогаем» | `restoreApFirstThenWifi`, `alreadyOnNetworkIsNotTouched`, `restoreOnlyTurnsNetworkOn` |
| 8 | `U(String,boolean)` (`CentralWatchdogService` 0x2880…): `v()` ⇒ `true`; ожидание `O()`; guard `c1()` («клиентский Wi-Fi включён и это не наш auto-enable ⇒ AP не открываю»); `synchronized` + `CountDownLatch` 25 с; `d()` — рефлексия `getDeclaredMethod("startSoftAp", WifiConfiguration)`, аргумент `null`, результат **не используется**; опрос `v()` до 20 000 мс шагом 1000; при FAILED — ≤3 попытки с паузой 2000 мс | `JitNetCore.kt:466 JitOp.openAp` (+`poll:559`), `JitNet.kt:170 AndroidNetState.startAp` | 🟡 логика и константы перенесены (25 с / 20 с / 1000 мс / 3 / 2000 мс, guard, «результат `startSoftAp` не используется»); **отличие**: вместо `CountDownLatch` в отдельном потоке — блокирующий опрос в фоновом воркере `BgWorker` (тот же порядок ожиданий) | `apGuardKeepsRadioWhenClientWifiIsOn`, `apAppearsLateButIsConfirmed`, `apTimeoutIsReportedHonestly`, `apRequestThrowsIsStillNotFailureOfTheWholeChain` |
| 9 | `w0/bb` (`case 1`, `S0`) → `w0/r7`: `H1(0x1f40 = 8000, "getprop dalvik.vm.usejit")`, `trim() == "true"`; `Boolean` по `/proc/self/maps` (`jit-cache`/`dalvik-jit-code-cache`, ошибка ⇒ `null`); `ok = (pedido == getprop) && (!pedido \|\| maps != FALSE)`; `remove("jit_pedido")`; `toggle.setActive(getprop)`; предупреждение `0x7f0f049a` при `!ok && pedido` | `JitNetCore.kt:519 JitOp.verifyApplied`, `JitRef.parseProp:201`, `JitRef.verify:221`, `JitRef.confirmedState:239`; `JitNet.kt:194 usejitFact`, `:196 codeCacheVisible` | 🟡 **два отличия**: (а) нечитаемый `getprop` = `UNKNOWN` + `jit_pedido` **не снимается** (в референсе «любая строка кроме `true`» = `false` и намерение снимается); (б) сообщение «не применилось» показывается только при `pedido=true` — как в `r7`. Команда при этом одна и та же: `getprop dalvik.vm.usejit`, таймаут 8000, `/proc/self/maps` | `verifyOkClearsPedido`, `verifyNotAppliedReportsAndStillClears`, `unreadablePropertyKeepsPedido`, `verifyDisableWithCacheGoneIsCacheMissing` |
| 10 | тумблер = отображение `getprop` (`toggle.setActive(getprop)`), без новых элементов управления | `ui/ToolsPanel.kt:addJitRuntimeLine`, `tools/ToolsBackend.kt` (`perfJitRuntime`, `perfJitConfirmed`) | ✅ в существующем UI, кнопок не добавлено; `unknown` показывается словами | `JitUiStateTest` (5) |
| 11 | в цепочке нет `persist.*`, `usejitprofiles`, `ip rule del/flush`, `ndc`, `settings`, `kill`, `setenforce`, маршрутов | `JitRefTest`, `JitChainScriptTest.noForbiddenCommands`, `JitRulesReadOnlyTest`, `AvasIndependenceTest` | ✅ проверено по тексту скрипта и по фактически выполненным командам на стенде | см. слева |
| 12 | JIT применяется **только по клику**; `onCreate` фреймворк не перезапускает | `JitStartZeroWritesTest` (5): обычный старт — ноль записей, ноль команд, ноль сетевых запросов; в коде нет `bootRestoreJit`/`jit_verified`/`jit_pending_verify`/`sleep 8` | ✅ | `plainStartWritesNothingAndSendsNoCommands`, `noAutomaticJitAnywhereInCode` |

### 2.1 Полный вызов «от нажатия до возврата сети» (наш путь против `APK`)

```
[наш UI] тумблер JIT  →  ToolsBackend (perfJit)  →  AppPerformance.jitApplyWithZygote(bool)
   └─ JitNet.click(app, want)  →  JitOp.click                              (JitNetCore.kt:297)
        ├─ AndroidNetState.wifiOn()/apUp()                ← WifiManager.isWifiEnabled(), ap0
        ├─ AndroidStore.putSnapshot(want, net)            ← 3 ключа, один commit (лог saved=)
        └─ AndroidShell.exec(JitRef.chainScript(want), 40000)   ← литерал w0/la.run
                                    │
          [zygote перезапущен, система поднимает процесс]
                                    ▼
[наш сервис] VoiceAssistantService.onCreate/onStartCommand → AppPerformance.checkPendingZygoteVerify
   └─ BgWorker → AppPerformance.jitPendingVerify → JitNet.onProcessStart
        └─ JitOp.onProcessStart (JitNetCore.kt:361)     ← аналог CentralWatchdogService.onCreate
             ├─ JitRef.parseRestore(getString("jit_restaurar"))      ← ^wifi=([01]),ap=([01])$
             ├─ putString("jit_restaurar", null)                     ← remove(...).apply() ДО работы
             ├─ AndroidWaiter.sleep(15000)                           ← postDelayed(e2, 15000)
             └─ JitOp.restoreNetwork (JitNetCore.kt:404)             ← w0/e2.run
                  ├─ if (ap)  JitOp.openAp("pós-JIT", false)         ← U(String, boolean)
                  └─ if (wifi && !wifiOn()) AndroidNetState.setWifi(true)

[панель] открытие/обновление → AppPerformance.jitRuntimeFact → JitNet.usejitFact
   └─ JitOp.verifyApplied (JitNetCore.kt:519)                            ← w0/bb + w0/r7
```

Сверка `w0/bb` в референсе вызывается из `MainActivity.S0()` при открытии настроек; у нас — при
построении панели инструментов (`ToolsPanel` → `ToolsBackend.perfJit*`) и после применения.
Это тот же момент «экран открыт», других кнопок нет.

---

## 3. Отдельный пункт: `umount -l` точек `/data/app/`

**Статус: 🟡 литерал перенесён и исполняется, ⛔ полем не проверялся.**

- Шаг **включён** (`JitNetCore.kt:157`), скрытых флагов нет: `chainScript(want) ==
  referenceScript(want)`, каждая отправка пишет `mount_step=present`. Требование «выключенный шаг
  нельзя выдавать за копию» выполнено снятием выключателя, а не оговоркой.
- Что именно делает референс: `for _m in $(grep ' /data/app/' /proc/mounts | awk '{print $2}'); do
  umount -l "$_m" 2>/dev/null; done` — **ленивое (detached) отмонтирование** точек монтирования,
  путь которых содержит `/data/app/`. Только эти точки: `/data/data`, `/data/media` и прочее не
  затрагиваются (проверено на стенде: `JitChainScriptTest.mountStepTouchesOnlyDataApp`).
- Чего я **не** утверждаю: что шаг безопасен, что он обратим, что «лечит только перезагрузка», что
  он ломает или не ломает APK. Данных с живого ГУ нет, `mountinfo` root-транспорта не снимался
  (владелец ГУ-исследования разрешает только read-only, а массовый `umount` ради проверки — нет).
- Что нужно, чтобы закрыть пункт: на ГУ — **до** первого боевого клика — снять read-only
  `mountinfo` из namespace root-транспорта и сравнить с namespace PID 1 (команды в §7 прежнего
  отчёта `REPORT_iter33p.md`, §3.2). Это отвечает на вопрос «какие точки реально уйдут», но
  **не** доказывает обратимость; обратимость без исполнения не доказывается вообще.

---

## 4. Запуск продолжения после рестарта — честная граница

- Что гарантировано нашим кодом: как только процесс приложения **поднялся** (любой старт
  `VoiceAssistantService`), `onProcessStart` один раз прочитает `jit_restaurar`, снимет строку,
  подождёт 15 000 мс и вернёт сеть. Второго клика не нужно.
- Что **не** гарантировано: что Android вообще поднимет наш сервис после `setprop ctl.restart
  zygote`. `ctl.restart zygote` — это перезапуск zygote, а не `BOOT_COMPLETED`; поведение
  «приложение поднимется само» зависит от того, как система перезапускает persist-приложения.
  Референс в этой ситуации полагается на свой foreground-сервис/watchdog. Заявлять, что zygote
  гарантирует `BOOT_COMPLETED`, нельзя — и мы этого не заявляем.
- Поэтому в логе обе точки различимы: `JIT_CHAIN_SENT … restart=not_claimed` (рестарт не
  подтверждён) и `JIT_START_RESULT … internet=not_checked action=net_restore_done` (что реально
  сделано при старте). Если процесс не поднялся — в логе просто нет второй строки; это и есть
  наблюдаемый факт, а не «всё хорошо».
- Риск референса, который мы **не** чиним (ибо это была бы своя надстройка): строка
  `jit_restaurar` снимается **до** 15-секундной паузы, поэтому смерть процесса в эти 15 с
  отменяет возврат сети навсегда. Это поведение референса; в отчёте — как известный риск (🟡).

---

## 5. Что удалено как «наше, не референса» (проверено по исходнику)

| механизм прежних версий | как проверено, что его нет |
|---|---|
| `HOLD_*`/`BUSY`, блокировка «одна операция», окно 60 с | `grep` по `app/src/main`; `JitStartZeroWritesTest.noAutomaticJitAnywhereInCode` |
| фазы `ARMED/SENT/…`, журнал фаз, `Op`/`decodeOp`, `KEY_OP`, `jit_op` | `JitRefTest.noOwnMechanismsLeftInCore` |
| TTL снимков, PID-проверки, `op-id` | там же |
| второй watcher / `FileObserver` / файл отмены / `sleep 8` | `JitStartZeroWritesTest`, `JitLogsCancelTest.noCancelOrCompensationMechanismInCode` |
| boot-backfill, автоприменение JIT на boot, метка загрузки | `JitStartZeroWritesTest`, `JitBootBoundaryTest` |
| `jit_verified` / `jit_pending_verify` как ключи состояния | `JitRefTest` |
| санация `ip rule` (`del`/`flush`/`ndc`/`settings`) | `JitRulesReadOnlyTest`, `JitChainScriptTest.noForbiddenCommands` |
| JIT-привязка AVAS (`AvasCore`/`AvasRestore`, mute, выбор звука) | `AvasIndependenceTest` (5) |

Остатки **прежних версий на устройстве** (не наши новые записи): `persist.dalvik.vm.usejit`,
`persist.sys.dalvik.vm.usejit`, `persist.*usejit_init` — только читаются и пишутся в лог
(`JIT_BOOT_LEGACY … action=none need=owner_decision`); файл `avas_snapshot` — только фиксируется
(`AVAS_LEGACY … action=none`). Ничего не удаляется: это запись в систему/файлы, на которую нужно
решение владельца.

---

## 6. Отличия от референса — полный список (все — заявленные, не «скрытые»)

1. **Нечитаемый `getprop` = `UNKNOWN`**, `jit_pedido` не снимается (референс: «не `true` ⇒ `false`»,
   намерение снимается). Мандат: различать невозможность чтения и подтверждённый результат.
2. **Тумблер показывает «состояние неизвестно»** вместо «выключено», когда `getprop` не прочитан
   (мандат: не подменять факт одной старой preference).
3. **Два хранилища prefs** (`cps`/`dps`) вместо одного; ключи и порядок записи те же, один
   `commit()` на хранилище.
4. **Точка входа продолжения** — `VoiceAssistantService.onCreate/onStartCommand` (у нас нет класса
   `CentralWatchdogService`); вызывается на каждом старте, в фоне (`BgWorker`), с тем же сном 15 с.
5. **`U` без `CountDownLatch`**: ожидание реализовано опросом в фоновом воркере с теми же
   таймаутами (25/20/1/3/2 с).
6. Индикатор `postDelayed(ua, 45000)` референса — у нас строка лога/текст панели; к сети и к
   свойствам отношения не имеет.

Ни одно из отличий не меняет **ни одной команды** цепочки и ни одного ключа префов.

---

## 7. Тесты: что покрыто и чем (локально, на подставных командах)

| файл | тестов | о чём |
|---|---|---|
| `JitOrchestratorTest.kt` | 24 | вся цепочка: клик → запись → скрипт → продолжение → возврат сети → сверка; порядок AP/Wi-Fi; guard `U`; таймауты; потеря канала; повторный старт; повторный клик |
| `JitRefTest.kt` | 12 | литерал скрипта (392 симв.), константы, `parseRestore`, `parseProp`, `verify`, `confirmedState`, `chainOutcome`, отсутствие старых механизмов в ядре |
| `JitChainScriptTest.kt` | 8 | реальный текст скрипта в настоящем `/bin/sh` на стенде: порядок, слив правил (0/20 итераций), только `/data/app/` под `umount -l`, отказ `setprop` не ломает порядок, нет запрещённых команд |
| `JitStartZeroWritesTest.kt` | 5 | обычный старт: ноль записей/команд/сетевых запросов; нет автоприменения JIT; boot-проба только читает |
| `JitLogsCancelTest.kt` | 5 | в логе раздельно состояние/запрос/ответ/сверка; нет механизма отмены/компенсации; возврат сети только включает |
| `JitUiStateTest.kt` | 5 | `on`/`off`/`unknown`; preference не управляет тумблером; новых кнопок нет |
| `JitNoPersistTest.kt` | 3 | нет `persist.*`, `usejitprofiles`; одна запись свойства в цепочке |
| `JitBootBoundaryTest.kt` | 4 | границы boot-пробы (только чтение) |
| `JitRulesReadOnlyTest.kt` | 2 | `ip rule` только читается |
| `AvasIndependenceTest.kt` | 5 | AVAS не в цепочке, отдельные функции звука не тронуты |
| `LogFilesTest.kt` | 6 | путь логов |
| `JitFake.kt`, `JitIsoStand.kt` | — | фейки (`Store`/`Net`/`Waiter`/`QueueShell`) и стенд на реальном `/bin/sh` |

**Отделено:** всё перечисленное — тесты на фейках (локально и в CI). Испытаний на живом ГУ в этой
итерации нет; полевого результата не заявляю.

Удалены как более не нужные: `JitLifecycleTest` (7), `JitLifecycleShell`, `JitStoreConsistencyTest`
(8) — они проверяли именно удалённую машину состояний; их инварианты перенесены в
`JitOrchestratorTest` (24) и `JitStartZeroWritesTest` (5).

---

## 8. Чек-лист мандата

| требование | статус |
|---|---|
| ядро по реальному коду референса (клик, состояние сети, команды, продолжение, сверка) | ✅ §2 |
| удалить свои механизмы (снимки/фазы/HOLD/TTL/PID/watcher/boot-backfill/лишние команды) | ✅ §5 |
| цепочка: `svc wifi disable` → ожидание `oif wlan0` → `umount -l` → `setprop` → `ctl.restart zygote` последней | ✅ литерал 392 симв. (`JitRefTest`) |
| не добавлять `persist.*`, `usejitprofiles`, watcher, `ip rule del`, `flush`, `ndc`, маршруты, `settings` | ✅ `JitChainScriptTest`, `JitRulesReadOnlyTest` |
| шаг `umount` не выдавать за копию, если выключен | ✅ выключения больше нет; ⛔ поле не проверено — §3 |
| возврат сети: `jit_restaurar` → удаление → 15 с → AP (`U`) → Wi-Fi | ✅ §2 п.6–8 |
| `U` перенесён целиком (условия, guard, таймауты, 3 попытки, рефлексия `startSoftAp(null)`) | 🟡 §2 п.8 (без `CountDownLatch`) |
| продолжение — по повторному запуску приложения/сервиса, без второго клика | ✅ §4 (и честная граница: zygote ≠ гарантия `BOOT_COMPLETED`) |
| сверка/UI: фактический `getprop`, cache, поведение тумблера, без новых кнопок | ✅ §2 п.9–10 |
| JIT только по клику; AVAS не трогать | ✅ §2 п.12, §5 |
| монтирования разрешены, скрытый флаг запрещён | ✅ §3 |
| отчёт с таблицей `файл:строка`/DEX, ✅/🟡/❌/⛔, CI run/job + `PASSED`/`FAILED`, `WORKLOG.md`, `SOURCE_SHA` | ✅ этот файл, §0 |
| полевой успех не заявлять | ✅ не заявляю |

---

## 9. Что осталось за владельцем

1. **Решение по `umount -l`**: шаг исполняется как в референсе (так требует мандат), но на живом
   ГУ не проверялся. Нужны read-only `mountinfo` root-транспорта + сравнение namespace с PID 1
   **до** первого клика — либо явное «идём как есть».
2. Остатки `persist.*` JIT и файл `avas_snapshot` прошлых версий — только фиксируются в логе;
   удаление = запись, нужно решение.
3. Полевой прогон: сборка владельцем, один клик тумблера, лог `voiceapp3-DD.MM.YYYY.log`
   (строки `JIT_CLICK`, `JIT_STORE`, `JIT_CHAIN_SENT/RETURN`, `JIT_START_RESULT`, `JIT pedido=…`).
