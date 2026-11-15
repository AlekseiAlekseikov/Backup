package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

/**
 * iter57 §9 — отчёт о логах не должен врать о МЕСТЕ.
 *
 * Аудит iter56 указал `FileLogCore.kt:255–257`: когда слияние `.new` нельзя подтвердить на
 * диске, свидетельство остаётся, но отчёт называет только ИМЯ файла. Владелец ищет логи в
 * `getExternalFilesDir(null)/logs`, а при фоллбэке файлы лежат в `filesDir/logs` — «логов нет»
 * читается как «файлы не созданы».
 *
 * Здесь проверяется:
 *  1. заметка о сохранённом свидетельстве содержит ПОЛНЫЙ путь и исходный каталог;
 *  2. отказ `rename` не выдаётся за успех: свидетельство остаётся по исходному пути, и это
 *     названо (`moved=0`);
 *  3. выбор каталога логов называет источник (`external` / `internal_fallback` с причиной).
 *
 * Чего тесты НЕ доказывают: поведение реальной ФС ГУ (в тестах — обычная ФС JVM) и что
 * `getExternalFilesDir` на ГУ действительно отдаёт внешний каталог.
 */
class Iter57LogTest {

    private fun dir(): File = File.createTempFile("it57log", "").let { it.delete(); it.mkdirs(); it }

    @Test
    fun keptEvidenceNamesTheFullLocationNotJustTheName() {
        val d = dir()
        val name = "voiceapp3-2026-10-22.log"
        val active = File(d, name)
        active.writeText("строка активного\n")
        val pending = File(d, "$name.new")
        pending.writeText("строка из new\n")
        // ФС приняла запись, но подтвердить её на диске не может.
        val oldProbe = FileLogCore.durableProbe
        FileLogCore.durableProbe = { false }
        val note = try { FileLogCore.recoverPendingNew(d, name) } finally {
            FileLogCore.durableProbe = oldProbe
        }

        assertTrue("свидетельство сохранено и это названо: $note",
            note.startsWith("new_merge_unsynced_kept_as="))
        assertTrue("в заметке ПОЛНЫЙ путь, а не только имя: $note",
            note.contains("at=${d.absolutePath}"))
        assertTrue("исходный каталог назван: $note", note.contains("origin=${d.absolutePath}"))
        assertTrue("перенос назван состоявшимся: $note", note.contains("moved=true"))
        val kept = d.listFiles()!!.filter { it.isFile && it.name.contains(".unsynced.") }
        assertEquals("ровно одно свидетельство: ${d.listFiles()!!.map { it.name }}", 1, kept.size)
        assertTrue("свидетельство не пуст, иначе строки потеряны", kept[0].length() > 0L)
        assertTrue("строки внутри свидетельства", kept[0].readText().contains("из new"))
    }

    @Test
    fun renameFailureIsNotReportedAsSuccessAndKeepsTheOriginalPlace() {
        val d = dir()
        val name = "voiceapp3-2026-10-22.log"
        val active = File(d, name)
        active.writeText("активный\n")
        val pending = File(d, "$name.new")
        pending.writeText("из new\n")
        // Отказ переименования: цель с ТЕМ ЖЕ именем уже занята непустым каталогом.
        val stamp = 1_700_000_123_456L
        val blocker = File(d, "$name.new.unsynced.$stamp")
        blocker.mkdirs()
        blocker.resolve("x").writeText("x")

        val oldProbe = FileLogCore.durableProbe
        val oldClock = FileLogCore.clock
        FileLogCore.durableProbe = { false }
        FileLogCore.clock = { stamp }
        val note = try { FileLogCore.recoverPendingNew(d, name) } finally {
            FileLogCore.durableProbe = oldProbe
            FileLogCore.clock = oldClock
        }

        assertTrue("отказ переименования назван, а не выдан за перенос: $note",
            note.contains("moved=false"))
        assertTrue("место названо исходным (файл остался там же): $note",
            note.contains("at=${pending.absolutePath}") &&
                note.contains("origin=${d.absolutePath}"))
        assertTrue("запись не потеряна: свидетельство на месте",
            pending.isFile && pending.readText().contains("из new"))
        assertFalse("успешное слияние не заявлено", note.contains("deleted=true"))
    }

    @Test
    fun logDirectoryChoiceNamesItsSource() {
        // Внешний каталог недоступен (null) ⇒ внутренний фоллбэк, названный причиной.
        LogFiles.lastDirChoice = "unknown"
        val home = dir()
        val chosen = LogFiles.dirFor({ null }, { home })
        assertTrue("каталог внутри filesDir: $chosen",
            chosen.absolutePath.startsWith(home.absolutePath))
        assertTrue("источник назван: ${LogFiles.lastDirChoice}",
            LogFiles.lastDirChoice.startsWith("internal_fallback") &&
                LogFiles.lastDirChoice.contains("reason=external_unavailable") &&
                LogFiles.lastDirChoice.contains(chosen.absolutePath))
    }

    @Test
    fun unavailableExternalIsNotSilentlyPresentedAsTheUsualPlace() {
        LogFiles.lastDirChoice = "unknown"
        LogFiles.dirFor({ dir() }, { dir() })
        assertTrue("внешний каталог назван как usual place: ${LogFiles.lastDirChoice}",
            LogFiles.lastDirChoice.startsWith("external") &&
                LogFiles.lastDirChoice.contains("path="))
    }

}
