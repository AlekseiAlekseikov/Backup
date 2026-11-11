# REPORT iter33p (1.0.61) — ТРЕТИЙ ЗАХОД: 11 замечаний REVIEW к `78a3248` исправлены

**Ветка:** `fix/v2` (без `--force`, `main` не тронут). **Версия:** `versionCode=61`,
`versionName="1.0.61"` (не устанавливалась — APK собирает и подписывает владелец).

> **Почему заходов несколько.** Первый (`c8e4c9c`) был построен на СВОЕЙ механике (журнал шагов,
> TTL, окно 60 с, `CONTINUE/COMPENSATE/HOLD`, watcher, boot-backfill) вместо копии `j9`→`la`→`e2`.
> Второй (`577d338` → `78a3248`) её убрал, но REVIEW нашёл 11 дефектов в самом исполнении:
> ложный признак «цепочка выполнена», потеря `ChainRun`, ложный `unknown` из-за перевода строки,
> признак «та же загрузка» по допуску ±60 с, вызов несуществующей функции и т. д. Этот документ
> описывает третий заход по мандату `PROMPT_iter33p_restart-2_…ИСПРАВЛЕНИЕ_1.0.61.md` (§0.1 — 11
> пунктов) и заменяет прежний отчёт целиком.

---

## 0. Публикация и CI

| Что | Значение |
|---|---|
| база (до работы) | `e8f5e23` (`fix/v2`, handoff второго захода) |
| код этой итерации | **`aed18fb`** (`aed18fb38769109c53a42e96aef16de7ea396063`) |
| CI по коду | run **`42954771103`**, job **`130307151164`** — **SUCCESS**, `BUILD SUCCESSFUL in 4m 27s` |
| тесты в CI | **186 PASSED / 0 FAILED** (`grep -c ' PASSED$'` = 186, `grep -c ' FAILED$'` = 0 по полному логу) |
| artifact | `app-debug`, id `13826483630`, 177 171 113 Б |
| handoff | отдельный коммит: только `docs/agent/**` + `app/buildinfo.properties` (`SOURCE_SHA=aed18fb`) |

`SKIPPED` в логе — только Gradle-задачи (`checkKotlinGradlePluginConfigurationErrors`,
`kspDebugUnitTestKotlin`), ни одного пропущенного теста.

### 0.1 Разбивка JIT-тестов в CI (из полного лога той же джобы)

| класс | в CI | локально |
|---|---|---|
| `JitOrchestratorTest` | 20 | 20 |
| `JitRefTest` | 13 | 13 |
| `JitChainScriptTest` | 8 | 8 |
| `JitStartZeroWritesTest` | 5 | 5 |
| `JitLogsCancelTest` | 5 | 5 |
| `AvasIndependenceTest` | 5 | 5 |
| `JitBootBoundaryTest` | 4 | 4 |
| `JitNoPersistTest` | 3 | 3 |
| `JitRulesReadOnlyTest` | 2 | 2 |
| `LogFilesTest` (§9, лог по датам) | 6 | 6 |
| **итого** | **71** | **71** |

Остальные 115 PASSED — тесты других подсистем (TTL, климат, статус-иконки, плагин, reconnect и
т. д.), они не менялись.

### 0.2 Что именно в коммите `aed18fb`

| файл | изменение |
|---|---|
| `tools/JitNetCore.kt` | ядро `JitRef`+`JitOp`: `startLock`/`startGate`, честный `parseProp`, `persist`-флаг сверки, `nothing_to_restore ⇒ DONE`, возврат `ChainRun`, скрипт приведён к DEX символ в символ |
| `tools/JitNet.kt` | адаптеры: `AndroidShell`/`AndroidStore` (два `commit()` + обратное чтение), `AndroidNetState` (рабочий `setWifiEnabled`), `click`/`onProcessStart`/`verifyApplied` |
| `tools/AppPerformance.kt` | `setAutoOptimize` (pref пишет ядро), `jitApplyWithZygote`→`JitNet.click`, `jitPendingVerify`→`JitNet.onProcessStart`, `verifyJitOnly` read-only, `jitRuntimeFact*`, восстановлен `reportLegacyAvasSnapshot` |
| `tools/ToolsBackend.kt` | `perfJit` = запрос, `perfJitRuntime`/`Raw`/`Reason` = факт из кэша |
| `ui/ToolsPanel.kt` | строка «Сейчас на устройстве: …» под тумблером (только кэш, без shell из UI) |
| `tools/JitBootMark.kt` | удалён недоказанный допуск ±60 с (`sameLoad`/`format`/`stripEpochPrefix`) |
| тесты | 5 классов переписано, 4 удалено, 5 добавлено (см. §4) |

