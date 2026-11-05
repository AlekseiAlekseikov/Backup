# REPORT_iter33e — 1.0.53, четвёртая пересборка (`fix4`)

Дата: 2026-11-05 · ветка `fix/v2` · история не переписана, `--force` не применялся, `main` не тронут.

| что | значение |
|---|---|
| база | `8b31998` (кончик `fix/v2` до итерации, = iter33d) |
| коммиты итерации | `1919c24` (A1–A6/B/C1/C2), `0fddb68` (A3: мёртвая ветка удалена), `4ff7ead` (C1: четвёртая ветка) |
| HEAD (код, он же head прогона CI) | `4ff7eadc02f1817c541e36f29a139ea30260b23a` |
| коммит этого отчёта | `21f7acd` — только `REPORT_iter33e.md` и `WORKLOG.md`, кода не касается, поэтому состояние кода = `4ff7ead` |
| версия | `versionCode = 53`, `versionName = "1.0.53"` — `app/build.gradle.kts:60-61` |
| изменено | 4 файла, `+221 / −65` (`git diff --stat 8b31998 HEAD`) |

```
 app/src/main/java/com/example/voiceapp3/VoiceAssistantService.kt |   7 ++
 app/src/main/java/com/example/voiceapp3/tools/AppPerformance.kt  | 131 +++++++++++++++------
 app/src/main/java/com/example/voiceapp3/tools/JitBootMark.kt     |  50 +++++++-
 app/src/test/java/com/example/voiceapp3/tools/JitBootMarkTest.kt |  98 +++++++++++----
 4 files changed, 221 insertions(+), 65 deletions(-)
```

---

## 1. CI

| что | значение |
|---|---|
| прогон | **`42029651330`** (`fix/v2`, head `4ff7ead`), `conclusion = success` |
| джоба | **`127252532655`** (шаг `Gradle build (debug)` — success) |
| сборка | `BUILD SUCCESSFUL in 4m 8s` |
| тесты | `> Task :app:testDebugUnitTest` + **20 PASSED / 0 FAILED** (JitBootMarkTest 10, HvacVerdictTest 6, ScriptChunkerTest 4) |
| артефакт | **`13415209603`** (`app-debug`) |
| размеры *раздельно* | zip **177 084 785 Б** · `apk/debug/app-debug.apk` **215 423 525 Б** · `logs/manifest-merger-debug-report.txt` **105 294 Б** |

Команда размеров (`unzip -l` по скачанному артефакту, без суммирования):

```
$ curl -sL -H "Authorization: token …" \
    https://api.github.com/repos/AlekseiAlekseikov/Backup/actions/artifacts/13415209603/zip -o art9.zip
$ ls -l art9.zip | awk '{print "zip_B="$5}'
zip_B=177084785
$ unzip -l art9.zip | awk '/app-debug.apk/ {print "apk_B="$1} /manifest-merger-debug-report/ {print "logs_B="$1}'
apk_B=215423525
logs_B=105294
```

Счёт тестов:

```
$ grep -a "PASSED" "11_Gradle build (debug).txt" | wc -l      → 20
$ grep -a "PASSED" … | grep -c JitBootMarkTest                 → 10
$ grep -a "PASSED" … | grep -c HvacVerdictTest                 → 6
$ grep -a "PASSED" … | grep -c ScriptChunkerTest               → 4
$ grep -a "FAILED" … | wc -l                                   → 0
$ grep -a "BUILD SUCCESSFUL" …                                 → BUILD SUCCESSFUL in 4m 8s
$ grep -a "> Task" … | grep testDebugUnitTest                  → 12:55:29 > Task :app:testDebugUnitTest
```

`UP-TO-DATE`/`NO-SOURCE` нет: задача исполнялась (время в логе — момент прогона), 20 имён `PASSED` присутствуют.

Промежуточные прогоны этой итерации (для трассировки, не приёмка): `42026010987` (job `127240283386`, 20 PASSED, артефакт `13414945725`), `42027659759` (job `127245821226`, `BUILD SUCCESSFUL in 4m 28s`, 20 PASSED, артефакт `13415002714`).

---

## 2. Сводная таблица

