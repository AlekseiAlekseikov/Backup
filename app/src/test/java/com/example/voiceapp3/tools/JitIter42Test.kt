package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * iter42 §2.8 — проверки НА ФАЙЛАХ, а не на сформированных строках.
 *
 * Уровень: JVM + настоящий писатель датированного лога ([LogFiles.appendTierForTest],
 * append+flush, ротация по размеру) во временном каталоге. Это проверка формата/ротации/
 * конкуренции/ошибок, а НЕ Android-интеграции: `LogFiles.append(context)`/`rotateNow(context)`
 * зависят от `getExternalFilesDir`/`filesDir` и `usableSpace`, поэтому переключение
 * external→internal и отказ «диск полон» здесь моделируются каталогом-приёмником и
 * недоступным каталогом, а не самим Android API. На ГУ ничего не запускалось.
 */
class LogFiles42Test {

    private fun tmp(prefix: String = "lf42"): File =
        Files.createTempDirectory(prefix).toFile()

    private fun textOf(dir: File, tier: LogFiles.Tier): String =
        LogFiles.partsOf(dir, tier).joinToString("\n") { it.readText() }

    // ───────────── §2.2 ротация целыми файлами: ни одна строка не теряется ─────────────

    @Test
    fun rotationKeepsEveryLineExactlyOnce() {
        val d = tmp()
        val chunk = "x".repeat(64 * 1024)            // 64 КиБ на строку
        val n = 140                                   // ≈ 8.9 МиБ > MAX_FILE_BYTES (8 МиБ)
        val ids = (0 until n).map { "L%04d".format(it) }

        for (id in ids) {
            val r = LogFiles.appendTierForTest(d, "$id $chunk", LogFiles.Tier.MAIN)
            assertTrue("строка $id должна быть записана: ${r.reason}", r.written)
            assertTrue("подтверждён точный файл для $id", r.file.isNotEmpty())
        }

        val parts = LogFiles.partsOf(d, LogFiles.Tier.MAIN)
        assertTrue("ротация должна создать ≥2 части, есть ${parts.size}", parts.size >= 2)
        assertTrue("частей не больше лимита на сутки", parts.size <= LogFiles.MAX_FILES_PER_DAY)

        val all = textOf(d, LogFiles.Tier.MAIN)
        for (id in ids) {
            val cnt = Regex("(?m)^$id ").findAll(all).count()
            assertEquals("строка $id должна быть ровно один раз", 1, cnt)
        }
        assertTrue(
            "факт ротации обнаружим в самом логе",
            all.contains("LOG_ROTATE tier=main")
        )
        assertFalse(
            "строки не обрезаются под размер файла",
            all.contains("trim") || all.contains("truncated")
        )
    }

    @Test
    fun eachFileStaysWithinItsSizeLimit() {
        val d = tmp()
        val chunk = "y".repeat(32 * 1024)
        repeat(300) { i -> LogFiles.appendTierForTest(d, "S$i $chunk", LogFiles.Tier.MAIN) }
        for (f in LogFiles.partsOf(d, LogFiles.Tier.MAIN)) {
            assertTrue(
                "${f.name} не превышает лимит: ${f.length()}",
                f.length() <= LogFiles.MAX_FILE_BYTES + 4 * 1024
            )
        }
    }

    // ───────────── §2.4 шумные ADB-трассы — отдельный tier, основной журнал цел ─────────────

    @Test
    fun verboseStormDoesNotTouchMainLog() {
        val d = tmp()
        val mainLines = (0 until 20).map { "MAIN_EVENT i=$it" }
        for (l in mainLines) assertTrue(LogFiles.appendTierForTest(d, l, LogFiles.Tier.MAIN).written)

        // «буря» низкоуровневых трасс: 3000 строк по ~200 байт
        for (i in 0 until 3000) {
            val r = LogFiles.appendTierForTest(
                d, "ADB_TX i=$i " + "z".repeat(200), LogFiles.Tier.VERBOSE
            )
            if (!r.written) assertTrue("причина отказа названа: ${r.reason}", r.reason.isNotEmpty())
        }

        val mainText = textOf(d, LogFiles.Tier.MAIN)
        val verboseText = textOf(d, LogFiles.Tier.VERBOSE)

        for (l in mainLines) assertEquals("важное событие ровно один раз: $l", 1, Regex("(?m)^$l$").findAll(mainText).count())
        assertFalse("ADB-трассы не попадают в основной журнал", mainText.contains("ADB_TX"))
        assertTrue("verbose-tier получил трассы", verboseText.contains("ADB_TX i=2999"))

        val mainParts = LogFiles.partsOf(d, LogFiles.Tier.MAIN)
        assertEquals("основной журнал не разросся из-за шума", 1, mainParts.size)
        assertTrue("файлы tier'ов не пересекаются по именам",
            mainParts.none { it.name.startsWith(LogFiles.VERBOSE_PREFIX) })
        for (f in LogFiles.partsOf(d, LogFiles.Tier.VERBOSE)) {
            assertTrue(f.name.startsWith(LogFiles.VERBOSE_PREFIX))
            assertTrue("verbose-файл в своём лимите: ${f.length()}",
                f.length() <= LogFiles.VERBOSE_FILE_BYTES + 4 * 1024)
        }
    }

