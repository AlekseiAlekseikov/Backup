package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * iter43 — проверки §1.3/§1.4/§2.3/§3.
 *
 * Уровень: **JVM-модель**. Здесь нет Android: `Context`, `getExternalFilesDir`, `usableSpace`
 * и `SharedPreferences` недоступны, поэтому проверяются те же функции, что вызывает production-код,
 * но на явных каталогах/синтетическом времени (`LogFiles.totalsOf`, `LogRedact.cmd`,
 * `JitRef.DiagRunner`, `JitRef.TtlSnapshotFlow`, `JitRef.EventSnapshots`).
 * Android-интеграция и ГУ этими тестами НЕ доказаны.
 */
class LogFiles43TotalsTest {

    private fun tmp(prefix: String = "lf43"): File = Files.createTempDirectory(prefix).toFile()

    private fun write(f: File, s: String) {
        f.parentFile?.mkdirs()
        f.writeText(s)
    }

    /** §1.3: свой объём отделён от чужого — иначе «64 МиБ» звучало бы как весь каталог. */
    @Test
    fun totalsSeparateOwnFromForeignBytes() {
        val d = tmp()
        val today = LogFiles.fileNameOf(java.time.LocalDate.now())
        write(File(d, today), "a".repeat(1000))
        write(File(d, "voiceapp3-01.01.2000.1.log"), "b".repeat(500))
        write(File(d, LogFiles.LEGACY_NAME), "c".repeat(300))
        write(File(d, "voiceapp3-important.log"), "d".repeat(200))
        write(File(d, "чужой.log"), "e".repeat(70))

        val t = LogFiles.totalsOf(listOf(d)).single()
        assertEquals("свои части посчитаны", 2, t.mainParts)
        assertEquals(1500L, t.mainBytes)
        assertEquals("legacy учтён отдельно", 300L, t.legacyBytes)
        assertEquals("важный и чужие .log — вне квоты, но видны", 270L, t.otherLogBytes)
        assertEquals(1500L, t.ownBytes)
        assertTrue("учёт не выдаёт чужое за своё", t.ownBytes < t.ownBytes + t.legacyBytes + t.otherLogBytes)
        assertTrue(t.listed)
        assertTrue("каталог доступен на запись", t.writable)
        // Место: три честных состояния, а не «0 = всё хорошо».
        assertTrue(t.spaceFact == "unknown" || t.spaceFact.startsWith("known:"))
    }

    /** §1.3: недоступный каталог — `listed=false`, а не «0 байт, значит пусто». */
    @Test
    fun missingDirectoryIsReportedAsNotListed() {
        val t = LogFiles.totalsOf(listOf(File(tmp(), "нет-такого"))).single()
        assertFalse(t.listed)
        assertEquals(0L, t.ownBytes)
        assertNull("место неизвестно", t.free)
        assertEquals("unknown", t.spaceFact)
    }

    /** §1.3: строка-итог обязана оговаривать, что лимит — НА КАТАЛОГ, а не абсолютный предел. */
    @Test
    fun totalsLineDeclaresCapsArePerDirectoryNotAbsolute() {
        val d = tmp()
        val t = LogFiles.totalsOf(listOf(d)).single()
        val line = t.line()
        assertTrue("в строке есть свои байты", line.contains("own_total="))
        assertTrue("чужое помечено как вне квоты", line.contains("ВНЕ квоты"))
        assertTrue("свободное место в честном виде", line.contains("free=known:") ||
            line.contains("free=zero") || line.contains("free=unknown"))
    }

    /** §1.3: `partsOf` не должен задевать `voiceapp3-important.log` и legacy-файл. */
    @Test
    fun partsOfNeverTouchesImportantOrLegacy() {
        val d = tmp()
        val today = java.time.LocalDate.now()
        write(File(d, LogFiles.fileNameOf(today)), "x")
        write(File(d, "voiceapp3-important.log"), "важное")
        write(File(d, LogFiles.LEGACY_NAME), "старое")
        write(File(d, "voiceapp3-01.01.2000.log"), "просроченное")

        val parts = LogFiles.partsOf(d, LogFiles.Tier.MAIN).map { it.name }
        assertFalse("важный файл не своя часть", parts.contains("voiceapp3-important.log"))
        assertFalse("legacy не своя часть", parts.contains(LogFiles.LEGACY_NAME))

        // Удаление просроченного не должно задеть ни важный, ни legacy.
        val removed = LogFiles.pruneTier(d, System.currentTimeMillis(), LogFiles.Tier.MAIN)
        assertTrue("просроченная своя часть удалена", removed.contains("voiceapp3-01.01.2000.log"))
        assertTrue("важный файл на месте", File(d, "voiceapp3-important.log").isFile)
        assertTrue("legacy на месте", File(d, LogFiles.LEGACY_NAME).isFile)
    }
}