| пункт | статус | файл:строка | доказательство (кратко) |
|---|---|---|---|
| A1 строка «попытка» до вызова + на диске | ✅ | `AppPerformance.kt:827-832` | `JIT_BOOT: zygote_restart intent=pending …` → `writeText(jit_pending_verify)` → `LogKit.flushOpsNow()`; вызов `ctl.restart zygote` ниже, `:838-839` |
| A2 `jit_pending_verify` + сверка на любом старте | ✅ | `AppPerformance.kt:688`, `:905-931`; `VoiceAssistantService.kt:266`, `:348` vs `:352` | вызовы сверки на 266 и 348, проверка extra — на 352 (`onCreate`/`onStartCommand` **выше**) |
| A3 `match_after_zygote` только из A2 | ✅ | `AppPerformance.kt:918-923` | строка печатается внутри блока `VERIFY`; во втором проходе её больше нет (`0fddb68`) |
| A4 положительный факт в watchdog раз за загрузку | ✅ | `AppPerformance.kt:938-952` | флаг `files/jit_verified` (`:690`) сверяется с `currentBootTag()` |
| A5 порядок «метка → лог → вызов», без повтора | ✅ | `AppPerformance.kt:820` → `:827` → `:831-832` → `:838-839`; guard `:810-813` | повтор блокируется `sameLoad` + `jitBootAttempted.compareAndSet` |
| A6 `PendingDecision` + 3 теста | ✅ | `JitBootMark.kt:95`, `:105-109`; `JitBootMarkTest.kt:102/108/118` | 3 `@Test`, все `PASSED` в CI |
| B `epochTag`/`stripEpochPrefix` + прод-тесты | ✅ | `JitBootMark.kt:40`, `:48`, `:50-51`, `:83-85`; тесты `:67-98` | тест гоняет `JitBootMark.epochTag`/`format`, не голые числа |
| C1 `persist_before` / `persist_after` в 4 ветках | ✅ | `AppPerformance.kt:767-768` + `:779`, `:786`, `:796`, `:799` | `grep -n probeCommon` = 5 строк (определение + 4 ветки) |
| C2 `sameBootIgnoresUptime` | ✅ | `JitBootMarkTest.kt:35-40` | маркер с меньшим uptime (`10_000` при 55 с) ⇒ `true` |
| C3 примеры лога из кода | ✅ | §7 | для каждой строки-примера указана команда `grep`, которой взята формат-строка; синтетика помечена |
| D/E запреты | ✅ | §8 | в диффе нет ни одного запрещённого токена (`git diff … | grep '^+'` — пусто) |

---

## 3. Цитаты по пунктам (код после правок)

### A1 — строка-намерение пишется на диск **до** вызова

`AppPerformance.kt:820-839`:

```kotlin
820        runCatching { markerFile.writeText(JitBootMark.format(bootTag, up)) }
...
827        AppLog.i(TAG, "JIT_BOOT: zygote_restart intent=pending boot_tag=$bootTag uptime_ms=$up " +
828            "marker=$JIT_BOOT_MARKER")
...
831        runCatching { File(app.filesDir, JIT_PENDING_FILE).writeText(JitBootMark.format(bootTag, up)) }
832        LogKit.flushOpsNow()
...
838        val (rc, out) = runSuCommand(
839            "setprop $PROP_USEJIT true; setprop $PROP_USEJIT_PROFILES true; setprop ctl.restart zygote")
```

Почему синхронность: `AppLog.i` идёт через `enqueueOrWrite` → `writeRaw` (`AppLog.kt:231`, `:260`), а `writeRaw` открывает `PrintWriter(FileWriter(target, true))` под `synchronized(lock)` и делает `out.flush()` (`AppLog.kt:278-283`) — то есть запись строки происходит **в вызывающем потоке**, не в очереди. Дополнительно `LogKit.flushOpsNow()` (`:832`) выжимает очередь операционного журнала. Итог: и метка `jit_boot_attempt`, и файл-намерение, и строка `intent=pending` лежат на диске **раньше**, чем уйдёт `ctl.restart zygote`, поэтому после смерти процесса (он потомок zygote) доказательство не теряется.

### A2 — сверка после рестарта, на **любом** старте, выше проверки `EXTRA_BOOT_RESTORE`

`VoiceAssistantService.kt:266` (`onCreate`) и `:348` (`onStartCommand`), проверка extra — `:352`:

```
264        // ВЫШЕ проверки EXTRA_BOOT_RESTORE — START_STICKY-рестарт приходит с intent == null
266        runCatching { AppPerformance.checkPendingZygoteVerify(this) }
...
348        runCatching { AppPerformance.checkPendingZygoteVerify(this) }
352        if (intent?.getBooleanExtra(EXTRA_BOOT_RESTORE, false) == true) {
```

Смысл: после `ctl.restart zygote` сервис поднимается по `START_STICKY` с `intent == null`, поэтому сверка обязана быть **до** ветвления по extra, иначе она недостижима.

Классификация и печать — `AppPerformance.kt:905-931`:

```kotlin
905    fun checkPendingZygoteVerify(context: Context) {
907        val pending = runCatching { File(app.filesDir, JIT_PENDING_FILE).readText().trim() }.getOrDefault("")
908        val bootTag = currentBootTag()
909        when (JitBootMark.pendingDecision(pending, bootTag)) {
910            JitBootMark.PendingDecision.NONE -> Unit
911            JitBootMark.PendingDecision.STALE -> {
912                runCatching { File(app.filesDir, JIT_PENDING_FILE).delete() }
913                AppLog.i(TAG, "JIT_VERIFY: skip reason=pending_stale pending=${LogKit.kv(pending)} boot_tag=$bootTag")
915            JitBootMark.PendingDecision.VERIFY -> BgWorker.post("AppPerformance.jitVerifyAfterRestart") {
918                AppLog.i(TAG, "JIT_VERIFY: runtime_after=$runtime persist=$persist source=after_restart " +
919                    "result=${if (ok) "OK" else "FAILED"} boot_tag=$bootTag")
922                    AppLog.i(TAG, "JIT_BOOT: match_after_zygote runtime=$runtime persist=$persist " +
923                        "(поднят перезапуском zygote в этой загрузке)")
925                runCatching { File(app.filesDir, JIT_PENDING_FILE).delete() }
```

Файл удаляется в **обеих** ветках (STALE — `:912`, VERIFY — `:925`, **вне** `if (ok)` — строка 920 открывает `if`, 924 закрывает), в систему ничего не пишется: только `getprop` (`jitProps()`) и свой файл.

