# REPORT_iter33g — 1.0.53, шестая пересборка (`fix6`)

Дата: 2026-11-05 · ветка `fix/v2` · история не переписана, `--force` не применялся, `main` не тронут.

| что | значение |
|---|---|
| база | `578f355` (кончик `fix/v2` = код iter33f; сверху был коммит отчёта `14768b2`) |
| коммиты итерации | `55aefd2` (C4 — перенос `*.md` в `docs/agent/`), `86a4238` (A1/A2/A3/B1/B2/C1/C2), `df0bb25` (B2 fix: `sameTag`) |
| HEAD кода (= head прогона CI) | `df0bb25` |
| версия | `versionCode = 53`, `versionName = "1.0.53"` — `app/build.gradle.kts:60-61` |

---

## 1. CI

| что | значение |
|---|---|
| прогон | **`42041029635`** (`fix/v2`, head `df0bb25`), `conclusion = success` |
| джоба | **`127291563056`** (шаг `Gradle build (debug)` — success) |
| сборка | `BUILD SUCCESSFUL in 4m 8s` |
| тесты | `> Task :app:testDebugUnitTest` + **26 PASSED / 0 FAILED** (JitBootMarkTest **16**, HvacVerdictTest 6, ScriptChunkerTest 4) |
| артефакт | **`13421983008`** (`app-debug`) |
| размеры *раздельно* | zip **177087351 Б** · `apk/debug/app-debug.apk` **215432385 Б** · `logs/manifest-merger-debug-report.txt` **105294 Б** |

Промежуточные прогоны этой итерации (для трассировки):

| прогон | что было | итог |
|---|---|---|
| `42039165265` | первая версия B2: `cycleDecision` сравнивал **метки** через `sameLoad` | ❌ `conclusion = failure`: `JitBootMarkTest > cycleDecisionSkipsOnlySameBoot FAILED` (`AssertionError at JitBootMarkTest.kt:187`), `BUILD FAILED in 4m 13s` |
| `42041029635` | после `df0bb25` (`sameTag`) | ✅ success, числа ниже |

Это ровно тот случай, ради которого тесты и нужны: ошибка была не в тесте, а в коде — `sameLoad` ищет в строке `:` (формат `тег:uptime`), а в файле `jit_verify_cycle` лежит «голый» тег, поэтому сравнение всегда давало `false` и счётчик не блокировал ничего. Исправлено в `df0bb25` функцией `JitBootMark.sameTag` (`:173-181`), тест дополнен случаем «часы уехали на 30 с».

Каждая `grep -c` — с напечатанным выводом:

```
$ grep -ac "PASSED" "11_Gradle build (debug).txt"
26
$ grep -a "PASSED" "11_Gradle build (debug).txt" | grep -ac "JitBootMarkTest"
16
$ grep -a "PASSED" "11_Gradle build (debug).txt" | grep -ac "HvacVerdictTest"
6
$ grep -a "PASSED" "11_Gradle build (debug).txt" | grep -ac "ScriptChunkerTest"
4
$ grep -ac "FAILED" "11_Gradle build (debug).txt"
0
$ grep -a "> Task" "11_Gradle build (debug).txt" | grep testDebugUnitTest
2026-11-05T14:13:17.5986635Z > Task :app:testDebugUnitTest
$ grep -a "BUILD SUCCESSFUL" "11_Gradle build (debug).txt"
2026-11-05T14:13:18.4981347Z BUILD SUCCESSFUL in 4m 8s
$ ls -l art.zip | awk '{print "zip_B="$5}'
zip_B=177087351
$ unzip -l art.zip | awk '/app-debug.apk/ {print "apk_B="$1} /manifest-merger-debug-report/ {print "logs_B="$1}'
apk_B=215432385
logs_B=105294
```

---

## 2. Сводная таблица

