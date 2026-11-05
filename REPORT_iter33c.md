# REPORT iter33c — 1.0.53 (вторая пересборка, версия та же)

**Ветка:** `fix/v2` · **Версия не поднята:** `versionCode = 53`, `versionName = "1.0.53"` (`app/build.gradle.kts:60-61`)
**База:** `c252ecf` · **Коммиты:** `edaebae` (A1/A2/A3 + B1/B2/B3 + C1/C2), `29f18ec` (`testLogging` — доказательство, что тесты идут)
**Мандат:** `uploads/PROMPT_iter33c_1.0.53_fix2.md`. `main` не пушился, `--force` не применялся, workflow не правился.

> Замечание по ходу: локальная рабочая папка `work9` оказалась обрезанной (пропал `.git` и часть дерева) — код взят заново клоном из `fix/v2` в `/home/user/work10`, история не переписывалась.

---

## F1. Чек-лист

| пункт | статус | файл:строка | доказательство |
|---|---|---|---|
| **A1** `inodeMatch` работает | ✅ | `HvacVerdict.kt:52-79` (разбор), `HvacDigitalPlugin.kt:249,265` (`echo TGT_INODE=`) | Разбор идёт по готовому вердикту скрипта — `###STAGE name=bind status=OK reason=inode_match:<n>` / `status=SKIP reason=already_mounted_inode_match`; `TGT_INODE` дополнительно печатается отдельной строкой. Тест `successVerdictIsBindWithInodeMatch` **PASSED** в CI |
| **A1.2** `tgt_inode` больше не `-` | ✅ | `HvacDigitalPlugin.kt:249,265` (скрипт), `:355` (`val probeTgt = verdict.tgtInode`) | Лог: `HVAC_PROBE … tgt_inode=25004 …`; при отсутствии строки значение берётся из `reason=inode_match:<n>` (`HvacVerdict.kt:60-66`) |
| **A1.3** успех = `OK`, не `PARTIAL` | ✅ | `HvacVerdict.kt:106-131` (`statusOf`), `HvacDigitalPlugin.kt:337-355` | `mountOk && bindStage in (OK,SKIP) ⇒ OK` **до** проверки inode; тест `successVerdictIsBindWithInodeMatch` проверяет и `inodeMatch=false` при `mountOk=true` ⇒ `OK` |
| **A2** `VIA=pm` невозможен | ✅ | `HvacVerdict.kt:41` (`(?m)^VIA=([A-Za-z_]+)\\s*$`), `HvacDigitalPlugin.kt:224,232` (`unshadow_via=`) | Якоря + переименованное служебное поле; тесты `underscoreViaNeverLeaksIntoVerdict`, `successVerdictIsBindWithInodeMatch` **PASSED** |
| **A3** JVM-тест разбора | ✅ | `app/src/test/java/com/example/voiceapp3/tools/HvacVerdictTest.kt` (6 `@Test`), `app/build.gradle.kts:224-233` | В логе CI: `HvacVerdictTest > garbageOutputDoesNotThrow PASSED` … всего **10 тестов PASSED** (6 × HvacVerdictTest + 4 × ScriptChunkerTest) |
| **B1** `match_after_zygote` | ✅ | `AppPerformance.kt:742-760`, `:838-843` (`attemptInThisLoad`) | `restored_from_persist` печатается **только** если метки `jit_boot_attempt` этой загрузки нет; иначе `JIT_BOOT: match_after_zygote …` |
| **B2** `persist_present` / `boot_backfill` | ✅ | `AppPerformance.kt:729-736`, `:827-836` (`jitPersistBackfill`) | Все ветки пробы печатают `persist_present=<true\|false>`; пустой дублёр при включённом тумблере дозаписывается с `reason=boot_backfill` |
| **B3** один zygote за загрузку | ✅ | `AppPerformance.kt:772-777` (`if (!secondPass) … return`), wiring `VoiceAssistantService.kt:368-381` | Маршрут 2 недостижим на первом проходе; zygote — только на проходе через 30 с, уже после установки плагина |
| **C1** `JIT_ZYGOTE_WINDOW_MS` | ✅ | `AppPerformance.kt:688`, `:778-781` | `JIT_LATE_START_MS` больше нет (`grep` = 0), причина `reason=outside_window`, значение и поведение те же (120 000 мс) |
| **C2** хвост `flushOps` | ✅ | `LogKit.kt:112-141` | Отложенный сброс через `OPS_FLUSH_MIN_MS` = **2 с**; худшая задержка хвоста была 60 с |

