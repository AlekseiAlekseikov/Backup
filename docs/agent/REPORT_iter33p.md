# REPORT iter33p (1.0.61) — ВТОРОЙ ЗАХОД: JIT ровно по механике референса

**Ветка:** `fix/v2` (без `--force`, `main` не тронут). **Версия:** `versionCode=61`, `versionName="1.0.61"`
(не устанавливалась — собирает и подписывает владелец).

> **Почему есть второй заход.** Первый заход (`c8e4c9c`, CI `42881198633` — 200/0) REVIEW признал
> неверным по существу: там была построена СВОЯ механика (журнал шагов, TTL операции, окно 60 с,
> `CONTINUE/COMPENSATE/HOLD`, watcher, boot-backfill), а не копия разрешённой последовательности
> `j9` → `la` → `e2`. Этот документ заменяет прежний отчёт целиком; прежние коммиты остаются в
> истории, но код 1.0.61 — тот, что ниже.

---

## 0. Публикация и CI

| Что | Значение |
|---|---|
| база (до работы) | `2e85b1abec8afc668c0a4d1055aafbdca4f5098b` (`fix/v2`, docs-коммит handoff прошлого захода) |
| код этой итерации | **`78a3248`** (первый push `577d338` не собрался — см. §0.4) |
| CI по коду | run `42896558590`, job `130111086379` — **SUCCESS**, 176 PASSED / 0 FAILED |
| handoff (`SOURCE_SHA=78a3248`) | отдельный коммит: только `docs/agent/**` + `app/buildinfo.properties`, кода не касается |

### 0.1 Что опубликовано

`2e85b1a..577d338`, затем `577d338..78a3248` в `fix/v2` (fast-forward, без `--force`).
`main` не тронут (`97c0cf1`).

| коммит | что |
|---|---|
| `577d338` | основной коммит кода (23 файла, +1594 / -3243) |
| `78a3248` | фикс сборки: возвращён `currentBootTag()` (см. §0.4) |

История не переписывалась (`--force` запрещён), поэтому неудачная попытка видна в ней.

Состав коммита кода: 23 файла, **+1594 / −3243**. Кода стало меньше на 1649 строк — это и есть
удаление собственных механизмов и привязки AVAS к JIT.

| файл | что |
|---|---|
| `tools/JitNetCore.kt` | `JitRef` (константы и текст скрипта референса) + `JitOp` (ядро операции) + швы `JitShell`/`JitStore`/`JitNetState` |
| `tools/JitNet.kt` | тонкие Android-адаптеры (`AdbClient`, `Prefs2`, `WifiManager`), без собственных решений |
| `tools/AppPerformance.kt` | `setAutoOptimize` → `jitApplyWithZygote` → `JitNet.click`; на загрузке только чтение |
| `tools/JitBootMark.kt` | только опознание загрузки (остальные «решения» удалены) |
| `tools/AvasCore.kt`, `tools/AvasRestore.kt` | **удалены целиком** |
| `VoiceAssistantService.kt` | убран повтор через 30 с |
| тесты | 8 классов переписано/добавлено, 5 удалено (§4) |

### 0.2 CI по опубликованному коду

| Run | Job | Коммит | Итог | PASSED / FAILED |
|---|---|---|---|---|
| `42894105428` | `130102548785` | `577d338` | **FAILED** (`:app:compileDebugKotlin`) — см. §0.4 | - |
| `42896558590` | `130111086379` | `78a3248` | **SUCCESS** (`BUILD SUCCESSFUL in 4m 35s`) | **176 / 0** |
| `42899434089` | `130121134202` | `163cb65` (handoff, только docs + `SOURCE_SHA`) | **SUCCESS** (`BUILD SUCCESSFUL in 3m 42s`) | **176 / 0** |

