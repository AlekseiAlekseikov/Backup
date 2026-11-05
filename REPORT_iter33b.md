# REPORT iter33b — 1.0.53 (пересборка той же версии)

**Ветка:** `fix/v2` · **Версия НЕ поднята:** `versionCode = 53`, `versionName = "1.0.53"` (`app/build.gradle.kts:60-61`)
**База:** `6acbc27` · **Коммиты этой итерации:** `14dbf68` (все правки A–D), `de67be0` (fix сборки: дубль `isAutoOptimizeEnabled`)
**Мандат:** `uploads/PROMPT_iter33b_1.0.53_fix.md`. `main` не пушился, `--force` не применялся, workflow не правился.

---

## 0. Проверки из части G (сводка)

| проверка | результат |
|---|---|
| `setThreadOp(null)` после `← execute` | ✅ см. §B2 — цитата `workerLoop`, `AdbClient.kt:311-341` |
| подавление не трогает сбойные команды | ✅ см. §B3 — флаг `jobFailed` + кольцо `jobRing`, `LogKit.kt:350-392`, вызов `AdbClient.kt:335` |
| тег `CENTRAL_AdbClient` вернулся | ✅ `ADB_TX/ADB_RX/ADB_CHANNEL` пишутся с ним; `CENTRAL_Trace` остался **только** у не-ADB шума (`TICK`, `PREF TRACE`, `ICON_GEOM`, `KmIconProbe`, `HVAC_MOUNT`) — это осознанно, см. §B3 |
| `writeSummary` вне тика | ✅ `grep -rn writeSummary` = `VoiceAssistantService.kt:474` (onDestroy) + определение `LogKit.kt:307`. Пункта диагностики в коде нет — **НЕТ В КОДЕ** |
| `echo "VIA=…"` в скрипте | ✅ `HvacDigitalPlugin.kt:200,226,271,297,298` |
| опрос HVAC/TTL раз в 5 тиков | ✅ `VoiceAssistantService.kt:443` + расчёт «команд за 10 минут» = **10** |
| `MEDIA_WAIT_TICKS` реально > 0 при рестарте | ✅ `AppPerformance.kt:480,492,494` — сравнение одного `pidof media.swcodec` с обеих сторон |
| версия не поднята | ✅ `app/build.gradle.kts:60` `53`, `:61` `"1.0.53"` |

---

## F1. Чек-лист

