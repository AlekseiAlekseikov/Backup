# REPORT iter35 (1.0.61) — работающий JIT по дереву референса + отдельная диагностика AVAS

Мандат: `ПРОМПТ_iter35_JIT_РЕФЕРЕНС_И_ДИАГНОСТИКА_AVAS.md`.
Ветка **`fix/v2`**, база — HEAD `50b7f0c` (код `9de9dce`, CI `42998269997`, 194/0).
Коммит этой итерации — см. §0 (`SOURCE_SHA`).

Проверка исходных данных перед работой: ZIP `8a65c6c29749c64d4e335c57490a347a` ✅,
`CentralEXAuto_v3.3.9.apk` `c3f1664125953b9851bf4483b265135f` ✅ (md5 пересчитан заново).
Разбор — baksmali 2.5.2 (`/tmp/sm`) + jadx 1.4.7 (`/tmp/jx`) по внешнему APK.
`/tmp` в этой сессии не сохраняется, поэтому всё существенное перенесено в текст ниже.

---

## 0. Публикация и CI

| что | значение |
|---|---|
| ветка / HEAD | `fix/v2` / `10cd137` (код итерации) |
| run / job | **`43014460281`** / **`130501940144`** — `success` |
| `PASSED` / `FAILED` из полного лога | **218 / 0** (`grep -a ' PASSED'` = 218, `' FAILED'` = 0, `BUILD SUCCESSFUL`) |
| артефакт | `app-debug`, id `13851849907`, 177 169 982 Б |
| `SOURCE_SHA` | `10cd137` |
| версия | `versionCode=61`, `versionName=1.0.61` |
| локально | `OK (103 tests)` на фейках/стенде (194 → 218 в CI: +24 новых JIT-теста) |

Docs-коммит (этот отчёт + `WORKLOG.md` + `SOURCE_SHA`) проверяется отдельным прогоном — см.
`WORKLOG.md`; код и его CI указаны выше и не меняются от правки документации.

**Ограничение:** зелёный CI — это сборка + unit-тесты на JVM. Он **не** является проверкой на ГУ:
устройства в этом окружении нет, JIT на ГУ не запускался, полевого результата нет.

---

## 1. Что в референсе на самом деле (DEX, а не память)

Все ссылки — smali (`/tmp/sm/...`, `#` = строка файла) и сверены с jadx там, где он декомпилирует.

### 1.1 Клик `w0/j9.onClick` (ветка `pswitch_2b`, `j9.smali:119…`)

```java
wifi = isWifiEnabled()                     // весь блок в catchall_48 ⇒ false
ap   = getByName("ap0") != null && isUp() && getInetAddresses().hasMoreElements()  // catchall_64 ⇒ false
edit().putBoolean("jit_pedido", t)
     .putBoolean("perf_auto_otimizar", t)
     .putString("jit_restaurar", "wifi=" + wifi + ",ap=" + ap)
     .commit();                            // результат НЕ проверяется
pool.execute(new la(p1, t, dialog))        // скрипт
```

### 1.2 Скрипт `w0/la.run` (`pswitch_10e`)

`svc wifi disable` → `_n=0; while [ $_n -lt 20 ]`: `[ "$(ip rule show | grep -c 'oif wlan0')" = "0" ] && break; sleep 1; _n=$((_n+1))`
→ `for _m in $(grep ' /data/app/' /proc/mounts | awk '{print $2}'); do umount -l "$_m" 2>/dev/null; done`
→ `setprop dalvik.vm.usejit true|false` → `setprop ctl.restart zygote`. Таймаут транспорта `H1(0x9c40=40000)`.

### 1.3 Продолжение `CentralWatchdogService.onCreate`

`getString("jit_restaurar", null)` → `Pattern.compile("^wifi=([01]),ap=([01])$").matcher(s).matches()`
→ `new oe(w,a)` → `remove("jit_restaurar").apply()` **до** выполнения → `postDelayed(new e2(...,0), 0x3a98=15000)`.
`e2` case 0: если `T2` (сервис создан) — `new Thread(e2(...,1)).start()`.

### 1.4 Возврат сети `w0/e2.java` (case 1) — jadx дословно