`git show --stat aed18fb`: 21 файл, **+1190 / −1182**.

### 0.3 `SOURCE_SHA`

`app/buildinfo.properties`: `SOURCE_SHA=e8f5e23` → **`aed18fb`** в handoff-коммите. `git rev-parse`
в сборке не используется — сборка читает файл-маркер (владелец собирает в Android Studio).

### 0.4 Замечания REVIEW → что сделано (по одному)

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

Наша копия: `JitOp.prepare` (`JitNetCore.kt:455`) — чтение состояния (`wifiOn`/`apUp`, `null` ⇒
`REFUSED_STATE_UNKNOWN`, ноль команд), одна запись снимка с подтверждением обратным чтением,
затем ключи референса `jit_pedido`/`perf_auto_otimizar`/`jit_restaurar` (`JitRef.KEY_*`), затем
скрипт. Отличие — «снимок одним значением» `jit_op` (§3).

### 1.2 Скрипт: `w0/la.run` — символ в символ

`JitRef.chainScript` (`JitNetCore.kt:105`) генерирует ровно литерал DEX (`w0/la.smali:326`+`:342`,
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

Наша копия — `JitOp.onProcessStart` + `JitOp.restoreNetwork` (`JitNetCore.kt:536`, `:666`): та же
регулярка (`JitRef.parseRestore`), пауза `RESTORE_DELAY_MS = 15_000` (`:65`), **AP первым**, затем
Wi-Fi; AP ждётся до 20 с шагом 1 с (`AP_POLL_*`, как `U`), Wi-Fi — до 10 с шагом 0.5 с (наше
отличие, §3).

**Как служба запускается и получает данные ИМЕННО этой операции.** `ctl.restart zygote` даёт
новую загрузку фреймворка ⇒ система поднимает приложение ⇒ `.BootReceiver` (BOOT_COMPLETED /
LOCKED_BOOT_COMPLETED / QUICKBOOT_POWERON) и `VoiceAssistantService.onCreate`/`onStartCommand` →
`AppPerformance.checkPendingZygoteVerify` → `jitPendingVerify` → `JitNet.onProcessStart`; плюс
`AppPerformance.jitBootProbe` из `App.kt`. Данные операции — префы (`jit_op` + три ключа
референса) и тег загрузки: они лежат на диске, поэтому переживают смерть процесса.

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

Наша копия — `JitRef.verify` (`JitNetCore.kt:163`) + `JitOp.verifyApplied` (`:761`): та же формула,
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

## 3. Отличия: референс против реализации (п.11 §0.1)

| № | поведение | референс | у нас | зачем / что при отказе | код |
|---|---|---|---|---|---|
| 1 | снимок состояния | три ключа одним `commit()`, результат `commit()` не проверяется | дополнительно `jit_op` одним значением + **обратное чтение** обоих хранилищ | иначе сеть уже выключена, а вернуть нечем; отказ ⇒ `SNAPSHOT_FAILED`, скрипт не отправляется, Wi-Fi не тронут | `JitNetCore.kt:455`, `JitNet.kt:75` |
| 2 | второй клик | всегда перезаписывает `jit_restaurar` | `REFUSE_PENDING`, пока снимок этой загрузки не разобран; чужой/повреждённый снимок не перезаписывается (`REFUSE_UNRESOLVED`) | незавершённая операция не должна терять снимок; при отказе — ноль команд устройству | `JitNetCore.kt:314`, `:411` |
| 3 | продолжение на старте | `onCreate` → `postDelayed(15000)` → возврат, без проверки «а цепочка выполнялась?» | решение по фактам: `boot_id` + признак «сеть уже изменилась»; иначе `WAIT_NO_EVIDENCE` | исключить возврат по одному факту старта; при отказе — сеть не трогаем, снимок сохраняем | `JitNetCore.kt:290`, `:635` |
| 4 | `jit_restaurar` | `remove(...)` сразу при чтении `onCreate` | снимок остаётся историей операции до `RESTORED`/`DONE` | при неудачном возврате снимок нужен для следующего старта; перезапись — только новым кликом | `JitNetCore.kt:536-620` |
| 5 | ожидание AP | `U()`: latch 25 с, `v()` до 20 000 мс шагом 1 с, до 3 попыток | то же окно и шаг, без «3 попыток» (третья попытка в референсе только при ответе `FAILED`) | лишний повтор — новая попытка открыть AP при уже включённом клиентском Wi-Fi; при отказе — `not_up_after_20s`, снимок не завершён | `JitNetCore.kt:666` |
| 6 | ожидание Wi-Fi | нет (только лог `c1()` сразу после запроса) | опрос до 10 с шагом 0.5 с | «запрошено» ≠ «включено»: иначе UI и лог показывали бы успех при отказе | `JitNetCore.kt:78` |
| 7 | сверка | `trim()=="true"`, rc не смотрится; `jit_pedido` снимается всегда | `rc≠0`/`ERR_`/многострочно ⇒ `UNKNOWN`; намерение сохраняется | при недоступном канале `false` считался бы фактом и при `want=false` дал бы ложный «успех» | `JitNetCore.kt:144`, `:761` |
| 8 | опознание загрузки | загрузка не опознаётся вообще (см. §3.1 ниже) | строгий `boot_id`-тег; `epoch…` ⇒ `UNKNOWN` ⇒ не применяем | снимок прошлой загрузки не должен отменять чужое решение | `JitNetCore.kt:261` |
| 9 | шаг `umount -l` §1.1 | выполняется всегда | реализован символ в символ, **выключен** (`MOUNT_STEP_ENABLED=false`) | обратимость не доказана (§3.2) ⇒ по мандату не выполнять | `JitNetCore.kt:86` |
| 10 | рестарт zygote | команда отправлена = процесс умрёт | `restart=not_claimed` в логе; итог — только по сверке на следующем старте | ни транспорт, ни `H1` не доказывают рестарт | `AppPerformance.kt:891`, `JitNet.kt:184` |
| 11 | индикация | `H1(45000)` + прогресс | `ToolsStep.Status.PARTIAL` («отправлено, итог на следующем старте») | процесс умирает, прочитать факт нечем | `AppPerformance.kt:891` |

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
   ничего не рвётся (их файловые дескрипторы живы), но новый `open()`/`mmap()` по этому пути даёт
   `ENOENT`.
3. **Последствия для ЧУЖИХ приложений.** Если точка — APK другого приложения (в том числе
   системного), то после снятия его код нельзя заново отобразить в память: первый же холодный
   старт/перезапуск процесса = падение или «приложение не установлено». Это ровно тот класс
   необратимых правок, который запрещён уточнением 1.0.61.
4. **Bind-mount на изолированных данных** (`/data/user/0/...`, `ce`/`de` storage): снятие точки с
   APK не затрагивает данные, но и не помогает: JIT-кэш (`dalvik-jit-code-cache`) лежит не там, а
   в `/data/dalvik-cache`, который шаг не трогает.
5. **Обратимость.** Штатного «вернуть как было» в рамках нашей программы нет: обратное
   `mount` требует знать исходные параметры (source, fstype, opts) и права, которых у нас нет;
   после `umount -l` восстановление возможно только перезагрузкой ГУ (а перезагрузка как лечение
   запрещена). Значит, требование «после выключения функций/удаления приложения система должна
   возвращаться в сток» этим шагом нарушается.
6. **На ГУ проверять запрещено** (мандат: исследование — только чтение, массовый unmount не
   запускать), поэтому состав `/data/app/` в `/proc/mounts` полем не подтверждён.

**Вывод:** обратимость и безопасность для чужих приложений **не доказаны**, поэтому шаг не
исполняется. Он реализован символ в символ, включается одной константой
(`JitRef.MOUNT_STEP_ENABLED`) после решения владельца; тест
`JitChainScriptTest.mountStepTouchesOnlyDataAppWhenEnabled` доказывает, что при включении снимаются
только точки `/data/app/`, шаг стоит до `setprop`, и появляется ровно 3 строки.

**Что нужно от владельца:** (а) подтвердить по `adb shell cat /proc/mounts | grep ' /data/app/'`,
что на его ГУ вообще есть такие точки (если их нет — вопрос снимается), (б) решение «выполнять /
не выполнять»; без него шаг остаётся выключенным.

---

## 4. Тесты (§4 мандата)

Локально: **71 / 71 PASS** на настоящем `/bin/sh` с подставными
`svc`/`ip`/`setprop`/`getprop`/`sleep`/`umount` (§7). В CI те же 71 (§0.1).

| класс | тестов | что покрывает |
|---|---|---|
| `JitRefTest` | 13 | формат `jit_restaurar`; **литерал из DEX символ в символ** (длина 392); первая/последняя команда, окно 20 с; `parseProp` (rc≠0, `ERR_`, многострочно, пусто); формула `bb/r7`; `bootMatch`; `startDecision`/`clickGate` |
| `JitChainScriptTest` | 8 | реальный `sh`: порядок команд, ранний выход при 0 правилах, исчерпание 20 с (20×`sleep 1`), `false`-ветка, только `/data/app/` при включённом шаге, отказ `setprop` не обрывает цепочку, ноль запрещённых литералов |
| `JitOrchestratorTest` | 20 | вся цепочка: клик → снимок → транспорт (с очередью!) → старт процесса → возврат AP→Wi-Fi → сверка. Потеря канала и поздний `drain`, смерть процесса до zygote, повторный клик при незавершённой операции, чужая загрузка, `epoch`-тег, повреждённый снимок, «нечего возвращать», отложенное появление AP/Wi-Fi, отказ API, повтор после неудачи, отсутствие повторного возврата, `verify` ok/cache_missing/unknown/not_applied, одновременные клики через барьер, одновременные старты |
| `JitStartZeroWritesTest` | 5 | обычный старт и «через 30 с»: **ноль записей** (счётчик записей хранилища), ноль команд, ноль сетевых запросов; остаток `jit_pedido=true` не переписывается; чужая загрузка — ноль записей и снимок цел; в стартовом коде нет рестарта/`persist`/собственных механизмов |
| `JitLogsCancelTest` | 5 | в логе раздельно состояние (`JIT_SNAPSHOT`), запрос (`JIT_CHAIN_SENT`), ошибки (`channel_lost`) и `unknown`; восстановление только включает (ни одного `false`-запроса); механизма отмены/компенсации нет (код + поведение) |
| `AvasIndependenceTest` | 5 | AVAS-механизм удалён; JIT-код не ссылается на AVAS; в цепочке нет ни одной AVAS/audio-команды (проверяется по журналу вызовов); остаток `avas_snapshot` только фиксируется; независимые функции звука на месте |
| `JitBootBoundaryTest` | 4 | нет `bootRestoreJit`/`jitPersistBackfill`/повтора 30 с/записи `persist.*`; `ctl.restart` ровно один раз и только в тексте скрипта; старт — только чтение; остатки persist только читаются |
| `JitNoPersistTest` | 3 | нет записи `persist.*`/`usejitprofiles`; в скрипте одно `setprop`; старые значения только читаются |
| `JitRulesReadOnlyTest` | 2 | `ip rule` только считается (код + исполнение): ни `del`, ни `flush`, ни `ndc`, ни `settings` |
| `LogFilesTest` | 6 | имя `voiceapp3-DD.MM.YYYY.log`, локальная дата, append, retention 14 сут. только своих файлов, чужие имена не трогаются, ошибка записи не ломает приложение |

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
=== run ===      OK (71 tests)
ИТОГ: PASS=71 FAIL=0
```

Как повторить: `/home/user/runtests.sh` (kotlinc 1.9.24 + `android.jar` + junit 4.13.2; в сборку
не входит, каталог вне `app/src/**`). Классы — те же 10 из §0.1.

Это **не** прогон на ГУ: `WifiManager`, `startSoftAp`, `ctl.restart zygote` подменены; Android-модуль
локально не собирается (нет Android SDK) — его проверяет CI (`:app:compileDebugKotlin`). Поэтому
дополнительно локально проверено: `JitNet.kt`+`JitNetCore.kt` типизируются против настоящего
`android.jar` (без ошибок), а весь `app/src/main/java` даёт ноль ошибок в JIT-файлах.

---

## 8. Чек-лист

### 8.1 Одиннадцать пунктов §0.1 мандата restart-2

| № | требование | статус | доказательство |
|---|---|---|---|
| 1 | гипотеза не выдаётся за гарантию; продолжение — по фактам | ✅ | `JitNetCore.kt:290`, `:635`; `JitOrchestratorTest.timeoutWithQueuedShellDoesNotTriggerEarlyRestore` |
| 2 | копия и ограничения различимы; всё, что не из референса, помечено | ✅ | §3 (таблица 11 отличий), §0.4 |
| 3 | сеть не возвращается и снимок не снимается по одному boot ID | ✅ | `JitNetCore.kt:290` (`WAIT_NO_EVIDENCE`), `JitStartZeroWritesTest` |
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
| 1.1 | снимок Wi-Fi/AP до изменений + ключи референса | ✅ | `JitNetCore.kt:455`, `JitOrchestratorTest.clickSavesSnapshotThenRunsReferenceChain` |
| 1.2 | один скрипт: `svc wifi disable` → слив ≤20 с → `usejit` → zygote последней | ✅ | `JitNetCore.kt:105`, `JitRefTest.verbatimReferenceLiteralFromSmali` (392 симв.), `JitChainScriptTest.orderIsReferenceOrder` |
| 1.3 | шаг монтирований §1.1 | ⛔ | реализован символ в символ, выключен: обратимость не доказана (§3.2) |
| 2 | возврат — продолжение той же операции, AP→Wi-Fi, 15 с | ✅ | `JitNetCore.kt:536`, `:666`; `JitOrchestratorTest.lateShellThenRestoreOnNextStart` |
| 2.1 | объяснить запуск службы и получение данных операции | ✅ | §1.3 |
| 2.2 | тайминг — по инструкциям, недоказанное не за факт | ✅ | §1.3 (15000 = `0x3a98`; «`onCreate` вовремя» не утверждается) |
| 3 | сверка `getprop` + `dalvik-jit-code-cache`, нет данных ⇒ `unknown` | ✅ | `JitNetCore.kt:163`, `:761`; `JitRefTest.onlyTrueAndFalseAreFacts` |
| 4 | обычный старт/ребут не применяют JIT и не рестартуют zygote | ✅ | `JitStartZeroWritesTest` (5) |
| 5 | §1.1 исследование обратимости | 🟡 | §3.2 |
| 6 | §1.2 убрать своё (persist, watcher, отмена, `sleep 8`, санация, boot-backfill, `bootRestoreJit`, `jitPersistBackfill`) | ✅ | §2, `JitNoPersistTest`, `JitStartZeroWritesTest.noOwnMechanismsInStartPath` |
| 6.1 | старые persist-значения не менять, дать read-only способ | ✅ | §2.1 |
| 6.2 | не копировать `ip rule del` | ✅ | `JitRulesReadOnlyTest` |
| 7 | жизненный цикл: одна операция, второй клик не перезаписывает, чужая загрузка ⇒ ноль записей | ✅ | `JitNetCore.kt:314`, `JitOrchestratorTest.concurrentClicksStartExactlyOneOperation`, `JitStartZeroWritesTest.previousBootStartWritesNothingAndKeepsSnapshot` |
| 7.1 | `ARMED`/«отправлено»/отсутствие журнала/60 с/таймаут — не доказательства | ✅ | `JitRef.chainOutcome`, `restart=not_claimed`, `JitLogsCancelTest.lostChannelIsLoggedAsErrorNotSuccess` |
| 7.2 | `setWifiEnabled(true)`/`startSoftAp` ≠ интернет; в логах раздельно | ✅ | `JitNetCore.kt:666` (`request`/`state_before`/`state_after`/`internet=not_checked`), §6 |
| 8 | AVAS — отдельные функции, убрать привязку к JIT | ✅ | §5 |
| 8.1 | поведение при старом снимке AVAS | ✅ | §2.2 |
| 9 | логи (путь, fallback, retention 14 сут.) | ✅ | §6, `LogFilesTest` (6) |
| 10 | не менять плагин/слоты/климат/батарею/TTL/CarPlay/рут-reconnect | ✅ | в `aed18fb` нет файлов этих подсистем (`git show --stat aed18fb`) |
| 11 | §4 тесты: вся цепочка, iso-стенд, одновременные клики, потеря транспорта, boot/30 с | ✅ | §4 |
| 11.1 | удалённые тесты перечислены с эквивалентом | ✅ | §4.1 |
| 12 | публикация + CI актуального SHA + run/job/PASSED/FAILED | ✅ | §0: `42954771103` / `130307151164` / 186 / 0 |
| 12.1 | полевой успех не заявлять | ✅ | на ГУ ничего не запускалось |

---

## 9. Что осталось за владельцем

1. **§1.1 монтирования** — решение по `umount -l` (шаг готов, выключен, §3.2). Полезная проверка
   только чтением: `adb shell cat /proc/mounts | grep ' /data/app/'`.
2. **Остатки persist-свойств JIT** прежних версий — чистить или оставить (сейчас только читаем, §2.1).
3. **Остаточный снимок AVAS** (`filesDir/avas_snapshot`) — удалять или оставить (сейчас не трогаем, §2.2).
4. **`ACTION_BOOT_HU`** — нужен ли отдельный фильтр, как в референсе (сейчас нет; продолжение
   привязано к старту процесса, не к конкретному boot-действию).
5. **Полевой прогон JIT** — до нового REVIEW владелец JIT на ГУ не запускает; всё подтверждённое
   здесь — локально и в CI, полевого успеха нет.
