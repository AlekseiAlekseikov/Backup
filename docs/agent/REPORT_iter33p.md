# REPORT iter33p (1.0.61, перезапуск задачи) — одна JIT-цепочка по референсу

**Ветка:** `fix/v2` (без `--force`, `main` не тронут). **Версия:** `versionCode=61`, `versionName="1.0.61"`.
**Фактическая база на момент выдачи:** HEAD `531ae7658c2bbf2e80e9d6be3edec34b76e165c6`, код `e248231` —
проверено перед началом работы (`git log -1` в чистом клоне).
**SHA кода этой итерации / CI / handoff:** см. §0 — заполняется после публикации.

---

## 0. Публикация и CI

| Что | Значение |
|---|---|
| база (до работы) | `531ae76` (docs), код `e248231` |
| код итерации | **`c8e4c9c`** |
| CI по коду | run `42881198633`, job `130057601380` — **SUCCESS**, 200 PASSED / 0 FAILED, artifact `13794792993` |
| handoff (`SOURCE_SHA=c8e4c9c`) | коммит с этим отчётом; кода не касается |

### 0.1 Что опубликовано

| коммит | что |
|---|---|
| `531ae76` | база (документация предыдущей итерации, код `e248231`) |
| **`c8e4c9c`** | **код этой итерации**: 4 файла кода/KDoc + 6 файлов тестов (5 изменено, 2 удалено, 2 добавлено), 1091 вставка / 1874 удаления — код стал меньше ровно настолько, насколько убран лишний механизм |
| коммит с этим отчётом | только `docs/agent/**` + `app/buildinfo.properties` (`SOURCE_SHA=e248231` → `c8e4c9c`) |

Пуш: `531ae76..c8e4c9c fix/v2 -> fix/v2` (без `--force`), `main` не тронут (остаётся `97c0cf1`).

### 0.2 CI по опубликованному коду

| Run | Job | Коммит | Итог | PASSED / FAILED | Artifact |
|---|---|---|---|---|---|
| `42881198633` | `130057601380` | `c8e4c9c` | **SUCCESS** (`BUILD SUCCESSFUL in 4m 39s`) | **200 / 0** | `13794792993` (`app-debug`, 177 191 780 Б) |

Число `PASSED` взято из полного лога джобы (`grep -c ' PASSED$'` = 200, `grep -c ' FAILED'` = 0).
Прошлый прогон по `e248231` (fix4) — 206 тестов; здесь 200, потому что два класса заменены:
`JitWatcherScriptTest` (4 теста watcher-а) удалён, `NetSanitizePlanTest` (7 тестов плана удаления
правил) заменён на `JitRulesReadOnlyTest` (3), `JitChainScriptTest` даёт 5, `JitNetCoreTest` — 23.
Ни один тест не удалён «потому что красный»: см. §5.

### 0.3 `SOURCE_SHA`

`app/buildinfo.properties`: `SOURCE_SHA=c8e4c9c` — SHA коммита с кодом, который собирает владелец.
`git rev-parse` при сборке не используется (сборка читает файл), git-команды в сборке запрещены.
`versionCode=61`, `versionName="1.0.61"` — не менялись.

**Локальный прогон (не заменяет Android CI):** см. §7.

---

## 1. Что реально делает референс (проверено по коду, не по литералам)

Референс скачан и проверен мной в этой итерации:

- ZIP `https://drive.usercontent.google.com/download?id=1nKMhxGDVuurRv_QgMdjIBJFZ35MYsq2S&export=download&confirm=t`,
  MD5 `8a65c6c29749c64d4e335c57490a347a`, 10 091 814 Б — **совпал**;
- внутри `CentralEXAuto_v3.3.9.apk`, MD5 `c3f1664125953b9851bf4483b265135f`, 13 448 765 Б — **совпал**;
- разбирался именно внешний APK; вложенный `assets/pip-overlay.apk` не использовался;
- декомпиляция: **jadx 1.4.7** (1.5.1 в песочнице нет — честное ограничение; версия влияет на имена,
  но не на смысл), плюс smali (`baksmali 2.5.2`) для методов, которые jadx не осилил
  (`w0/j9.onClick` и `CentralWatchdogService.U(...)` — «Method not decompiled»).