| пункт | статус | файл:строка | доказательство (код после правки) + строка лога |
|---|---|---|---|
| **A1** одна boot-попытка плагина | ✅ | `HvacDigitalPlugin.kt:382-419`, `StatusIcons.kt:946-963`, `VoiceAssistantService.kt:368` | см. цитату ниже; лог: `HVAC_BOOT: attempt=1 reason=first_boot kill=0 result=OK ms=3120` |
| **A2** строка `HVAC_BOOT:` на попытку | ✅ | `HvacDigitalPlugin.kt:413-414` | `HVAC_BOOT: attempt=1 reason=not_mounted kill=0\|1 result=OK\|FAILED ms=…` — ровно одна на загрузку (флаг `AtomicBoolean` + преф `hvac_boot_install_uptime`) |
| **A2а** видео на boot | ✅ | `AppPerformance.kt:655-687`, вызов `VoiceAssistantService.kt:373` | `VIDEO_BOOT: pref=on applied_before=true result=OK ms=41 action=skip` / `…applied_before=false result=OK ms=… action=install` |
| **A2б** JIT, маршрут 1 (persist) | ✅ | `AppPerformance.kt:252-262` (запись дублёра в `setAutoOptimize`), `:720-787` (boot) | `JIT_PERSIST_SET desired=true readback=true`; `JIT_PERSIST_PROBE: supported=false runtime=false persist=true reason=runtime_not_raised_from_persist`; при удаче — `JIT_BOOT: restored_from_persist` (см. замечание в F3) |
| **A2б** маршрут 2 (`ctl.restart zygote`) | ✅ | `AppPerformance.kt:757-786` | `JIT_BOOT: pref=on desired=true runtime_before=false action=setprop+zygote uptime_ms=41230 marker=jit_boot_attempt rc=0` |
| **A2б** маршрут 3 (отказ) | ✅ | `AppPerformance.kt:790-796` | `JIT_VERIFY: runtime_after=false … result=FAILED` + `JIT_BOOT: unsupported …` |
| **A2б** цена перезапуска zygote | 🟡 | — | **НЕ ИЗМЕРЕНО на устройстве.** В логе 1.0.52 ручной клик дал `ZYGOTE_PID_BEFORE=1174`/`AFTER=20740` и `dt` шага `perf_prop_zygote` — по мандату это и есть цена; в новой сборке она печатается в `JIT_VERIFY` (`zygote_pid_before/after`). Точные секунды чёрного экрана появятся в первом логе 1.0.53 |
| **B1** `LOG_SUMMARY` не в тике | ✅ | `VoiceAssistantService.kt:459-462`, `LogKit.kt:307-335` | таблица пишется в `app.log` (`LOG_SUMMARY_ROW`), в выжимку — шапка + ≤5 действий |
| **B2** `op=` в `← execute` | ✅ | `AdbClient.kt:330-338` | `← execute dt=812ms rc=0 len=1841 port=5555 state=ROOT_5555 run=a1b2c3d4 op=5f0e9d21 cycle=- cmd=…` |
| **B3** тег + hex при сбое | ✅ | `LogKit.kt:37,350-392`, `AdbClient.kt:335,1138,1151,1706` | при `rc != 0` печатается `ADB_TRACE_REPLAY rc=1 why=rc n=<k>` и ВСЕ строки кольца (hex+ASCII) |
| **B4** выжимка: debounce + внутри лока | ✅ | `LogKit.kt:105-127`, `:120`, `:295`, `:338` | запись ≤1 раза в 2 с (`OPS_FLUSH_MIN_MS`), принудительно — `heartbeat`, `[ERROR]`, `onDestroy` |
| **B5** `VIA=` больше не «?» | ✅ | `HvacDigitalPlugin.kt:200,226,271,297-298,311` | `HVAC_PROBE … via=bind` / `via=nenhum` / `via=install` |
| **C1** опрос раз в 5 тиков | ✅ | `VoiceAssistantService.kt:443-455` | `CYCLE_SUMMARY n=7 actions=0 exec=0 dur=3` на «лёгких» тиках |
| **C2** `(-1,-1)` ⇒ SKIP | ✅ | `NetworkTtl.kt:246-251` | `TTL: SKIP (unknown, channel down) v4=-1 v6=-1` |
| **D1** ожидание рестарта кодека | ✅ | `AppPerformance.kt:480,492,494` | `MEDIA_PID_BEFORE=812` → `MEDIA_WAIT_TICKS=3` `MEDIA_PID_AFTER=1904` |
| **D2** `steps=` без PARTIAL | ✅ | `ToolsBackend.kt:216-223`, `LogKit.kt:229-236` | `OP_SUMMARY … result=PARTIAL steps=1/3 ok=1 partial=1 failed=0 total=3 dur=1840` |

---

## Цитаты кода (обязательные по части G)

### A1 — boot-попытка плагина (`HvacDigitalPlugin.kt:382-419`)

```kotlin
fun bootInstall(ctx: Context): Boolean {
    val app = ctx.applicationContext
    if (!bootInstallDone.compareAndSet(false, true)) { … "HVAC_BOOT: skip (attempt already made this boot)" … }
    val up = android.os.SystemClock.elapsedRealtime()
    val prevUp = Prefs2.getLong(app, PREF_BOOT_INSTALL_UPTIME, 0L)
    if (prevUp > 0 && up > prevUp && up - prevUp < 5 * 60_000L) { … "skip (process restart…)" … }
    Prefs2.putLong(app, PREF_BOOT_INSTALL_UPTIME, up)
    val st = runCatching { checkStatus(app) }.getOrNull() ?: return false
    if (st.overlayMounted && inodeMatchesStage(st.oemPkgPath)) { … "HVAC_BOOT: skip (already mounted)" …; return false }
    …
    AppLog.i(TAG, "HVAC_BOOT: attempt=1 reason=$reason kill=$kill result=${if (ok) "OK" else "FAILED"} ms=$dt")
```
Три предохранителя на месте: (1) `AtomicBoolean` + uptime-метка в префах, (2) `checkStatus` + `stat -c '%i'` источника/цели **до** любой записи, (3) вызов только из boot-ветки (`VoiceAssistantService.kt:368`), в `settingsWatchdog` его нет.