| пункт | статус | файл:строка | доказательство (кратко) |
|---|---|---|---|
| A1 `verifyOutcome` — четыре ветви, `persist` участвует | ✅ | `JitBootMark.kt:132-137` | `runtime.isNotEmpty()` → `FAILED`, `persist.isNotEmpty()` → `FAILED`, `UNKNOWN` только когда обе пусты |
| A2 два разных `reason` у `FAILED` | ✅ | `JitBootMark.kt:144-149`; `AppPerformance.kt:1048-1051` | `runtime_not_true` / `runtime_empty` / `no_channel`, слово берётся из `JitBootMark.outcomeReason` |
| A3 тесты: изменён + 2 новых (+1 на `cycleDecision`) | ✅ | `JitBootMarkTest.kt:148/163/172/182` | `grep -c "@Test"` = **16**; изменённый `verifyOutcomeUnknownWhenEmpty` теперь ждёт `FAILED` |
| B1 `files/jit_verify_cycle` + `skip reason=already_this_boot` | ✅ | `AppPerformance.kt:699`, `:959-969` | запись тега **выше** `BgWorker.post` (`:969` < `:970`) |
| B2 границы счётчика | ✅ | `JitBootMark.kt:152-163`, `:173-181` | `cycleDecision` через `sameTag` (теги, не метки) → `RUN|SKIP`; `@Test` на 4 случая |
| C1 сверка ниже `AdbClient.attachContext` | ✅ | `VoiceAssistantService.kt:268` → `:270` | вызов остался выше `EXTRA_BOOT_RESTORE` (`:353`) |
| C2 `claim none` один раз за процесс | ✅ | `AppPerformance.kt:718`, `:1016-1018` | `saidNoClaim.compareAndSet(false, true)` |
| C3 два вида `JIT_VERIFY` названы | ✅ | §5 | без `source=` — «процесс выжил, +20 с»; с `source=after_restart` — «процесс поднялся» |
| C4 `*.md` агента в `docs/agent/` | ✅ | коммит `55aefd2` | `git ls-tree --name-only HEAD` в корне — только `README.md`; `docs/agent` = 21 файл |

---

## 3. Цитаты кода после правок

### 3.1 A1 — четыре ветви, `persist` в решении

`JitBootMark.kt:132-137`:

```kotlin
132    fun verifyOutcome(runtime: String, persist: String): VerifyOutcome = when {
133        runtime == "true" -> VerifyOutcome.OK
134        runtime.isNotEmpty() -> VerifyOutcome.FAILED      // ответ есть, JIT не поднят
135        persist.isNotEmpty() -> VerifyOutcome.FAILED      // канал ответил (persist прочитан), runtime пуст
136        else -> VerifyOutcome.UNKNOWN                     // обе строки пусты ⇒ канала нет
137    }
```

```
$ sed -n '132,137p' app/src/main/java/com/example/voiceapp3/tools/JitBootMark.kt | grep -n "persist"
1:    fun verifyOutcome(runtime: String, persist: String): VerifyOutcome = when {
4:        persist.isNotEmpty() -> VerifyOutcome.FAILED      // канал ответил (persist прочитан), runtime пуст
```

`persist` — не только в подписи, но и в условии ветви 3.

### 3.2 A2 — два вида `FAILED` различаются словом

`JitBootMark.kt:144-149`:

```kotlin
144    fun outcomeReason(outcome: VerifyOutcome, runtime: String, persist: String): String = when {
145        outcome == VerifyOutcome.UNKNOWN -> "no_channel"
146        outcome == VerifyOutcome.FAILED && runtime.isEmpty() -> "runtime_empty"
147        outcome == VerifyOutcome.FAILED -> "runtime_not_true"
148        else -> ""
149    }
```

`AppPerformance.kt:1048-1051`:

```kotlin
1048        val reasonWord = JitBootMark.outcomeReason(outcome, runtime, persist)
1049        val reason = if (reasonWord.isEmpty()) "" else " reason=$reasonWord"
1050        AppLog.i(TAG, "JIT_VERIFY: runtime_after=${kvE(runtime)} persist=${kvE(persist)} source=after_restart " +
1051            "result=${outcome.name}$reason boot_tag=$bootTag attempt=${attempt + 1}")
```