```java
if (ap) { try { U("pós-JIT", false); } catch (Throwable th) { log("JIT: restauro falhou: " + th); return; } }
if (wifi) { if (!wm.isWifiEnabled()) wm.setWifiEnabled(true); }
log("JIT: restaurado após o restart (wifi=…, ap=…); wifi agora=" + c1() + " ap agora=" + v());
```
Общий `catch` вокруг AP-пути обрывает и Wi-Fi-путь — это подтверждено и в smali (`catchall` на весь блок U).

### 1.5 `U(String, boolean)` — полный разбор (`CentralWatchdogService.smali:10353–11047`)

| шаг | код референса | смысл |
|---|---|---|
| 0 | `sombra_projecao` → `V1.compareAndSet(false,true)` → daemon `p1(service, tag)` | «предоткрытие AP» для проекции; в нашем пути не участвует |
| 1 | `if (v()) return true` | AP уже в воздухе — **до** любых ожиданий |
| 2 | `if (O()) { while (O()) sleep(1000); if (v()) return true; }` | ожидание CarplayMonitor; `O() = n1==0 && (elapsed-o1) < p1 && K()`; `K() = M() && w0.u.b(prefs)`; `M()` = установлен `com.njda.adapter` и его `sourceDir` в `/data/app/`; `w0.u.b` = строка `connadaptor_modo` из ИХ префов |
| 3 | `c1()` | `isWifiEnabled() \|\| getWifiState()==2 (ENABLING)`; **исключение ⇒ true** (`c1.smali:12959`) |
| 4 | `if (p2) { if (M0 == PESSOA) return false; }` | проверка «владельца» — только для вызовов с `p2=true` (`p1`, `AP sempre aberto`); наш вызов `p2=false` |
| 5 | `if (c1() && !V) return false;` | «AP fechado, mas o WiFi cliente está ligado — não vou disputar o rádio» |
| 5′ | `if (c1() && V) log("…ligado pelo auto-enable — abro o AP por cima")` | `V` ставится в `y1` при `setWifiEnabled(true)==true`, снимается в `t1` при `!c1()` |
| 6 | `synchronized(v1){ if (w1==null) w1=new CountDownLatch(1) else mine=existing }` | если AP уже открывает другой запрос — `await(0x19=25 s)` и вернуть `v()` |
| 7 | `d(tag)` → опрос `v()` до `0x4e20=20000` мс шагом `0x3e8=1000`; при `u1 > t0` — `FAILED` | `u1` пишется в `w0/u2` по broadcast `android.net.wifi.WIFI_AP_STATE_CHANGED`, `wifi_state == 14` |
| 8 | `FAILED && попытка < 3` → `sleep(0x7d0=2000)`, повтор | 3 попытки максимум |
| 9 | иначе `false` + «entregando ao ConnAdaptor» | латч снимается и `countDown()` во всех исходах; при исключении — `countDown()` и проброс |

`d(tag)`: `WifiManager.class.getDeclaredMethod("startSoftAp", Class.forName("android.net.wifi.WifiConfiguration"))`
+ `setAccessible(true)` + `invoke(wm, new Object[]{null})`; **результат не используется**.

### 1.6 Сверка `w0/bb` + `w0/r7` (jadx `w0/r7.java` — авторитетный, smali-ветвление читается неверно)

```java
boolean z4 = this.b;                       // getprop.trim() == "true"
toggle.setActive(z4);
text.setText(getString(z4 ? master_perf_jit_on : master_perf_jit_off));
if (prefs.contains("jit_pedido")) {
    boolean z5 = prefs.getBoolean("jit_pedido", false);
    if (z5 == z4 && (!z5 || !Intrinsics.areEqual(bool, Boolean.FALSE))) z3 = true;   // ok
    log("JIT pedido=… getprop=… code-cache-executavel=…");
    prefs.edit().remove("jit_pedido").apply();          // НАСТОЯЩЕЕ удаление
    if (z3 || !z5) return;
    показ(master_perf_jit_not_applied);                 // предупреждение при !ok && pedido
}
```
`bool` = `w0/j5.d(...)` по карте `/proc/self/maps` этого процесса (`null` — не прочиталось).

---

## 2. Сопоставление: ветка/условие → референс → наш исполняемый путь → тест