Числа взяты из полного лога джобы: `grep -c ' PASSED$'` = **176**, `grep -c ' FAILED$'` = **0**.
`SKIPPED` в логе — только Gradle-задачи (`checkKotlinGradlePluginConfigurationErrors`,
`kspDebugUnitTestKotlin`), ни одного пропущенного теста. Artifact `app-debug` — `13802266250`,
177 156 675 Б.

Разбивка JIT-классов этой итерации (из того же лога): `JitChainEndToEndTest 14`, `JitRefTest 13`,
`JitChainScriptTest 6`, `JitBootTest 5`, `JitTogglePathTest 4`, `JitBootMarkTest 4`,
`AvasIndependenceTest 4`, `JitNoPersistTest 3`, `JitRulesReadOnlyTest 2` — итого **55**
(ровно как в локальном прогоне §7). Прежние 200 PASSED были выше только потому, что удалённые
классы (`AvasCoreTest 13`, `AvasContractTest 5`, `JitNetCoreTest 23`, `JitToggleChainTest 5`,
`JitPersistTest 3`, старый `JitBootTest 6`) проверяли код, которого больше нет; эквивалентное
покрытие — §4.1.

### 0.4 Первый прогон CI: что упало и почему

`42894105428` / `130102548785` по `577d338` — **FAILED**, единственная причина:

```
e: .../tools/AppPerformance.kt:802:23 Unresolved reference 'currentBootTag'.
e: .../tools/AppPerformance.kt:875:23 Unresolved reference 'currentBootTag'.
e: .../tools/AppPerformance.kt:936:36 Unresolved reference 'currentBootTag'.
Execution failed for task ':app:compileDebugKotlin'.
```

Причина честная: вместе с удалённым блоком `jitProps()`/`jit_verified` из `AppPerformance` ушла
и локальная `currentBootTag()`. Исправлено в `78a3248`: функция восстановлена и делегирует в
`HvacDigitalPlugin.currentBootTag()` (`AppPerformance.kt:712`), чтобы опознание загрузки было
одно на приложение. Компиляцию модуля песочница не делает (нет Android SDK), поэтому это поймал
CI — локальный JVM-прогон `AppPerformance` не собирает по определению (§7).

### 0.3 `SOURCE_SHA`

`app/buildinfo.properties`: `SOURCE_SHA=c8e4c9c` → **`78a3248`** в handoff-коммите. `git rev-parse`
в сборке не используется (сборка читает файл).

**Локальный JVM-прогон** (не заменяет Android CI): `PASS=55 FAIL=0`, см. §7.
Полевого подтверждения на ГУ нет и в этом отчёте не заявляется.

---

## 1. Механика референса — что именно скопировано

Проверено по коду `CentralEXAuto_v3.3.9.apk` (md5 `c3f1664125953b9851bf4483b265135f` из ZIP
md5 `8a65c6c29749c64d4e335c57490a347a` — суммы совпали, APK скачан заново в этой итерации;
декомпиляция jadx 1.4.7 + baksmali 2.5.2). Выписки — `docs/agent/ref/centralexauto_v339_jit_chain.md`.

### 1.1 Клик: `w0/j9.onClick`

```java
wifi = wm.isWifiEnabled();  ap = ap0 != null && ap0.isUp() && ap0.getInetAddresses().hasMoreElements();
prefs.edit().putBoolean("jit_pedido", target).putBoolean("perf_auto_otimizar", target)
     .putString("jit_restaurar", "wifi=" + wifi + ",ap=" + ap).commit();   // ДО любых изменений
pool.execute(new la(activity, target, progress));
```

Наша копия: `JitOp.prepare` (`JitNetCore.kt:265-315`) — чтение состояния, одна запись набора
ключей (`jit_pedido`, `perf_auto_otimizar`, `jit_restaurar`, `<наши>` boot/ts), затем скрипт.
Ключи — те же (`JitRef.KEY_PEDIDO/KEY_AUTO/KEY_RESTORE`, `JitNetCore.kt:35-46`).

### 1.2 Скрипт: `w0/la.run` — одна строка в строку