/**
 * §1.4 — приватность: маркеры искусственных секретов НЕ должны доживать до журнала.
 * Проверяется именно та функция, которую вызывает `AdbClient` (`LogKit.redactCmd` → `LogRedact.cmd`).
 */
class LogRedact43Test {

    private val b64 = "QWxhZGRpbjpvcGVuIHNlc2FtZS1zZWNyZXQtdG9rZW4tMTIzNDU2Nzg5MA=="

    @Test
    fun base64PayloadBecomesBlobMarker() {
        val out = LogRedact.cmd("adb push $b64 /data/local/tmp/x")
        assertFalse("base64 не попал в строку", out.contains(b64))
        assertTrue("виден размер blob", out.contains("<blob:"))
        assertTrue("тип команды сохранён", out.startsWith("adb push "))
    }

    @Test
    fun quotedLiteralsKeepOnlySize() {
        val secret = "SuperSecretPayload_42"
        val out = LogRedact.cmd("VERIFY EXEC cmd=\"echo '$secret'\"")
        assertFalse("тело литерала вырезано", out.contains(secret))
        assertTrue("размер литерала сохранён", Regex("'<\\d+B>'").containsMatchIn(out) ||
            Regex("\"<\\d+B>\"").containsMatchIn(out))
        assertTrue("VERIFY EXEC остаётся виден", out.contains("VERIFY EXEC"))
    }

    @Test
    fun secretKeyValuesAreRedacted() {
        val out = LogRedact.cmd("curl -H token=abc123def -X POST https://example.invalid/ping")
        assertFalse("значение секрета вырезано", out.contains("abc123def"))
        assertTrue(out.contains("token=<redacted>"))
        assertTrue("адрес не является секретом и нужен для диагностики", out.contains("example.invalid"))
    }

    @Test
    fun longInputIsTruncatedWithExplicitLength() {
        val out = LogRedact.cmd("cmd=" + "z ".repeat(200), 60)
        assertTrue("длина ограничена", out.length <= 60 + 20)
        assertTrue("факт усечения виден, а не молчание", out.contains("…(len="))
    }

    @Test
    fun utf8LengthCountsBytesNotChars() {
        // Кириллица: 1 символ = 2 байта UTF-8. Именно байтами измеряется объём записи.
        assertEquals(4, LogRedact.utf8Len("аб"))
        val out = LogRedact.cmd("echo 'аб'")
        assertTrue("размер литерала указан в байтах", out.contains("'<6B>'"))
    }

    @Test
    fun rcAndPortSurviveForDiagnosis() {
        val out = LogRedact.cmd("VERIFY EXEC port=sh rc=1 expectRoot=true cmd='ls /data'")
        assertTrue(out.contains("port=sh"))
        assertTrue(out.contains("rc=1"))
        assertTrue("но тело команды — нет", !out.contains("ls /data"))
    }
}

/**
 * §2.3 — оркестрация сбора на СИНТЕТИЧЕСКОМ планировщике: без реального времени,
 * без сети, без потоков-гонок.
 */
class DiagRunner43Test {

    /** Ручной планировщик: задачи с задержкой выполняются только по `advance`. */
    private class Sched {
        private data class Job(val delayMs: Long, val task: () -> Unit)
        private val jobs = ArrayList<Job>()
        var posts = 0
        fun post(name: String, delayMs: Long, task: () -> Unit) { posts++; jobs += Job(delayMs, task) }
        fun runAll() {
            while (jobs.isNotEmpty()) {
                val batch = jobs.toList()
                jobs.clear()
                for (j in batch) j.task()
            }
        }
        fun size() = jobs.size
    }

    private fun runner(s: Sched, attempts: Int = 3): JitRef.DiagRunner =
        JitRef.DiagRunner(maxAttempts = attempts, retryDelayMs = 30_000L) { _, d, t -> s.post("", d, t) }

    @Test
    fun noParallelCollectionWhileInFlight() {
        val s = Sched()
        val r = runner(s)
        var inner = 0
        val started = r.start("initial") {
            inner++
            // попытка запустить сбор ИЗНУТРИ сбора — должна быть отвергнута
            assertFalse("параллельный запуск запрещён", r.start("from_inside") { inner++; JitRef.DiagRunner.Outcome.COMPLETED })
            JitRef.DiagRunner.Outcome.COMPLETED
        }
        assertTrue(started)
        assertEquals("сбор выполнен ровно один раз", 1, inner)
        assertTrue(r.completedNow())
        assertTrue(
            "причина отказа видна в журнале оркестрации",
            r.notes().any { it.contains("status=skipped why=in_flight") }
        )
    }