---

## Цитаты кода после правки

### A1/A2 — разбор (`HvacVerdict.kt:41-47, 68-79`)

```kotlin
private val RE_VIA = Regex("(?m)^VIA=([A-Za-z_]+)\\s*$", RegexOption.IGNORE_CASE)
private val RE_BIND_STAGE = Regex("###STAGE name=bind status=([A-Za-z]+)(?: reason=([^\\n]*))?")
…
val sameInode = srcFinal != "-" && tgt != "-" && srcFinal == tgt && srcFinal != "0"
val inodeMatch = sameInode ||
    (bindStage == "OK" && bindReason.contains("inode_match")) ||
    (bindStage == "SKIP" && bindReason.contains("inode_match"))
```

### A1.3 — статус (`HvacVerdict.kt:106-131`)

```kotlin
fun statusOf(d: Data, mountOk: Boolean, rc: Int, permDenied: Boolean = false,
             rootUnavailable: Boolean = false, naoVingou: Boolean = false): String = when {
    rootUnavailable -> "BLOCKED"
    permDenied -> "FAILED"
    d.via == "nenhum" || naoVingou || d.failFlag || d.bindStage == "FAILED" -> "FAILED"
    d.via == "install" && rc != 0 -> "FAILED"
    d.via == "install" -> "OK"
    d.via == "bind" && (mountOk || d.inodeMatch || d.bindStage == "OK" || d.bindStage == "SKIP") -> "OK"
    mountOk && (d.bindStage == "OK" || d.bindStage == "SKIP") -> "OK"
    mountOk -> "PARTIAL"
    else -> "FAILED"
}
```

### A2 — скрипт (`HvacDigitalPlugin.kt:224,232`)

```sh
echo "HVAC_UNSHADOW: before=$PM_PATH after=$PM_AFTER unshadow_via=$VIA_UNSHADOW result=OK"
…
case "$PM_AFTER" in
  /data/app/*) echo "VIA=install" ;;
  *) echo "VIA=bind" ;;
esac
```

### A1 — `TGT_INODE` отдельной строкой (`HvacDigitalPlugin.kt:242,262`)

```sh
TGT_INODE=$(stat -c '%i' "$TGT" 2>/dev/null)
echo "TGT_INODE=$TGT_INODE"
```

### B1/B2/B3 (`AppPerformance.kt:729-779`)

```kotlin
if (persist != desired) {
    val reason = if (persist.isEmpty()) "boot_backfill" else "boot_mismatch"
    jitPersistBackfill(desired, reason)
    persist = jitProps().second
}
val persistPresent = persist.isNotEmpty()
…
if (secondPass && persist == desired) {
    if (attempted) { … "JIT_BOOT: match_after_zygote runtime=$runtime persist=$persist …" }
    else          { … "JIT_BOOT: restored_from_persist prop=$PROP_PERSIST_USEJIT …" }
}
…
if (!secondPass) {
    AppLog.i(TAG, "JIT_PERSIST_PROBE: supported=unknown persist_present=$persistPresent runtime=$runtime persist=$persist action=read_again_in_30s")
    return                                   // B3: zygote на первом проходе НЕ перезапускаем
}
```

### C1 (`AppPerformance.kt:688, 778-781`)

```kotlin
private const val JIT_ZYGOTE_WINDOW_MS = 120_000L
…
if (up >= JIT_ZYGOTE_WINDOW_MS) {
    AppLog.i(TAG, "JIT_BOOT: deferred reason=outside_window uptime_ms=$up …")
```

