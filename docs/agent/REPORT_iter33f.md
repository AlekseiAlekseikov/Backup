# REPORT_iter33f — 1.0.53, пятая пересборка (`fix5`)

Дата: 2026-11-05 · ветка `fix/v2` · история не переписана, `--force` не применялся, `main` не тронут.

| что | значение |
|---|---|
| база | `9d36e69` (кончик `fix/v2` до итерации; код был на `4ff7ead`) |
| коммиты итерации | `a1f4333` (A1/A2/A3/B1/B2/B3), `578f355` (A2: отдельная строка `claim none`) |
| HEAD кода (= head прогона CI) | `578f355` |
| версия | `versionCode = 53`, `versionName = "1.0.53"` — `app/build.gradle.kts:60-61` |
| изменено | `git diff --stat 9d36e69 HEAD` — см. §5 |

---

## 1. CI

| что | значение |
|---|---|
| прогон | **`42035283163`** (`fix/v2`, head `578f355`), `conclusion = success` |
| джоба | **`127271703677`** (шаг `Gradle build (debug)` — success) |
| сборка | `BUILD SUCCESSFUL in 4m 17s` |
| тесты | `> Task :app:testDebugUnitTest` + **23 PASSED / 0 FAILED** (JitBootMarkTest 13, HvacVerdictTest 6, ScriptChunkerTest 4) |
| артефакт | **`13419106325`** (`app-debug`) |
| размеры *раздельно* | zip **177085868 Б** · `apk/debug/app-debug.apk` **215429529 Б** · `logs/manifest-merger-debug-report.txt` **105294 Б** |

Команды (вывод включён, ничего не суммируется):

```
$ grep -a "BUILD SUCCESSFUL" "11_Gradle build (debug).txt"
2026-11-05T13:35:14.3348441Z BUILD SUCCESSFUL in 4m 17s
$ grep -ac "PASSED" "11_Gradle build (debug).txt"
23
$ grep -a "PASSED" "11_Gradle build (debug).txt" | grep -ac "JitBootMarkTest"
13
$ grep -a "PASSED" "11_Gradle build (debug).txt" | grep -ac "HvacVerdictTest"
6
$ grep -a "PASSED" "11_Gradle build (debug).txt" | grep -ac "ScriptChunkerTest"
4
$ grep -ac "FAILED" "11_Gradle build (debug).txt"
0
$ grep -a "> Task" "11_Gradle build (debug).txt" | grep testDebugUnitTest
2026-11-05T13:35:13.5345065Z > Task :app:testDebugUnitTest
$ ls -l art.zip | awk '{print "zip_B="$5}'
zip_B=177085868
$ unzip -l art.zip | awk '/app-debug.apk/ {print "apk_B="$1} /manifest-merger-debug-report/ {print "logs_B="$1}'
apk_B=215429529
logs_B=105294
```

Промежуточный прогон этой итерации (для трассировки): `42033400534` (head `a1f4333`, джоба `127265244564`) — `conclusion = success`, то есть структура A1/B1/B2 компилируется и тесты идут; итоговые числа выше — из финального прогона.

---

## 2. Сводная таблица