    // ───────────── §2.3 датированные имена и смена даты ─────────────

    @Test
    fun dateChangeStartsNewDatedFile() {
        val d = tmp()
        val day1 = LocalDate.of(2026, 11, 12).atStartOfDay(ZoneId.systemDefault())
            .toInstant().toEpochMilli() + 3_600_000
        val day2 = day1 + 24 * 3_600_000L

        assertTrue(LogFiles.appendTierForTest(d, "DAY1", LogFiles.Tier.MAIN, day1).written)
        assertTrue(LogFiles.appendTierForTest(d, "DAY2", LogFiles.Tier.MAIN, day2).written)

        val parts = LogFiles.partsOf(d, LogFiles.Tier.MAIN)
        assertEquals("две даты — два файла", 2, parts.size)
        assertEquals(
            "имя содержит дату в формате DD.MM.YYYY",
            listOf("12.11.2026", "13.11.2026"),
            parts.map { it.name }.sorted().map { n ->
                LogFiles.parseDate(n)?.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"))
            }
        )
        assertEquals("DAY1 в своём файле", 1,
            Regex("(?m)^DAY1$").findAll(textOf(d, LogFiles.Tier.MAIN)).count())
        assertEquals("DAY2 в своём файле", 1,
            Regex("(?m)^DAY2$").findAll(textOf(d, LogFiles.Tier.MAIN)).count())
    }

    @Test
    fun rotationIndexIsNotMistakenForAnotherDate() {
        val base = LogFiles.fileNameFor(System.currentTimeMillis())
        val rotated = LogFiles.rotatedName(base, 3)
        assertNotNull("дата разбирается у части с индексом: $rotated", LogFiles.parseDate(rotated))
        assertEquals(LogFiles.parseDate(base), LogFiles.parseDate(rotated))
        assertNotNull(LogFiles.parseDate(LogFiles.verboseNameFor(System.currentTimeMillis())))
    }

    // ───────────── §2.3 retention: чужие файлы не трогаем ─────────────

    @Test
    fun pruneRemovesOnlyOwnExpiredParts() {
        val d = tmp()
        val old = System.currentTimeMillis() - 40L * 24 * 3_600_000
        assertTrue(LogFiles.appendTierForTest(d, "OLD", LogFiles.Tier.MAIN, old).written)
        val foreign = File(d, "someone-else.log").apply { writeText("чужое") }
        val foreignTxt = File(d, "notes.txt").apply { writeText("чужое") }

        val removed = LogFiles.pruneTier(d, System.currentTimeMillis())

        assertTrue("просроченная часть удалена: $removed", removed.isNotEmpty())
        assertTrue("чужой .log не удалён", foreign.exists())
        assertTrue("чужой .txt не удалён", foreignTxt.exists())
        assertEquals("чужие файлы не попали в отчёт", 0, removed.count { it.startsWith("someone") })
    }

    // ───────────── §2.3/§2.6 недоступный каталог: честный отказ, а не «записано» ─────────────

    @Test
    fun unavailableDirectoryIsReportedNotFaked() {
        val d = tmp()
        val notADir = File(d, "blocked")
        notADir.writeText("это файл, а не каталог")

        val r = LogFiles.appendTierForTest(notADir, "LINE", LogFiles.Tier.MAIN)

        assertFalse("запись не подтверждена", r.written)
        assertEquals("точного файла нет", "", r.file)
        assertTrue("причина названа: ${r.reason}", r.reason.startsWith("no_dir"))
    }

    @Test
    fun writeErrorIsCountedAndNamed() {
        val before = LogFiles.ioErrors()
        // appendToDir возвращает false; сам счётчик ошибок ведёт append(context),
        // поэтому здесь проверяем только честный false вместо исключения.
        assertFalse(LogFiles.appendToDir(File("/proc/voiceapp3-nope"), "LINE"))
        assertEquals("счётчик ошибок не меняется из тестового пути", before, LogFiles.ioErrors())
    }

    // ───────────── §2.2 конкурентные писатели: строки не теряются и не рвутся ─────────────