| № | ветка / условие | референс | наш путь (`файл:строка`) | тест |
|---|---|---|---|---|
| 1 | снимок Wi-Fi/AP и три ключа одним `commit()` | `j9.smali:119-235` | `JitNetCore.kt:389-397` (`JitOp.click`), `JitNet.kt:105-160` (`AndroidStore.putSnapshot`) | `JitOrchestratorTest.clickSavesThreeKeysInEveryStore` |
| 2 | `commit()` не гейтит отправку скрипта | `j9` (результат не читается) | `JitNetCore.kt:397-405` | `clickSnapshotFailureDoesNotStopChain`, `clickThrowingStoreStillWritesSecond` |
| 3 | исключение чтения Wi-Fi/AP ⇒ `false` | `catchall_48/64` | `JitNetCore.kt:391-393` (`runCatching`) + `JitNet.kt:176-186` | `clickReadErrorsBecomeFalseAsInReference` |
| 4 | литерал скрипта (392 симв.), `ctl.restart zygote` последней | `la.smali` `pswitch_10e` | `JitNetCore.kt:224-247` | `JitRefTest.chainScriptIsReferenceLiteralByteForByte`, `JitChainScriptTest.orderIsReferenceOrder` |
| 5 | шаг `umount -l` `/data/app/` — **включён** | `la.smali` | `JitNetCore.kt:238-240`; лог `mount_step=present` | `JitChainScriptTest.mountStepTouchesOnlyDataApp` |
| 6 | `jit_restaurar` совпал целиком (без trim) | `onCreate` | `JitRef.parseRestore` (`JitNetCore.kt:196-200`) | `JitRefTest.parseRestoreMatchesOnlyFullString` |
| 7 | `remove(jit_restaurar)` **до** 15 с | `onCreate` | `JitNetCore.kt:459-472` | `snapshotRemovedSynchronouslyAndRestoreScheduled15s` |
| 8 | `postDelayed(e2, 15000)` без блокировки потока | `onCreate` + handler сервиса | `JitNetCore.kt:475-480` → `JitNet.kt:41-46` (`BgWorker.postDelayed`) | `restoreHappensOnlyWhenScheduledTaskRuns`, `concurrentStartsScheduleOneRestore` |
| 9 | `e2`: AP первым, затем Wi-Fi; исключение обрывает всё | `e2.java` case 1 | `JitNetCore.kt:497-560` (`restoreNetwork`) | `restoreApFirstThenWifi`, `apExceptionAbortsWifiPathAsInE2` |
| 10 | `U` шаг 1: AP уже в воздухе ⇒ `true` | `U:10560-10575` | `JitNetCore.kt:600-604` | `apAlreadyUpReturnsTrueWithoutCalls` |
| 11 | `U` шаг 2: ожидание `O()` | `U:10576-10650`, `O()` `:7371` | `JitNetCore.kt:605-620` | `carplayBusyWaitsThenContinues`, `carplayBusyButApComesUpDuringWait` |
| 12 | `U` шаг 3: `c1()` = enabled **или** ENABLING, исключение ⇒ true | `c1` `:12959` | `JitRef.clientWifiBusy` (`JitNetCore.kt:325`), `JitNet.kt:188-192` | `clientWifiBusyFollowsC1`, `wifiEnablingCountsAsClientWifiBusy`, `wifiReadExceptionCountsAsBusy` |
| 13 | `U` шаг 4: owner только при `p2=true` | `U:10490-10540` | `JitNetCore.kt:622-630` (`ownerCheck`) — наш вызов `false` | `ownerCheckBlocksOnlyWhenOwnerIsPerson` |
| 14 | `U` шаг 5: `c1() && !V` ⇒ не открывать; `V` ⇒ «por cima» | `U:10545-10590`, `y1:91`, `t1:160` | `JitNetCore.kt:631-645` + `AUTO_WIFI_ENABLED` | `clientWifiOnBlocksApAndKeepsRadio`, `autoEnabledWifiDoesNotBlockAp` |
| 15 | `U` шаг 6: общий latch ≤25 с, второй получает чужой результат | `U:10600-10700` | `JitNetCore.kt:646-665` | `concurrentSecondCallerWaitsForFirstResult`, `secondCallerReturnsApStateWhenFirstGivesUp` |
| 16 | `U` шаг 7: `d()` + опрос `v()` 20×1000 мс | `U:10700-10880` | `JitNetCore.kt:666-706` | `apTimeoutWithoutFailedSignalIsReported` |
| 17 | `U` шаг 8: FAILED (`wifi_state==14`) ⇒ ≤3 попыток, пауза 2 с | `u2:1648`, `U:10800-10990` | `JitNetCore.kt:668-700`, `JitNet.kt:214-247` | `apFailedSignalRetriesTwiceThenGivesUp`, `apAppearsOnSecondAttemptAfterFailed`, `apWatchIsRegisteredAndReleased` |
| 18 | latch снимается во всех исходах | `U:10850-11040` | `finally` в `JitNetCore.kt:707-711` | (косвенно) `concurrentSecondCallerWaitsForFirstResult` |
| 19 | `ok = (pedido==getprop) && (!pedido \|\| maps != FALSE)` | `r7.java` | `JitRef.verify` (`JitNetCore.kt:280-292`) | `verifyTableForConfirmedValues` (12 сочетаний) |
| 20 | `remove("jit_pedido")` — удаление, не `false` | `r7` | `JitNetCore.kt:735-742` + `JitNet.remove` | `verifyOkRemovesPedidoInBothStores`, `verifyRemovalFailureIsReported` |
| 21 | предупреждение только при `!ok && pedido` | `r7` | `JitNetCore.kt:743-749` | `verifyNotAppliedStillRemovesAndWarns`, `verifyCacheFalseWithPedidoTrueIsNotSuccess` |
| 22 | тумблер = `setActive(getprop)` | `r7` | `JitRef.switchPosition/switchUpdate` + `ToolsPanel.applyJitFact` | `JitUiStateTest` (6) |
| 23 | следующая сверка без ключа не считает его запросом | `r7` (`contains`) | `JitNetCore.kt:714-733` | `secondVerifyAfterRemovalDoesNotSeeNewRequestForOff` |
| 24 | в цепочке нет `persist.*`, `usejitprofiles`, `ip rule del`, `ndc`, `settings`, `kill` | всё дерево | `JitRef.chainScript` | `JitRefTest.noForbiddenPropertiesOrCommandsInScript`, `JitChainScriptTest.noForbiddenCommands` |
| 25 | JIT только по клику, `zygote` только в скрипте клика | `j9`/`la` | `JitNetCore.kt` (единственное вхождение — литерал) | `clickedScriptPutsZygoteOnlyInClickPath`, `noOwnMechanismsLeftInCore` |