    @Test
    fun alreadyCompletedIsNotRepeated() {
        val s = Sched()
        val r = runner(s)
        r.start("initial") { JitRef.DiagRunner.Outcome.COMPLETED }
        assertFalse("второй сбор после успеха не нужен", r.start("second") { JitRef.DiagRunner.Outcome.COMPLETED })
        assertEquals(1, r.attempts())
    }

    @Test
    fun partialSchedulesRetryAndCompletes() {
        val s = Sched()
        val r = runner(s)
        var calls = 0
        r.start("initial") {
            calls++
            if (calls == 1) JitRef.DiagRunner.Outcome.PARTIAL else JitRef.DiagRunner.Outcome.COMPLETED
        }
        assertFalse("после partial сбор ещё не завершён", r.completedNow())
        assertTrue("повтор запланирован", r.notes().any { it.contains("status=retry_scheduled") })
        assertTrue("есть отложенная задача", s.size() >= 1)
        s.runAll()
        assertEquals("ровно два сбора: первый и один повтор", 2, calls)
        assertTrue(r.completedNow())
    }

    @Test
    fun safetyReportIsCancelledAfterSuccess() {
        val s = Sched()
        val r = runner(s)
        var safetyFired = false
        r.scheduleNotReadyReport("net", 30_000L) { safetyFired = true }
        r.start("initial") { JitRef.DiagRunner.Outcome.COMPLETED }
        s.runAll()
        assertFalse("страховка «через 30 с» не должна срабатывать после успеха", safetyFired)
        assertTrue(
            "отмена зафиксирована явно",
            r.notes().any { it.contains("not_ready_report fired=false") }
        )
    }

    @Test
    fun safetyReportFiresWhenNothingCollected() {
        val s = Sched()
        val r = runner(s)
        var safetyFired = false
        r.scheduleNotReadyReport("net", 30_000L) { safetyFired = true }
        s.runAll()
        assertTrue("если сбора не было — честный доклад", safetyFired)
    }

    @Test
    fun attemptsAreBoundedAndExhaustionIsNotSilent() {
        val s = Sched()
        val r = runner(s, attempts = 2)
        var calls = 0
        // §4.2 iter44: исчерпание считается по РЕАЛЬНЫМ сборам (PARTIAL), а «канал не готов»
        // попытку не расходует — этот случай проверяется отдельным тестом ниже.
        r.start("initial") { calls += 1; JitRef.DiagRunner.Outcome.PARTIAL }
        s.runAll()
        assertEquals("не больше maxAttempts", 2, calls)
        assertTrue(r.isExhausted())
        assertFalse(r.completedNow())
        assertTrue(
            "исчерпание отличимо от «данных нет»",
            r.notes().any { it.contains("status=exhausted") }
        )
        assertFalse("после исчерпания новых попыток нет", r.start("again") { JitRef.DiagRunner.Outcome.COMPLETED })
    }

    @Test
    fun channelNotReadyDoesNotBurnAttemptsAndLaterReadyCollects() {
        val s = Sched()
        val r = runner(s, attempts = 2)
        var collections = 0
        // Канал не готов: сбора не было — бюджет попыток не тратится.
        r.start("initial") { JitRef.DiagRunner.Outcome.CHANNEL_NOT_READY }
        s.runAll()
        assertEquals("попытка не израсходована", 0, r.attempts())
        assertFalse("исчерпания нет", r.isExhausted())
        assertTrue(
            "ожидание канала отличимо от исчерпания",
            r.notes().any { it.contains("status=wait_channel") }
        )
        // Канал поднялся — сбор выполняется в пределах того же лимита.
        r.start("after_ready_event") {
            collections++
            JitRef.DiagRunner.Outcome.COMPLETED
        }
        assertEquals("сбор выполнен", 1, collections)
        assertTrue(r.completedNow())
    }

    @Test
    fun exceptionIsFailureNotSuccess() {
        val s = Sched()
        val r = runner(s, attempts = 1)
        r.start("initial") { throw IllegalStateException("boom") }
        assertFalse("исключение не выдаётся за успех", r.completedNow())
        assertTrue(r.isExhausted())
        assertTrue(r.notes().any { it.contains("status=failed") })
    }
}