| пункт | статус | файл:строка | доказательство (кратко) |
|---|---|---|---|
| A1.1 захват синхронно, до `BgWorker.post` | ✅ | `AppPerformance.kt:931-950` | `claimPendingIntent` вызывается на `:933`, `BgWorker.post` — на `:947` (позже) |
| A1.2 захват — `renameTo`, не чтение | ✅ | `AppPerformance.kt:974` | `src.renameTo(claimed)`, `claimed = JIT_PENDING_FILE + ".processing"` (`:696`, `:966`) |
| A1.2b `renameTo=false` не теряет намерение | ✅ | `AppPerformance.kt:975-980` | `claim failed reason=rename` при живом `src` |
| A1.3 `AtomicBoolean verifyScheduled` | ✅ | `AppPerformance.kt:710`, `:968-971`, `:993` | флаг ставится на захвате, второй вход видит `claim taken reason=already_scheduled` |
| A1.4 решение по захваченному файлу | ✅ | `AppPerformance.kt:935-936` | `claimed.readText()` → `pendingDecision(...)` |
| A1.5 `STALE` удаляет `.processing` | ✅ | `AppPerformance.kt:941-946` | `claimed.delete()` + `reason=pending_stale` |
| A1.6 `VERIFY` получает путь `claimed`; удаление в обеих ветках | ✅ | `AppPerformance.kt:1017-1039` | `claimed.delete()` в `OK` (`:1033`) и в `FAILED` (`:1037`) |
| A1.7 лог захвата | ✅ | `AppPerformance.kt:970/979/987/992/997` | `claim ok` / `claim ok reason=leftover` / `claim taken` / `claim failed` / `claim none` |
| A2 таблица «строк на рестарт» | ✅ | §3.2 | 1 `claim ok` + 1 `claim taken` + 1 `source=after_restart` + ≤1 `match_after_zygote` |
| A3 `VerifyOutcome` + 3 теста | ✅ | `JitBootMark.kt:122-128`; `JitBootMarkTest.kt:130/139/148` | 13 `@Test` в классе, все `PASSED` |
| B1 три исхода вместо двух | ✅ | `AppPerformance.kt:1017-1026` | `result=${outcome.name}` + `reason=`; `runtime_after=empty` вместо пустоты |
| B2 рамки повторов | ✅ | `AppPerformance.kt:698-699`, `:1040-1050` | ≤3 × 10 с, в повторе только `jitProps()`; намерение возвращается на место |
| B3 пустые значения как `empty` | ✅ | `AppPerformance.kt:717`, `:1025` | `kvE(...)`: `runtime_after=empty persist=empty` |
| C1 `flushOpsNow` ≠ `flush app.log` | ✅ | §4.1 | формулировка исправлена: `app.ops.log` (`LogKit.kt:143`), `app.log` — `AppLog.writeRaw` |
| C2 `JIT_PROBE: runtime=true` — раз за загрузку | ✅ | §4.2 | доказательство рестарта — `source=after_restart`, не `JIT_PROBE` |
| C3 `grep -c persist_present` = 3 (комментарии) | ✅ | §4.3 | вывод команды приведён: 3 совпадения, все — комментарии |
| C4 каждая `grep -c` — с выводом | ✅ | весь §4 | рядом с каждой командой напечатан результат |

---

## 3. Цитаты кода после правок

### 3.1 A1 — захват намерения выше постановки в очередь

Порядок (номера строк): `claimPendingIntent` — `:933`, `BgWorker.post` — `:947`.

```kotlin
931    fun checkPendingZygoteVerify(context: Context) {
932        val app = context.applicationContext
933        val claimed = claimPendingIntent(app) ?: return
934        val bootTag = currentBootTag()
935        val pending = runCatching { claimed.readText().trim() }.getOrDefault("")
936        when (JitBootMark.pendingDecision(pending, bootTag)) {
```

Сам захват — `AppPerformance.kt:964-999`:

```kotlin
964        private fun claimPendingIntent(app: Context): File? {
965            val src = File(app.filesDir, JIT_PENDING_FILE)
966            val claimed = File(app.filesDir, JIT_PENDING_FILE + PROCESSING_SUFFIX)
967            val bootTag = currentBootTag()
968            // 1. Второй вход (onStartCommand сразу после onCreate): сверка уже забрана этим процессом.
969            if (jitVerifyScheduled.get()) {
970                AppLog.i(TAG, "JIT_VERIFY: claim taken reason=already_scheduled boot_tag=$bootTag")
971                return null
972            }
973            // 2. Захват: переименование, а не чтение.
974            val renamed = runCatching { src.renameTo(claimed) }.getOrDefault(false)
975            if (!renamed) {
976                if (src.exists()) {
977                    // renameTo отказал, а намерение на месте — не теряем его молча.
978                    val left = runCatching { src.readText().trim() }.getOrDefault("")
979                    AppLog.i(TAG, "JIT_VERIFY: claim failed reason=rename pending=${kvE(left)} boot_tag=$bootTag")
980                    return null
981                }
982                if (claimed.exists()) {
983                    // Остаток прерванной сверки (процесс умер между захватом и проверкой):
984                    // забираем его, иначе `.processing` висел бы вечно.
985                    jitVerifyScheduled.set(true)
986                    val left = runCatching { claimed.readText().trim() }.getOrDefault("")
987                    AppLog.i(TAG, "JIT_VERIFY: claim ok reason=leftover boot_tag=$bootTag pending=${kvE(left)}")
988                    return claimed
989                }
990                // Захватывать нечего: файла-намерения нет вовсе (обычный старт из приложения).
991                // Это НЕ `claim taken` — «забран другим вызовом» и «нечего забирать» разные факты.
992                AppLog.i(TAG, "JIT_VERIFY: claim none boot_tag=$bootTag")
993                return null
994            }
995            jitVerifyScheduled.set(true)
996            val pending = runCatching { claimed.readText().trim() }.getOrDefault("")
997            AppLog.i(TAG, "JIT_VERIFY: claim ok boot_tag=$bootTag pending=${kvE(pending)}")
998            return claimed
```

Флаг `jitVerifyScheduled` — `AppPerformance.kt:710`; суффикс `.processing` — `:696`; рамки повторов — `:698-699`.

### 3.2 A2 — сколько строк на один рестарт (таблица сценариев старта)

| сценарий старта сервиса | `JIT_VERIFY: claim …` | `source=after_restart` | `match_after_zygote` |
|---|---|---|---|
| обычный старт из приложения, файла-намерения нет | `claim none` на `onCreate`, `claim none` на `onStartCommand` (забирать нечего, флаг не ставится) | 0 | 0 |
| `START_STICKY`-рестарт после нашего `ctl.restart zygote` (намерение этой загрузки) | `claim ok` (`onCreate`) + `claim taken reason=already_scheduled` (`onStartCommand`) | **1** | **≤ 1** |
| boot-путь после перезагрузки устройства, лежит намерение ПРОШЛОЙ загрузки | `claim ok` → `STALE` → `skip reason=pending_stale`; затем на `onStartCommand` уже `claim none` | 0 | 0 |
| остался `.processing` от убитого процесса | `claim ok reason=leftover`, дальше по содержимому (STALE/VERIFY) | 0 или 1 | ≤ 1 |

Почему ровно одна сверка: `renameTo` физически переносит имя файла, поэтому второй вход уже не видит `jit_pending_verify`; дополнительно флаг `jitVerifyScheduled` ставится **в момент захвата** и снимается только после удаления/возврата файла — то есть повторная сверка в том же процессе невозможна, пока файла нет. Строка `claim taken reason=already_scheduled` — это **не вторая сверка**, а её доказательство-наоборот: второй вход увидел занятый флаг.

**Важная оговорка (честно):** если канал не ответил и сработал сценарий `UNKNOWN` (B2), намерение по правилу мандата **не сжигается** и возвращается на место, флаг снимается. Тогда более **поздний** старт сервиса (не `onStartCommand` этого же рестарта, а, например, повторный запуск приложения) снова его заберёт — это задумано («доказательство заберёт следующий старт сервиса»). То есть «ровно 1 на рестарт» — про нормальный путь `OK`/`FAILED`; в сценарии `UNKNOWN` каждая новая попытка печатает свой `attempt=N`, и это единственный случай, когда `source=after_restart` может появиться дважды за загрузку.

### 3.3 A3 — `VerifyOutcome` и три исхода

`JitBootMark.kt:122-128`:

```kotlin
122    enum class VerifyOutcome { OK, FAILED, UNKNOWN }
124    fun verifyOutcome(runtime: String, persist: String): VerifyOutcome = when {
125        runtime == "true" -> VerifyOutcome.OK
126        runtime.isEmpty() -> VerifyOutcome.UNKNOWN
127        else -> VerifyOutcome.FAILED
128    }
```

