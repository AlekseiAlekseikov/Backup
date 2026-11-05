# REPORT iter33d — 1.0.53 (третья пересборка, версия та же)

**Ветка:** `fix/v2` · **Версия не поднята:** `versionCode = 53` (`app/build.gradle.kts:60`), `versionName = "1.0.53"` (`app/build.gradle.kts:61`)
**База:** `bf8ceb2` (код на `29f18ec`) · **Коммит этой итерации:** `1bde92f`
**Мандат:** `uploads/PROMPT_iter33d_1.0.53_fix3.md`. `main` не пушился, `--force` не применялся, workflow не правился, разрешения и `targetSdk` не менялись.

---

## F1. Чек-лист

| пункт | статус | файл:строка | доказательство |
|---|---|---|---|
| **A1** диагноз: метка не различает загрузки | ✅ | было — `AppPerformance.kt` на `29f18ec` (арифметика по метке и uptime); сейчас удалено | арифметики больше нет: `grep -n "JIT_ATTEMPT_GUARD_MS\|up - prev"` = **0** вхождений (константа `JIT_ATTEMPT_GUARD_MS` тоже удалена). Диагноз подтверждён таблицей «было/стало» ниже |
| **A2** `bootTag` + формат `<tag>:<uptimeMs>`, fallback `bootEpoch`, допуск | ✅ | `JitBootMark.kt:32` (`format`), `:44` (`sameLoad`), `AppPerformance.kt:877` (`currentBootTag`) | `"/proc/sys/kernel/random/boot_id"` → иначе `"epoch" + (currentTimeMillis - elapsedRealtime)/1000`; для числовых тегов `abs(a-b) <= 60_000` мс |
| **A2.2** одна функция опознания | ✅ | `AppPerformance.kt:730-733` (одна проверка `sameLoad`), `:807` (guard маршрута 2), `:866` (`attemptInThisLoad`) | `grep -rn "attemptInThisLoad\|sameLoad"` → единственная реализация в `JitBootMark.kt`, два вызова в `AppPerformance` |
| **A2.3** legacy-метка | ✅ | `AppPerformance.kt:737-741` | `isLegacy` ⇒ `sameLoad == true` (консервативно) + перезапись в новый формат + `JIT_BOOT: attempt_marker legacy=true action=rewrite …` |
| **A3** строка `attempt_marker` | ✅ | `AppPerformance.kt:733` | `JIT_BOOT: attempt_marker boot_tag=<id\|epoch…> same_load=<true\|false> uptime_ms=<n> marker=<old\|empty>` — печатается до всех ветвей |
| **A4** `JitBootMark` + тесты | ✅ | `JitBootMark.kt` (4 функции), `app/src/test/.../JitBootMarkTest.kt` (6 `@Test`) | в CI: `JitBootMarkTest > differentBootDoesNotBlock PASSED` … всего **16 PASSED / 0 FAILED** |
| **B** хвост выжимки не на главном потоке | ✅ | `LogKit.kt:136` (`BgWorker.postDelayed`), `BgWorker.kt:39` (планировщик), `:92` (`postDelayed`) | `grep -c mainHandler LogKit.kt` = **1**, и это строка комментария (`LogKit.kt:134`); сам `Handler` удалён |
| **C** `persist_before` / `backfill` | ✅ | `AppPerformance.kt:750-761` (`persistBefore`, `probeCommon`) | все четыре ветки пробы печатают `persist_before=… persist_present=… backfill=…` (`:772`, `:777`, `:793`, `:796`) |
| **D** дисциплина отчёта | ✅ | этот файл | три размера раздельно (§CI), ссылки проверены `sed -n` (§Ссылки), счётные утверждения с `grep` (§Проверки), оговорка про `persist_present` (§C) |

---

## A1 — что именно было сломано и что удалено

Старый признак «попытка была в этой загрузке» — `prev in 1..up`, где `prev` — uptime прошлой попытки.
`elapsedRealtime()` сбрасывается при ребуте, поэтому метка прошлой загрузки (всегда ≤ 120 с) может быть
**меньше** текущего uptime:

| ситуация | prev | up | было | стало |
|---|---|---|---|---|
| попытка в прошлой загрузке на 40 с; сейчас второй проход на 55 с | 40 000 | 55 000 | **SKIP — JIT не восстановится** | попытка разрешена (`same_load=false`) |
| попытка в прошлой загрузке на 40 с; сейчас проход на 35 с | 40 000 | 35 000 | попытка разрешена | попытка разрешена (`same_load=false`) |
| повтор в ТОЙ ЖЕ загрузке (ради чего guard и делался) | 40 000 | 45 000 | SKIP | SKIP (`same_load=true`) |

Плюс третье следствие: `restored_from_persist` печатался ложно, потому что `attemptInThisLoad`
опирался на тот же сломанный признак. Теперь обе точки используют одну функцию.

**Удалено:** константа `JIT_ATTEMPT_GUARD_MS`, условие с разницей uptime, вторая копия эвристики в
`attemptInThisLoad`.

---

## Цитаты кода после правки

### A2/A3 — опознание загрузки (`JitBootMark.kt:32, 44`)

```kotlin
fun format(tag: String, uptimeMs: Long): String = "$tag:$uptimeMs"

fun sameLoad(marker: String, currentTag: String, toleranceMs: Long = DEFAULT_TOLERANCE_MS): Boolean {
    …
    if (sep < 0) return m.toLongOrNull() != null        // legacy: число без ':' ⇒ «эта загрузка»
    if (tag == cur) return true
    val a = tag.toLongOrNull() ?: return false
    val b = cur.toLongOrNull() ?: return false
    return kotlin.math.abs(a - b) <= toleranceMs        // epoch-теги: ±60 с
}
```

### A2 — источник `bootTag` (`AppPerformance.kt:877-882`)

```kotlin
private fun currentBootTag(): String {
    val id = runCatching { File("/proc/sys/kernel/random/boot_id").readText().trim() }.getOrDefault("")
    if (id.isNotEmpty()) return id
    val epochSec = (System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()) / 1000L
    return "epoch$epochSec"
}
```

### A3 — строка на каждом проходе (`AppPerformance.kt:733-734`)

```kotlin
AppLog.i(TAG, "JIT_BOOT: attempt_marker boot_tag=$bootTag same_load=$sameLoad uptime_ms=$upNow " +
    "marker=${LogKit.kv(markerRaw.ifEmpty { "empty" })}")
```

### A1/A2 — guard маршрута 2 (`AppPerformance.kt:807-811`)

```kotlin
if (sameLoad) {
    AppLog.i(TAG, "JIT_BOOT: skip reason=attempt_guard same_load=true " +
        "marker_uptime_ms=${JitBootMark.uptimeOf(markerRaw)} now_ms=$up")
    return
}
```

### A2 — запись метки (`AppPerformance.kt:817`)

```kotlin
runCatching { markerFile.writeText(JitBootMark.format(bootTag, up)) }
```

### B — хвост выжимки на фоне (`LogKit.kt:136-139`)

```kotlin
BgWorker.postDelayed("LogKit.tailFlush", OPS_FLUSH_MIN_MS) {
    tailFlushScheduled = false
    flushOps(force = true)
}
```
`BgWorker.kt:92-93` — планировщик отдаёт работу в тот же пул `CENTRAL_Bg-N`:
```kotlin
fun postDelayed(name: String, delayMs: Long, block: () -> Unit) {
    scheduler.schedule({ post(name, block) }, delayMs, TimeUnit.MILLISECONDS)
}
```
**Цена:** одновременно висит **максимум одна** задача (`tailFlushScheduled`), задержка 2 с; потоки —
`CENTRAL_BgSched` (пустой, только отсчёт) и обычный воркер пула (сама запись). Главный поток не участвует.

### C — снимок дублёра до дозаписи (`AppPerformance.kt:750-761`)

```kotlin
val persistBefore = persist
var backfilled = false
if (persist != desired) { … jitPersistBackfill(desired, reason); persist = jitProps().second; backfilled = true }
val persistPresent = persistBefore.isNotEmpty()
val probeCommon = "persist_before=$persistBeforeWire persist_present=$persistPresent backfill=$backfillWire " +
    "runtime=$runtime persist=$persist"
```
Ветки, где появляется `probeCommon`: `:772` (match с попыткой zygote), `:777` (match из persist),
`:793` (первый проход, `read_again_in_30s`), `:796` (`runtime_not_raised_from_persist`).