### A3 — `match_after_zygote` больше не в мёртвой ветке

`grep -n "match_after_zygote" AppPerformance.kt` → `775` (комментарий) и `922` (печать). Печать — только из A2 (`checkPendingZygoteVerify`). Второй проход (`probeJitPersist`) отвечает теперь только за маршрут 1 — `AppPerformance.kt:771-786`:

```kotlin
771            // Этот второй проход отвечает ТОЛЬКО за маршрут 1 (persist): runtime уже поднят,
772            // попытки zygote в этой загрузке быть не могло — иначе процесс не дожил бы до прохода.
778            if (secondPass && persist == desired) {
779                AppLog.i(TAG, "JIT_PERSIST_PROBE: supported=true $probeCommon " +
780                    "reason=runtime_raised_from_persist")
781                AppLog.i(TAG, "JIT_BOOT: restored_from_persist prop=$PROP_PERSIST_USEJIT runtime=$runtime " +
782                    "persist=$persist (zygote не перезапускали)")
786                AppLog.i(TAG, "JIT_BOOT: match $probeCommon (ноль пишущих команд)")
```

Ветка `attempted` (`JIT_PERSIST_PROBE … reason=runtime_raised_by_our_zygote` + вторая копия `match_after_zygote`) **удалена** коммитом `0fddb68` вместе с хелпером `attemptInThisLoad` — она была недостижима (`grep -n attemptInThisLoad` → пусто) и содержала вторую копию опознания загрузки.

### A4 — положительный факт JIT в watchdog, один раз за загрузку

`AppPerformance.kt:938-952`:

```kotlin
945        val tagNow = currentBootTag()
947        val verifiedFile = File(app.filesDir, JIT_VERIFIED_FILE)      // ":690 JIT_VERIFIED_FILE = \"jit_verified\""
948        val seen = runCatching { verifiedFile.readText().trim() }.getOrDefault("")
949        if (seen == tagNow) return
950        runCatching { verifiedFile.writeText(tagNow) }
951        AppLog.i(TAG, "JIT_PROBE: runtime=true persist=$persist (уже поднят, read-only) boot_tag=$tagNow")
```

Хранение флага — файл приложения `files/jit_verified` (в нём `boot_tag`), а не память процесса (процесс перезапускается) и **не** системное свойство.

### A5 — порядок «метка → лог → вызов», повторной попытки нет

Порядок виден по номерам строк: `:820` метка → `:827` лог → `:831` файл-намерение + `:832` выжимка → `:838-839` вызов. Защита от цикла — `AppPerformance.kt:807-818`:

```kotlin
810        if (sameLoad) {
811            AppLog.i(TAG, "JIT_BOOT: skip reason=attempt_guard same_load=true " +
812                "marker_uptime_ms=${JitBootMark.uptimeOf(markerRaw)} now_ms=$up")
813            return
815        if (!jitBootAttempted.compareAndSet(false, true)) {
816            AppLog.i(TAG, "JIT_BOOT: skip reason=attempt_guard_process")
```

Плюс `A2` ничего не перезапускает (только `getprop`), watchdog — только `getprop` (`verifyJitOnly`, `:938`). Тест на этот guard — `JitBootMarkTest.sameBootBlocks` (`:27`) и `sameBootIgnoresUptime` (`:35`). **Честно:** юнит-теста, который проверял бы *порядок строк* в `probeJitPersist`, нет — порядок доказывается номерами строк и тем, что после рестарта zygote процесс не доживает до `:841`, поэтому единственная сохранившаяся запись — та, что была до вызова.

### A6 — `PendingDecision`

`JitBootMark.kt:95-109`:

```kotlin
95    enum class PendingDecision { NONE, STALE, VERIFY }
105    fun pendingDecision(pending: String, currentTag: String): PendingDecision = when {
106        pending.isBlank() -> PendingDecision.NONE
107        sameLoad(pending, currentTag) -> PendingDecision.VERIFY
108        else -> PendingDecision.STALE
109    }
```

Тесты: `pendingDecisionNoneWhenEmpty` (`:102`), `pendingDecisionStaleForOtherBoot` (`:108`), `pendingDecisionVerifyForSameBoot` (`:118`) — все три `PASSED` в прогоне `42029651330`.

### B — `epochTag` / `stripEpochPrefix`, корректные единицы

`JitBootMark.kt:40-51`, `:66-85`:

```kotlin
40    const val EPOCH_PREFIX = "epoch"
48    fun epochTag(epochMs: Long): String = EPOCH_PREFIX + (epochMs / 1000L)
50    fun stripEpochPrefix(tag: String): String =
51        if (tag.startsWith(EPOCH_PREFIX)) tag.removePrefix(EPOCH_PREFIX) else tag
...
83        val a = stripEpochPrefix(tag).toLongOrNull() ?: return false
84        val b = stripEpochPrefix(cur).toLongOrNull() ?: return false
85        val toleranceSec = (toleranceMs / 1000L).coerceAtLeast(1L)
86        return kotlin.math.abs(a - b) <= toleranceSec
```

`currentBootTag()` берёт формат только из `JitBootMark` — `AppPerformance.kt:883-891`:

```kotlin
884        val id = runCatching { File("/proc/sys/kernel/random/boot_id").readText().trim() }
886        if (id.isNotEmpty()) return id
890        val epochMs = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()
891        return JitBootMark.epochTag(epochMs)
```