Тесты (`JitBootMarkTest.kt`): `verifyOutcomeOk` — `:130`, `verifyOutcomeFailed` — `:139`, `verifyOutcomeUnknownWhenEmpty` — `:148`. Всего в классе **13** `@Test` (`grep -c "@Test"` → `13`), как и требовалось.

### 3.4 B1/B2/B3 — три исхода, рамки повторов, `empty`

`AppPerformance.kt:1017-1049`:

```kotlin
1017    private fun verifyClaimedAfterRestart(app: Context, claimed: File, bootTag: String, attempt: Int) {
1018        val (runtime, persist) = jitProps()   // единственное действие повтора: чтение getprop
1019        val outcome = JitBootMark.verifyOutcome(runtime, persist)
1020        val reason = when (outcome) {
1021            JitBootMark.VerifyOutcome.OK -> ""
1022            JitBootMark.VerifyOutcome.FAILED -> " reason=runtime_not_true"
1023            JitBootMark.VerifyOutcome.UNKNOWN -> " reason=no_channel"
1024        }
1025        AppLog.i(TAG, "JIT_VERIFY: runtime_after=${kvE(runtime)} persist=${kvE(persist)} source=after_restart " +
1026            "result=${outcome.name}$reason boot_tag=$bootTag attempt=${attempt + 1}")
```

```kotlin
1036            JitBootMark.VerifyOutcome.FAILED -> {
1037                runCatching { claimed.delete() }
1038                jitVerifyScheduled.set(false)
1039            }
1040            JitBootMark.VerifyOutcome.UNKNOWN -> {
1041                if (attempt + 1 >= JIT_VERIFY_RETRY_MAX) {
1042                    AppLog.i(TAG, "JIT_VERIFY: result=UNKNOWN retries=$JIT_VERIFY_RETRY_MAX reason=no_channel " +
1043                        "boot_tag=$bootTag (намерение оставлено следующему старту)")
1044                    restorePendingIntent(app, claimed)
1045                    jitVerifyScheduled.set(false)
1046                } else {
1047                    BgWorker.postDelayed("AppPerformance.jitVerifyRetry", JIT_VERIFY_RETRY_DELAY_MS) {
1048                        verifyClaimedAfterRestart(app, claimed, bootTag, attempt + 1)
1049                    }
1050                }
1051            }
```

Пустые значения (`B3`) — `AppPerformance.kt:717`:

```kotlin
717    private fun kvE(s: String): String = if (s.isEmpty()) "empty" else LogKit.kv(s)
```

`LogKit.kv("")` даёт `-`, поэтому голую пустоту он не закрывает — отсюда отдельный хелпер; строка `:1025` печатает `runtime_after=empty persist=empty`.

---

## 4. Уточнения части C (кода не касаются)

### 4.1 C1 — `flushOpsNow` ≠ `flush app.log`

Прошлая формулировка («`LogKit.flushOpsNow()` гарантирует строку на диске») смешивала два файла. Правильно:

* `LogKit.flushOpsNow()` выжимает **операционный** журнал `app.ops.log` (`LogKit.kt:143` — `fun flushOpsNow()` рядом с `opsLog`);
* запись в `app.log` синхронна сама по себе: `AppLog.i` → `enqueueOrWrite` → `writeRaw`, а `writeRaw` открывает `PrintWriter(FileWriter(target, true))` и делает `out.flush()` под `synchronized(lock)` (`AppLog.kt:278-283`);
* вывод не меняется (строка-намерение лежит на диске до вызова), меняется только объяснение: за `app.log` отвечает `AppLog.writeRaw`, за `app.ops.log` — `LogKit.flushOpsNow`.

### 4.2 C2 — `JIT_PROBE: runtime=true` один раз **за загрузку**

`AppPerformance.kt:1053-1066` (`verifyJitOnly`): флаг — `files/jit_verified`, сравнивается с `currentBootTag()`, поэтому строка печатается один раз на загрузку, а **не** после каждого рестарта zygote. В приёмке §G это сказано явно; доказательство именно рестарта — `JIT_VERIFY … source=after_restart`, а не `JIT_PROBE`.