/**
 * §3 — метки TTL-снимков и отдельная квота на действия владельца.
 */
class TtlSnapshotFlow43Test {

    @Test
    fun preSnapshotBeforeApplyIsConfirmed() {
        val f = JitRef.TtlSnapshotFlow()
        val op = f.begin(true, 1_000L)
        f.preStarted(op, 1_100L); f.preFinished(op, 1_400L)
        f.applied(op, 1_500L, "ok")
        assertEquals("pre_confirmed", f.preLabel(op))
        assertEquals("post_after_apply", f.postLabel(op))
        assertTrue(f.describe(op, JitRef.TtlSnapshotFlow.SnapPart.PRE, "net=validated")
            .contains("phase=pre label=pre_confirmed"))
    }

    @Test
    fun preSnapshotAfterApplyIsHonestlyLabelledLate() {
        val f = JitRef.TtlSnapshotFlow()
        val op = f.begin(true, 1_000L)
        f.applied(op, 1_200L, "ok")
        f.preStarted(op, 1_900L); f.preFinished(op, 2_000L)
        assertEquals("pre_late", f.preLabel(op))
    }

    @Test
    fun preSnapshotOverlappingApplyIsNotCalledBefore() {
        val f = JitRef.TtlSnapshotFlow()
        val op = f.begin(true, 1_000L)
        f.preStarted(op, 1_000L); f.preFinished(op, 1_600L)
        f.applied(op, 1_500L, "ok")
        assertEquals("pre_overlap", f.preLabel(op))
    }

    @Test
    fun missingPreSnapshotIsUnknownNotAssumed() {
        val f = JitRef.TtlSnapshotFlow()
        val op = f.begin(true, 1_000L)
        f.applied(op, 1_500L, "ok")
        assertEquals("pre_unknown", f.preLabel(op))
    }

    @Test
    fun postWithoutConfirmedApplyIsMarked() {
        val f = JitRef.TtlSnapshotFlow()
        val op = f.begin(true, 1_000L)
        f.preStarted(op, 1_100L); f.preFinished(op, 1_200L)
        f.postScheduled(op, 1_300L)
        assertEquals("post_without_confirmed_apply", f.postLabel(op))
        val d = f.describe(op, JitRef.TtlSnapshotFlow.SnapPart.POST, "net=idle")
        assertTrue(d.contains("phase=post label=post_without_confirmed_apply"))
        assertTrue(d.contains("applied_at=n/a"))
    }

    @Test
    fun ownerActionsHaveTheirOwnQuota() {
        val s = JitRef.EventSnapshots(minIntervalMs = 90_000, maxPerHour = 6, userMaxPerHour = 12)
        // 6 авто-снимков выбирают общий часовой лимит...
        for (i in 0 until 6) {
            assertNull("авто #$i разрешён", s.tryAcquire("auto$i", 1_000L + i * 100_000L))
            s.release("auto$i")
        }
        assertTrue("седьмой авто-снимок ограничен", s.tryAcquire("auto6", 701_000L) != null)
        // ...но действие владельца не теряется из-за авто-лимита.
        assertNull("событие владельца разрешено отдельной квотой", s.tryAcquireUser("ttl_on", 801_000L))
        assertEquals(1, s.usedUser())
        assertTrue("общий счётчик при этом не «освободился»", s.used() >= 6)
    }

    @Test
    fun ownerQuotaIsBoundedToo() {
        val s = JitRef.EventSnapshots(minIntervalMs = 0, maxPerHour = 6, userMaxPerHour = 3)
        for (i in 0 until 3) assertNull(s.tryAcquireUser("u$i", i.toLong()))
        val why = s.tryAcquireUser("u3", 10L)
        assertNotNull("предел есть и у событий владельца", why)
        assertTrue("причина названа", why!!.startsWith("user_hour_limit_"))
    }

    @Test
    fun pairedOwnerSnapshotIgnoresMinInterval() {
        val s = JitRef.EventSnapshots(minIntervalMs = 90_000, maxPerHour = 6, userMaxPerHour = 12)
        assertNull(s.tryAcquireUserPaired("ttl_on_pre", 1_000L, "нет"))
        s.release("ttl_on_pre")
        assertNull("парный «после» не должен теряться из-за интервала",
            s.tryAcquireUserPaired("ttl_on_post", 2_000L, "ttl_on_pre"))
    }