`JitRef.chainScript` (`JitNetCore.kt:95-113`):

```
svc wifi disable
_n=0
while [ $_n -lt 20 ]; do
  [ "$(ip rule show | grep -c 'oif wlan0')" = "0" ] && break
  sleep 1; _n=$((_n + 1))
done
for _m in $(grep ' /data/app/' /proc/mounts | awk '{print $2}'); do   ← только при MOUNT_STEP_ENABLED
  umount -l "$_m" 2>/dev/null
done
setprop dalvik.vm.usejit <true|false>
setprop ctl.restart zygote          ← последняя команда
```

Совпадение с оригиналом проверено построчно: `JitRefTest.scriptStartsWithWifiDisableAndEndsWithZygoteRestart`,
`drainLoopIsReferenceShaped`, `mountStepIsOffByDefaultAndOnlyAddsReferenceLine`, а фактическое
исполнение — `JitChainScriptTest.orderIsReferenceByteForByte` (настоящий `/bin/sh`, подставные
`svc`/`ip`/`setprop`/`getprop`/`sleep`/`umount`): последняя запись в журнале вызовов — ровно
`setprop ctl.restart zygote`, и она одна.

Таймаут транспорта — `JitRef.CHAIN_TIMEOUT_MS = 40_000` (`JitNetCore.kt:58`), как `H1(40000, …)`.

### 1.3 Возврат после подъёма фреймворка — `w0/e2.run`

```java
if (s != null && Pattern.compile("^wifi=([01]),ap=([01])$").matcher(s).matches()) {
    prefs.edit().remove("jit_restaurar").apply();
    handler.postDelayed(new e2(restauro, service, 0), 15000L);
}
// e2 case 1:  if (ap) service.U("pós-JIT", false);            // AP ПЕРВЫМ
//             if (wifi && !wm.isWifiEnabled()) wm.setWifiEnabled(true);  // затем Wi-Fi
```

Наша копия — `JitOp.continueAfterBoot` + `JitOp.restoreNetwork` (`JitNetCore.kt:332-420`):
разбор той же регуляркой (`JitRef.parseRestore`, `:80`), пауза `RESTORE_DELAY_MS = 15_000`
(`:55`), **AP первым**, затем Wi-Fi; `JitChainEndToEndTest.continuationRestoresApFirstThenWifi`
проверяет и порядок запросов (`["ap=start", "wifi=true"]`), и величину паузы.

**Как служба запускается и получает данные именно этой операции.** В референсе —
`CentralBootReceiver` ловит `BOOT_COMPLETED`/`ACTION_BOOT_HU`/`MY_PACKAGE_REPLACED` и вызывает
`startForegroundService(... "motivo_inicio" = "boot"|"update")`; `ctl.restart zygote` даёт новую
загрузку фреймворка ⇒ `BOOT_COMPLETED` ⇒ `onCreate` читает `jit_restaurar` из ТЕХ ЖЕ префов и
планирует `e2` через 15 с. У нас эквивалент — `.BootReceiver` (BOOT_COMPLETED /
LOCKED_BOOT_COMPLETED / QUICKBOOT_POWERON; `ACTION_BOOT_HU` в манифесте НЕТ — отличие ниже) и
`AppPerformance.jitPendingVerify` из `VoiceAssistantService.onCreate` (`:270`) и
`onStartCommand` (`:349`) + `AppPerformance.jitBootProbe` из `App.kt:51`.
Данные операции попадают в продолжение через префы `jit_restaurar` + наша метка загрузки.

**Тайминг подтверждён только инструкциями:** 15000 мс — это литерал `0x3a98` в
`CentralWatchdogService.onCreate` (smali, jadx метод не осилил). Что наш `onCreate` будет вызван
ровно в этот момент, ничем на ГУ не доказано: поэтому пауза выдерживается нами внутри
`continueAfterBoot`, а не «надеждой, что сервис поднимется вовремя». Если `onCreate` случится
позже, вернём сеть позже — это не ошибка механики, но и не подтверждённый тайминг.

