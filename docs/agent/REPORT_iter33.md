# REPORT iter33 — версия 1.0.53

**Ветка:** `fix/v2` · **Версия:** `versionCode = 53`, `versionName = "1.0.53"` (`app/build.gradle.kts:60-61`)
**Коммиты:** `6b66f62` (основной), `34dbdee`, `44526ed`, `a3a4964`, `6acbc27` (правки по результатам CI)
**CI:** прогон **41996706107** — `conclusion=success`, шаг `Gradle build (debug)` зелёный, в нём же `> Task :app:testDebugUnitTest` (см. F2)
**Артефакт:** **13400294287** `app-debug` — **177 067 555 Б** (zip), внутри **`app-debug.apk` = 215 383 361 Б**
**База:** `21df015` (1.0.52). `main` не пушился, `--force` не использовался.

Мандат: `uploads/PROMPT_iter33_1.0.53.md`. Лог владельца: `uploads/voiceapp3.log` (3827 строк, 658 748 Б) — числа мандата сверены с ним и совпали (см. §0).

---

## 0. Сверка чисел мандата с логом владельца (факты, не пересказ)

| что | мандат | в `voiceapp3.log` | команда |
|---|---|---|---|
| `ERR_SCRIPT_UPLOAD` | 18 | **18** | `grep -c` |
| `setenforce 0` | 14 | **14** | `grep -c` |
| `← execute` | 235 | **235** | `grep -c` |
| `ADB_CHANNEL|ADB_TX|ADB_RX` | 1033 | **1595** (в мандате — только 3 префикса без `ADB_RX note`) | `grep -acE` |
| `HVACDIGITAL_PUSH` | 44 | **44** | `grep -c` |
| `rc=137` | 14 | **14** | `grep -c` |
| `checkStatus mounts` | 35 | **35** | `grep -c` |
| `VERIFY video_codec_swap` | 13 | **13** | `grep -c` |
| `enable called` | 17 | **17** | `grep -c` |
| размер лога за 10 мин | 658 КБ | **658 748 Б** | `wc -c` |

Вывод: причины, названные в мандате, подтверждены логом; ничего не переисследовалось.

---

## F1. Чек-лист с доказательством

### Часть A — статус-бар / плагин цифрового климата

| # | пункт | статус | файл:строка | доказательство |
|---|---|---|---|---|
| A0 | чанкер режет скрипт корректно: кириллица 4963 Б → файл 4963 Б, тест в CI | ✅ | `ScriptChunker.kt:36-68`, `AdbClient.kt:257-280`, `ScriptChunkerTest.kt:56-62` | Новый путь — **только base64**: `ScriptChunker.chunks()` (`:36`) отдаёт печатный ASCII по 2500 симв.; заливка `chunkCommand()` (`:59`) и предохранитель `verifyAndRunCommand()` (`:67`) с `exit 99`. Старый `String(bytes, offset, end-offset, UTF_8)` удалён целиком (`git show 6b66f62 -- AdbClient.kt`). Тест `chunkerKeepsBytesForCyrillicScript` (4963 Б, кириллица) **зелёный в CI** |
| A0.2 | `ERR_SCRIPT_UPLOAD` ⇒ провал шага, а не «rc=0» | ✅ | `AdbClient.kt:275-278` | `if (out.contains("ERR_SCRIPT_UPLOAD")) AppLog.e("SCRIPT_UPLOAD FAIL …")`; `exit 99` доезжает как `rc=99` (A1) |
| A1 | настоящий `rc`: `exit 99` виден как `rc=99` | ✅ | `AdbClient.kt:1748-1762` (`wrapForRc`/`splitRc`), `:1690-1700`, `:1727`, `:1384`, `:326` | Выбран путь **(а)** мандата: команда оборачивается в `sh -c '<cmd>'; echo "__ADB_RC=$?"`, маркер вырезается, значение → `rc`. Работает и на TCP 5555, и на unix-сокете (`:1370`). В лог идёт `← execute dt=… rc=<настоящий> … cmd=…` (`:326-329`) |
| A2 | `setHvacDigital` возвращает реальный статус; хардкод `Status.OK` удалён | ✅ | `StatusIcons.kt:886-905` | `pluginStatus = when { r==null→FAILED; any FAILED→FAILED; any BLOCKED→BLOCKED; all OK→OK; else PARTIAL }`; `finalStatus = pluginStatus ?: OK` (`:904`). Строки `status = ToolsStep.Status.OK` в `setHvacDigital` больше нет (`git show 6b66f62 -- StatusIcons.kt`, было `:844`) |
| A3 | ветка «снять затмение» `/data/app` → `/system` + лог `HVAC_UNSHADOW`; `pm install` из скрипта удалён | ✅ | `HvacDigitalPlugin.kt:196-232` | `case "$PM_PATH" in /data/app/*` → `pm uninstall` → при неудаче `pm uninstall-system-updates` (и `cmd package …`) → повторный `pm path`; строки `HVAC_UNSHADOW: before=… after=… via=… result=OK|FAILED` (`:218`, `:224`), при провале `exit 2` → статус `FAILED`, а не «SUCCESS». `pm install -r -g` в файле отсутствует (`grep -c 'pm install' HvacDigitalPlugin.kt` = 0) |
| A4 | `setenforce 0`, `rm -rf /data/dalvik-cache/*`, `sleep 3` удалены; `kill` SystemUI только при реальной замене APK | ✅ | `HvacDigitalPlugin.kt:264-283`, `:23-26` | `grep -c 'setenforce\|dalvik\|sleep 3' HvacDigitalPlugin.kt` = **0**. `kill` только при `APK_REPLACED=1` **и** `killAllowed=1` (`:268`), где `killAllowed` гасится после первого перезапуска за процесс (`:23`, `:175`). Idempotent-SKIP (`mounted=1 && SRC_INODE==TGT_INODE`) сохранён (`:240-242`) |
| A5 | блок `HVAC_PROBE` + `HVAC_RESULT` | ✅ | `HvacDigitalPlugin.kt:329-338`, `StatusIcons.kt:958-961` | Формат: `HVAC_PROBE pm_path=… mounted=… src_inode=… tgt_inode=… selinux=… via=… rc=… props=…` и `HVAC_RESULT: OK|PARTIAL|FAILED reason=…` — обе строки из реальных значений скрипта (`PM_PATH`, `SRC_INODE`, `SELINUX`, `PROPS`), а не из констант |