### A2а — видео (`AppPerformance.kt:655-687`)

```kotlin
if (!isVideo60FpsEnabled(app)) { LogKit.stateVideo(false); AppLog.i(TAG, "VIDEO_BOOT: pref=off (no attempt)"); return }
val appliedBefore = isVideoApplied()
if (appliedBefore) { … "VIDEO_BOOT: pref=on applied_before=true result=OK ms=… action=skip" …; return }
if (!videoBootAttempted.compareAndSet(false, true)) { … return }
val r = runCatching { setVideo60Fps(app, true) }.getOrNull()
AppLog.i(TAG, "VIDEO_BOOT: pref=on applied_before=false result=${if (ok) "OK" else "FAILED"} ms=$dt action=install")
```

### A2б — JIT, три маршрута (`AppPerformance.kt:720-796`)

```kotlin
val (runtime, persist) = jitProps()                       // один read-only getprop-вызов, RT/PS
if (runtime == desired) { "JIT_BOOT: match …"; return }   // маршрут 0: уже равно — ноль записи
if (persist == desired) { … второй проход через 30 с → "JIT_PERSIST_PROBE: supported=false …" }
val up = android.os.SystemClock.elapsedRealtime()
if (up >= JIT_LATE_START_MS) { "JIT_BOOT: deferred reason=late_start …"; return }
if (prev in 1..up && up - prev < JIT_ATTEMPT_GUARD_MS) { "JIT_BOOT: skip reason=attempt_guard …"; return }
val (rc, out) = runSuCommand("setprop dalvik.vm.usejit true; setprop dalvik.vm.usejitprofiles true; setprop ctl.restart zygote")
AppLog.i(TAG, "JIT_BOOT: pref=on desired=true runtime_before=$runtime action=setprop+zygote uptime_ms=$up marker=jit_boot_attempt rc=$rc …")
… postDelayed 20 с → "JIT_VERIFY: runtime_after=… zygote_pid_before=… zygote_pid_after=… result=OK|FAILED"
```
`svc wifi disable`, `umount /data/app/*`, `sleep` в boot-пути JIT **отсутствуют** — это только ручной клик (`setAutoOptimize`).
Persist-запись ровно одна: `persist.sys.dalvik.vm.usejit` (`AppPerformance.kt:252-262`), read-back печатается `JIT_PERSIST_SET desired=… readback=…`.

### B2 — `workerLoop` целиком (`AdbClient.kt:311-341`)

```kotlin
while (true) {
    val job = try { queue.take() } catch (_: InterruptedException) { return }
    val t0 = SystemClock.uptimeMillis()
    LogKit.setThreadOp(job.op)
    LogKit.beginJobTrace()
    val out = try { runCommand(job.cmd, job.timeoutMs, job.expectRoot) }
              catch (t: Throwable) { Log.e(TAG, "runCommand error", t); Pair(1, "ERR_EXEC: …") }
    val dt = SystemClock.uptimeMillis() - t0
    LogKit.countExec()
    try {
        AppLog.d(TAG, "← execute dt=${dt}ms rc=${out.first} len=… cmd=…")   // op= ещё жив
        LogKit.noteJobRc(out.first)
        if (out.first != 0) LogKit.failJobTrace(out.first, "rc")
    } finally {
        LogKit.setThreadOp(null)                                            // B2: сброс ПОСЛЕ строки
    }
    try { Handler(Looper.getMainLooper()).post { job.onResult(out) } } catch (_: Throwable) { job.onResult(out) }
}
```