### 1.1 Клик тумблера — `w0/j9.onClick` (внутри `w0/b9` case 22)

```
isWifiEnabled()                                  // try/catch → false
NetworkInterface.getByName("ap0") isUp && hasInetAddresses   // AP
SharedPreferences.edit()
    .putBoolean("jit_pedido", target)
    .putBoolean("perf_auto_otimizar", target)
    .putString("jit_restaurar", "wifi=<0|1>,ap=<0|1>")   // ← снимок сети
    .commit()                                     // синхронно, ДО любых изменений
→ thread pool: w0/la.run(...)
```

### 1.2 Единственный скрипт — `w0/la.run` (case 0)

```sh
svc wifi disable
_n=0
while [ $_n -lt 20 ]; do                       # слив правил, ≤20 с
  [ "$(ip rule show | grep -c 'oif wlan0')" = "0" ] && break
  sleep 1; _n=$((_n + 1))
done
for _m in $(grep ' /data/app/' /proc/mounts | awk '{print $2}'); do
  umount -l "$_m" 2>/dev/null                  # ← НЕ копируем (чужие монтирования)
done
setprop dalvik.vm.usejit <true|false>
setprop ctl.restart zygote                     # ← последняя команда, без watcher-а и без задержки
```

Запуск: `mainActivity.H1(40000, script)` (root-транспорт, таймаут 40 с) + `postDelayed(ua, 45000)` для UI.
**Никакого отдельного watcher-а, файла-отмены, `nohup`, `sleep 8`, второй кнопки в референсе нет.**

### 1.3 Возврат сети после рестарта — `CentralWatchdogService.onCreate` → `w0/e2.run`

```
s = prefs.getString("jit_restaurar", null)
if (Regex("^wifi=([01]),ap=([01])$").matches(s)) {
    oe = Restauro(wifi = g1, ap = g2)
    prefs.edit().remove("jit_restaurar").apply()      // ← снимается ДО выполнения
    handler.postDelayed(e2(oe, service, 0), 15000)    // 15 с после старта
}
e2 case 0: if (CentralWatchdogService.T2) thread { e2(..., 1) } else return
e2 case 1: if (ap)  garantirApAberto("pós-JIT")        // AP ПЕРВЫМ
           if (wifi && !wm.isWifiEnabled()) wm.setWifiEnabled(true)   // потом Wi-Fi
           log "JIT: restaurado após o restart (wifi=… ap=…)"
```

AP — `CentralWatchdogService.d(String)` (рефлексия `startSoftAp(WifiConfiguration)`), состояние AP — `v()`
(`ap0` + адреса), состояние Wi-Fi — `c1()` (`isWifiEnabled || state==ENABLING`).

### 1.4 Проверка результата — `w0/bb.run` (case 1) → `w0/r7.run` (default)

```
getprop dalvik.vm.usejit  → z4
/proc/self/maps           → есть ли dalvik-jit-code-cache (bool; исключение → null)
r7: toggle.setActive(getprop); if (prefs.contains("jit_pedido")) {
      ok = (pedido == getprop) && (!pedido || maps != FALSE);
      log "JIT pedido=… getprop=… code-cache-executavel=…"; prefs.remove("jit_pedido")
      if (!ok && pedido) toast master_perf_jit_not_applied
    }
```

**Рестарт zygote в референсе не подтверждается вообще:** ни pid, ни файл, ни задержка. Это его слабое
место — у нас подтверждение добавлено и честно помечено как наша часть (§3).

### 1.5 AVAS в референсе (исправление прежнего неверного указания ревьюера)

- `CentralWatchdogService.z()` — возврат **муты** и только при `avas_muted_saved` → `setAVASMode(0)`
  (рефлексия `getAVASMode`/`setAVASMode`); активный AVAS-mode JIT-цепочка не сохраняет;
- `CentralWatchdogService.A()` — sound type из `avas_sound_type_saved` (>0) по `w0/u.b` = `Politica.h(7)`
  = `0x21407057`, области `w0/u.c` = `{0,1}`, `getIntProperty`/`setIntProperty` (**void**), лог
  «restaurado para N após boot» при расхождении;