`reason` теперь выбирается в чистой функции `JitBootMark`, а не руками в `AppPerformance` — поэтому он покрыт тестом.

### 3.3 A3 — тесты: старый **изменён**, добавлены новые

Было (`iter33f`, до правки):

```kotlin
    fun verifyOutcomeUnknownWhenEmpty() {
        assertEquals(JitBootMark.VerifyOutcome.UNKNOWN, JitBootMark.verifyOutcome("", ""))
        assertEquals(JitBootMark.VerifyOutcome.UNKNOWN, JitBootMark.verifyOutcome("", "true"))  // ← фиксировал дефект
    }
```

Стало (`JitBootMarkTest.kt:147-159`):

```kotlin
147    fun verifyOutcomeUnknownWhenEmpty() {
150        assertEquals(JitBootMark.VerifyOutcome.UNKNOWN,
151            JitBootMark.verifyOutcome("", ""))
152        // БЫЛО (до iter33g, ошибочное ожидание):
153        //   assertEquals(JitBootMark.VerifyOutcome.UNKNOWN, JitBootMark.verifyOutcome("", "true"))
154        // СТАЛО: persist пришёл из того же ответа, значит канал ответил — это честный FAILED
157        assertEquals(JitBootMark.VerifyOutcome.FAILED,
158            JitBootMark.verifyOutcome("", "true"))
159    }
```

Новые тесты: `verifyOutcomeEmptyRuntimeWithPersistIsFailed` (`:163-169`, `("","1")` и `("","false")` → `FAILED`) и `verifyOutcomeFailedKeepsRuntimeReason` (`:172-179`, `outcomeReason` → `runtime_not_true` / `runtime_empty` / `no_channel` / `""`).

**Число тестов: 16, а не 15.** Причина: в мандате B2 проверка «счётчик не блокирует новую загрузку» просит вынести логику в чистую функцию и покрыть `@Test`. Я вынес `cycleDecision` в `JitBootMark` и добавил `cycleDecisionSkipsOnlySameBoot` (`:182-192`) — это 16-й тест. Итого `JitBootMarkTest` 16 + `HvacVerdictTest` 6 + `ScriptChunkerTest` 4 = **26 PASSED** (в мандате ожидалось 25 при 15 тестах).

### 3.4 B1 — счётчик цикла, запись **до** сверки

`AppPerformance.kt:955-972`:

```kotlin
955            JitBootMark.PendingDecision.VERIFY -> {
959                val cycleFile = File(app.filesDir, JIT_VERIFY_CYCLE_FILE)
960                val storedCycle = runCatching { cycleFile.readText().trim() }.getOrDefault("")
961                if (JitBootMark.cycleDecision(storedCycle, bootTag) == JitBootMark.CycleDecision.SKIP) {
962                    AppLog.i(TAG, "JIT_VERIFY: skip reason=already_this_boot boot_tag=$bootTag " +
963                        "(цикл сверки в этой загрузке уже был)")
965                    restorePendingIntent(app, claimed)
966                    jitVerifyScheduled.set(false)
967                    return
968                }
969                runCatching { cycleFile.writeText(bootTag) }
970                BgWorker.post("AppPerformance.jitVerifyAfterRestart") {
971                    verifyClaimedAfterRestart(app, claimed, bootTag, 0)
972                }
```

Запись — `:969`, постановка в очередь — `:970`: запись строго выше. Файл — `files/jit_verify_cycle` (`:699`), содержимое — `bootTag`.

### 3.5 B2 — границы: новая загрузка не блокируется

`JitBootMark.kt:152-163`:

```kotlin
152    enum class CycleDecision { RUN, SKIP }
159    fun cycleDecision(stored: String, currentTag: String): CycleDecision = when {
160        stored.isBlank() -> CycleDecision.RUN
161        sameTag(stored, currentTag) -> CycleDecision.SKIP
162        else -> CycleDecision.RUN
163    }
173    fun sameTag(a: String, b: String): Boolean {
174        val x = a.trim()
175        val y = b.trim()
176        if (x.isEmpty() || y.isEmpty()) return false
177        if (x == y) return true
178        val na = stripEpochPrefix(x).toLongOrNull() ?: return false
179        val nb = stripEpochPrefix(y).toLongOrNull() ?: return false
180        return kotlin.math.abs(na - nb) <= (DEFAULT_TOLERANCE_MS / 1000L).coerceAtLeast(1L)
181    }
```

Тест `cycleDecisionSkipsOnlySameBoot` (`JitBootMarkTest.kt:182-192`): пустой файл → `RUN`; тот же `epochTag(t)` → `SKIP`; `epochTag(t + 300_000)` → `RUN`.

Маршруты 1/2 (`bootRestoreJit`) часть B не касается: файл читается только в `checkPendingZygoteVerify`, вызовов `JIT_VERIFY_CYCLE_FILE` вне неё нет (`grep -n JIT_VERIFY_CYCLE_FILE` → `:699`, `:959`). Таймеров не добавлено: единственный `postDelayed` в сверке — уже разрешённый повтор 3 × 10 с.

### 3.6 C1 — сверка ниже инициализации ADB-канала

`VoiceAssistantService.kt:263-270`:

```kotlin
265        // iter33g C1: сначала инициализируем ADB-канал (внутри — AdbAuth.init), и только потом
266        // ставим сверку: иначе первый же `getprop` уходит в неподнятую авторизацию и даёт
267        // ложный пустой ответ. Порядок по-прежнему ВЫШЕ проверки EXTRA_BOOT_RESTORE (:353).
268        runCatching { com.example.voiceapp3.tools.AdbClient.attachContext(this) }
270        runCatching { AppPerformance.checkPendingZygoteVerify(this) }
```

Было: сверка `:266`, `attachContext` `:269` (сверка **выше** канала). Стало: `attachContext` `:268`, сверка `:270`. Требование «сверка на любом старте» сохранено, вызов остался выше проверки extra (`:353`).

### 3.7 C2 — `claim none` один раз за процесс

`AppPerformance.kt:718` + `:1016-1018`:

```kotlin
718    private val saidNoClaim = java.util.concurrent.atomic.AtomicBoolean(false)
1016            if (saidNoClaim.compareAndSet(false, true)) {
1017                AppLog.i(TAG, "JIT_VERIFY: claim none boot_tag=$bootTag")
1018            }
```

Сценарий обычного открытия приложения (`onCreate` + `onStartCommand`, файла-намерения нет): первая строка печатается, вторая — нет. Остальные строки захвата (`claim ok`, `claim ok reason=leftover`, `claim failed`, `claim taken`) печатаются как раньше.

### 3.8 C3 — два вида `JIT_VERIFY` (названы явно, см. §5)

---

## 4. Обязательные проверки

### 4.1 `persist` реально участвует в решении

См. §3.1: цитата функции + вывод `grep -n "persist"` по её телу (4-я строка диапазона — условие ветви).

### 4.2 Старый тест изменён, а не подогнан

См. §3.3: в теле теста сохранён комментарий «БЫЛО / СТАЛО», ожидание `("", "true")` изменилось с `UNKNOWN` на `FAILED`; появились два новых теста, которые закрепляют новые ветви.

### 4.3 Число тестов

```
$ grep -c "@Test" app/src/test/java/com/example/voiceapp3/tools/JitBootMarkTest.kt
16
$ grep -a "PASSED" "11_Gradle build (debug).txt" | wc -l
26
```

16 в классе, 26 суммарно (см. §1 и объяснение в §3.3, почему 16, а не 15).

### 4.4 Счётчик цикла стоит до сверки

См. §3.4: `cycleFile.writeText(bootTag)` — `:969`, `BgWorker.post(...)` — `:970`.

### 4.5 Счётчик не блокирует новую загрузку