### B3 — подавление не трогает сбойные команды (`LogKit.kt:350-392`)

```kotlin
fun traceAdb(kind: String, line: () -> String) {
    val text = line()
    synchronized(lock) {
        if (jobRing.size >= JOB_RING) jobRing.removeFirst()
        jobRing.addLast(text)                                   // кольцо ЭТОЙ команды
        val seen = traceSeen[kind] ?: 0
        if (seen < TRACE_PER_SESSION || jobFailed) { traceSeen[kind] = seen + 1; write = true }
        else { traceSuppressed[kind] = …; traceLast[kind] = text }
    }
    if (write) AppLog.d(ADB_TAG, text) else flushSuppressed(force = false)   // ADB_TAG = "CENTRAL_AdbClient"
}

fun failJobTrace(rc: Int, why: String) {
    val lines = synchronized(lock) { jobFailed = true; jobRing.toList() }
    AppLog.d(ADB_TAG, "ADB_TRACE_REPLAY rc=$rc why=${kv(why)} n=${lines.size} (провал: трассировка не подавляется)")
    for (l in lines) AppLog.d(ADB_TAG, l)
}
```

### B4 — выжимка (`LogKit.kt:105-127`)

```kotlin
private fun flushOps(force: Boolean = false) {
    val f = opsFile() ?: return
    val now = SystemClock.uptimeMillis()
    synchronized(lock) {
        if (!force && now - lastOpsFlushMs < OPS_FLUSH_MIN_MS) { opsDirty = true; return }
        lastOpsFlushMs = now; opsDirty = false
        runCatching { f.writeText(opsLines.joinToString("\n") + "\n") }   // запись ВНУТРИ лока
    }
}
fun flushOpsNow() = flushOps(force = true)
```

### B5 — `VIA=` (`HvacDigitalPlugin.kt:294-299`)

```sh
case "$PM_AFTER" in
  /data/app/*) echo "VIA=install" ;;
  *) echo "VIA=bind" ;;
esac
```
(`VIA=nenhum` — на провальных выходах `:200`, `:271`; `VIA=install` — при не снятом затмении `:226`.)
Регулярка: `Regex("VIA=([A-Za-z]+)", RegexOption.IGNORE_CASE)`.

### C1 — раз в 5 тиков + расчёт (`VoiceAssistantService.kt:443-455`)

```kotlin
val heavy = (n % 5 == 0)
if (heavy) {
    runCatching { NetworkTtl.bootRestore(app) }.onSuccess { actions++ }        // 1 команда
    runCatching { StatusIcons.bootRestoreHvacDigital(app) }.onSuccess { actions++ }  // 2 команды
    runCatching { AppPerformance.verifyVideoOnly(app) }.onSuccess { actions++ }      // 1 команда
    runCatching { AppPerformance.verifyJitOnly(app) }.onSuccess { actions++ }        // 1 команда
}
```
**Расчёт «команд за 10 минут в покое»:** 10 тиков / 10 мин; тяжёлый тик — каждый 5-й ⇒ 2 тяжёлых тика × 5 команд = **10 команд** (было 30–40). Яркость в этот счёт не входит: она теперь read-first и локальная (`ScreenBrightness.kt:161-176` — при совпадении ни одной записи).

### C2 — `NetworkTtl.kt:246-251`

```kotlin
val (cnt4, cnt6) = ttlRuleCounts()
if (cnt4 < 0 || cnt6 < 0) { AppLog.i(TAG, "TTL: SKIP (unknown, channel down) v4=$cnt4 v6=$cnt6"); return }
```

### D1 — `AppPerformance.kt:480,492,494`

```sh
MEDIA_PID_BEFORE=$(pidof media.swcodec 2>/dev/null)
while [ "$(pidof media.swcodec 2>/dev/null)" = "$MEDIA_PID_BEFORE" ] && [ $_t -lt 10 ]; do sleep 0.5; _t=$((_t+1)); done
echo "MEDIA_WAIT_TICKS=$_t"
MEDIA_PID_AFTER=$(pidof media.swcodec 2>/dev/null)
```