---

## 3. Что исправлено в этой итерации (по пунктам мандата)

### 3.1 §1.1 Возврат сети и точка доступа — ✅ (с одной заявленной адаптацией)

- `openAp` переписан как **полный `U`**: ранний `v()`, ожидание `O()`, `c1()` c ENABLING и
  fallback-исключением, owner-ветвь (`p2`), флаг `V`, общий `CountDownLatch` 25 с, `d()` + опрос
  до 20 с шагом 1 с, **сигнал FAILED** с 3 попытками и паузой 2 с, снятие latch во всех исходах.
  Было (справедливая претензия): «любой включённый Wi-Fi запрещает AP» + опрос без FAILED/латча/owner.
- **Источник FAILED** теперь реальный: динамический приёмник того же broadcast, что в референсе
  (`android.net.wifi.WIFI_AP_STATE_CHANGED`, `wifi_state == 14`), регистрируется **только на время
  попытки** и снимается в `endApWatch()` (тест `apWatchIsRegisteredAndReleased`).
- **`V`** — тот же смысл по факту: ставится, когда `setWifiEnabled(true)` вернул `true` в нашем
  auto-enable (`restoreNetwork`), снимается, когда прочитано «Wi-Fi выключен» (`t1`).
- 🟡 **Адаптация (не копия):** `O()`/CarplayMonitor в нашем приложении недоступен — в референсе это
  поля `n1/o1/p1` сервиса + установленный `com.njda.adapter` (`M()`) + их преф `connadaptor_modo`
  (`w0.u.b`). `JitNetState.carplayBusy()` возвращает `null`, и `U` пишет
  `carplay_wait=skipped reason=signal_unavailable`, а не изображает ожидание. Тесты покрывают оба
  варианта («сигнал есть» — на фейке: ждать, дождаться AP, продолжить; «сигнала нет» — skip).
- `onProcessStart` больше **не спит** 15 с в потоке пула и не держит `RESTORE_LOCK`: чтение снимка и
  `remove` выполняются в короткой критической секции, затем `postDelayed(e2, 15000)`
  (`BgWorker.postDelayed`). Риск референса сохранён и назван: снимок снят до паузы ⇒ смерть процесса
  в эти 15 с отменяет возврат (`deathBeforeScheduledRestoreIsDocumentedRisk`).

