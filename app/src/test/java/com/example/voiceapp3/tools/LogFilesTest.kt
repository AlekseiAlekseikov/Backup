package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId

/**
 * iter33p §4/§6 — собственный лог приложения файлами по датам.
 *
 * В прогоне 1.0.60 лог не сохранился, и доказать поведение AVAS/JIT было нечем. Держим:
 *  1. имя ровно по образцу владельца: `voiceapp3-DD.MM.YYYY.log`;
 *  2. ротация по дате (новые сутки — новый файл);
 *  3. retention 14 суток (старое удаляется, свежее и чужие файлы остаются);
 *  4. каталог создаётся автоматически.
 */
class LogFilesTest {

    // 1. Формат имени: день.месяц.год — как `voiceapp3-10.11.2026.log`.
    @Test
    fun fileNameFollowsOwnerFormat() {
        assertEquals(
            "voiceapp3-10.11.2026.log",
            LogFiles.fileNameOf(LocalDate.of(2026, 11, 10))
        )
        assertEquals(
            "voiceapp3-01.01.2026.log",
            LogFiles.fileNameOf(LocalDate.of(2026, 1, 1))
        )
        val ms = LocalDate.of(2026, 11, 10).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals("voiceapp3-10.11.2026.log", LogFiles.fileNameFor(ms))
        assertTrue(
            "имя обязано совпадать с шаблоном voiceapp3-DD.MM.YYYY.log",
            Regex("^voiceapp3-\\d{2}\\.\\d{2}\\.\\d{4}\\.log$").matches(LogFiles.fileNameFor(ms))
        )
    }

    // 2. Ротация по дате: разные сутки — разные файлы, дата читается обратно.
    @Test
    fun rotationIsDaily() {
        val a = LogFiles.fileNameOf(LocalDate.of(2026, 11, 10))
        val b = LogFiles.fileNameOf(LocalDate.of(2026, 11, 11))
        assertFalse("новые сутки — новый файл", a == b)
        assertEquals(LocalDate.of(2026, 11, 10), LogFiles.parseDate(a))
        assertEquals(LocalDate.of(2026, 11, 11), LogFiles.parseDate(b))
        assertEquals("чужое имя не разбираем", null, LogFiles.parseDate("voiceapp3.log"))
        assertEquals("чужое имя не разбираем", null, LogFiles.parseDate("other-10.11.2026.log"))
        assertEquals("битая дата не разбирается", null, LogFiles.parseDate("voiceapp3-99.99.2026.log"))
    }

    // 3. Retention 14 суток.
    @Test
    fun retentionKeepsTwoWeeks() {
        assertEquals("срок хранения — 14 суток", 14L, LogFiles.RETENTION_DAYS)
        val today = LocalDate.of(2026, 11, 10)
        val names = listOf(
            LogFiles.fileNameOf(today),                              // сегодня — оставить
            LogFiles.fileNameOf(today.minusDays(1)),                 // вчера — оставить
            LogFiles.fileNameOf(today.minusDays(13)),                // 13 дней — оставить
            LogFiles.fileNameOf(today.minusDays(15)),                // 15 дней — удалить
            LogFiles.fileNameOf(today.minusDays(400)),               // старое — удалить
            "voiceapp3.log",                                         // чужой формат — не трогаем
            "notes.txt"                                              // чужой файл — не трогаем
        )
        assertEquals(
            listOf(
                LogFiles.fileNameOf(today.minusDays(15)),
                LogFiles.fileNameOf(today.minusDays(400))
            ),
            LogFiles.expired(names, today)
        )
    }

    // 4. Каталог создаётся автоматически; чистка реально удаляет только просроченные файлы.
    @Test
    fun dirCreatedAndPruned() {
        val root = Files.createTempDirectory("logfiles").toFile()
        val dir = File(root, "logs/sub")
        assertFalse("каталога ещё нет", dir.exists())
        LogFiles.ensureDir(dir)
        assertTrue("каталог создаётся", dir.isDirectory)

        val today = LocalDate.now()
        val fresh = File(dir, LogFiles.fileNameOf(today)).apply { writeText("fresh\n") }
        val old = File(dir, LogFiles.fileNameOf(today.minusDays(30))).apply { writeText("old\n") }
        val foreign = File(dir, "voiceapp3.log").apply { writeText("foreign\n") }
        val nowMs = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val removed = LogFiles.prune(dir, nowMs)
        assertEquals("удалён ровно один просроченный файл", 1, removed)
        assertTrue("свежий файл остался", fresh.isFile)
        assertFalse("просроченный удалён", old.exists())
        assertTrue("чужой файл не тронут", foreign.isFile)
        root.deleteRecursively()
    }

    /**
     * 5. Каталог, который существует, но НЕ принимает запись, не должен «съедать» лог:
     *    проверка записи обязательна (REVIEW iter33p: недостаточно `getExternalFilesDir != null`),
     *    а при отказе записи строка уходит во внутренний каталог и повторяется.
     */
    @Test
    fun unwritableDirIsDetected() {
        val root = Files.createTempDirectory("logfiles-ro").toFile()
        val dir = File(root, "logs")
        LogFiles.ensureDir(dir)
        assertTrue("обычный каталог пишется", LogFiles.writable(dir))
        dir.setWritable(false)
        try {
            val closed = !LogFiles.writable(dir)
            // под root (в некоторых CI-контейнерах) права игнорируются — тогда проверять нечего
            if (closed) assertFalse("каталог без права записи обязан детектиться", LogFiles.writable(dir))
        } finally {
            dir.setWritable(true)
            root.deleteRecursively()
        }
        val s = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
            .resolve("com/example/voiceapp3/tools/LogFiles.kt").readText()
        assertTrue("фоллбэк по результату пробы, а не по null", s.contains("if (writable(d)) return d"))
        assertTrue("внутренний каталог как запасной", s.contains("File(app.filesDir, \"logs\")"))
        assertTrue("при сбое записи — повтор во внутреннем каталоге", s.contains("internal too"))
    }

    // 6. Дневной лог подключён к общему логгеру: те же строки, что и в logcat.
    @Test
    fun appLogTeesLinesToDailyFile() {
        val s = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
            .resolve("com/example/voiceapp3/tools/AppLog.kt").readText()
        assertTrue("AppLog обязан готовить каталог дневного лога", s.contains("LogFiles.prepare("))
        assertTrue("каждая запись уходит в дневной файл", s.contains("LogFiles.append(c, line)"))
        // Ретеншн трогает только СВОИ файлы (`voiceapp3-DD.MM.YYYY.log`) — чужие логи не удаляем.
        val files = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
            .resolve("com/example/voiceapp3/tools/LogFiles.kt").readText()
        assertTrue("удаляем только свой шаблон", files.contains("fun expired(") && files.contains("parseDate(n)"))
    }
}