### 1.4 Сверка: `w0/bb` + `w0/r7`

```java
getprop = "getprop dalvik.vm.usejit" → trim() == "true"
maps    = есть ли "dalvik-jit-code-cache" в /proc/self/maps  (ошибка → null)
ok = (pedido == getprop) && (!pedido || maps != Boolean.FALSE);
prefs.edit().remove("jit_pedido").apply();  // снимается после сверки
if (!ok && pedido) toast(master_perf_jit_not_applied);
```

Наша копия — `JitRef.verify` (`:116-124`) + `JitOp.verifyApplied` (`:382-410`): те же условия;
`NO_DATA` (пустой `getprop`) даёт `unknown`, а не успех; `jit_pedido` снимается только после чтения.
Тесты: `JitRefTest.verifyFollowsReferenceFormula`, `noDataIsUnknownNeverSuccess`,
`JitChainEndToEndTest.verifyWithoutCacheIsNotSuccess` / `verifyWithoutDataIsUnknown`.

---

## 2. Что удалено как «наше, не референса» (§1.2 мандата)

| удалено | где было | чем подтверждается, что нет |
|---|---|---|
| `jitPersistBackfill`, запись `persist.dalvik.vm.usejit` / `persist.sys.dalvik.vm.usejit` | `AppPerformance.kt` | `JitNoPersistTest.noPersistWritesAnywhereInJitCode`, `JitTogglePathTest` |
| `dalvik.vm.usejitprofiles` | `AppPerformance.kt` | `JitNoPersistTest` |
| `bootRestoreJit` + повтор через 30 с | `VoiceAssistantService.kt:382` | `JitBootTest.bootRestoreRemovedFromAllPaths` (нет `bootRestoreJit`, нет `30_000L`) |
| watcher, файл-отмена, `sleep 8`, журнал шагов `JITSTEP`, TTL операции, окно 60 с, PID zygote, `CONTINUE/COMPENSATE/HOLD` | `JitNetCore.kt` | `JitBootTest.noOwnWatcherOrJournalMechanismsLeft` |
| санация/удаление `ip rule` | `JitNet.kt` | `JitRulesReadOnlyTest.rulesAreOnlyCounted`, `countIsDiagnosticsNotVerdict` |
| файл `jit_verified`, `jitProps()`, `lastJitInitKey` | `AppPerformance.kt` | `verifyJitOnly` теперь read-only (`AppPerformance.kt:927`) |
| `AvasCore.kt`, `AvasRestore.kt` целиком | `tools/` | `AvasIndependenceTest.avasMechanismRemoved` |

**Оставшиеся отличия от референса — по одному, каждое с назначением** (§3 ниже).

### 2.1 Остатки старых версий на ГУ (только чтение)

`AppPerformance.jitBootProbe` (`:721-755`) читает `persist.dalvik.vm.usejit`,
`persist.sys.dalvik.vm.usejit`, `dalvik.vm.usejitprofiles` и печатает
`JIT_BOOT_LEGACY … action=none reason=leftover_from_previous_versions need=owner_decision`.
Ничего не очищает и не перезаписывает. Проверка владельцем:
`adb shell getprop persist.dalvik.vm.usejit` и `getprop persist.sys.dalvik.vm.usejit`.

### 2.2 Остаточный снимок AVAS прошлых версий

Файл `filesDir/avas_snapshot` **не применяется и не удаляется**: `reportLegacyAvasSnapshot`
(`AppPerformance.kt:814`) пишет только `AVAS_LEGACY snapshot=present … action=none need=owner_decision`.
Что будет при старом снимке: ничего — JIT его не читает, AVAS-функций в сборке нет.

---

## 3. Наши отличия от референса (по одному, с назначением и поведением при отказе)