Литералов `"epoch…"` в `AppPerformance.kt` нет (`grep -c '"epoch'` → `0`).

Тест на **прод-функциях** (`JitBootMarkTest.kt:67-98`):

```kotlin
69        val t = 1_700_000_000_000L
71        assertTrue(JitBootMark.sameLoad(JitBootMark.format(JitBootMark.epochTag(t), 40_000),
72            JitBootMark.epochTag(t + 30_000)))
76        assertFalse(JitBootMark.sameLoad(JitBootMark.format(JitBootMark.epochTag(t), 40_000),
77            JitBootMark.epochTag(t + 300_000)))
```

и `epochTagFormatIsProductionLike` (`:89-98`) — префикс `EPOCH_PREFIX`, `stripEpochPrefix(...)` парсится в число, `"boot-A"` не портится.

### C1 — `persist_before` / `persist_after` / `backfill` / `runtime` в 4 ветках

`grep -n probeCommon AppPerformance.kt` → `767` (определение), `779`, `786`, `796`, `799`.

```kotlin
767        val probeCommon = "persist_before=$persistBeforeWire persist_after=$persistAfterWire " +
768            "backfill=$backfillWire runtime=$runtime"
779                AppLog.i(TAG, "JIT_PERSIST_PROBE: supported=true $probeCommon " +
786                AppLog.i(TAG, "JIT_BOOT: match $probeCommon (ноль пишущих команд)")
796            AppLog.i(TAG, "JIT_PERSIST_PROBE: supported=unknown $probeCommon action=read_again_in_30s")
799        AppLog.i(TAG, "JIT_PERSIST_PROBE: supported=false $probeCommon reason=runtime_not_raised_from_persist")
```

`persist_present` в файле отсутствует (`grep -c persist_present` → `0`). Ветка `match` переведена на `probeCommon` коммитом `4ff7ead`, при этом `persist=` рядом с `persist_after=` не печатается — именно за дубль и был снят `persist_present`.

### C2 — `sameBootIgnoresUptime`

`JitBootMarkTest.kt:35-39`:

```kotlin
35    fun sameBootIgnoresUptime() {
36        assertTrue(JitBootMark.sameLoad(JitBootMark.format("boot-A", 10_000), "boot-A"))   // меньше
37        assertTrue(JitBootMark.sameLoad(JitBootMark.format("boot-A", 55_000), "boot-A"))   // больше
38        assertEquals(10_000L, JitBootMark.uptimeOf(JitBootMark.format("boot-A", 10_000)))
```

Тест действительно проверяет независимость опознания от uptime: маркер с **меньшим** (10 с) и **большим** (55 с) uptime при одном теге дают `true`.

---

## 4. Тринадцать обязательных проверок

### 4.1 Сверка после рестарта существует и достижима

```
$ grep -n "jit_pending_verify" app/src/main/java/com/example/voiceapp3/tools/AppPerformance.kt
688:    private const val JIT_PENDING_FILE = "jit_pending_verify"
907:        val pending = runCatching { File(app.filesDir, JIT_PENDING_FILE).readText().trim() }.getOrDefault("")
912:                runCatching { File(app.filesDir, JIT_PENDING_FILE).delete() }
925:                runCatching { File(app.filesDir, JIT_PENDING_FILE).delete() }
831:        runCatching { File(app.filesDir, JIT_PENDING_FILE).writeText(JitBootMark.format(bootTag, up)) }
$ grep -n "jit_pending_verify" app/src/main/java/com/example/voiceapp3/VoiceAssistantService.kt
(пусто — имя файла сервис не знает, он зовёт функцию)
$ grep -n "checkPendingZygoteVerify\|EXTRA_BOOT_RESTORE" app/src/main/java/com/example/voiceapp3/VoiceAssistantService.kt
112:        const val EXTRA_BOOT_RESTORE = "boot_restore"
264:        // ВЫШЕ проверки EXTRA_BOOT_RESTORE — START_STICKY-рестарт приходит с intent == null
266:        runCatching { AppPerformance.checkPendingZygoteVerify(this) }
348:        runCatching { AppPerformance.checkPendingZygoteVerify(this) }
352:        if (intent?.getBooleanExtra(EXTRA_BOOT_RESTORE, false) == true) {
404:        if (intent?.getBooleanExtra(EXTRA_BOOT_RESTORE, false) != true) {
```

**Вызовы 266 и 348 — оба выше проверки 352.** Достижимость: файл-намерение создаётся на boot-пути (`:831`), а читается на ближайшем старте сервиса (`onCreate`), который после `ctl.restart zygote` неизбежен — процесс-потомок умирает, `START_STICKY` поднимает сервис заново.

### 4.2 `JIT_VERIFY … source=after_restart`

`AppPerformance.kt:918-919`:

```kotlin
918                AppLog.i(TAG, "JIT_VERIFY: runtime_after=$runtime persist=$persist source=after_restart " +
919                    "result=${if (ok) "OK" else "FAILED"} boot_tag=$bootTag")
```

Второй `JIT_VERIFY` (`:851-853`, сверка через 20 с на boot-пути) поля `source=` не имеет — поэтому `grep "source=after_restart"` даёт **только** строки A2, то есть послерестартовые.

### 4.3 `match_after_zygote` не в мёртвой ветке