---

## Проверки (часть G)

| проверка | команда | результат |
|---|---|---|
| арифметики по uptime нет | `grep -n "JIT_ATTEMPT_GUARD_MS\|up - prev" tools/AppPerformance.kt` | **0** |
| одна функция опознания | `grep -rn "fun sameLoad\|attemptInThisLoad\|sameLoad" tools/*.kt` | `JitBootMark.kt:44` (реализация); `AppPerformance.kt:733, 807, 866` (использование) |
| `attempt_marker` печатается | `grep -n "attempt_marker" tools/AppPerformance.kt` | ровно 2 строки: `:733` (основная) и `:739` (legacy-перезапись) |
| `JitBootMarkTest` идёт в CI | лог джобы `127230879289` | 6 строк `JitBootMarkTest > … PASSED`, суммарно **16 PASSED / 0 FAILED** |
| разные загрузки не блокируют | `JitBootMarkTest > differentBootDoesNotBlock PASSED` | см. таблицу «было/стало» выше |
| хвост не на главном потоке | `grep -c mainHandler tools/LogKit.kt` | **1** — только строка комментария `:134`; `Handler`/`Looper` в файле отсутствуют |
| `persist_before` в пробе | `grep -n "persist_before" tools/AppPerformance.kt` / `grep -n "probeCommon" …` | `persist_before=` формируется в `:761`; `probeCommon` печатается в 4 ветках: `:772`, `:777`, `:793`, `:796` |
| `ctl.restart zygote` | `grep -n "ctl.restart zygote" tools/AppPerformance.kt` | на boot — **один** (`:824`, маршрут 2); ещё два — только в ручном клике (`:242` в скрипте, `:301` в тексте шага); `:213`, `:713` — комментарии |
| версия не поднята | `sed -n '60,61p' app/build.gradle.kts` | `versionCode = 53` / `versionName = "1.0.53"` |
| тесты реально идут | лог джобы | `> Task :app:testDebugUnitTest` + 16 `PASSED` (не `UP-TO-DATE`/`NO-SOURCE`) |

### Ссылки `файл:строка` — проверены `sed -n`

```
JitBootMark.kt:32     → fun format(tag: String, uptimeMs: Long): String = "$tag:$uptimeMs"
JitBootMark.kt:44     → fun sameLoad(marker: String, currentTag: String, toleranceMs: Long = DEFAULT_TOLERANCE_MS): Boolean {
AppPerformance.kt:733 → AppLog.i(TAG, "JIT_BOOT: attempt_marker boot_tag=$bootTag same_load=$sameLoad uptime_ms=$upNow " +
AppPerformance.kt:761 → val probeCommon = "persist_before=$persistBeforeWire persist_present=$persistPresent backfill=$backfillWire " +
AppPerformance.kt:807 → if (sameLoad) {
AppPerformance.kt:817 → runCatching { markerFile.writeText(JitBootMark.format(bootTag, up)) }
AppPerformance.kt:824 → "setprop $PROP_USEJIT true; setprop $PROP_USEJIT_PROFILES true; setprop ctl.restart zygote")
AppPerformance.kt:877 → private fun currentBootTag(): String {
LogKit.kt:136         → BgWorker.postDelayed("LogKit.tailFlush", OPS_FLUSH_MIN_MS) {
BgWorker.kt:92        → fun postDelayed(name: String, delayMs: Long, block: () -> Unit) {
build.gradle.kts:60   → versionCode = 53
build.gradle.kts:61   → versionName = "1.0.53"
```

---

## CI

| что | значение |
|---|---|
| прогон | **42023172917** (`push`, `1bde92f`) — **success** |
| итог | `BUILD SUCCESSFUL in 2m 42s` |
| тесты | **16 PASSED / 0 FAILED** (`JitBootMarkTest` 6, `HvacVerdictTest` 6, `ScriptChunkerTest` 4) |
| артефакт | **13412982735** (`app-debug`) |
| `zip` | **177 081 062 Б** |
| `apk/debug/app-debug.apk` | **215 418 521 Б** |
| `logs/manifest-merger-debug-report.txt` | **105 294 Б** |