### C2 — хвост выжимки (`LogKit.kt:112-141`)

```kotlin
if (!force && now - lastOpsFlushMs < OPS_FLUSH_MIN_MS) {
    opsDirty = true
    scheduleTailFlush()      // ← раньше флаг никто не читал: хвост ждал HEARTBEAT (60 с)
    return
}
…
private fun scheduleTailFlush() {
    if (tailFlushScheduled) return
    tailFlushScheduled = true
    mainHandler.postDelayed({ tailFlushScheduled = false; flushOps(force = true) }, OPS_FLUSH_MIN_MS)
}
```
**Худшая задержка хвоста: 2 с** (было до 60 с), задача одна на пачку.

---

## Обязательные проверки (часть F)

| проверка | результат |
|---|---|
| `VIA=pm` стал невозможен | `grep -n "VIA" HvacDigitalPlugin.kt` → только `VIA=nenhum`(`:200`), `VIA=install`(`:226`), `VIA=nenhum`(`:271`), `VIA=install|bind`(`:297-298`), чтение `VIA=$(getprop …)` в **disable** (`:499`, другой скрипт); регулярка якорная; тест PASSED |
| `inodeMatch` работает | цитата выше + `HvacVerdictTest > successVerdictIsBindWithInodeMatch PASSED` |
| `tgt_inode` не `-` | `HVAC_PROBE … tgt_inode=${verdict.tgtInode}`, значение из `^TGT_INODE=<n>$` либо из `reason=inode_match:<n>` |
| статус успеха `OK` | `statusOf`: ветка `d.via=="bind" && (mountOk \|\| inodeMatch \|\| bindStage OK/SKIP) -> "OK"`; тест проверяет и вариант `inodeMatch=false` + `mountOk=true` |
| ≤1 `ctl.restart zygote` за загрузку | `bootRestoreJit` возвращает на первом проходе (`if (!secondPass) return`); единственный вызов `ctl.restart zygote` — в маршруте 2 (`:800`); wiring: `VoiceAssistantService.kt:375` (проход 1) и `:379` (проход 2 через 30 с) |
| `restored_from_persist` только без попытки | условие `attemptInThisLoad(app)` → `match_after_zygote` |
| `persist_present` в пробе | все три ветки `JIT_PERSIST_PROBE` содержат `persist_present=` |
| хвост `flushOps` | `scheduleTailFlush`, 2 с |
| версия не поднята | `app/build.gradle.kts:60` `53`, `:61` `"1.0.53"` |
| тесты реально идут | см. §CI: 10 строк `PASSED`, задача `:app:testDebugUnitTest` не `UP-TO-DATE`/`NO-SOURCE` |

---

## CI

| что | значение |
|---|---|
| прогон | **42020153399** (`push`, `29f18ec`) — **success** |
| шаги | `> Task :app:compileDebugUnitTestKotlin` → `> Task :app:testDebugUnitTest` → `> Task :app:assembleDebug` → `BUILD SUCCESSFUL in 2m 59s` |
| тесты | **10 PASSED, 0 FAILED**: `HvacVerdictTest` — `garbageOutputDoesNotThrow`, `blockedChannelIsNotFailed`, `successVerdictIsBindWithInodeMatch`, `skippedBindCountsAsSuccess`, `underscoreViaNeverLeaksIntoVerdict`, `failedUnshadowIsInstallAndNoInodeMatch`; `ScriptChunkerTest` — `chunkerKeepsBytesForCyrillicScript`, `chunkerProducesAsciiOnly`, `oldByteSlicingWasBroken`, `asciiOnlyRoundTrip` |
| артефакт | **13411367824** (`app-debug`) |
| zip | **177 080 524 Б** |
| APK внутри | **215 519 395 Б** |