`grep -n match_after_zygote` → `775` (комментарий), `922` (печать). Печать — внутри `PendingDecision.VERIFY` A2 (`:915-926`). Во втором проходе строки нет: `grep -n "match_after_zygote"` по диапазону второго прохода (строки 744–799) даёт только `775` — комментарий.

### 4.4 Положительный факт в watchdog + способ хранения флага

`AppPerformance.kt:951` (цитата в §3/A4) + `:690` `JIT_VERIFIED_FILE = "jit_verified"`. Флаг = `boot_tag`, для которого факт уже напечатан; одно совпадение → `return` (`:949`). Итого одна строка `JIT_PROBE: runtime=true …` за загрузку.

### 4.5 Второй попытки zygote нет

```
$ grep -c "ctl.restart zygote" app/src/main/java/com/example/voiceapp3/tools/AppPerformance.kt
7
$ grep -n "ctl.restart zygote" app/src/main/java/com/example/voiceapp3/tools/AppPerformance.kt
213:  // комментарий (порт la.java)
242:       setprop ctl.restart zygote            ← клик `setAutoOptimize` (не boot)
301:       command = "svc wifi disable; …; ctl.restart zygote; …"   ← тот же клик, вторая ветка
717:  * Маршрут 2 — один `ctl.restart zygote` …
825:  // Наш процесс — потомок zygote: после `ctl.restart zygote` он умирает …
839:       "setprop … ctl.restart zygote")      ← ЕДИНСТВЕННЫЙ boot-вызов
897:  * Зачем: наш процесс — потомок zygote …
```

**7 = 1 boot-вызов (`:839`) + 2 вызова по явному клику владельца (`:242`, `:301`) + 4 комментария.** На boot — ровно один, повторной попытки нет: после рестарта `A2` только читает `getprop`, watchdog (`verifyJitOnly`) только читает `getprop`, таймеров на перезапуск нет.

Guard:

```kotlin
810        if (sameLoad) {
811            AppLog.i(TAG, "JIT_BOOT: skip reason=attempt_guard same_load=true " + …)
813            return
```

плюс `secondPass`/окно: `AppPerformance.kt:800-803` (`JIT_BOOT: deferred reason=outside_window uptime_ms=$up`) и `:692` `JIT_ZYGOTE_WINDOW_MS = 120_000L`.

### 4.6 fallback-тег реально сравнивается

`JitBootMark.kt:48/50-51` (цитата в §3/B) + тест, гоняющий прод-функции: `JitBootMarkTest.kt:66-98`. Тест проверяет и допуск (±30 с → `true`), и выход за допуск (300 с → `false`), и чужое пространство имён (`epoch`-тег против `boot-A` → `false`), и явно сужение допуска (`toleranceMs = 1_000L` → `false`).

### 4.7 `epoch`-тест не на голых числах

```
$ grep -n "1700000000" app/src/test/java/com/example/voiceapp3/tools/JitBootMarkTest.kt
92:        assertEquals("1700000000", JitBootMark.stripEpochPrefix(tag))
```

Единственное вхождение — **ожидаемое значение** для `stripEpochPrefix(JitBootMark.epochTag(1_700_000_000_000L))`, то есть проверяется прод-функция; сам тег строится `epochTag(...)`, входных «голых» тегов в тестах нет. Основание: `val tag = JitBootMark.epochTag(1_700_000_000_000L)` (`:90`).

### 4.8 `persist_before` / `persist_after` в 4 ветках

`grep -n probeCommon` — 5 строк (определение `:767` + ветки `:779`, `:786`, `:796`, `:799`). Цитаты — §3/C1.

### 4.9 Примеры лога выведены из кода

§7 — для каждой строки-примера приведена команда `grep`, которой взята формат-строка.

### 4.10 Ссылки `файл:строка` проверены (`sed -n`, 18 ссылок)