Логика вынесена в чистую функцию `JitBootMark.cycleDecision` (§3.5) и покрыта тестом `cycleDecisionSkipsOnlySameBoot`, в том числе случаем «другая загрузка через 5 минут ⇒ RUN».

### 4.6 Вызов сверки выше `EXTRA_BOOT_RESTORE`

```
$ grep -n "checkPendingZygoteVerify\|attachContext\|EXTRA_BOOT_RESTORE" app/src/main/java/com/example/voiceapp3/VoiceAssistantService.kt
112:        const val EXTRA_BOOT_RESTORE = "boot_restore"
267:        // ложный пустой ответ. Порядок по-прежнему ВЫШЕ проверки EXTRA_BOOT_RESTORE (:353).
268:        runCatching { com.example.voiceapp3.tools.AdbClient.attachContext(this) }
270:        runCatching { AppPerformance.checkPendingZygoteVerify(this) }
349:        runCatching { AppPerformance.checkPendingZygoteVerify(this) }
353:        if (intent?.getBooleanExtra(EXTRA_BOOT_RESTORE, false) == true) {
405:        if (intent?.getBooleanExtra(EXTRA_BOOT_RESTORE, false) != true) {
```

Оба вызова (270 и 349) — выше проверки 353; в `onCreate` сверка теперь **после** `attachContext` (268).

### 4.7 `claim none` один раз

См. §3.7. Число вхождений в сценарии обычного старта: `claim none` — 1 (было 2), потому что печать закрыта `AtomicBoolean`; в коде строка одна — `grep -n "claim none"` → `:1017`.

### 4.8 `ctl.restart zygote` по-прежнему один на boot

```
$ grep -n "setprop ctl.restart zygote" app/src/main/java/com/example/voiceapp3/tools/AppPerformance.kt
242:                setprop ctl.restart zygote
869:            "setprop $PROP_USEJIT true; setprop $PROP_USEJIT_PROFILES true; setprop ctl.restart zygote")
```

`:242` — кликовый путь `setAutoOptimize`; `:869` — единственный boot-вызов. Новых `ctl.restart` в этой итерации не добавлено.

### 4.9 Ни одной новой записи в систему

```
$ git diff 578f355 HEAD -- app/src | grep '^+' | grep -nE "setprop|settings put|su |ctl\.restart|mount"
(пусто)
```

Новых записей нет: добавлены только чтение/запись своих файлов в `filesDir` (`jit_verify_cycle`) и `getprop`.

### 4.10 Порядок в дереве

```
$ git ls-tree --name-only HEAD | grep '\.md$'
README.md
$ git ls-tree --name-only HEAD docs/agent/ | wc -l
21
$ git show --stat --oneline 55aefd2 | head -3
55aefd2 iter33g C4: все *.md агента из корня в docs/agent/ (git mv, 21 файл: 19 REPORT_iter*, WORKLOG.md, AUDIT.md); README.md остаётся в корне
 AUDIT.md => docs/agent/AUDIT.md                   | 0
 REPORT_iter21.md => docs/agent/REPORT_iter21.md   | 0
```

Перенос сделан `git mv` (история файлов сохранена, `R`-переименования видны в `git show --stat`); `rm` + `cp` не использовались.

### 4.11 Перенос ничего не сломал

```
$ grep -rn "REPORT_iter\|WORKLOG\.md\|AUDIT\.md" .github scripts tools README.md
(пусто — 0 совпадений)
$ grep -rn "REPORT_iter\|WORKLOG\.md\|AUDIT\.md" docs | wc -l
19
```

В `.github/`, `scripts/`, `tools/`, `README.md` ссылок нет — пути править не пришлось. 19 совпадений внутри `docs/` — это самоцитирование в архивных отчётах (`docs/agent/AUDIT.md:195` упоминает `AUDIT.md`, `WORKLOG.md`; `docs/agent/REPORT_iter30*.md` — свои коммиты). Это текст архива, не пути сборки; содержимое по условию мандата не редактировалось.

### 4.12 `README.md` не тронут