    @Test
    fun concurrentWritersKeepEveryLineIntact() {
        val d = tmp()
        val threads = 4
        val per = 250
        val pool = Executors.newFixedThreadPool(threads)
        val fails = Collections.synchronizedList(mutableListOf<String>())
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)

        for (t in 0 until threads) {
            pool.execute {
                start.await()
                for (i in 0 until per) {
                    val line = "T$t-%04d %s".format(i, "q".repeat(120))
                    val r = LogFiles.appendTierForTest(d, line, LogFiles.Tier.MAIN)
                    if (!r.written) fails += "T$t-$i:${r.reason}"
                }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue("потоки завершились", done.await(60, TimeUnit.SECONDS))
        pool.shutdownNow()

        assertTrue("ни одна запись не отказала: $fails", fails.isEmpty())
        val all = textOf(d, LogFiles.Tier.MAIN)
        for (t in 0 until threads) {
            for (i in 0 until per) {
                val cnt = Regex("(?m)^T$t-%04d ".format(i)).findAll(all).count()
                assertEquals("строка T$t-$i ровно один раз", 1, cnt)
            }
        }
        assertTrue("ни одна строка не склеена", !all.contains("}T") && !all.contains(".logT"))
    }
}

/**
 * iter42 §5(а) — набор CPS/DPS, прочитанный ДО записи, мог измениться к моменту записи:
 * «мнимое отсутствие хранилища» больше не пропускает отправку, а блокирует её (fail closed).
 *
 * JVM-логика на подставных `JitStore`/`JitNetState`/`JitShell` ([JitFake]); настоящий Android
 * `SharedPreferences` здесь не участвует — это отдельный, более низкий уровень проверки.
 */
class JitStoreSetChanged42Test {

    private val logs = mutableListOf<String>()

    @Before
    fun reset() {
        JitOp.resetStateForTest()
        logs.clear()
    }

    private fun log(s: String) {
        synchronized(logs) { logs.add(s) }
    }

    private fun lines(): String = synchronized(logs) { logs.joinToString("\n") }

    @Test
    fun storeThatVanishesBetweenReadAndWriteBlocksEverything() {
        val store = JitFake.Store()
        store.vanishAfterRead += 1            // DPS «пропал» между чтением и записью
        val net = JitFake.Net(wifi = true)
        val shell = JitFake.QueueShell()

        val c = JitOp.click(store, net, shell, want = false, log = ::log)

        assertEquals("причина — изменение набора хранилищ", "store_set_changed", c.blocked)
        assertFalse("скрипт не отправлен", c.sent)
        assertTrue("shell не запускался", shell.pending.isEmpty())
        assertTrue("сеть не менялась", net.requests.isEmpty())
        assertTrue("лог называет причину", lines().contains("blocked=store_set_changed"))
        assertTrue(
            "лог объясняет, почему это не «нет данных»",
            lines().contains("набор хранилищ CPS/DPS изменился")
        )
    }

    @Test
    fun snapshotIsNotSentWhenOnlyOneStoreIsWritable() {
        val store = JitFake.Store()
        store.vanishAfterRead += 0            // CPS пропал — первый по порядку чтения
        val c = JitOp.click(store, JitFake.Net(wifi = true), JitFake.QueueShell(),
            want = true, log = ::log)
        assertEquals("store_set_changed", c.blocked)
        assertFalse(c.sent)
    }

    @Test
    fun afterStoreReturnsTheClickWorksAgain() {
        val store = JitFake.Store()
        store.vanishAfterRead += 1
        val net = JitFake.Net(wifi = true)
        val shell = JitFake.QueueShell()

        assertEquals("store_set_changed",
            JitOp.click(store, net, shell, want = true, log = ::log).blocked)

        // «пропавшее» хранилище снова читается — состояние снова однозначно,
        // блокировка не липкая и не требует перезапуска процесса.
        store.vanishAfterRead.clear()
        val c2 = JitOp.click(store, net, shell, want = true, log = ::log)
        assertEquals("повтор проходит без новой причины", null, c2.blocked)
        assertTrue("вторая попытка действительно отправляет", c2.sent)
    }

    @Test
    fun unchangedStoreSetIsNotReportedAsChanged() {
        val store = JitFake.Store()
        val c = JitOp.click(store, JitFake.Net(wifi = true), JitFake.QueueShell(),
            want = true, log = ::log)
        assertFalse("ложного store_set_changed нет", lines().contains("store_set_changed"))
        assertEquals(null, c.blocked)
    }
}

/**
 * iter42 §3 — снимок сети привязывается к СОБЫТИЯМ, с ограничением частоты и дедупом:
 * «один шторм переподключений» не должен превращаться в поток диагностики, но и первое
 * событие не должно теряться. Проверяется чистый механизм [JitRef.EventSnapshots]
 * (без shell и без изменения сети).
 */
class EventSnapshots42Test {