| № | отличие | зачем | что при отказе | код |
|---|---|---|---|---|
| 1 | метка загрузки `jit_restaurar_boot` + время `jit_restaurar_ts` | иначе нельзя отличить «продолжение этой операции» от «остаток прошлой загрузки» (§1.3) | метка не пишется ⇒ операция не начинается (сеть остаётся включённой) | `JitNetCore.kt:301`, `JitRef.continuation:145` |
| 2 | подтверждение записи снимка чтением | иначе сеть уже выключена, а вернуть нечем | запись не подтверждена ⇒ `SNAPSHOT_WRITE_FAILED`, скрипт НЕ запускается, `jit_pedido` снимается | `JitNetCore.kt:299-311` |
| 3 | отказ второго клика, пока снимок этой загрузки не разобран | §1.3: повторный клик не должен перезаписывать незавершённую операцию | `REFUSED_PENDING`: ноль команд устройству | `JitRef.canStart:157`, `JitNetCore.kt:274-281` |
| 4 | взаимное исключение на участок «проверить → записать → подтвердить» | два одновременных клика иначе перезаписывают `jit_restaurar` | второй клик получает `REFUSED_PENDING` | `JitNetCore.kt:233,246` |
| 5 | снимок снимается ТОЛЬКО после подтверждения возврата (в референсе `remove` до выполнения) | при неудачном возврате снимок должен остаться для следующего старта | возврат не подтверждён ⇒ `UNCONFIRMED`, снимок остаётся | `JitNetCore.kt:365-379` |
| 6 | шаг `umount -l` реализован, но выключен (`MOUNT_STEP_ENABLED=false`) | §1.1: обратимость не доказана ⇒ по мандату не выполнять, изложить вывод и спросить владельца | шаг не исполняется; включается одной константой после решения | `JitNetCore.kt:69,95` |
| 7 | `restart=not_claimed` в логах | ни референс, ни транспорт не доказывают рестарт (§1.3) | ничего не ломает: это только текст лога | `AppPerformance.kt:899`, `JitNet.kt` |

**Отличие по манифесту:** у нас нет `ACTION_BOOT_HU` (в референсе он есть в `CentralBootReceiver`).
Это не влияет на JIT-цепочку: продолжение у нас привязано к старту процесса сервиса, а не к
конкретному boot-действию. Добавлять не стали — это новый фильтр, а мандат запрещает расширять
поверхность без нужды.

### 3.1 Шаг монтирований §1.1 — вывод и запрос решения владельцу (⛔ не выполнено)

Что делает команда референса: `grep ' /data/app/' /proc/mounts | awk '{print $2}'` → для каждой
точки `umount -l "<точка>"`. Ленивое снятие (`-l`) отсоединяет точку от дерева сразу, а реальное
освобождение происходит, когда закрыт последний пользователь. На ГУ из `/data/app/` обычно
монтируются сами APK (bind-mount/loop), поэтому:

- для приложения, чей APK смонтирован оттуда, `umount -l` означает, что при следующем запуске
  файл может быть уже недоступен (вплоть до падения). Для СВОИХ точек это необратимо до ребута;
- для чужих приложений (в т.ч. системных) последствие то же — их APK перестанут открываться;
- штатного «вернуть как было» без перезагрузки нет: точка монтирования исчезает из таблицы, и
  восстановить её можно только повторным монтированием, которое мы не выполняем и не будем.

Что проверено на ГУ: **ничего** — только чтение по мандату, а состав `/data/app/` в `/proc/mounts`
полем не подтверждён; массовый unmount на ГУ не запускался (это прямо запрещено мандатом).

Итог: **обратимость не доказана**, поэтому шаг выключен константой, реализован построчно как в
референсе и включается одним значением после решения владельца. Тест доказывает, что при
включении он снимет только точки `/data/app/` и останется перед `setprop`
(`JitChainScriptTest.mountStepTouchesOnlyDataAppPoints`).

---

## 4. Тесты (§4 мандата)

Локальный прогон на JVM (настоящий `sh`, подставные команды): **PASS=55 FAIL=0** (§7).

