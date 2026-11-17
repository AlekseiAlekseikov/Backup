package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * iter62 §B2 — ЖУРНАЛ НЕ ТЕРЯЕТ И НЕ ДУБЛИРУЕТ ЗАПИСИ.
 *
 * Владелец видел в логе обрезанные записи. Проверяется весь путь записи на РЕАЛЬНЫХ файлах
 * (production-код [FileLogCore], без Android):
 *  1. ротация не рвёт строки: каждая строка в сохранённых частях — целая запись с маркером;
 *  2. ни один маркер в сохранённых частях не встречается дважды (в том числе на границе `.new`);
 *  3. восстановление «повисшего» `.new` не дублирует строку, уже попавшую в активный файл;
 *  4. в строки журнала не попадают IP/MAC/SSID (редактирование — production [LogRedact]).
 *
 * Чего тест НЕ доказывает: поведение внешнего хранилища на ГУ и слышимость владельцем
 * (это ⛔ в отчёте), а также то, что старые части не удаляются по бюджету — удаление старых
 * частей это бюджет, а не потеря: здесь проверяется, что среди СОХРАНЁННЫХ строк нет рванья и
 * дублей.
 */
class Iter62LogMarkerTest {

    private val dir: File = File.createTempFile("log62", "").let { it.delete(); it.mkdirs(); it }

    private fun linesOf(f: File): List<String> =
        if (!f.isFile) emptyList() else f.readLines().filter { it.isNotEmpty() }

    private fun marker(i: Int): String = "M%04d".format(i)

    // ───────────── 1. Ротация: ни рванья, ни дублей ─────────────

    @Test
    fun rotationNeverTearsOrDuplicatesLines() {
        val n = 120
        val name = "app.log"
        for (i in 0 until n) {
            // Каждая строка заметно больше 1 байта и содержит маркер, чтобы обрыв был виден.
            FileLogCore.append(dir, name, "${marker(i)} tail_payload_${"x".repeat(40)}\n", maxBytes = 900)
        }
        val parts = listOf(File(dir, name), File(dir, "$name.1"), File(dir, "$name.new"))
        val kept = parts.flatMap { linesOf(it) }

        assertTrue("журнал вообще писался: ${kept.size}", kept.isNotEmpty())
        // 1) Ни одной разорванной строки: каждая начинается с маркера и содержит весь хвост.
        kept.forEach { line ->
            assertTrue("строка разорвана: <$line>", Regex("^M\\d{4} tail_payload_x{40}$").matches(line))
        }
        // 2) Дублей нет.
        val dup = kept.groupingBy { it }.eachCount().filterValues { it > 1 }
        assertTrue("дубли строк в сохранённых частях: $dup", dup.isEmpty())
        // 3) Дыр из нулевых байтов нет (владелец видел именно их).
        parts.forEach { p ->
            if (p.isFile) {
                assertEquals("нулевые байты в хвосте ${p.name}", 0, Toggles45.trailingNulBytes(p.readBytes()))
            }
        }
        // 4) Порядок внутри части — возрастающий: ротация не перемешивает записи.
        linesOf(File(dir, name)).zipWithNext().forEach { (a, b) ->
            assertTrue("записи перемешаны: $a / $b", a < b)
        }
    }

    // ───────────── 2. Восстановление `.new` без дублей ─────────────

    @Test
    fun pendingNewIsMergedWithoutDuplicatingTheBoundaryLine() {
        val name = "voiceapp3.log"
        val active = File(dir, name)
        active.writeText("M0001 a\nM0002 b\n")
        // `.new` содержит ТУ ЖЕ последнюю строку (падение после записи в `.new`) и новую.
        val pending = File(dir, "$name.new")
        pending.writeText("M0002 b\nM0003 c\n")

        FileLogCore.recoverPendingNew(dir, name)

        val merged = linesOf(active)
        assertTrue("новая строка перенесена: $merged", merged.contains("M0003 c"))
        assertEquals("граничная строка не задвоена: $merged", 1, merged.count { it == "M0002 b" })
        assertEquals("журнал содержит ровно три записи: $merged", 3, merged.size)
    }

    @Test
    fun pendingNewSurvivesWhenActiveIsMissing() {
        val name = "voiceapp3b.log"
        val pending = File(dir, "$name.new")
        pending.writeText("M0100 kept\n")
        // Активного файла нет — повышать не во что: отчёт обязан это назвать, а не «потерять».
        val reason = FileLogCore.recoverPendingNew(dir, name)
        assertTrue("причина названа: $reason", reason.isNotEmpty())
        assertTrue("данные остались на месте", pending.isFile && pending.readText().contains("M0100"))
    }

    // ───────────── 3. В лог не уходят секреты ─────────────

    @Test
    fun secretsAreRedactedBeforeWriting() {
        val raw = "ssid=HomeNet bssid=AA:BB:CC:DD:EE:FF ip=192.168.1.77 mac=11:22:33:44:55:66"
        val out = LogRedact.text(raw, 300)
        listOf("HomeNet", "AA:BB:CC:DD:EE:FF", "192.168.1.77", "11:22:33:44:55:66").forEach { secret ->
            assertTrue("секрет «$secret» попал в строку журнала: $out", !out.contains(secret))
        }
    }
}