### Часть B — нагрузка (CarPlay / ViperFX)

| # | пункт | статус | файл:строка | доказательство |
|---|---|---|---|---|
| B1 | `setVideo60Fps` вне watchdog; SKIP при уже применённом; `ctl.restart mediacodec` только по клику | ✅ | `VoiceAssistantService.kt:413-440`, `AppPerformance.kt:354-376`, `:398-410`, `:470-480` | В теле watchdog'а вызова `setVideo60Fps` нет — только `AppPerformance.verifyVideoOnly` (`:430`, read-only). В самом `setVideo60Fps` первым делом `isVideoApplied()` (`:354`, одна команда `grep /proc/mounts` + `stat -c %i`) → `video_codec_swap: SKIP (already applied)` (`:402`) без `umount`/`mount`/`ctl.restart`/`sleep` |
| B1.3 | `sleep 3` убран | ✅ | `AppPerformance.kt:470-478` | `while [ "$(pidof media.swcodec)" = "$MEDIA_PID_BEFORE" ] && [ $_t -lt 10 ]; do sleep 0.5; …` + `echo MEDIA_WAIT_TICKS=…` (бюджет 5 с) |
| B1.4 | `ctl.restart zygote` / `umount /data/app/*` из watchdog/автозапуска не вызываются | ✅ | — | В watchdog'е таких вызовов нет (см. B1); остались только в ручном `setAutoOptimize` по клику (не тронут) |
| B2 | `bootRestoreHvacDigital` вне watchdog; `enable` только по клику | ✅ | `StatusIcons.kt:939-962`, `HvacDigitalPlugin.kt:166-173` | Функция теперь только `checkStatus` (pm path + /proc/mounts) и одна строка `HVAC_PROBE: not mounted (watchdog, no action)`; `setprop`-сброс в 0 удалён. Повторный `enable` в том же процессе — `already_enabled_this_process` без действий (`:166-172`) |
| B3 | `LockSound` через чанкованный `executeScript`, не в watchdog | ✅ | `LockSound.kt:828-836` | `if (copyScript.size > 3500) AdbClient.executeScript("lock_wav", …)` — 286 КБ base64 больше не уходят одной командой; `LockSound.onBootOrWatchdog` из watchdog удалён (`VoiceAssistantService.kt:413-440`) |
| B4 | TTL: сначала read-only проверка, заливка только при отсутствии правила | ✅ | `NetworkTtl.kt:238-256`, `:265-272` | `ttlRuleCounts()` — одна команда `iptables -t mangle -S … | grep -c ttl-set; ip6tables … hl-set`. Есть правило → `TTL: SKIP (rule present)` и `return` (`:252`) |
| B5 | бюджеты: ≤30 exec и ≤40 соединений /10 мин; `app.log` ≤150 КБ | 🟡 | `LogKit.kt:244-259` (`HEARTBEAT` со `exec/min`, `conn/min`), `StatusIcons.kt:776-806` (dirty-flag) | Что сделано: убраны повторные `enable` (17/10мин), `setVideo60Fps` (13/10мин), заливка звука и скрипта TTL каждый тик, `setprop` теперь только при изменении значения (`fingerprint == lastPushedHvac → return`, `:797`). Замер на устройстве — в F3 (в песочнице нет ГУ) |