- `5 × 30 с` из ревью — **AEB observer**, к AVAS не относится. Наш график окон — собственная
  адаптация (§5), «точным графиком референса» не называется.

### 1.6 `ip rule`

В показанном `e2` удаления правил **нет**; `la.run` правила только читает (`grep -c`). Удаление `ip rule`
из нашей цепочки убрано (§4), причина прошлой потери интернета полевым логом не доказана — и не
заявляется.

---

## 2. Наша цепочка: одна операция, один скрипт, продолжение после рестарта

```
клик тумблера (AppPerformance.jitApplyWithZygote, AppPerformance.kt:951)
 0. хвост предыдущей операции            JitNet.reconcilePending("pre_click")      :965
 1. снимок операции + чтение обратно     JitNet.saveOperation(...)                 :971
      op=<id>;boot=<tag>;wifi;ap;want;zygote=<pid>;rt=<до>;ts=<ms>   (JitNetCore.kt:415)
 2. старые свои журналы (кроме текущего) JitNet.purgeOldJournals                    :989
 3. снимок AVAS (только чтение)          AvasRestore.saveBeforeRestart              :993
 4. ЕДИНСТВЕННЫЙ скрипт                  JitNet.runChain(...) → JitNetCore.kt:472   :1002
      svc wifi disable → слив ≤20 с → setprop dalvik.vm.usejit
      → setprop persist.dalvik.vm.usejit → setprop ctl.restart zygote (последняя)
 5. если скрипт ВЕРНУЛ управление, а рестарта нет:
      reconcilePending(..., lateRestartPossible=false) → компенсация в этой же операции  :1010
 6. иначе — PARTIAL: «итог на следующем старте»
```

После подъёма процесса (`AppPerformance.jitPendingVerify`, `:901`):

```
op = JitNet.currentOp();  phase = JitNet.phaseOf(op)        // журнал, только строки нашего op
facts = JitNet.applyFacts(op)                               // getprop / maps / pid zygote → verdict
net = JitNet.reconcilePending(app, bootTag, "start")        // ОДНО решение (JitFlow.pendingAction)
AVAS: AvasRestore.scheduleRestore(JIT_TOGGLE) — только если снимок операции существует
лог: JIT_APPLIED getprop=… want=… persist=… maps=… restart=… verdict=… op=… phase=… net=…
```

Решение принимает чистая функция `JitFlow.pendingAction` (`JitNetCore.kt:300`):

| ситуация | действие | сетевые команды |
|---|---|---|
| журнала нет, сеть как была | `DISCARD_NO_EFFECT` | нет |
| журнала нет, сеть другая | `HOLD_UNKNOWN` (`need=owner_decision`) | нет |
| фаза есть, pid zygote сменился | `CONTINUE_RESTORE` — продолжение операции, **без второго клика** | AP → Wi-Fi |
| фаза есть, окно позднего рестарта (60 с) не истекло | `HOLD_UNKNOWN` | нет |
| фаза есть, окно истекло / скрипт вернул управление / была перезагрузка ГУ | `COMPENSATE_RESTORE` (+ откат usejit к прежнему) | AP → Wi-Fi |
| операция старше 10 мин | `HOLD_UNKNOWN` | нет |
| снимка нет | `Recovery.NONE` (обычный старт) | нет |

**Раздельные факты вместо «ok» из таймаута:** `restart` = `ok|no|unknown` только по сравнению pid
zygote (`JitFlow.restartVerdict`, `:353`); `verdict` = `applied|unverified|not_applied|unknown`
(`:363`) — getprop без подтверждённого рестарта успехом не считается, отсутствие `maps` даёт
`unverified`, а не `applied`.

---

## 3. Отличия от референса — каждое с причиной