### 3.2 §1.2 Сверка и настоящее удаление — ✅

- `verify` приведён к DEX: `ok = (pedido == getprop) && (!pedido || maps != FALSE)`.
  Проверены **все** сочетания `pedido × getprop × cache` (12) + `getprop` нечитаем (6):
  `JitRefTest.verifyTableForConfirmedValues`, `verifyUnknownPropertyIsNotAVerdict`.
  В частности `true/true/cache=false` → **не** `OK` (`CACHE_MISSING` + предупреждение),
  `false/false/cache=false` → `OK`.
- `jit_pedido` теперь **удаляется** (`Editor.remove().commit()`), а не записывается `false`;
  результат проверяется повторным `contains` в обоих хранилищах, тест проверяет `contains=false`,
  а не `getBool=false`. Следующая сверка без ключа не воспринимает его как запрос (тест есть).
- Лог больше не лжёт: при удалении пишется `write=remove(jit_pedido)`, при нечитаемом свойстве —
  `write=none` + `JIT_PEDIDO kept` (заявленное отличие).
- Достижимость: сверка вызывается при **открытии панели** (`ToolsPanel.addJitRuntimeLine` →
  `AppPerformance.verifyJitOnly`, как «настройки» в референсе) и в watchdog **раз в 5 тиков**, но
  только если в префах есть `jit_pedido` (локальная проверка, без shell). Иначе — ни команд, ни
  записей (`startSendsNoCommandsToDevice`).

### 3.3 §1.3 Настоящий тумблер — ✅

- `ToolsPanel.addToggle`: положение JIT = `JitRef.switchPosition(confirmed, preference)` —
  подтверждённый `getprop` побеждает preference в обе стороны (был реальный баг: `on` при
  `perfJitConfirmed=on` возвращал preference).
- После фонового ответа обновляются **и** строка, **и** тумблер (`applyJitFact`): слушатель на время
  программной установки снимается (`setOnCheckedChangeListener(null)`), затем возвращается, поэтому
  клик-обработчик не запускается и настройка не пишется (тест по исходнику + чистая логика в
  `JitRef.switchUpdate`).
- `unknown` ⇒ подпись «· состояние неизвестно», положение не меняется программно. Новых кнопок нет.

### 3.4 §1.4 Хранилища и честные логи — ✅

- `saved = saved && …` убрано: каждое хранилище пишется **независимо**, результат —
  `WriteResult(attempted, ok, detail)`; сводка `2/2`, `1/2`, причина по каждому (`s0:commit_false`,
  `s1:exception`). Тесты: `clickSnapshotFailureDoesNotStopChain`, `clickThrowingStoreStillWritesSecond`,
  `verifyRemovalFailureIsReported`. Конфликт значений читается честно (`JIT_STORE conflict`).
- `onProcessStart` не пишет `removed=true` без подтверждения: пишет `removed=<allOk>`,
  `stores=<ok>/<attempted>`, `still_present=<bool>`.
- `JIT_START_RESULT` больше не содержит `restarted=`/`net_restore_done`: только
  `snapshot=`, `restore_removed=`, `restore_stores=`, `e2_scheduled=`, `delay_ms=`,
  `zygote_restart=not_claimed`, `internet=not_checked`.
- Устаревший комментарий про «`umount` выключен» исправлен: шаг включён и пишет `mount_step=present`.

---

## 4. `umount`: перенос ≠ безопасность — ⛔ блокер (решение владельца)

Что **фактически** включено (не «выключенный шаг под флагом»): в отправляемом скрипте есть

```sh
for _m in $(grep ' /data/app/' /proc/mounts | awk '{print $2}'); do
  umount -l "$_m" 2>/dev/null
done
```

Это **общий** ленивый `umount` всех точек, в пути которых есть `/data/app/`, а не только тех, что
смонтировало наше приложение. На стенде проверено, что других префиксов он не касается
(`JitChainScriptTest.mountStepTouchesOnlyDataApp`: `umount -l` ровно 2 раза, `/data/data` не
затронут, порядок «до `setprop`», последняя команда — `ctl.restart zygote`).

Чего **нет** и что поэтому **не доказано**: данных с живой ГУ о том, какие именно точки попадут под
`umount -l` в её mount namespace (root-транспорт и PID 1 могут видеть разные наборы), что за
источники/нижние слои у этих точек, и обратимо ли это после выключения функции/удаления программы.
Обратимость нельзя предполагать, а пробный `umount`, монтирование, рестарт или перезагрузку ради
проверки я не делал и не предлагаю.