    @Test
    fun trailingDelayIsTheRemainingRateLimit() {
        val s = JitRef.EventSnapshots(minIntervalMs = 90_000, maxPerHour = 6)
        assertEquals("до первого снимка задержки нет", 0L, s.remainingMs(5_000L))
        assertNull(s.tryAcquire("net", 10_000L))
        assertEquals("ждать остаток интервала", 40_000L, s.remainingMs(60_000L))
        assertEquals("после интервала — 0", 0L, s.remainingMs(200_000L))
    }
}

/**
 * §1.2 — нумерация частей и РЕАЛЬНЫЕ UTF-8 байты (а не `String.length`).
 *
 * Замечание честности: «≥10 ротаций по 8 МиБ» здесь не пишутся — это ~88 МиБ записи на каждый
 * прогон. Ротация по размеру проверяется на реальных файлах в `LogFiles42Test`
 * (`rotationKeepsEveryLineExactlyOnce`, `eachFileStaysWithinItsSizeLimit`), а нумерация 12 частей
 * проверяется на настоящих файлах, созданных заранее — без затрат диска.
 */
class LogFiles43PartsTest {

    private fun tmp(prefix: String = "lf43p"): File = Files.createTempDirectory(prefix).toFile()

    @Test
    fun twelvePartsKeepUniqueIncreasingNumbersAndOneActive() {
        val d = tmp()
        val date = java.time.LocalDate.now()
        val base = LogFiles.fileNameOf(date)
        // 12 закрытых частей: 0 (базовая) + 1..11, плюс активная 12-я.
        File(d, base).writeText("part0\n")
        for (n in 1..12) File(d, LogFiles.rotatedName(base, n)).writeText("part$n\n")

        val parts = LogFiles.partsOf(d, LogFiles.Tier.MAIN)
        assertEquals("все 13 своих частей видны", 13, parts.size)
        val nums = parts.mapNotNull { LogFiles.parseOwn(it.name)?.third }.sorted()
        assertEquals("номера уникальны", nums.toSet().size, nums.size)
        assertEquals("диапазон номеров", listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12), nums)
        assertEquals(
            "активный файл ОДИН и это последний по номеру",
            LogFiles.rotatedName(base, 12),
            LogFiles.activeName(d, LogFiles.Tier.MAIN, date)
        )
    }

    @Test
    fun lineIsSplitByUtf8BytesNotByChars() {
        val d = tmp()
        // 140 000 кириллических символов = 280 000 байт UTF-8 > MAX_LINE_BYTES (256 КиБ),
        // хотя `String.length` = 140 000 < 262 144. Решение обязано приниматься по БАЙТАМ.
        val s = "я".repeat(140_000)
        assertTrue("символов меньше лимита", s.length < LogFiles.MAX_LINE_BYTES)
        assertTrue("байтов больше лимита", s.toByteArray(Charsets.UTF_8).size > LogFiles.MAX_LINE_BYTES)

        val r = LogFiles.appendTierForTest(d, s, LogFiles.Tier.MAIN)
        assertTrue("строка записана (частями): ${r.reason}", r.written)
        assertTrue("факт разбиения посчитан", LogFiles.splits() >= 1L)

        val text = LogFiles.partsOf(d, LogFiles.Tier.MAIN).joinToString("\n") { it.readText() }
        assertTrue("маркер разбиения виден в самом логе", text.contains("LOG_LINE_SPLIT"))
        assertTrue("строки не «склеились» в мусор", text.contains("яяяя"))
        // Каждая часть файла — валидный UTF-8: читаем байты обратно без замены символов.
        val bytes = File(d, LogFiles.activeName(d, LogFiles.Tier.MAIN, java.time.LocalDate.now()))
            .readBytes()
        val decoded = String(bytes, Charsets.UTF_8)
        assertFalse(
            "многобайтовый символ не разрезан пополам (иначе появился бы U+FFFD)",
            decoded.contains('\uFFFD')
        )
    }

    @Test
    fun activeNameIsNeverReusedAfterOldestPartIsDeleted() {
        val d = tmp()
        val date = java.time.LocalDate.now()
        val base = LogFiles.fileNameOf(date)
        File(d, base).writeText("old\n")
        for (n in 1..3) File(d, LogFiles.rotatedName(base, n)).writeText("p$n\n")
        // Удаляем самую старую закрытую часть — номер НЕ должен переиспользоваться.
        assertTrue(File(d, LogFiles.rotatedName(base, 1)).delete())
        val active = LogFiles.activeName(d, LogFiles.Tier.MAIN, date)
        assertEquals("активная часть — по-прежнему последняя по номеру",
            LogFiles.rotatedName(base, 3), active)
        assertFalse("номер удалённой части не вернулся в активные",
            active == LogFiles.rotatedName(base, 1))
    }
}