Числа получены так (раздельно, без суммирования):
```
$ curl -sL …/artifacts/13412982735/zip -o art.zip && ls -l art.zip   → 177081062
$ unzip -l art.zip | awk 'NR>3 && NF>=4 {print $1, $4}'
215418521 apk/debug/app-debug.apk
105294 logs/manifest-merger-debug-report.txt
```

Прошлый прогон для сравнения: `42020153399` — `zip` 177 080 524 Б, `apk` **215 414 101 Б** (в
`REPORT_iter33c.md` я ошибочно указал 215 519 395 Б — это была сумма APK и лога; впредь три числа
раздельно). Прирост: `zip` +538 Б, `apk` +4 420 Б.

---

## Честные пробелы

1. **На ГУ не проверялось.** Всё подтверждено кодом + 16 тестами + зелёной сборкой.
2. **Читается ли `/proc/sys/kernel/random/boot_id` на прошивке Geely — НЕ ДОКАЗАНО.** Если нет,
   работает штатный fallback `epoch…` с допуском ±60 с; в логе это видно по виду `boot_tag`.
3. **Срабатывает ли маршрут 1 (persist) — НЕ ДОКАЗАНО.** Теперь проба честно печатает
   `persist_before` / `persist_present` / `backfill`, так что ответ будет однозначным.
4. **Цена `ctl.restart zygote`** (секунды чёрного экрана и переподключения CarPlay) — не измерена;
   поля для расчёта есть в `JIT_VERIFY` (`zygote_pid_before/after`).
5. **Фактический `exec/min` в покое и объём лога ≤150 КБ/10 мин** — ожидаемые, не замеренные.
6. **Известное ограничение (по мандату, не менял):** если сервис стартует позже ~90-й секунды
   uptime, второй проход попадает в `JIT_BOOT: deferred reason=outside_window`, и JIT в эту загрузку
   не поднимется — это будет **видно в логе** и лечится следующей перезагрузкой.
7. **Определённое поведение для мусорных меток** (`JitBootMarkTest.emptyOrGarbageIsNotSameLoad`):
   нет тега или тег не разбирается ⇒ `same_load=false` (попытка разрешена); тег совпал, а uptime в
   метке мусорный ⇒ `true` — для опознания загрузки uptime не нужен, он только для диагностики.
8. **`grep mainHandler` = 1** — это комментарий, объясняющий правку; кода с `Handler` в `LogKit` нет.

---

## Что владелец увидит в логе (часть H)

```
JIT_BOOT: attempt_marker boot_tag=8a1f…c3 same_load=false uptime_ms=9600 marker=empty
JIT_PERSIST_SET desired=true readback=true reason=boot_backfill
JIT_PERSIST_PROBE: supported=unknown persist_before=empty persist_present=false backfill=yes runtime=false persist=true action=read_again_in_30s
JIT_PERSIST_PROBE: supported=true persist_before=empty persist_present=false backfill=no runtime=true persist=true reason=runtime_raised_from_persist
JIT_BOOT: restored_from_persist prop=persist.sys.dalvik.vm.usejit runtime=true persist=true (zygote не перезапускали)

# следующая загрузка — boot_tag ДРУГОЙ, блокировки из прошлой загрузки нет:
JIT_BOOT: attempt_marker boot_tag=9b2e…77 same_load=false uptime_ms=9200 marker=8a1f…c3:41230
```
Если persist-маршрут не сработает на этой прошивке (второй проход):
```
JIT_PERSIST_PROBE: supported=false persist_before=empty persist_present=false backfill=no runtime=false persist=true reason=runtime_not_raised_from_persist
JIT_BOOT: pref=on desired=true runtime_before=false action=setprop+zygote uptime_ms=40000 marker=jit_boot_attempt rc=0
JIT_VERIFY: runtime_after=true persist=true zygote_pid_before=812 zygote_pid_after=1904 result=OK
JIT_BOOT: match_after_zygote runtime=true persist=true (поднят перезапуском zygote в этой загрузке)
```
Проверка защиты от повторов — на **второй** перезагрузке подряд:
`grep "reason=attempt_guard"` не должен показывать skip, оставшийся от первой загрузки.