| класс | тестов | что покрывает |
|---|---|---|
| `JitRefTest` | 10 | формат `jit_restaurar`, текст скрипта (первая/последняя команда, окно 20 с, шаг монтирований, запрещённые литералы), формула `bb/r7`, `unknown` без данных, решение о продолжении/отказе клика |
| `JitChainScriptTest` | 6 | **настоящий `/bin/sh`**: порядок ровно `la.run`, ранний выход при 0 правилах, исчерпание 20 с, `false` для выключения, только `/data/app/` при включённом шаге, ноль persist/`rule del` |
| `JitChainEndToEndTest` | 13 | сквозная операция: снимок → disable → слив → usejit → один zygote → возврат AP/Wi-Fi без второго клика; пауза 15000; отказ записи снимка; неизвестное состояние сети; чужой снимок не применяется и не удаляется; обычный старт — ноль команд; неразбираемый снимок; неподтверждённый возврат; сверка ok/cache_missing/unknown; потеря канала |
| `JitChainEndToEndTest.concurrentClicksShareOneStoreAndStartOnce` | 1 | 4 клика через барьер на ОДНОМ store: ровно 1 `STARTED`, 3 `REFUSED_PENDING`, один рестарт в журнале |
| `JitBootTest` | 5 | на загрузке ноль JIT-записей и ноль рестартов; в коде нет `bootRestoreJit`/`30_000L`/собственных механизмов; проба только читает |
| `JitRulesReadOnlyTest` | 2 | `ip rule` только считается: ни `del`, ни `flush`, ни `ndc`, ни `settings` |
| `JitNoPersistTest` | 3 | нет записи `persist.*` и `usejitprofiles`; остатки только читаются |
| `AvasIndependenceTest` | 4 | механизм AVAS удалён; JIT-код не ссылается на AVAS; в цепочке нет ни одной AVAS-команды; остаточный снимок не применяется |
| `JitTogglePathTest` | 4 | путь `setAutoOptimize` → `jitApplyWithZygote` → `JitNet.click`: нет backfill-а, своей сборки скрипта и своего `AdbClient`; `ctl.restart` только в тексте UI; сервис не пишет свойства |
| `JitBootMarkTest` | 4 | опознание загрузки (точный тег, epoch ±60 с, мусор ⇒ чужая загрузка) |

### 4.1 Удалённые тесты и эквивалентное покрытие

| удалён | было тестов | почему | эквивалент |
|---|---|---|---|
| `JitNetCoreTest` | 23 | проверял СВОЮ модель (журнал/фазы/TTL), которой больше нет | `JitRefTest` + `JitChainEndToEndTest` (13) — те же переходы, но на ядре `JitOp` |
| `JitToggleChainTest` | 5 | статические проверки прежней цепочки с `AvasRestore.saveBeforeRestart` | `JitTogglePathTest` (4) |
| `AvasCoreTest` | 13 | AVAS-механизм удалён из продукта | `AvasIndependenceTest` (4) — граница, а не поведение удалённого кода |
| `AvasContractTest` | 5 | то же | `AvasIndependenceTest` |
| `JitPersistTest` | 3 | проверял НАЛИЧИЕ persist-записи, которая теперь запрещена | `JitNoPersistTest` (3) |
| `JitBootTest` (старый) | 6 | опирался на `JitFlow`/файл-намерение | новый `JitBootTest` (5) |
| `JitWatcherScriptTest`, `NetSanitizePlanTest` | 4 + 7 | удалены ещё в первом заходе (watcher и санация правил) | `JitChainScriptTest`, `JitRulesReadOnlyTest` |

Ни один тест не удалён «потому что красный»: удалённые классы проверяли код, которого больше нет.

---

## 5. AVAS — отдельные функции, не часть JIT (§2 мандата)