| # | Референс | У нас | Причина |
|---|---|---|---|
| 1 | `umount -l` всех точек `/data/app/` | **нет** | Границы владельца: чужие монтирования не трогаем; функция к сети/JIT не относится |
| 2 | только `dalvik.vm.usejit` | + `persist.dalvik.vm.usejit` | Согласованный владельцем init-ключ (был в 1.0.60); без него JIT не поднимается после ребута. `usejitprofiles` **не пишем** (в `la.run` его нет) |
| 3 | `jit_restaurar` в SharedPreferences, без op-id, без загрузки ГУ | снимок с `op`, `boot`, `zygote pid`, `rt_before`, `ts` | Нужна привязка к КОНКРЕТНОЙ операции: старый/чужой снимок не должен подтверждать новый запуск |
| 4 | повторный клик может перезаписать снимок | `saveOperation` отказывает, если снимок есть (`refused reason=pending_operation`) | «Параллельный/повторный клик не должен перезаписать незавершённое состояние» |
| 5 | снимок без подтверждения записи | запись подтверждается чтением; иначе операция не начинается | «При невозможности надёжно сохранить снимок — отказ до disable» |
| 6 | снимок снимается ДО выполнения возврата | снимается только при подтверждённом возврате по read-back | Прежняя ошибка «сняли состояние, не вернув сеть» |
| 7 | рестарт не подтверждается | `restart=ok` только по смене pid zygote, иначе `no|unknown` | Ложный «рестарт ok» недопустим |
| 8 | `postDelayed(15 с)` и `e2` теряет восстановление, если сервис не готов (`T2=false`, снимок уже удалён) | «окно позднего рестарта» 60 с + `HOLD_UNKNOWN`, снимок сохраняется до подтверждения | Не терять состояние, если момент не определён |
| 9 | нет фазы операции | журнал `JITSTEP op=<id> step=…` (start / wifi_disabled / drained / usejit / restart_sent) | Нужно отличать «дошло до рестарта» от «умерло раньше», не убивая процессы |
| 10 | нет отката props | откат `usejit` к прежнему значению при доказанном отсутствии рестарта | Компенсация до исходного состояния, а не «полуприменённый JIT» |
| 11 | `u.b`/`u.c` sound type на загрузке, `z()` мута | наш снимок mode+sound type в цепочке JIT и на старте | Разрешено владельцем; выдаётся за нашу адаптацию, не за референс |
| 12 | нет проверки `oif wlan0` после возврата | read-only счётчик правил в логе | Диагностика без удаления |

---

## 4. Чек-лист требований