```
$ git diff 578f355 HEAD -- README.md | wc -l
0
```

### 4.13 Ссылки `файл:строка` проверены (`sed -n`, 19 ссылок)

```
JitBootMark.kt:132   | fun verifyOutcome(runtime: String, persist: String): VerifyOutcome = when {
JitBootMark.kt:135   |     persist.isNotEmpty() -> VerifyOutcome.FAILED      // канал ответил (persist прочитан), runtime пуст
JitBootMark.kt:144   | fun outcomeReason(outcome: VerifyOutcome, runtime: String, persist: String): String = when {
JitBootMark.kt:146   |     outcome == VerifyOutcome.FAILED && runtime.isEmpty() -> "runtime_empty"
JitBootMark.kt:152   | enum class CycleDecision { RUN, SKIP }
JitBootMark.kt:159   | fun cycleDecision(stored: String, currentTag: String): CycleDecision = when {
JitBootMark.kt:173   | fun sameTag(a: String, b: String): Boolean {
AppPerformance.kt:699  | private const val JIT_VERIFY_CYCLE_FILE = "jit_verify_cycle"
AppPerformance.kt:959  | val cycleFile = File(app.filesDir, JIT_VERIFY_CYCLE_FILE)
AppPerformance.kt:962  | AppLog.i(TAG, "JIT_VERIFY: skip reason=already_this_boot boot_tag=$bootTag " +
AppPerformance.kt:969  | runCatching { cycleFile.writeText(bootTag) }
AppPerformance.kt:970  | BgWorker.post("AppPerformance.jitVerifyAfterRestart") {
AppPerformance.kt:1016 | if (saidNoClaim.compareAndSet(false, true)) {
AppPerformance.kt:1048 | val reasonWord = JitBootMark.outcomeReason(outcome, runtime, persist)
VAS.kt:268           | runCatching { com.example.voiceapp3.tools.AdbClient.attachContext(this) }
VAS.kt:270           | runCatching { AppPerformance.checkPendingZygoteVerify(this) }
VAS.kt:349           | runCatching { AppPerformance.checkPendingZygoteVerify(this) }
VAS.kt:353           | if (intent?.getBooleanExtra(EXTRA_BOOT_RESTORE, false) == true) {
app/build.gradle.kts:60 | versionCode = 53
app/build.gradle.kts:61 | versionName = "1.0.53"
```

Команда (по одной ссылке, остальные так же):

```
$ sed -n "969p" app/src/main/java/com/example/voiceapp3/tools/AppPerformance.kt
                runCatching { cycleFile.writeText(bootTag) }
```

### 4.14 Три размера раздельно

§1: zip / `apk/debug/app-debug.apk` / `logs/…` — три числа + команда `unzip -l | awk`, без суммирования.

### 4.15 Версия не поднята

```
$ sed -n '60,61p' app/build.gradle.kts
        versionCode = 53
        versionName = "1.0.53"
```

### 4.16 Тесты реально идут

§1: `> Task :app:testDebugUnitTest` + 26 `PASSED`, 0 `FAILED`, включая имена `verifyOutcomeOk`, `verifyOutcomeFailed`, `verifyOutcomeUnknownWhenEmpty`, `verifyOutcomeEmptyRuntimeWithPersistIsFailed`, `verifyOutcomeFailedKeepsRuntimeReason`, `cycleDecisionSkipsOnlySameBoot`.

---

## 5. Что владелец увидит: два вида `JIT_VERIFY` (C3)

В коде живут **две** сверки, и в логе они различимы только по `source=`:

| строка | когда появляется | формат |
|---|---|---|
| **без** `source=` | процесс **выжил** после `ctl.restart zygote`, сработала отложенная сверка через 20 с (`AppPerformance.kt:881-883`) | `JIT_VERIFY: runtime_after=$rt persist=$ps zygote_pid_before=… zygote_pid_after=… result=OK` |
| **с** `source=after_restart` | процесс **умер** и поднялся заново (`START_STICKY`), сработала сверка A2 (`AppPerformance.kt:1050-1051`) | `JIT_VERIFY: runtime_after=… persist=… source=after_restart result=… reason=… boot_tag=… attempt=…` |