### D2 — `ToolsBackend.kt:216-223`

```kotlin
val okSteps = res.steps.count { it.status == ToolsStep.Status.OK }
val partialSteps = res.steps.count { it.status == ToolsStep.Status.PARTIAL }
val failedSteps = res.steps.count { it.status == ToolsStep.Status.FAILED || it.status == ToolsStep.Status.BLOCKED }
LogKit.opSummary(op, toggle.pref, res.statusWire.uppercase(), okSteps, partialSteps, failedSteps, res.steps.size, dur, res.summary)
```
Формат зафиксирован в `LOG_INDEX` (`LogKit.kt:203`): `OP_SUMMARY=итог действия(ровно одна строка; ok/partial/failed/total)`.

---

## CI

| что | значение |
|---|---|
| прогон | **42004017024** (`push`, `de67be0`) — **success** |
| conclusion | `success`, шаг `Gradle build (debug)` — success |
| артефакт | **13403369333** (`app-debug`) |
| zip | **177 079 317 Б** |
| APK внутри | **215 403 953 Б** (`apk/debug/app-debug.apk`) |
| подтверждение теста | `> Task :app:testDebugUnitTest` → `> Task :app:assembleDebug` → `BUILD SUCCESSFUL in 4m 16s` (лог job `127166098252`, строки 254895/254898/254902) |

Числа отличаются от прошлой сборки 1.0.53 (`41996706107`: zip 177 067 555 Б, APK 215 383 361 Б) — это ожидаемо: версия та же, код изменён (мандат, часть G). Разница +11 762 Б zip / +20 592 Б APK.

Предыдущий прогон `42002436101` (коммит `14dbf68`) — **failure**, `:app:compileDebugKotlin`: `Conflicting overloads` на `AppPerformance.kt:64` (мой новый `private fun isAutoOptimizeEnabled` дублировал существующий публичный). Исправлено коммитом `de67be0`.

---

## Честные пробелы

1. **На ГУ не проверялось.** Все ✅ подтверждены кодом + зелёной сборкой; поведение после ребута (`HVAC_BOOT`, `VIDEO_BOOT`, `JIT_*`) увидит владелец — это и есть цель итерации.
2. **Маршрут 1 для JIT (persist) на прошивке Geely — НЕ ДОКАЗАНО.** Логика реализована ровно по мандату: два чтения (сразу + через 30 с), результат честно пишется `JIT_PERSIST_PROBE: supported=true|false`. Если `supported=false`, работает маршрут 2 (одноразовый `ctl.restart zygote` при `uptime < 120 с`), и первый ребут после включения тумблера пройдёт с коротким перезапуском интерфейса — предупредить владельца.
3. **`JIT_BOOT: restored_from_persist`** печатается только когда runtime реально поднялся из persist (проверка на втором проходе). Строку нельзя гарантировать заранее — это факт прошивки.
4. **Цена перезапуска zygote (секунды)** — измерится по `JIT_VERIFY` первого реального клика/ребута; сейчас в отчёте только поля, по которым её считать.
5. **`CENTRAL_Trace` не равен нулю**: под этим тегом остались **не-ADB** виды шума (`TICK`, `PREF TRACE`, `ICON_GEOM`, `KmIconProbe`, `HVAC_MOUNT`). Мандат требовал вернуть `CENTRAL_AdbClient` для `ADB_CHANNEL/ADB_TX/ADB_RX` — это сделано; переименовывать в него не-ADB строки было бы ложью в тегах.
6. **Измерения B5/C1** (exec/min, ≤150 КБ) — ожидаемые, не замеренные: цифры даст `HEARTBEAT` первого лога 1.0.53.
7. **`writeSummary` по кнопке диагностики** — кнопки в коде нет: `ЭТАЛОН: НЕТ В КОДЕ`, выжимка пишется в `onDestroy`.