**Статус до решения: ⛔. Запускать JIT на ГУ до ответа по этому пункту не нужно.**

Короткие команды **только на чтение** (по одной, вывод в чат/лог; временных скриптов не требуется):

```sh
cat /proc/self/mountinfo | grep -n '/data/app/'
```
```sh
cat /proc/1/mountinfo | grep -n '/data/app/'
```
```sh
ls -l /proc/self/ns/mnt /proc/1/ns/mnt
```
```sh
for m in $(grep ' /data/app/' /proc/mounts | awk '{print $2}'); do echo "== $m"; findmnt -no SOURCE,FSTYPE,OPTIONS --target "$m" 2>/dev/null || grep -F " $m " /proc/mounts; done
```

Что они покажут: (1) какие точки `/data/app/` видит **наш** транспорт; (2) какие видит PID 1 —
если наборы различаются, `umount -l` из нашего namespace уйдёт не туда, куда кажется; (3) совпадают
ли namespace; (4) источник и опции каждой точки (romount/overlay/bind — это и есть кандидаты на
«необратимо»). Решение после этих данных: оставить общий `umount -l` как в референсе **или**
разрешить минимальную адаптацию — `umount -l` только для точек, которые наше приложение само
занесло в собственный список (`files/jit_mounts.txt`), с записью «что сняли». Без вашего решения
ничего из этого не меняю.

---

## 5. Заявленные отличия от референса (минимальные, обоснованные)

| № | отличие | почему | где видно |
|---|---|---|---|
| 1 | нечитаемый `getprop` = «неизвестно», `jit_pedido` **не** удаляется | неизвестное нельзя выдавать за «выключено»/успех; иначе одна неудачная попытка чтения теряет намерение | `JitRef.parseProp/verify`, `JitOp.verifyApplied`, лог `JIT_PEDIDO kept` |
| 2 | тумблер показывает «состояние неизвестно» вместо «выключено» | то же требование; в референсе «любая строка кроме true» = `false` | `JitRef.confirmedState`, `ToolsPanel` |
| 3 | `O()` / CarplayMonitor пропускается (`carplay_wait=skipped`) | источника `n1/o1/p1`, `com.njda.adapter`, `connadaptor_modo` в нашем приложении нет | `JitNet.AndroidNetState.carplayBusy = null` |
| 4 | два хранилища (CPS+DPS) вместо одного, каждое — независимая попытка | так устроен проект; ключи/порядок/один `commit()` на хранилище сохранены | `JitNet.AndroidStore` |
| 5 | планировщик — `BgWorker.postDelayed`, а не handler сервиса | `CentralWatchdogService` у нас нет; ожидание не занимает поток пула | `JitNet.AndroidScheduler` |
| 6 | индикатор `postDelayed(ua, 45000)` — строка лога/текст панели | к сети и свойствам отношения не имеет | `JitOp.click` лог |
| 7 | owner-ветвь `U` реализована параметром и всегда `false` в JIT-пути | наш вызов — `U("pós-JIT", false)`, как в референсе | `JitOp.openAp(ownerCheck=…)` |

Ни одно отличие не меняет ни одной команды цепочки и ни одного ключа префов.

---

## 6. Тесты

Локально (`runtests.sh`: kotlinc 1.9.24 + `android.jar`-стабы + JUnit4): **`OK (103 tests)`**, 0 падений.
Это тесты на фейках/стенде, **не на ГУ**; на устройстве в этой итерации ничего не запускалось.