### 4.3 C3 — `grep -c persist_present` = 3, все три — комментарии

```
$ grep -c "persist_present" app/src/main/java/com/example/voiceapp3/tools/AppPerformance.kt
3
$ grep -n "persist_present" app/src/main/java/com/example/voiceapp3/tools/AppPerformance.kt
774:        // iter33d C: снимок ДО дозаписи. Раньше `persist_present` считался после неё и означал
784:        // iter33e C1: `persist_present` убран — он дублировал `persist_before`. Теперь два разных
807:                // рядом с `persist_after=` не дублируем (именно за дубль убран `persist_present`).
```

В прошлом отчёте стояло `0` — неверно (я считал по коду, а не по комментариям). Кода с этим полем нет: все три вхождения — комментарии, которыми объясняется удаление. Вывод прежний, число исправлено.

### 4.4 C4 — правило на будущее

Каждая `grep`/`grep -c` в этом отчёте сопровождается напечатанным выводом (§4.3, §4.5, §4.6, §1).

---

## 5. Изменения по коммитам

| коммит | содержание |
|---|---|
| `a1f4333` | A1: `PROCESSING_SUFFIX`, `jitVerifyScheduled`, `claimPendingIntent` (rename + флаг), решение по захваченному файлу, `restorePendingIntent`, `verifyClaimedAfterRestart` с тремя исходами и повторами; A3: `JitBootMark.VerifyOutcome` + `verifyOutcome` + 3 теста; B3: `kvE` |
| `578f355` | A2: `JIT_VERIFY: claim none` для «забирать нечего» — `claim taken` остаётся только за вторым входом (`reason=already_scheduled`), иначе грепы `claim` в приёмке читались бы неверно |

`git diff --stat 9d36e69 HEAD`:

```
 .../com/example/voiceapp3/tools/AppPerformance.kt  | 157 ++++++++++++++++++---
 .../com/example/voiceapp3/tools/JitBootMark.kt     |  19 +++
 .../com/example/voiceapp3/tools/JitBootMarkTest.kt |  28 ++++
 3 files changed, 188 insertions(+), 16 deletions(-)
```

---

## 6. Обязательные проверки

### 6.1 Намерение «забирается» атомарно

```
$ grep -n "claimPendingIntent(app)\|BgWorker.post(\"AppPerformance.jitVerifyAfterRestart\"" app/src/main/java/com/example/voiceapp3/tools/AppPerformance.kt
933:        val claimed = claimPendingIntent(app) ?: return
947:            JitBootMark.PendingDecision.VERIFY -> BgWorker.post("AppPerformance.jitVerifyAfterRestart") {
$ grep -n "src.renameTo(claimed)" app/src/main/java/com/example/voiceapp3/tools/AppPerformance.kt
974:        val renamed = runCatching { src.renameTo(claimed) }.getOrDefault(false)
```

Захват (`:933` → `renameTo` на `:974`) стоит **выше** постановки в очередь (`:947`). Цитата — §3.1.

### 6.2 Повторный вход невозможен

Цитаты: `renameTo` (`:974`) + `AtomicBoolean` (`:710`, проверка `:969`, установка `:995`, снятие `:939/944/1034/1038/1045`). Таблица сценариев — §3.2.

### 6.3 `source=after_restart` ровно 1 на рестарт

Объяснение + цитата — §3.2/§3.4. **Статус: 🟡 `НЕ ДОКАЗАНО` машинно на устройстве.** Юнит-тестом это не проверяется (`File`/`renameTo`/`BgWorker` в JVM-тест не тащим — запрет мандата A3); гарантия выводится из атомарности `renameTo` и порядка вызовов. План проверки на ГУ: `grep -ac "source=after_restart" app.log` после одного рестарта zygote должно быть `1` (см. §8).

### 6.4 `.processing` не остаётся навсегда

```
$ grep -n "claimed.delete()\|restorePendingIntent(app, claimed)\|claimed.renameTo(src)" app/src/main/java/com/example/voiceapp3/tools/AppPerformance.kt
938:                runCatching { claimed.delete() }
943:                runCatching { claimed.delete() }
1004:        val ok = runCatching { claimed.renameTo(src) }.getOrDefault(false)
1033:                runCatching { claimed.delete() }
1037:                runCatching { claimed.delete() }
1044:                    restorePendingIntent(app, claimed)
```