| # | Требование | Статус | Где |
|---|---|---|---|
| 1 | Порядок: снимок → `svc wifi disable` → слив ≤20 с → `setprop usejit` → **единственный** рестарт по клику | ✅ | `JitNetCore.kt:132` (текст), исполнение — `JitChainScriptTest` |
| 2 | Возврат сохранённых AP/Wi-Fi **в той же операции**, без второго клика | ✅ | `AppPerformance.kt:909` → `JitNetCore.kt:300,497` |
| 3 | AP раньше Wi-Fi (как `w0/e2.run`) | ✅ | `JitNetCore.kt:596` (порядок в `restore`), тест `confirmedRestartReturnsWifiWithoutSecondClick` |
| 4 | На обычной загрузке — ни рестарта, ни сетевой операции | ✅ | `pendingAction` → `NONE/DISCARD/HOLD`; тесты `coldStartWithoutOperationTouchesNothing`, `noEvidenceAndNetworkChangedHoldsForOwner` |
| 5 | Снимок: id операции + загрузка ГУ + Wi-Fi/AP + цель + read-only признаки, подтверждён до сети | ✅ | `JitNetCore.kt:415`, формат `Op.wire()` `:70` |
| 6 | Повторный/параллельный клик не затирает незавершённое | ✅ | `JitNetCore.kt:416`, тест `secondClickDoesNotOverwritePendingOperation` |
| 7 | Отказ до disable, если снимок не сохраняется | ✅ | `JitNetCore.kt:415-460`, тест `saveRefusedWhenWriteNotConfirmed` |
| 8 | Различаются: смена фреймворка / отмена до рестарта / неизвестность / другая загрузка | ✅ | таблица §2, `JitFlow.pendingAction` |
| 9 | `ARMED`/`RESTARTING` не считаются доказательством рестарта | ✅ | watcher-а нет вовсе; `restartVerdict` — только pid |
| 10 | Смена app-PID не считается доказательством рестарта zygote | ✅ | `restartVerdict` сравнивает **pid zygote** |
| 11 | Не снимать снимок/не объявлять возврат преждевременно, пока возможен поздний рестарт | ✅ | окно 60 с → `HOLD_UNKNOWN`; тест `interruptionBeforeZygoteCompensatesOnlyAfterWindow` |
| 12 | Компенсация сети только до сохранённого состояния | ✅ | `restore()` использует только `op.wifi/op.ap` |
| 13 | При неопределённости — не заявлять ложную отмену, не удалять состояние | ✅ | `HOLD_UNKNOWN`, тесты `failedRestoreKeepsSnapshot`, `noEvidenceAndNetworkChangedHoldsForOwner` |
| 14 | Аварийный случай без новых команд — остановиться и запросить решение | ✅ | лог `JIT_OP_HOLD … need=owner_decision`; §8 |
| 15 | Никаких `kill`/`pkill`/`pgrep -f`, новых кнопок, авто-действий на старте | ✅ | `JitToggleChainTest.chainHasNoForeignMechanisms` |
| 16 | Убрать 8-секундный watcher, файл-отмену и чтение `ARMED` | ✅ | `JitWatcherScriptTest` удалён, `JitChainScriptTest` — вместо него |
| 17 | Запрос `setWifiEnabled`/`startSoftAp` ≠ «интернет есть» | ✅ | `RestoreResult` (`wifiConfirmed`/`apConfirmed`/`link` отдельно), тест `wifiRequestWithoutStateIsNotSuccess` |
| 18 | `ok`/`failed`/`unknown` различаются | ✅ | `ScriptOut.channelOk`, `RestoreResult`, `restartVerdict`, `verdict` |
| 19 | `ip rule`: без безусловного удаления, только read-only | ✅ | `rulesProbeCommand` `:117`; `NetSanitizePlanTest` заменён на `JitRulesReadOnlyTest` |
| 20 | Никаких `flush`/`ndc`/`settings`/route-правок | ✅ | те же тесты (проверка по коду без комментариев) |
| 21 | `JIT_APPLIED` раздельно: getprop, maps, рестарт, вердикт | ✅ | `AppPerformance.kt:901-930`, `JitNet.ApplyFacts.log()` |
| 22 | Ошибки shell-строк (`resetWatcherResult` и т.п.) исчезли с механизмом | ✅ | методы удалены; см. §6 |
| 23 | AVAS: только сохранённые mode и sound type, только цепочка JIT + старт | ✅ | `AvasRestore` header, `AppPerformance.kt:993`, `AvasContractTest.callSitesAreStillTwo` |
| 24 | AVAS: read-back, недоступное не пишем, снимок атомарный и привязан к операции | ✅ | `AvasCore` (без изменений), `AvasCoreTest` 13 |
| 25 | Не утверждать, что AVAS-график/снимок скопирован из референса | ✅ | §1.5 + исправленный KDoc `AvasRestore.kt` |
| 26 | Суточные логи в каталоге приложения, fallback, retention только своих | ✅ | `LogFiles.kt` (не менялся), `LogFilesTest` |
| 27 | TTL/CarPlay/плагин/раскладка/климат/батарея/температура не тронуты | 🟡 | проверено диффом (§6): изменены только JIT-файлы; полевых проверок нет |
| 28 | Полевой прогон JIT | ⛔ | по условию — только после нового REVIEW |

---

## 5. Тесты (исполняемые, с фейковым каналом и настоящим `sh`)