### Часть C — структурированный лог

| # | пункт | статус | файл:строка | доказательство |
|---|---|---|---|---|
| C1 | два файла: `app.log` + `app.ops.log` | ✅ | `LogKit.kt:30,84-105`, `AppLog.kt:98` | `OPS_NAME = "app.ops.log"` в том же каталоге; выжимка переписывается целиком с лимитом 200 строк (`flushOps`, `:84-91`); маркер идёт и в рабочий лог, и в выжимку (`marker`, `:101-104`) |
| C2 | маркеры `SESSION_START`/`LOG_INDEX`/`OP_START`/`OP_STEP`/`OP_SUMMARY`/`CYCLE_*`/`HEARTBEAT`/`LOG_SUMMARY` в `key=value` | ✅ | `LogKit.kt:192-213`, `:244-259`, `:261-277`, `:325-330`; `ToolsBackend.kt:208-220` | Все значения через `kv()` (пробелы → `_`), поля в фиксированном порядке |
| C3 | сокращения по таблице C3 + `ADB_TRACE_SUPPRESSED n=…` | ✅ | `LogKit.kt:293-322`; `AdbClient.kt:1129,1142,1697`; `StatusIcons.kt:655-659,663-672,540-547,602-608`; `HvacDigitalPlugin.kt:96-104` | Первые 20 строк на сеанс построчно, дальше — счётчик и `ADB_TRACE_SUPPRESSED n=ADB_TX=…,ADB_RX=… last=…` раз в минуту. Свёрнуты: `ADB_CHANNEL/ADB_TX/ADB_RX`, `TICK`, `PREF TRACE`, `ICON_GEOM`, `KmIconProbe`, `checkStatus mounts` |
| C4 | сквозные `run=`/`op=`/`cycle=`: `grep "op=<id>" app.log` даёт всю цепочку | ✅ | `LogKit.kt:139-146` (`ctxSuffix`), `AppLog.kt:305-313`, `AdbClient.kt:158-168` (`Job.op`), `:318-330` | `op` рождается в `ToolsBackend.set` (`:211`) и передаётся **параметром** в `Job.op`; `AdbWorker` выставляет его на поток на время выполнения команды — поэтому `ADB_*`, `VERIFY EXEC`, `← execute` получают тот же `op`. Каждая строка файла получает `run=… op=…` в `formatLine` |
| C5 | `RU:` к каждому шагу (что произошло / что значит / что делать) | ✅ | `ToolsBackend.kt:233-256`; `LogKit.kt:192-213`; `LogKit.kt:111-133` | `emitOpSteps` пишет `OP_STEP` + `RU:` на каждый шаг с ветками OK/PARTIAL/BLOCKED/FAILED; `SCRIPT_TRACE` тоже сопровождается `RU:` |
| C6 | `###STAGE` в скриптах + разобранный `SCRIPT_TRACE` | ✅ | `HvacDigitalPlugin.kt:186-286` (метки), `:292`; `LogKit.kt:111-133` | Скрипт печатает `###STAGE name=… status=…` на этапах check/unshadow/bind/sysui; приложение печатает `SCRIPT_TRACE script=hvac_enable stages=N failed=<шаг>:<причина>` + `STAGE i/N name status reason` |
| C7 | `HEARTBEAT` раз в 60 с, `LOG_SUMMARY` по кнопке/`onDestroy` | ✅ | `LogKit.kt:244-277`; `VoiceAssistantService.kt:433-434`, `:447` | `HEARTBEAT uptime=… exec/min=… conn/min=… hvac=… ttl=… video=… sysuiKills=… codecRestarts=…`; `LOG_SUMMARY run=… ops=… failed=…` + таблица 20 действий + 5 ошибок (кольцо ошибок наполняется в `AppLog.e`, `AppLog.kt:182`) |
| C8 | один синхронизированный писатель; ротация 16 МБ; лог не делает ADB-команд | ✅ | `AppLog.kt:40,44-60,170-176,305-313`; `LogKit.kt:60-99` | Писатель один — `AppLog.writeRaw` под `synchronized(lock)`; `MAX = 16 МБ`, архивы `app.log.1/.2` (`MAX_ARCHIVES=2`); в `LogKit` нет ни одного вызова `AdbClient` (проверяется грепом: `grep -c AdbClient LogKit.kt` = 0) |
| C9 | таблица «маркер → было в 1.0.52 → стало → чем факт заменён» | ✅ | см. §1 ниже | ни одна строка не «убрана без замены» |