**По коду референса** (`docs/agent/ref/centralexauto_v339_jit_chain.md` §5): mute — `w0/f2`,
выбор звука — `w0/r1`; вызовы `H0()` (при `avas_muted_saved` → `f2(this,2)`) и `I0()` (при
`avas_sound_type_saved > 0` → `r1(this,1)`) планируются через **12000 мс**, отдельный wake-путь
`onCreate` — через **5000 мс**. Ни `w0/la.run`, ни `w0/e2.run` AVAS не трогают.

**Что сделано у нас:** `AvasCore.kt` и `AvasRestore.kt` удалены целиком (они существовали только
ради JIT-рестарта: save-before-restart + restore после подъёма), вызовы удалены из
`AppPerformance`, `VoiceAssistantService` их не вызывает. Проверка — `AvasIndependenceTest`.
Новых кнопок, мута, `audioserver`, звуковых файлов — нет.

**Что осталось AVAS-поведением вне JIT** (не менялось): `tools/LockSound.kt` (звук замков,
независимая функция, `dev.testAvasSound` в панели тестов — существующие кнопки, не новые).
Мандат требовал не менять независимые функции AVAS — они не менялись.

---

## 6. Логи

`AppLog` пишет в `getExternalFilesDir(null)/logs/voiceapp3-DD.MM.YYYY.log`, fallback
`filesDir/logs/`, локальная дата, append, retention 14 суток только для своих файлов (код не
менялся в этой итерации, `tools/LogFiles.kt`). JIT-строки этой итерации:
`JIT_APPLIED_REQUEST`, `JIT_SNAPSHOT`, `JIT_CHAIN_SENT`, `JIT_CHAIN_RETURN`, `JIT_CLICK`,
`JIT_APPLIED`, `JIT_NET_CONT`, `JIT_NET_RESTORE`, `JIT_VERIFY`, `JIT_NOT_APPLIED`,
`JIT_BOOT`, `JIT_BOOT_LEGACY`, `AVAS_LEGACY`. Состояние/запрос/ошибки/`unknown` — раздельными
полями (`runtime=`, `request=`, `state_before=`, `result=unknown`).

---

## 7. Локальный прогон

```
ИТОГ: PASS=55 FAIL=0
```

Классы: `JitRefTest 10`, `JitChainScriptTest 6`, `JitChainEndToEndTest 13`, `JitBootTest 5`,
`JitRulesReadOnlyTest 2`, `JitNoPersistTest 3`, `AvasIndependenceTest 4`, `JitTogglePathTest 4`,
`JitBootMarkTest 4`. Как повторить — `scripts/localjvm/README.md` (Gradle эти заглушки не видит:
каталог вне `app/src/**`).

Это **не** прогон на ГУ: `WifiManager`, `startSoftAp`, `ctl.restart zygote` подменены.

---

## 8. Чек-лист мандата