| Класс | Что проверяет |
|---|---|
| `JitNetCoreTest` (23) | снимок и его подтверждение; отказ второго клика; единственный скрипт и его порядок; фазы по журналу; продолжение после рестарта (Wi-Fi+AP, AP первым); холодный старт без команд; прерывание до zygote (HOLD → компенсация после окна); возврат управления скриптом (`lateRestartPossible=false` → компенсация + откат props); пустой журнал при изменённой сети → `HOLD need=owner_decision`; пустой журнал при нетронутой сети → `DISCARD`; просрочка → `HOLD`; другая загрузка ГУ → компенсация; чужой журнал не подтверждает фазу; `restartVerdict`; `verdict`; запрос ≠ подтверждение; `ap=0` не ошибка; правила только считаются; чужой `clearOp` отклоняется; `opId`/путь журнала shell-safe |
| `JitChainScriptTest` (5) | **настоящий `sh`** с подставными `svc`/`ip`/`setprop`/`getprop`/`sleep`: фактический порядок вызовов, рестарт ровно один и последний, журнал со всеми шагами и нашим `op`, `wait=20` при несходящихся правилах, тишина в stdout, отсутствие `nohup`/фона/отмены |
| `JitRulesReadOnlyTest` (3) | проба правил только считает; в коде нет `ip rule del/flush`, `ndc`, `settings put`; число правил — диагностика и не влияет на «возврат выполнен» |
| `JitToggleChainTest` (5) | порядок в тексте цепочки; отсутствие чужих механизмов (`umount`, `usejitprofiles`, `nohup`, `cancel`, `kill`, `setenforce`, `pm install`, `ip rule del`, `sleep 8`); снимок до скрипта; единственность точки рестарта; сеть только в трёх файлах |
| `JitBootTest` (6) | `JIT_BOOT` печатается всегда; ключи; литерал `ctl.restart zygote` только в `JitNetCore.kt` и ровно один; `AppPerformance` запускает цепочку через `JitNet.runChain`; авто-восстановления JIT при загрузке нет |
| `AvasCoreTest` (13) | окна/повторы/чужие задачи/просрочка/пустой снимок (без изменений — механизм не трогали) |
| `AvasContractTest` (5) | порядок снимков (операция → AVAS → скрипт), отсутствие отмены снимка, void-API, две точки вызова |
| `BuildMarkerTest` (3) | `SOURCE_SHA` в маркере и `versionCode/versionName` |

**Заменённые тесты (не удалены «потому что красные»):**

- `JitWatcherScriptTest` → **`JitChainScriptTest`**: watcher-а больше нет; тот же подход (настоящий `sh`,
  подставные команды), но проверяется единственная цепочка — включая рестарт последней командой.
- `NetSanitizePlanTest` → **`JitRulesReadOnlyTest`**: удаление правил убрано по требованию, вместо плана
  удаления — read-only диагностика и статические границы.
- `JitNetCoreTest` переписан под новую модель (старые сценарии сохранены по смыслу, добавлены
  «второй клик», «пустой журнал», «возврат управления скриптом», «другая загрузка ГУ»).

---

## 6. Изменённые файлы и выведенные из использования механизмы

**Изменено (5 файлов кода/тестов):**

| файл | что |
|---|---|
| `app/src/main/java/.../tools/JitNetCore.kt` | переписан: модель операции, единственный скрипт, журнал шагов, `JitFlow.pendingAction`, `JitNetCore` (снимок/цепочка/разбор/возврат) |
| `app/src/main/java/.../tools/JitNet.kt` | адаптер: снимок, цепочка, разбор, факты; удалены `preRestartScript`/`sanitizeScript`/`sanitizePlan`/`restoreAfterRestart`/`compensate` |
| `app/src/main/java/.../tools/AppPerformance.kt` | `jitApplyWithZygote` и `jitPendingVerify` переписаны; `netOnProcessStart`, `abortAfterNet`, `cancelWatcherAndVerify`, `readWatcherResult`, `resetWatcherResult`, `waitWatcherArmed`, `writeIntentConfirmed`, `jitProfiles`, `AbortOutcome` удалены; мёртвые константы прежнего механизма убраны |
| `app/src/main/java/.../tools/AvasRestore.kt` | только KDoc: снят недоказанный «точный график референса», зафиксировано, что снимок mode+sound type — наша адаптация |
| тесты | `JitNetCoreTest`, `JitChainScriptTest` (новый), `JitRulesReadOnlyTest` (новый), `JitToggleChainTest`, `JitBootTest`, `AvasContractTest`; удалены `JitWatcherScriptTest`, `NetSanitizePlanTest` |

**Выведено из использования (и почему):**