Удаление/возврат во всех терминальных ветках: `NONE` (`:938`), `STALE` (`:943`), `OK` (`:1033`), `FAILED` (`:1037`), финальный `UNKNOWN` — возврат на место (`:1044` → `restorePendingIntent` `:1002-1004`). Плюс отдельная ветка `claim ok reason=leftover` (`:983-989`) забирает `.processing`, оставшийся от убитого процесса, — то есть даже аварийный остаток не висит вечно.

### 6.5 `UNKNOWN` не удаляет намерение

Цитата — §3.4 (`:1041-1046`): ветка `UNKNOWN` не вызывает `claimed.delete()`, а на исчерпании повторов делает `restorePendingIntent`.

### 6.6 Повторы ничего не пишут в систему

`AppPerformance.kt:1018` — единственное действие повтора: `val (runtime, persist) = jitProps()`, а `jitProps` (`:720-733`) — это `getprop` через `AdbClient.execute("echo RT=$(getprop …); echo PS=$(getprop …)")`, без `setprop`/`su`/`ctl.*`. Повтор вызывается рекурсивно тем же телом (`:1049`), новых команд нет.

### 6.7 `ctl.restart zygote` по-прежнему один на boot

```
$ grep -n "setprop ctl.restart zygote" app/src/main/java/com/example/voiceapp3/tools/AppPerformance.kt
242:                setprop ctl.restart zygote
861:            "setprop $PROP_USEJIT true; setprop $PROP_USEJIT_PROFILES true; setprop ctl.restart zygote")
$ grep -c "ctl.restart zygote" app/src/main/java/com/example/voiceapp3/tools/AppPerformance.kt
8
$ grep -n "ctl.restart zygote" app/src/main/java/com/example/voiceapp3/tools/AppPerformance.kt
213: комментарий (порт la.java)
242: клик `setAutoOptimize` (не boot)
301: клик, вторая ветка (`svc wifi disable; …; ctl.restart zygote; …`)
739: комментарий «Маршрут 2 — один `ctl.restart zygote` …»
847: комментарий «после `ctl.restart zygote` он умирает…»
861: ЕДИНСТВЕННЫЙ boot-вызов
919: комментарий «…после `ctl.restart zygote` ни `postDelayed(20 c)`…»
1014: комментарий «…никаких повторных `ctl.restart zygote`…»
```

**8 = 1 boot-вызов (`:861`) + 2 вызова по клику (`:242`, `:301`) + 5 комментариев.** В этой итерации новых `ctl.restart` не добавлено: повторы `UNKNOWN` — только чтение.

### 6.8 Пустые значения как `empty`

Цитата `:1025` (§3.4) + `kvE` (`:717`).

### 6.9 `verifyOutcome` покрыт тестами

Три имени в логе CI: `verifyOutcomeOk`, `verifyOutcomeFailed`, `verifyOutcomeUnknownWhenEmpty` — `PASSED` (§1), `JitBootMarkTest` = 13 `PASSED`.

### 6.10 `grep -c` с выводом команды

См. §1, §4.3, §6.7 — рядом с каждым числом напечатан вывод.

### 6.11 Ссылки `файл:строка` проверены (`sed -n`, 15 ссылок)