| файл | тестов | охват |
|---|---|---|
| `JitOrchestratorTest` | 31 | клик, запись (в т.ч. падение 1-го/2-го хранилища), продолжение (снимок снят → 15 с → возврат), `U` целиком (ранний AP, carplay есть/нет, ENABLING, исключение, owner, `V`, латч, FAILED×3, timeout), сверка + удаление + повторный проход, сквозная цепочка |
| `JitRefTest` | 13 | литерал скрипта (392), разбор снимка, `getprop`, **таблица `ok` (12 сочетаний)**, тумблер, `c1()`, запреты, отсутствие своих механизмов |
| `JitChainScriptTest` | 8 | реальный `/bin/sh`: порядок, слив правил, только `/data/app/` под `umount -l`, отказ `setprop`, нет запрещённых команд |
| `JitUiStateTest` | 6 | настоящее положение тумблера, программное обновление без клика, «неизвестно», сверка при открытии |
| `JitStartZeroWritesTest` | 5 | обычный старт: ноль записей/команд/сетевых вызовов |
| `JitLogsCancelTest` | 5 | раздельные строки состояния/запроса/ответа/сверки; нет механизма отмены |
| `JitNoPersistTest` | 3 | нет `persist.*`, `usejitprofiles` |
| `JitBootBoundaryTest` | 4 | boot-проба только читает |
| `JitRulesReadOnlyTest` | 2 | `ip rule` только читается |
| `AvasIndependenceTest` | 5 | AVAS не в цепочке; независимые функции звука не тронуты; legacy-проверка честная |
| `LogFilesTest` | 6 | путь логов |

Сымитированы потери ответа транспорта без ложного успеха: `QueueShell(runInline=0)` даёт `rc=137`,
`outcome=channel_lost`, команда остаётся в очереди, `JIT_CHAIN_ERROR`, ни одного `result=ok`
(`lostChannelIsNotLoggedAsSuccess`, `fakeQueueShellDoesNotLieAboutTimeout`).

---

## 7. AVAS — отдельная диагностика (причина НЕ установлена)

### 7.1 Что подтверждено кодом (факты, не гипотезы)

| путь | условие запуска | что делает | вывод |
|---|---|---|---|
| `WindowDoors.kt:234-240` (`carCallback`, `PROP_DOOR_UNLOCK_STATE = 0x214020bd`) | включён звук открытия (`LockSound.isUnlockSoundEnabled`) | на **каждый** callback вызывает `fireUnlockSound` без проверки `raw`/перехода; дебаунс 5 с (`WindowDoors.kt:120`) | в 1.0.60 это единственный триггер звука открытия; **сам факт прихода callback на ГУ не доказан** (логов 1.0.60 нет) |
| `WindowDoors.kt:317-325` | `initCarService` | `registerCallback(PROP_DOOR_POS, 0.0f)` и `registerCallback(PROP_DOOR_UNLOCK_STATE, 0.0f)` | подписка на VHAL-свойство замка; при отсутствии свойства callback не приходит |
| `LockSound.kt:687-736` (`playExternalSound`) | `playSound`/`playUnlockSound` | `MediaPlayer` по очереди 29 → 73 → 13 (`USAGE_ASSISTANCE_SONIFICATION`) | успешный `start()` и лог `AVAS external` **не доказывают** физический маршрут (наружу/в салон) |
| `LockSound.kt:584-618` (`playPreview`) | кнопка «Тест звука» в Инструментах | играет **в салон** через `USAGE_MEDIA` | это предпрослушивание, не штатный AVAS |
| `UiBridge.kt:662-678` (`dev.testAvasSound`, `dev.simulateDoorOpen`) | dev-кнопки | прямой `playExternalSound`/инъекция события двери | диагностика, не автоматика |
| `LockSound.kt:740-775` (`onBootOrWatchdog`) | включён звук замка | `Settings.System.putString(lock_car_audio_path / lock_car_audio_enable / unlock_car_audio_path)`, bind-mount `/vendor/etc/carlock/*.wav` (`:855-877`) | это **звук замка**, не AVAS движения; маунты только в `/vendor/etc/carlock/`, `/system/etc/avas/**` не трогается (`:860`, `:964-1000`) |
| `VoiceAssistantService.kt:353-362` | boot-ветка | вызывает `LockSound.onBootOrWatchdog` | те же записи/маунты, только при boot |
| `AppPerformance.kt:769-790` | boot | проверка остатков `avas_mode_saved` (1.0.60) / `avas_snapshot` — **только существование** | значения не читаются, ничего не применяется |
| `CarAudioPlayer.kt:161-171, 268-284` | TTS/озвучка | `AudioAttributes` usage 13/2/29/73/12/1, `requestAudioFocus(TRANSIENT_MAY_DUCK)` | аудиофокус/ducking может влиять на vendor-политику, но **перенаправление штатного AVAS не доказано** |
| `VoiceAssistantService.kt:851-887` | приветствие | `requestAudioFocus` (MAY_DUCK, `USAGE_VOICE_COMMUNICATION`) | то же |