| № | требование | статус | доказательство |
|---|---|---|---|
| 1.1 | снимок Wi-Fi/AP до изменений, ключи референса | ✅ | `JitNetCore.kt:265-315`, `JitRefTest.parseRestore*`, `JitChainEndToEndTest.clickThenContinuationWithoutSecondClick` |
| 1.2 | один скрипт, `svc wifi disable` → слив ≤20 с → usejit → zygote последней | ✅ | `JitRef.chainScript:95`, `JitChainScriptTest.orderIsReferenceByteForByte` |
| 1.3 | шаг монтирований §1.1 | ⛔ | реализован построчно, выключен: обратимость не доказана — §3.1, нужно решение владельца |
| 2 | возврат после подъёма фреймворка — продолжение той же операции, AP→Wi-Fi, 15 с | ✅ | `JitOp.continueAfterBoot:332`, `JitChainEndToEndTest.continuationRestoresApFirstThenWifi` |
| 2.1 | объяснить запуск службы и получение данных операции | ✅ | §1.3: `.BootReceiver` + `VoiceAssistantService:270,349` + `App.kt:51`; отличие `ACTION_BOOT_HU` указано |
| 2.2 | тайминг подтверждать инструкциями, недоказанное не выдавать за факт | ✅ | §1.3: 15000 мс = `0x3a98`; «`onCreate` будет вовремя» — не утверждается |
| 3 | сверка `getprop` + `jit-cache`/`dalvik-jit-code-cache`, нет данных ⇒ `unknown` | ✅ | `JitRef.verify:116`, `JitOp.verifyApplied:382`, `JitRefTest.noDataIsUnknownNeverSuccess` |
| 4 | обычный старт/перезагрузка не применяют JIT и не рестартуют zygote | ✅ | `JitBootTest.plainBootTouchesNoNetwork`, `serviceStartupDoesNotRestartZygote` |
| 5 | §1.1 исследование обратимости `umount -l` | 🟡 | §3.1: исследование по механике есть, проверки на ГУ запрещены мандатом; решение за владельцем |
| 6 | §1.2 убрать собственное (persist, watcher, отмена, sleep 8, санация, boot-backfill, bootRestoreJit, jitPersistBackfill) | ✅ | §2, `JitNoPersistTest`, `JitBootTest` |
| 6.1 | старые persist-значения не менять, дать read-only способ узнать | ✅ | `AppPerformance.kt:721-755`, §2.1 |
| 6.2 | не копировать `ip rule del`, без `flush`/`ndc`/`route`/`settings` | ✅ | `JitRulesReadOnlyTest` |
| 7 | жизненный цикл: снимок = одна операция, второй клик не перезаписывает, чужая загрузка ⇒ ноль записей | ✅ | `JitRef.continuation:145`, `JitOp.click:239`, `JitChainEndToEndTest.foreignSnapshotIsNeitherAppliedNorDeleted`, `concurrentClicks…` |
| 7.1 | транспорт: `ARMED`/«рестарт отправлен»/отсутствие журнала/60 с/таймаут — не доказательства | ✅ | `JitRef.chainOutcome`, `restart=not_claimed`, `transportLossIsNotRestartProof` |
| 7.2 | `setWifiEnabled(true)`/`startSoftAp` ≠ интернет, в логах раздельно | ✅ | `JitOp.restoreNetwork:412-420` (`state_before/state_after/request`), §6 |
| 8 | AVAS — отдельные функции; убрать привязанные к JIT save/restore | ✅ | §5, `AvasIndependenceTest` |
| 8.1 | оценить поведение новой версии при старом снимке AVAS | ✅ | §2.2: не применяется и не удаляется |
| 9 | логи (путь, fallback, retention) | ✅ | §6 |
| 10 | не менять плагин/слоты/климат/батарею/TTL/CarPlay/рут-reconnect | ✅ | в коммите нет файлов этих подсистем (`git show --stat 577d338`) |
| 11 | §4 тесты: вся цепочка, iso-стенд, одновременные клики, потеря транспорта, boot/30 с | ✅ | §4 |
| 11.1 | удалённые тесты перечислены, эквивалентное покрытие показано | ✅ | §4.1 |
| 12 | публикация + CI актуального SHA + run/job/PASSED/FAILED | ✅ | §0: `42896558590` / `130111086379` / 176 / 0 |
| 12.1 | полевой успех не заявлять | ✅ | на ГУ ничего не запускалось |

---

## 9. Что осталось за владельцем

1. **§1.1 монтирования** — решение по `umount -l` (шаг готов, выключен).
2. **Остатки persist-свойств JIT** прежних версий — нужно ли их чистить (сейчас только читаем).
3. **Остаточный снимок AVAS** (`filesDir/avas_snapshot`) — удалять или оставить (сейчас не трогаем).
4. **`ACTION_BOOT_HU`** — нужен ли отдельный фильтр, как в референсе (сейчас нет).
5. **Отсутствие анимации/индикации 10–20 с**: `PARTIAL` — единственный честный статус шага, пока
   процесс умирает от рестарта; подтверждение приходит на следующем старте.