```
app/src/main/java/com/example/voiceapp3/tools/AppPerformance.kt:696  | private const val PROCESSING_SUFFIX = ".processing"
app/src/main/java/com/example/voiceapp3/tools/AppPerformance.kt:710  | private val jitVerifyScheduled = java.util.concurrent.atomic.AtomicBoolean(false)
app/src/main/java/com/example/voiceapp3/tools/AppPerformance.kt:717  | private fun kvE(s: String): String = if (s.isEmpty()) "empty" else LogKit.kv(s)
app/src/main/java/com/example/voiceapp3/tools/AppPerformance.kt:931  | fun checkPendingZygoteVerify(context: Context) {
app/src/main/java/com/example/voiceapp3/tools/AppPerformance.kt:974  | val renamed = runCatching { src.renameTo(claimed) }.getOrDefault(false)
app/src/main/java/com/example/voiceapp3/tools/AppPerformance.kt:997  | AppLog.i(TAG, "JIT_VERIFY: claim ok boot_tag=$bootTag pending=${kvE(pending)}")
app/src/main/java/com/example/voiceapp3/tools/AppPerformance.kt:1025 | AppLog.i(TAG, "JIT_VERIFY: runtime_after=${kvE(runtime)} persist=${kvE(persist)} source=after_restart " +
app/src/main/java/com/example/voiceapp3/tools/AppPerformance.kt:1047 | BgWorker.postDelayed("AppPerformance.jitVerifyRetry", JIT_VERIFY_RETRY_DELAY_MS) {
app/src/main/java/com/example/voiceapp3/tools/JitBootMark.kt:122     | enum class VerifyOutcome { OK, FAILED, UNKNOWN }
app/src/main/java/com/example/voiceapp3/tools/JitBootMark.kt:124     | fun verifyOutcome(runtime: String, persist: String): VerifyOutcome = when {
app/src/main/java/com/example/voiceapp3/VoiceAssistantService.kt:266  | runCatching { AppPerformance.checkPendingZygoteVerify(this) }
app/src/main/java/com/example/voiceapp3/VoiceAssistantService.kt:348  | runCatching { AppPerformance.checkPendingZygoteVerify(this) }
app/src/main/java/com/example/voiceapp3/VoiceAssistantService.kt:352  | if (intent?.getBooleanExtra(EXTRA_BOOT_RESTORE, false) == true) {
app/build.gradle.kts:60                                            | versionCode = 53
app/build.gradle.kts:61                                            | versionName = "1.0.53"
```

Команда (по одной ссылке, остальные так же):

```
$ sed -n "974p" app/src/main/java/com/example/voiceapp3/tools/AppPerformance.kt
        val renamed = runCatching { src.renameTo(claimed) }.getOrDefault(false)
```

### 6.12 Три размера раздельно

§1: zip / `apk/debug/app-debug.apk` / `logs/…` — три числа + команда `unzip -l | awk`. Не суммируются.

### 6.13 Версия не поднята

```
$ sed -n '60,61p' app/build.gradle.kts
        versionCode = 53
        versionName = "1.0.53"
```

### 6.14 Тесты реально идут

§1: `> Task :app:testDebugUnitTest` + 23 `PASSED`, 0 `FAILED`.

---

## 7. Примеры строк (собраны из формат-строк, `пример, не из лога`)

```
$ grep -n "JIT_VERIFY: claim\|JIT_VERIFY: runtime_after=\${kvE\|result=UNKNOWN retries" tools/AppPerformance.kt
970/979/987/992/997 — claim; 1025 — runtime_after; 1043 — итог UNKNOWN
```

`пример, не из лога` — нормальный путь (одна сверка на рестарт):

```
JIT_VERIFY: claim ok boot_tag=epoch1762345678 pending=epoch1762345678:43120
JIT_VERIFY: claim taken reason=already_scheduled boot_tag=epoch1762345678
JIT_VERIFY: runtime_after=true persist=true source=after_restart result=OK boot_tag=epoch1762345678 attempt=1
JIT_BOOT: match_after_zygote runtime=true persist=true (поднят перезапуском zygote в этой загрузке)
```

`пример, не из лога` — канал не ответил:

```
JIT_VERIFY: claim ok boot_tag=epoch1762345678 pending=epoch1762345678:43120
JIT_VERIFY: runtime_after=empty persist=empty source=after_restart result=UNKNOWN reason=no_channel boot_tag=epoch1762345678 attempt=1
JIT_VERIFY: claim taken reason=already_scheduled boot_tag=epoch1762345678
JIT_VERIFY: result=UNKNOWN retries=3 reason=no_channel boot_tag=epoch1762345678 (намерение оставлено следующему старту)
```