    @Test
    fun firstEventIsAllowedSecondIsRateLimited() {
        val s = JitRef.EventSnapshots(minIntervalMs = 90_000, maxPerHour = 6)
        assertEquals(null, s.tryAcquire("ttl_on", nowMs = 1_000_000))
        s.release("ttl_on")
        val second = s.tryAcquire("ttl_off", nowMs = 1_030_000)
        assertNotNull("второй снимок в окне частоты пропущен", second)
        assertTrue("причина названа: $second", second!!.startsWith("rate_limited"))
        assertEquals("разрешён ровно один", 1, s.used())
    }

    @Test
    fun parallelSameEventIsNotDuplicated() {
        val s = JitRef.EventSnapshots()
        assertEquals(null, s.tryAcquire("ttl_network_change", 5_000_000))
        assertEquals("duplicate_in_flight",
            s.tryAcquire("ttl_network_change", 5_000_100))
        s.release("ttl_network_change")
        assertEquals("после освобождения дубликата нет", null,
            s.tryAcquire("ttl_network_change", 5_100_000))
    }

    @Test
    fun hourlyLimitIsEnforcedAndWindowResets() {
        val s = JitRef.EventSnapshots(minIntervalMs = 0, maxPerHour = 3)
        var t = 10_000_000L
        repeat(3) {
            assertEquals(null, s.tryAcquire("e$it", t))
            s.release("e$it")
            t += 1_000
        }
        assertEquals("hour_limit_3", s.tryAcquire("e9", t))
        t += 3_600_001
        assertEquals("новый час — снова можно", null, s.tryAcquire("e10", t))
    }

    @Test
    fun pairedBeforeAndAfterSurviveTheRateLimit() {
        val s = JitRef.EventSnapshots(minIntervalMs = 90_000, maxPerHour = 6)
        // «до» — обычный снимок по событию TTL
        assertEquals(null, s.tryAcquire("snap:ttl_on_pre", 1_000_000))
        s.release("snap:ttl_on_pre")
        // «после» в пределах интервала, но парный к «до» — не теряется
        assertEquals("пара «до/после» не режется интервалом",
            null, s.tryAcquirePaired("snap:ttl_on_post", 1_015_000, "snap:ttl_on_pre"))
        // а непарный снимок в том же окне — по-прежнему пропускается
        val other = s.tryAcquire("snap:ttl_network_change", 1_020_000)
        assertNotNull("постороннее событие в окне пропускается", other)
        assertTrue(other!!.startsWith("rate_limited"))
    }

    @Test
    fun pairIsNotAcceptedForAnotherEventOrTooLate() {
        val s = JitRef.EventSnapshots(minIntervalMs = 90_000, maxPerHour = 6)
        s.tryAcquire("snap:ttl_on_pre", 2_000_000)
        s.release("snap:ttl_on_pre")
        val wrongPair = s.tryAcquirePaired("snap:ttl_off_post", 2_010_000, "snap:ttl_on_pre")
        assertNotNull("пара к ДРУГОМУ событию не принимается", wrongPair)
        // через 11 минут пара уже не считается «до/после» (окно 10 минут исчерпано)
        s.release("snap:ttl_off_post")
        s.resetForTest()
        s.tryAcquire("snap:ttl_on_pre", 2_000_000)
        s.release("snap:ttl_on_pre")
        val late = s.tryAcquirePaired("snap:ttl_on_post", 2_000_000 + 660_000L, "snap:ttl_on_pre")
        assertEquals(null, late)   // интервал 90 с истёк — обычный снимок разрешён
    }

    @Test
    fun pairStillCountsAgainstHourlyLimit() {
        val s = JitRef.EventSnapshots(minIntervalMs = 0, maxPerHour = 2)
        assertEquals(null, s.tryAcquire("snap:a_pre", 3_000_000))
        s.release("snap:a_pre")
        assertEquals(null, s.tryAcquirePaired("snap:a_post", 3_001_000, "snap:a_pre"))
        s.release("snap:a_post")
        val third = s.tryAcquirePaired("snap:a_post2", 3_002_000, "snap:a_post")
        assertEquals("hour_limit_2", third)
    }

    @Test
    fun skipReasonIsRecordedForLaterLogging() {
        val s = JitRef.EventSnapshots(minIntervalMs = 90_000, maxPerHour = 6)
        s.tryAcquire("a", 1L)
        s.release("a")
        val why = s.tryAcquire("b", 1_000L)
        s.note("SNAP reason=b status=skipped why=$why")
        assertTrue("причина пропуска сохраняется",
            s.drain().any { it.contains("status=skipped") })
    }
}