```
app/src/main/java/com/example/voiceapp3/tools/AppPerformance.kt:688    | private const val JIT_PENDING_FILE = "jit_pending_verify"
app/src/main/java/com/example/voiceapp3/tools/AppPerformance.kt:690    | private const val JIT_VERIFIED_FILE = "jit_verified"
app/src/main/java/com/example/voiceapp3/tools/AppPerformance.kt:767    | val probeCommon = "persist_before=$persistBeforeWire persist_after=$persistAfterWire " +
app/src/main/java/com/example/voiceapp3/tools/AppPerformance.kt:786    | AppLog.i(TAG, "JIT_BOOT: match $probeCommon (ноль пишущих команд)")
app/src/main/java/com/example/voiceapp3/tools/AppPerformance.kt:820    | runCatching { markerFile.writeText(JitBootMark.format(bootTag, up)) }
app/src/main/java/com/example/voiceapp3/tools/AppPerformance.kt:827    | AppLog.i(TAG, "JIT_BOOT: zygote_restart intent=pending boot_tag=$bootTag uptime_ms=$up " +
app/src/main/java/com/example/voiceapp3/tools/AppPerformance.kt:831    | runCatching { File(app.filesDir, JIT_PENDING_FILE).writeText(JitBootMark.format(bootTag, up)) }
app/src/main/java/com/example/voiceapp3/tools/AppPerformance.kt:839    | "setprop $PROP_USEJIT true; setprop $PROP_USEJIT_PROFILES true; setprop ctl.restart zygote")
app/src/main/java/com/example/voiceapp3/tools/AppPerformance.kt:918    | AppLog.i(TAG, "JIT_VERIFY: runtime_after=$runtime persist=$persist source=after_restart " +
app/src/main/java/com/example/voiceapp3/tools/AppPerformance.kt:922    | AppLog.i(TAG, "JIT_BOOT: match_after_zygote runtime=$runtime persist=$persist " +
app/src/main/java/com/example/voiceapp3/tools/AppPerformance.kt:951    | AppLog.i(TAG, "JIT_PROBE: runtime=true persist=$persist (уже поднят, read-only) boot_tag=$tagNow
app/src/main/java/com/example/voiceapp3/VoiceAssistantService.kt:266    | runCatching { AppPerformance.checkPendingZygoteVerify(this) }
app/src/main/java/com/example/voiceapp3/VoiceAssistantService.kt:348    | runCatching { AppPerformance.checkPendingZygoteVerify(this) }
app/src/main/java/com/example/voiceapp3/VoiceAssistantService.kt:352    | if (intent?.getBooleanExtra(EXTRA_BOOT_RESTORE, false) == true) {
app/src/main/java/com/example/voiceapp3/tools/JitBootMark.kt:48         | fun epochTag(epochMs: Long): String = EPOCH_PREFIX + (epochMs / 1000L)
app/src/main/java/com/example/voiceapp3/tools/JitBootMark.kt:84         | val toleranceSec = (toleranceMs / 1000L).coerceAtLeast(1L)
app/src/main/java/com/example/voiceapp3/tools/JitBootMark.kt:105        | fun pendingDecision(pending: String, currentTag: String): PendingDecision = when {
app/build.gradle.kts:61                                                | versionName = "1.0.53"
```

Команда проверки (по одной ссылке):

```
$ sed -n "831p" app/src/main/java/com/example/voiceapp3/tools/AppPerformance.kt
```

### 4.11 Три размера раздельно

См. §1: **177 084 785** / **215 423 525** / **105 294** Б + команда `unzip -l | awk`. Не суммируются.

### 4.12 Версия не поднята

```
$ sed -n '58,61p' app/build.gradle.kts
        minSdk = 28
        targetSdk = 32
        versionCode = 53
        versionName = "1.0.53"
```

### 4.13 Тесты реально идут

§1: `> Task :app:testDebugUnitTest` в `12:55:29`, 20 `PASSED`, 0 `FAILED`.

---

## 5. Что именно изменилось в коде (по коммитам)

| коммит | содержание |
|---|---|
| `1919c24` | A1 (строка + файл + `flushOpsNow` до вызова), A2 (`checkPendingZygoteVerify` + вызовы на `onCreate`/`onStartCommand`), A3 (строка `match_after_zygote` в A2, `source=after_restart`), A4 (`files/jit_verified`), A6 (`PendingDecision` + 3 теста), B (`EPOCH_PREFIX`/`epochTag`/`stripEpochPrefix`, сравнение через `stripEpochPrefix` обеих сторон, единицы: тег в секундах, допуск мс→с), C1 (`persist_present` убран, `persist_after` добавлен), C2 (`sameBootIgnoresUptime`) |
| `0fddb68` | A3 доведён: удалена недостижимая ветка `attempted`/`runtime_raised_by_our_zygote` и хелпер `attemptInThisLoad` — вместе со второй копией опознания загрузки |
| `4ff7ead` | C1 доведён: ветка `JIT_BOOT: match` тоже печатает `probeCommon` (4 ветки из 4), без дублирующего `persist=` |

---

## 6. Найденный по ходу дефект (честно)

При сборке отчёта обнаружено, что после первой версии A3 строка `JIT_BOOT: match runtime=… persist=…` осталась единственной из четырёх веток без `persist_before`/`persist_after` — то есть требование C1 «во всех 4 ветках» выполнялось на 3/4. Исправлено в `4ff7ead`, проверка — §4.8. Версия/поведение не менялись.

Отдельно: в тестах время переведено в миллисекунды (`1_700_000_000_000L`), потому что `epochTag` принимает мс. До этого `epochTag` получал секунды при допуске в мс ⇒ ±60 000 «мс» = ±16 ч, и «чужая загрузка» считалась своей (ловилось Python-моделью из 17 кейсов).

---

## 7. C3 — примеры лога, собранные из формат-строк кода

Ниже — **не реальный лог** (на устройстве эта сборка ещё не запускалась): строки собраны подстановкой значений в формат-строки из кода. Для каждой указана команда, которой взята формат-строка.

**7.1 Метка попытки (boot-путь, начало)**

```
$ grep -n "JIT_BOOT: attempt_marker" tools/AppPerformance.kt
737:  AppLog.i(TAG, "JIT_BOOT: attempt_marker boot_tag=$bootTag same_load=$sameLoad uptime_ms=$upNow " +
738:      "marker=${LogKit.kv(markerRaw.ifEmpty { "empty" })}")
```

`пример, не из лога`:

```
JIT_BOOT: attempt_marker boot_tag=epoch1762345678 same_load=false uptime_ms=41230 marker=empty
```

**7.2 Намерение перед вызовом (A1)**

```
$ grep -n "zygote_restart intent=pending" tools/AppPerformance.kt
827:  AppLog.i(TAG, "JIT_BOOT: zygote_restart intent=pending boot_tag=$bootTag uptime_ms=$up " +
828:      "marker=$JIT_BOOT_MARKER")
```