Охват поиска: `grep -ri 'avas' app/src/main` + чтение перечисленных файлов; `setAVASMode`,
`AvAsMode`, запись в `/system/etc/avas`, `audioserver`, звуковые файлы AVAS — в текущем коде
**отсутствуют** (`AvasRestore.kt`/`AvasCore.kt` удалены в 1.0.61).

### 7.2 Гипотезы (не доказательства)

1. Звук, который вы слышите, — **звук открытия/закрытия замка** (наша ветка выше), а не AVAS
   движения. Тогда он идёт через `MediaPlayer(29/73/13)` и может быть слышен в салоне, если на этой
   ГУ `usage 29` не изолирован наружу.
2. Это **штатный AVAS** (движение/задний ход) — наша ветка замков тут ни при чём, а совпадение по
   времени случайно.
3. Влияние через **аудиофокус** TTS/ассистента на vendor audio policy.

Ни одна не подтверждена: лога 1.0.60 нет, `MediaPlayer.start()` маршрут не доказывает.

### 7.3 Вопросы владельцу (без ответов ничего не меняю)

1. Это мелодия **открытия/закрытия замка**, **предпрослушивание** из Инструментов, **голос/бип**
   ассистента или **непрерывный** звук штатного AVAS на движении/заднем ходу?
2. При каком событии слышен (открыл дверь / завёл / поехал / включил заднюю / нажал кнопку в панели)?
3. Слышен ли **одновременно снаружи** (попросите помощника послушать у бампера)?
4. Какие функции были включены: «звук замка», «звук открытия», ассистент/TTS?

Предлагаемый read-only план (с вашего согласия, **без** клика JIT и без перезагрузки):
в момент симптома снять `dumpsys audio` (и `dumpsys media.audio_flinger` при наличии) в файл и
прислать вместе с датированным логом `voiceapp3-ДД.ММ.ГГГГ.log` — там уже есть строки
`UNLOCK sound: firing`, `playSound[unlock] … usage=`, `playExternalSound: playing … usage=`.
Этого достаточно, чтобы отличить ветку замка от штатного AVAS, не меняя ни одной строки звука.

---

## 8. Чек-лист приёмки

| требование §§1–5 | статус |
|---|---|
| §1.1 полный `U` (ранний AP, `O()`, `c1`, owner, `V`, latch 25 с, 20 с опрос, FAILED×3, 2 с) | ✅ с адаптацией `O()` (🟡 п.3 отличий) |
| §1.1 FAILED: источник и время, отличие от timeout, очистка latch, исключения | ✅ (`u2`-эквивалент, `finally`) |
| §1.1 `onProcessStart` без блокировки пула, `remove → postDelayed`, риск смерти процесса | ✅ |
| §1.1 сетевые действия только в цепочке JIT | ✅ (иных `svc wifi`/`setWifiEnabled` в JIT нет) |
| §1.2 формула `r7` + все сочетания; `remove`, а не `false`; следующий проход | ✅ |
| §1.2 `UNKNOWN` как заявленное отличие | ✅ |
| §1.2 когда и как вызывается сверка; убран ложный `write=none` | ✅ |
| §1.3 настоящий `Switch`, обе стороны, `unknown`, async без клика, тесты | ✅ |
| §1.4 независимые хранилища, `removed` только при подтверждении, честные логи | ✅ |
| §2 `umount`: перенос, отсутствующие данные, блокер, короткие read-only команды | ⛔ (блокер, §4) |
| §3 AVAS: подтверждённые пути, гипотезы отдельно, вопросы, read-only план | ✅ (причина не установлена) |
| §4 запреты (нет boot-restore, watcher/HOLD/кнопок, `setenforce`, `pm install`, 5555, kill audioserver, запись звуков) | ✅ проверено тестами по исходнику |
| §5.3 shell-цепочка соответствует референсу, `zygote` только по клику, потеря ответа без ложного успеха | ✅ |
| §5.4 полный CI с passed/failed и причинами ограничений | ✅ / ограничение: green CI ≠ проверка ГУ |
| §5.7 пуш только `fix/v2`, версия 61/1.0.61, `SOURCE_SHA` | ✅ |

**Чего я не утверждаю:** что JIT проверен на ГУ, что `umount -l` обратим, что AVAS найден и/или
исправлен, что после `ctl.restart zygote` приложение обязательно поднимется и вернёт сеть.