- watcher-скрипт `/data/local/tmp/jit_zygote_watch.sh` и `nohup`-запуск — рестарт это последняя команда;
- файл-итог `/data/local/tmp/jit_zygote_result.txt` и файл-отмена `/data/local/tmp/jit_zygote_cancel`;
- строки `JIT_ZYGOTE_ARMED|RESTARTING|DONE|ABORTED` как доказательства фазы → журнал `JITSTEP op=…`;
- 8-секундная задержка рестарта и её ожидание;
- файл-намерение `filesDir/jit_pending_verify`, `jit_pending_want`, `.processing`, `jit_verify_cycle`;
- `dalvik.vm.usejitprofiles` в записи (чтение в `JIT_BOOT` оставлено);
- удаление правил `ip rule … oif wlan0` (`sanitizeScript`, `sanitizePlan`) → read-only подсчёт;
- `pkill`/`pgrep -f`/любые `kill` (их и не было в этой ветке — подтверждено тестом).

---

## 7. Локальный прогон

```
JitNetCoreTest (23)        PASS 23 / FAIL 0
JitChainScriptTest (5)     PASS 5  / FAIL 0   ← настоящий /bin/sh
JitRulesReadOnlyTest (3)   PASS 3  / FAIL 0
JitToggleChainTest (5)     PASS 5  / FAIL 0
JitBootTest (6)            PASS 6  / FAIL 0
AvasCoreTest (13)          PASS 13 / FAIL 0
AvasContractTest (5)       PASS 5  / FAIL 0
BuildMarkerTest (3)        PASS 3  / FAIL 0
ИТОГ: PASS=63 FAIL=0
```

Компиляция целевых файлов (`AppPerformance.kt`, `JitNet.kt`, `JitNetCore.kt`, `AvasRestore.kt`,
`AvasCore.kt`) с заглушками `scripts/localjvm` — **0 ошибок**. Остальные заглушки Android неполные,
поэтому локальный прогон ограничен этим набором; решает Android CI (§0.2).

Поймано локальным прогоном и исправлено до коммита:

1. в тройных кавычках Kotlin `\$@` остаётся буквальным (`\$@`) — подставные команды получали битые
   аргументы; в тесте `$` теперь только через переменную;
2. правило «снимок до сети» ломалось на пробе `usejit`, которую я делал отдельным скриптом —
   проба переведена в один вызов чтения, счёт скриптов в тесте уточнён;
3. `assertEquals` на пустое `usejitBefore` показал, что проба не возвращала значение в фейке —
   добавлен честный дефолт фейка (это и есть польза исполняемых тестов).

---

## 8. Ограничения (честно)

- **Полевого прогона JIT нет** (по условию — после нового REVIEW): `ctl.restart zygote`, `svc wifi disable`,
  `startSoftAp`, `ip rule` на ГУ этой итерацией не запускались.
- **Граница процесса проверена не на устройстве.** Утверждение «скрипт доживает до конца после смерти
  app-процесса» не доказано полем; но конструкция от этого не зависит: рестарт — последняя команда,
  а всё, что читается после, пишется ДО неё (журнал), поэтому даже если скрипт оборвётся на
  `setprop ctl.restart zygote`, ничего не теряется — фаза останется `usejit`, и разбор честно уйдёт в
  компенсацию после окна.
- **`HOLD_UNKNOWN` — это остановка, а не лечение.** Если журнала нет, а сеть уже не та, автоматики нет:
  строка `JIT_OP_HOLD … need=owner_decision` + снимок сохраняется. Решение владельца требуется
  (аварийный сценарий, который нельзя закрыть без расширения разрешённых действий).
- **`maps` и его смысл** — наша адаптация проверки референса: наличие `dalvik-jit-code-cache` в
  `/proc/self/maps` нашего процесса; если строки нет — `unverified`, а не «провал».
- **jadx 1.4.7, а не 1.5.1** (в песочнице нет 1.5.1); `w0/j9.onClick` и `CentralWatchdogService.U(...)`
  читались по smali — jadx их не декомпилировал, это указано в §1.
- **`persist.dalvik.vm.usejit`** пишется вместе с `dalvik.vm.usejit` (согласованный ключ 1.0.60);
  референс пишет только runtime-ключ. Это отличие зафиксировано в §3, а не выдано за референс.
- **TTL 10 минут** — наша политика, не референс; после неё автоматики нет (§2, строка «операция старше»).
- Локальный прогон не заменяет Android CI: решает §0.2.