`пример, не из лога`:

```
JIT_BOOT: zygote_restart intent=pending boot_tag=epoch1762345678 uptime_ms=43120 marker=jit_boot_attempt
```

**7.3 Сверка после рестарта (A2/A3)**

```
$ grep -n "source=after_restart\|match_after_zygote" tools/AppPerformance.kt
918:  … "JIT_VERIFY: runtime_after=$runtime persist=$persist source=after_restart " +
919:      "result=${if (ok) "OK" else "FAILED"} boot_tag=$bootTag"
922:  … "JIT_BOOT: match_after_zygote runtime=$runtime persist=$persist " +
923:      "(поднят перезапуском zygote в этой загрузке)"
```

`пример, не из лога`:

```
JIT_VERIFY: runtime_after=true persist=true source=after_restart result=OK boot_tag=epoch1762345678
JIT_BOOT: match_after_zygote runtime=true persist=true (поднят перезапуском zygote в этой загрузке)
```

**7.4 Отброшенное намерение прошлой загрузки**

```
$ grep -n "pending_stale" tools/AppPerformance.kt
913:  AppLog.i(TAG, "JIT_VERIFY: skip reason=pending_stale pending=${LogKit.kv(pending)} boot_tag=$bootTag")
```

`пример, не из лога` (значение `pending` — это содержимое `files/jit_pending_verify`, формат `JitBootMark.format` = `тег:uptime`):

```
JIT_VERIFY: skip reason=pending_stale pending=epoch1762345678:43120 boot_tag=epoch1762440000
```

**7.5 Положительный факт в watchdog (A4)**

```
$ grep -n "JIT_PROBE: runtime=true" tools/AppPerformance.kt
951:  AppLog.i(TAG, "JIT_PROBE: runtime=true persist=$persist (уже поднят, read-only) boot_tag=$tagNow")
```

`пример, не из лога`:

```
JIT_PROBE: runtime=true persist=true (уже поднят, read-only) boot_tag=epoch1762345678
```

**7.6 Четыре ветки `probeCommon` (C1)**

```
$ grep -n "probeCommon" tools/AppPerformance.kt
767  (определение)  779  786  796  799
```

`примеры, не из лога` — по одному на строку (ветка `supported=true` печатает две связанные строки):

```
JIT_PERSIST_PROBE: supported=unknown persist_before=empty persist_after=empty backfill=no runtime=false action=read_again_in_30s
JIT_PERSIST_PROBE: supported=true persist_before=empty persist_after=true backfill=yes runtime=true reason=runtime_raised_from_persist
JIT_BOOT: restored_from_persist prop=persist.sys.dalvik.vm.usejit runtime=true persist=true (zygote не перезапускали)
JIT_BOOT: match persist_before=true persist_after=true backfill=no runtime=true (ноль пишущих команд)
JIT_PERSIST_PROBE: supported=false persist_before=true persist_after=true backfill=no runtime=false reason=runtime_not_raised_from_persist
```

Почему здесь **нет** дефекта iter33d (`backfill=no` после `backfill=yes`): `persistBefore` берётся из свежего `jitProps()` в начале **каждого** прохода (`AppPerformance.kt:746`, `:754`), а дозапись первого прохода к моменту второго уже лежит в системе; поэтому на втором проходе честная пара — `persist_before=true … backfill=no`. Возможная пара «`persist_before=empty` + `backfill=no`» — это первый проход при уже поднятом `runtime` (маршрут 1, дозапись не понадобилась), и она тоже непротиворечива.

**7.7 `ctl.restart zygote` в boot-пути**

```
$ grep -n "setprop ctl.restart zygote" tools/AppPerformance.kt
839:  "setprop $PROP_USEJIT true; setprop $PROP_USEJIT_PROFILES true; setprop ctl.restart zygote")
```

`пример, не из лога`:

```
JIT_BOOT: pref=on desired=true runtime_before=false action=setprop+zygote uptime_ms=43140 marker=jit_boot_attempt rc=0 out=-
```

---

## 8. Запреты D/E — машинная проверка

```
$ git diff 8b31998 HEAD -- app/src | grep '^+' | grep -nE \
   "ADB_TOGGLE|persist\.adb\.tcp\.port|settings put|mt_gpio|setenforce 0|dalvik-cache|pm install|sleep 3|svc wifi disable|umount /data/app"
(пусто)
```

То есть **ни одна** запрещённая конструкция в этой итерации не добавлена. Токены, существующие в проекте исторически (`svc wifi disable`/`umount /data/app` — только кликовый путь `:242`/`:301`; `setenforce 0` — другие файлы), не тронуты. Boot-путь содержит ровно три команды (`:838-839`, комментарий `:836`: «Ровно три команды, без svc wifi / umount /data/app / sleep»). Новые файлы — только `filesDir`: `jit_pending_verify`, `jit_verified`. Манифест и workflow не менялись (`git diff --stat` — 4 файла, см. §0).

---

## 9. Ответ на обязательный вопрос: `app.log` после рестарта zygote

**Это продолжение того же файла, а не новый файл.** Механика (`AppLog.kt:38`, `:112`; `LogKit.kt:88`):