`пример, не из лога` — намерение прошлой загрузки:

```
JIT_VERIFY: claim ok boot_tag=epoch1762440000 pending=epoch1762345678:43120
JIT_VERIFY: skip reason=pending_stale pending=epoch1762345678:43120 boot_tag=epoch1762440000
JIT_VERIFY: claim none boot_tag=epoch1762440000
```

---

## 8. Честные пробелы

1. **Порядок `onCreate` → `onStartCommand` на ГУ не наблюдался.** Вся логика одиночной сверки опирается на порядок жизненного цикла Android (и на атомарность `renameTo`); на машине 1.0.53 ещё не устанавливалась, поэтому фактический порядок вызовов на устройстве — `НЕ ДОКАЗАНО`. Проверка: `grep -ac "source=after_restart" app.log` = 1 и `grep -a "JIT_VERIFY: claim"` даёт ровно один `claim ok` на рестарт.
2. **Цена `ctl.restart zygote`** (секунды чёрного экрана / переподключение CarPlay) не измерена.
3. **Читается ли `boot_id` на прошивке Geely** — не проверено; при отсутствии работает `epoch`-тег с допуском ±60 с (покрыто тестами, на железе не наблюдалось).
4. **Срабатывает ли маршрут 1** (`restored_from_persist`) — зависит от того, переживает ли `persist.sys.dalvik.vm.usejit` перезагрузку; не подтверждено.
5. **Фактический `exec/min`** после правок не замерялся; в этой итерации добавлены только файловые операции (`renameTo`, `delete`) и повторы `getprop` (до 3 за `UNKNOWN`).
6. **Сценарий `UNKNOWN` даёт потенциально вторую сверку** на более позднем старте сервиса (намерение не сжигается по мандату). Это осознанный компромисс: «ровно одно доказательство» гарантировано для нормального пути и для `FAILED`; при `UNKNOWN` каждая попытка нумеруется `attempt=N`.
7. **Юнит-тестов на `claimPendingIntent`/`verifyClaimedAfterRestart` нет** — это работа с файлами и потоками, в JVM её не тащим (запрет A3). Покрыта только чистая часть — `verifyOutcome`.

---

## 9. Приёмка владельцем (§G)

После установки 1.0.53 и **двух** перезагрузок подряд (файл на устройстве — `voiceapp3.log`):

```
grep -a "JIT_BOOT: zygote_restart intent=pending" app.log   # есть ДО рестарта
grep -ac "source=after_restart" app.log                     # РОВНО 1 на рестарт (было 2)
grep -ac "match_after_zygote" app.log                       # столько же, сколько source=after_restart
grep -a "JIT_VERIFY: claim" app.log                         # один claim ok + один claim taken reason=already_scheduled
grep -a "result=UNKNOWN" app.log                            # если канал не готов: UNKNOWN, а НЕ FAILED
grep -a "reason=pending_stale" app.log                      # намерение прошлой загрузки отброшено
grep -ac "JIT_PROBE: runtime=true" app.log                  # ровно 1 за загрузку (доказательство рестарта — source=after_restart)
grep -aE "runtime_after=empty|persist=empty" app.log        # пустые значения помечены
grep -ac "setprop ctl.restart zygote" app.log               # не больше одного на загрузку
grep -aE "persist_before=|persist_after=" app.log           # дублёр унаследован или дозаписан
grep -aE "HVAC VERDICT:|VERIFY hvacdigital.enable:" app.log # VIA=bind / OK, не PARTIAL
```

Ожидаемая последовательность на один рестарт zygote (см. §7): `claim ok` → `claim taken reason=already_scheduled` → `runtime_after=… source=after_restart result=OK` → `match_after_zygote`. Строка `claim taken` без предшествующей `claim ok` — сигнал, что намерение забрал прошлый процесс; два `source=after_restart` подряд — дефект, которого быть не должно.

Глазами: цифры климата после ребута появляются сами; «Видео 60 fps» — одна попытка; мигание статус-бара одно; загрузка не подвисает.