### Тест A0.3 в CI

```
> Task :app:testDebugUnitTest          ← выполняется (не UP-TO-DATE/SKIPPED)
> Task :app:assembleDebug
BUILD SUCCESSFUL in 3m 16s
```
Задача теста **прицеплена к сборке debug** (`app/build.gradle.kts:218-221`, `tasks.matching { assembleDebug } .dependsOn("testDebugUnitTest")`), потому что файл workflow править нельзя: PAT не имеет scope `workflow` (первый push был отклонён: `refusing to allow a Personal Access Token to create or update workflow .github/workflows/build.yml`). Тесты, которые реально прогоняются: `chunkerKeepsBytesForCyrillicScript`, `chunkerProducesAsciiOnly`, `oldByteSlicingWasBroken`, `asciiOnlyRoundTrip` (`app/src/test/java/com/example/voiceapp3/tools/ScriptChunkerTest.kt:22-104`).

---

## 1. C9 — таблица «маркер → было в 1.0.52 → стало → чем факт заменён»

| маркер / поток | было в 1.0.52 | стало в 1.0.53 | **чем факт заменён** |
|---|---|---|---|
| `ADB_CHANNEL`/`ADB_TX`/`ADB_RX` | 1033–1595 строк / 10 мин | первые 20 на сеанс построчно | `ADB_TRACE_SUPPRESSED n=ADB_TX=… last=…` (раз в минуту) + `HEARTBEAT exec/min, conn/min` + строки `OP_STEP` того действия |
| `TICK: dt=… reads=…` | 43 | только при `dt > 100` мс или раз в минуту | `HEARTBEAT` (`exec/min`, `conn/min`) |
| `PREF TRACE` (чтение префа) | каждые 15 с | только при смене значения | `ADB_TRACE_SUPPRESSED n=PREF_TRACE=…` |
| `ICON_GEOM`, `KmIconProbe` | ~200 строк | только при изменении отрисованного | `ADB_TRACE_SUPPRESSED n=ICON_GEOM=…, n=ICON_POST=…` + `HEARTBEAT` |
| `checkStatus mounts` | 35 | при смене состояния | `HVAC_PROBE` (A5) |
| `enable called` + всё внутри | 17 × (setenforce 0, kill SystemUI, dalvik, pm install) | только по клику, 1 раз за процесс | `OP_START/OP_STEP/OP_SUMMARY` действия `hvacBatt` + `HVAC_PROBE`/`HVAC_RESULT` |
| `VERIFY video_codec_swap` | 13 | только по клику | `HEARTBEAT video=…, codecRestarts=…` + `video_codec_swap: SKIP (already applied)` |
| `HVACDIGITAL_PUSH` | 44 | только при смене значения | строка `HVACDIGITAL_PUSH skip reason=unchanged batt=…` (DEBUG) + `PROPS=…` в `HVAC_PROBE` |
| `CENTRAL_Bg-N` служебное | 1063 | без изменений; каждая строка получила `run=`/`op=`/`cycle=` | grep по `op=` собирает цепочку |
| `[ERROR]`, `[WARN]`, `VERIFY …`, `OP_*`, `CYCLE_*`, `SCRIPT_TRACE`, `HVAC_*`, вывод упавших шагов | — | **не сокращаются** (запрет C3 соблюдён) | — |

---

## F2. CI

| что | значение |
|---|---|
| прогон | **41996706107** (`push`, `6acbc27`) |
| conclusion | **success** |
| шаги | `Unit tests` → нет отдельного шага (тест в Gradle-графе); `Gradle build (debug)` = success; `> Task :app:testDebugUnitTest`, `> Task :app:assembleDebug`, `BUILD SUCCESSFUL in 3m 16s` |
| артефакт | **13400294287** `app-debug` |
| размер zip | **177 067 555 Б** |
| APK внутри | **215 383 361 Б** (`apk/debug/app-debug.apk`) |