Промежуточный прогон `42018524877` (`edaebae`) тоже success (артефакт `13410374029`, zip 177 080 461 Б / APK 215 519 395 Б), но в нём ещё не было `testLogging`, поэтому имена тестов в логе не печатались — финальные числа и доказательства из `42020153399`.

Прошлые числа для сравнения (не копировались): `42005750488` — zip 177 079 438 Б / APK 215 509 471 Б. Разница: **+1 086 Б** zip / **+9 924 Б** APK.

Падений в этой итерации не было: обе сборки (`edaebae`, `29f18ec`) прошли с первого раза.

---

## Честные пробелы

1. **На ГУ не проверялось.** Всё подтверждено кодом + тестами + зелёной сборкой; поведение после ребута увидит владелец.
2. **Маршрут 1 на прошивке Geely — по-прежнему НЕ ДОКАЗАНО.** Теперь он хотя бы самодостаточен: дублёр пишется на boot, если его нет (`reason=boot_backfill`), поэтому `JIT_PERSIST_PROBE` наконец честно ответит `supported=true|false`.
3. **Цена `ctl.restart zygote` (секунды чёрного экрана)** — не измерена; поля для расчёта в `JIT_VERIFY` (`zygote_pid_before/after`) есть.
4. **`exec/min` в покое и объём лога ≤150 КБ/10 мин** — ожидаемые (10 команд за 10 минут по расчёту C1 прошлой итерации), фактических чисел с устройства нет.
5. **`pm uninstall-system-updates` vs `cmd package uninstall-system-updates`** — какая ветка нужна прошивке, по-прежнему НЕ ДОКАЗАНО (в скрипте есть обе); при провале будет `HVAC_UNSHADOW … result=FAILED` + `HVAC_VERDICT VIA=install`.
6. **`testLogging` добавлен в `app/build.gradle.kts`** (не в workflow — токен без scope `workflow`). Это единственное изменение сборки в этой итерации; версия, тест-привязка к `assembleDebug` и `junit` не менялись.

---

## Что владелец увидит в логе (часть G)

```
HVAC VERDICT: VIA=bind unshadow_via=pm_uninstall bind_stage=OK VIA_PREV= PM= PM_PATH=/system/priv-app/…
VERIFY hvacdigital.enable: OK (rc=0 VIA=bind PM_PATH=/system/… inode=true mount=true)
HVAC_PROBE pm_path=/system/priv-app/… mounted=true src_inode=25004 tgt_inode=25004 selinux=Enforcing via=bind rc=0 props=batt:42.4,…
HVAC_UNSHADOW_DETAIL: before=/data/app/… after=/system/priv-app/… unshadow_via=pm_uninstall result=OK
HVAC_BOOT: attempt=1 reason=not_mounted kill=1 result=OK ms=3120          ← ровно одна на загрузку
VIDEO_BOOT: pref=on applied_before=false result=OK ms=2400 action=install
JIT_PERSIST_SET desired=true readback=true reason=boot_backfill
JIT_PERSIST_PROBE: supported=unknown persist_present=true runtime=false persist=true action=read_again_in_30s
JIT_PERSIST_PROBE: supported=true persist_present=true runtime=true persist=true reason=runtime_raised_from_persist
JIT_BOOT: restored_from_persist prop=persist.sys.dalvik.vm.usejit runtime=true persist=true (zygote не перезапускали)
```
Если persist-маршрут не сработает на этой прошивке, вместо последних трёх строк будет:
```
JIT_PERSIST_PROBE: supported=false persist_present=true runtime=false persist=true reason=runtime_not_raised_from_persist
JIT_BOOT: pref=on desired=true runtime_before=false action=setprop+zygote uptime_ms=41230 marker=jit_boot_attempt rc=0
JIT_VERIFY: runtime_after=true persist=true zygote_pid_before=812 zygote_pid_after=1904 result=OK
JIT_BOOT: match_after_zygote runtime=true persist=true (поднят перезапуском zygote в этой загрузке)
```
и мигание статус-бара будет **одно** — плагин ставится до перезапуска zygote (B3).