Норма: на один рестарт zygote видна **вторая** строка (процесс — потомок zygote, он не выживает). Первая строка — только если система пощадила процесс; тогда в логе будут обе, и это не дубль, а два разных пути, у каждого свой маркер (`source=` и `zygote_pid_*`).

---

## 6. Честные пробелы

1. **Цена `ctl.restart zygote`** (секунды чёрного экрана / переподключение CarPlay) не измерена.
2. **Читается ли `boot_id` на прошивке Geely** — не проверено; при отсутствии работает `epoch`-тег с допуском ±60 с (покрыто тестами, на железе не наблюдалось).
3. **Срабатывает ли маршрут 1** (`restored_from_persist`) — не подтверждено.
4. **Фактический `exec/min`** после правок не замерялся.
5. **Порядок `onCreate` → `onStartCommand` на ГУ не наблюдался** — одиночность сверки опирается на атомарность `renameTo` + флаг `jitVerifyScheduled` + (новое) счётчик `jit_verify_cycle`; порядок жизненного цикла на устройстве не подтверждён.
6. **`verifyOutcome` проверен только юнит-тестом**: на железе ни `FAILED reason=runtime_not_true`, ни `FAILED reason=runtime_empty` ещё не встречались — обе ветви пока теоретические.
7. **C4 закрывает только `*.md` из корня.** Полный список перенесённого: 19 × `REPORT_iter*.md`, `WORKLOG.md`, `AUDIT.md` = 21 файл. Если владелец найдёт в корне ещё что-то «агентское» — это не `*.md`; из файлов такого рода в корне остаются только служебные (`check-*.sh`, `diag-shareduid-crash.sh`, `HvacDigital-platform-signed.apk`, `app-platform-signed.apk.idsig`) и они не переносились.
8. **Ветка `UNKNOWN` при живом канале теперь недостижима** — это и было целью A1; но обратная проверка («канал действительно молчит ⇒ ровно `UNKNOWN`») на железе не выполнена.

---

## 7. Приёмка владельцем (§G)

После установки 1.0.53 и **двух** перезагрузок подряд (файл на устройстве — `voiceapp3.log`):

```
grep -a "JIT_BOOT: zygote_restart intent=pending" app.log   # есть ДО рестарта
grep -ac "source=after_restart" app.log                     # 1 на загрузку (в сценарии UNKNOWN — тоже 1)
grep -a "result=FAILED reason=runtime_empty" app.log        # «прошивка не даёт» названо своим именем
grep -a "result=FAILED reason=runtime_not_true" app.log     # «свойство читается, но не true»
grep -ac "reason=no_channel" app.log                        # только если канал реально молчит
grep -a "reason=already_this_boot" app.log                  # повторный цикл в этой загрузке заблокирован
grep -a "JIT_VERIFY: claim" app.log                          # один claim ok; claim none — не больше одного за старт
grep -ac "JIT_PROBE: runtime=true" app.log                  # ровно 1 за загрузку
grep -ac "setprop ctl.restart zygote" app.log               # не больше одного на загрузку
grep -aE "persist_before=|persist_after=" app.log           # дублёр унаследован или дозаписан
grep -aE "HVAC VERDICT:|VERIFY hvacdigital.enable:" app.log # VIA=bind / OK, не PARTIAL
```

Плюс один раз глазами по клону: **корень чистый** — в нём нет `REPORT_*.md`, `WORKLOG.md`, `AUDIT.md`; все они в `docs/agent/`.

Главный критерий: вердикт по JIT отвечает тремя разными словами — «поднялся» (`result=OK`), «не поднялся» (`FAILED reason=runtime_not_true|runtime_empty`), «канала нет» (`UNKNOWN reason=no_channel`); и на одну загрузку приходится один цикл сверки (`reason=already_this_boot` на повторных стартах).