Предыдущие прогоны на этом же коде (для истории, все с ошибками, которые исправлены):
`41992482078` — синтаксис: вложенный `/*` внутри KDoc (Kotlin считает `/*` вложенным комментарием) → `VoiceAssistantService.kt`;
`41993905567` — `Job`-параметр `op` стоял после `onResult`, trailing lambda перестала быть `onResult` → 4 ошибки типов в `AdbClient.kt`;
`41995309265` — компиляция прошла, упал `testDebugUnitTest`: генератор тестового скрипта давал не ровно 4963 байта.

---

## F3. Честные пробелы

1. **На ГУ не проверялось.** Ни одна строка нового лога (`HEARTBEAT`, `OP_*`, `SCRIPT_TRACE`, `HVAC_UNSHADOW`) не наблюдалась вживую. Всё, что помечено ✅, подтверждено кодом (`файл:строка`) + зелёной сборкой CI; байтовые/тестовые пункты — JVM-тестом.
2. **Бюджеты B5 (≤30 exec, ≤40 соединений, ≤150 КБ)` — не измерены.** Ожидание основано на удалении повторяющихся операций; фактические числа даст `HEARTBEAT` в первом же логе 1.0.53. Если `exec/min` окажется выше — следующий шаг: переиспользуемое соединение (один сокет вместо `Socket().use{}` на команду), это отдельная итерация (в этой запрещено трогать транспорт).
3. **`pm uninstall-system-updates`** на Android 9 (API 28) может отсутствовать как `pm`-подкоманда — тогда сработает `cmd package uninstall-system-updates`; обе ветки в скрипте есть, но какая именно нужна прошивке Geely — **НЕ ДОКАЗАНО**. Если обе не сработают, лог покажет `HVAC_UNSHADOW: … result=FAILED` и `HVAC_RESULT: FAILED reason=pm_path:/data/app`, а владельцу нужен ручной `pm uninstall com.flyme.auto.systemuiplugin` с ПК.
4. **`HEARTBEAT hvac=mounted`** появляется из двух источников (`StatusIcons` при клике и `bootRestoreHvacDigital` в watchdog'е). Порядок обновления не проверялся на устройстве.
5. **`LOG_SUMMARY` по «кнопке диагностики»** — кнопки с `diag.txt` в текущем коде нет (грепом не найдена), поэтому выжимка пишется в `onDestroy` и каждый тик watchdog'а. В мандате предполагалась существующая кнопка — её нет, `ЭТАЛОН: НЕТ В КОДЕ`.
6. **Имя логгера.** Мандат говорит про `AppFileLogger`; фактический единственный писатель в проекте — `AppLog` (`AppFileLogger` — мёртвый код, вызовов 0). Правки сделаны в `AppLog`, дублирования писателя не создано (требование «один писатель» важнее имени файла).
7. **`scripts/`**: правки вносились скриптами `/home/user/scripts/patch_iter33_*.py` (лог, adb, hvac, status, watchdog, noise).

---

## Что владелец увидит в логе после установки 1.0.53

```
ADB_CONST all=ok                       → транспорт не тронут (закрыто в 1.0.52)
SESSION_START run=… ver=1.0.53 dev=…
LOG_INDEX …                            → шпаргалка по маркерам
OP_START op=… cat=HVAC action=hvacBatt src=click
HVAC_UNSHADOW: before=/data/app/… after=/system/priv-app/… via=pm_uninstall result=OK
SCRIPT_TRACE script=hvac_enable stages=… failed=none
HVAC_PROBE pm_path=… mounted=true src_inode=… tgt_inode=… selinux=Enforcing via=bind rc=0 props=batt:95.1,…
HVAC_RESULT: OK reason=via:bind mount:true inode:true rc:0
OP_STEP op=… step=1/6 name=hvacdigital.hvacBatt status=ok …
RU: Шаг 1 из 6 (hvacdigital.hvacBatt) выполнен: …
OP_SUMMARY op=… action=hvacBatt result=OK steps=6/6 dur=…ms reason=применено
CYCLE_START n=1 reason=watchdog → CYCLE_SUMMARY n=1 …
HEARTBEAT uptime=60 exec/min=… conn/min=… hvac=mounted ttl=off video=off sysuiKills=0 codecRestarts=0
```

И через `grep` по `app.ops.log`: `OP_SUMMARY` — ровно по одной строке на действие, `HEARTBEAT` — раз в минуту, `RU:` — на каждый шаг, `SCRIPT_TRACE` — где встал скрипт.