* имя файла фиксированное — `voiceapp3.log` (`AppLog.kt:38 LOG_NAME = "voiceapp3.log"`), путь — `getExternalFilesDir(null)` (`AppLog.kt:364-369`, `pickFile`), в нём нет ни pid, ни boot_id;
* запись всегда **дописывает** файл: `PrintWriter(FileWriter(target, true))` (`AppLog.kt:278`), ротация — только по размеру: 16 МБ и два архива `voiceapp3.log.1/.2` (`AppLog.kt:40-41`, `:405`);
* значит после `ctl.restart zygote` владелец увидит **тот же** `voiceapp3.log`, в котором выше по тексту лежат строки прошлого процесса (в том числе `JIT_BOOT: zygote_restart intent=pending …`), а ниже — строки нового процесса, начинающиеся со свежих маркеров сеанса: `AppLog.init path=… ok=true` (`AppLog.kt:99`) и `SESSION_START run=… ver=… dev=… uptime=…` (`LogKit.kt:226`). `uptime` в `SESSION_START` маленький — это и есть граница «до/после рестарта».

Единственный риск «нового файла» — если внешний каталог в момент старта недоступен: тогда пишется внутренний `files/logs/voiceapp3.log` (`AppLog.kt:111`), и путь виден в строке `AppLog.init FAIL path=…`/`AppLog init internal path=…`.

**По какой строке видно, что сверка прошла** — `AppPerformance.kt:918-919`:

```
JIT_VERIFY: runtime_after=true persist=true source=after_restart result=OK boot_tag=<тег>
```

плюс следом `JIT_BOOT: match_after_zygote …` (`:922`). Если сверка не удалась — та же строка с `result=FAILED`; если намерение оказалось от прошлой загрузки — `JIT_VERIFY: skip reason=pending_stale …` (`:913`).

---

## 10. Честные пробелы (не измерено / не проверено на устройстве)

1. **Цена `ctl.restart zygote`** — секунды чёрного экрана и переподключения CarPlay **не измерены**: на ГУ эта сборка ещё не запускалась, в песочнице ADB-канала к машине нет.
2. **Читается ли `boot_id` на прошивке Geely** — не проверено. Если не читается, работает fallback-тег `epoch…` с допуском ±60 с; ветка покрыта тестами (`epochTolerance`, `epochTagFormatIsProductionLike`), но на железе не наблюдалась.
3. **Срабатывает ли маршрут 1** (`restored_from_persist`) — зависит от того, переживает ли `persist.sys.dalvik.vm.usejit` перезагрузку на этой прошивке; на устройстве не подтверждено.
4. **Фактический `exec/min`** после правок не замерялся (в этой итерации новых команд не добавлено, только `getprop` и файловые операции).
5. **Размер выборки**: два ребута из §G ещё не выполнялись, поэтому строки §7 синтетические по построению (помечены).
6. **Юнит-теста на порядок строк** в `probeJitPersist` нет; порядок доказан номерами строк (§3/A5).
7. Проверка `JIT_VERIFY` через 20 с (`:851`) на послерестартовом пути принципиально недостижима — именно поэтому A2 и появился; это не пробел, а причина правки, но фиксирую как ограничение: «зелёная» строка после рестарта приходит только из A2.

---

## 11. Приёмка владельцем (§G) — что грепать после установки 1.0.53 и двух перезагрузок

```
grep -a "JIT_BOOT: zygote_restart intent=pending" app.log   # доказательство, что попытка была, — ДО рестарта
grep -a "source=after_restart" app.log                      # сверка на новом старте, result=OK|FAILED
grep -a "match_after_zygote" app.log                        # только рядом с source=after_restart
grep -a "JIT_PROBE: runtime=true" app.log                   # ≥1 строка за загрузку
grep -a "reason=pending_stale" app.log                      # намерение прошлой загрузки отброшено
grep -a "JIT_BOOT: attempt_marker" app.log                  # boot_tag разный на разных загрузках
grep -ac "ctl.restart zygote" app.log                       # на boot не больше одного
grep -a "boot_tag=epoch" app.log                            # если fallback — видно
grep -aE "persist_before=|persist_after=" app.log           # дублёр унаследован или дозаписан
grep -aE "HVAC VERDICT:|VERIFY hvacdigital.enable:" app.log # VIA=bind / OK, не PARTIAL
```

Файл на устройстве называется `voiceapp3.log` (внешний каталог приложения); `app.log` в грепах — то же самое, как владелец его называет.

Глазами: цифры климата после ребута появляются сами; «Видео 60 fps» — одна попытка; мигание статус-бара одно; загрузка не подвисает.

Главный критерий — по логу можно ответить: **JIT поднялся, каким маршрутом и чем доказано**:
* маршрут 2 (наш рестарт zygote): `JIT_BOOT: zygote_restart intent=pending …` → `JIT_VERIFY … source=after_restart result=OK` → `JIT_BOOT: match_after_zygote …`;
* маршрут 1 (persist пережил ребут): `JIT_PERSIST_PROBE: supported=true … backfill=… runtime=true` → `JIT_BOOT: restored_from_persist …`;
* «уже поднят без наших команд»: `JIT_BOOT: match persist_before=… persist_after=… backfill=no runtime=true` → позже `JIT_PROBE: runtime=true …`;
* «не поднялся»: `JIT_PERSIST_PROBE: supported=false … reason=runtime_not_raised_from_persist` + `JIT_VERIFY … result=FAILED`.
