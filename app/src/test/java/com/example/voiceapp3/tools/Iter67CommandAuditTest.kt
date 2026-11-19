package com.example.voiceapp3.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iter67 §F — разделители в многошаговых командах.
 *
 * Живая строка 1.0.59 (09.11):
 * `cmd=setprop dalvik.vm.usejit true 2>/dev/null setprop dalvik.vm.usejitprofiles true … → SUCCESS`.
 */
class Iter67CommandAuditTest {

    @Test
    fun `команда без разделителей обнаруживается`() {
        val bad = "setprop dalvik.vm.usejit true 2>/dev/null setprop dalvik.vm.usejitprofiles true"
        // fail-before: объекта `CommandAudit` не существовало, склейка не ловилась.
        assertTrue("склейка не найдена: ${CommandAudit.missingSeparators(bad)}",
            CommandAudit.missingSeparators(bad).isNotEmpty())
    }

    @Test
    fun `правильная цепочка не даёт срабатываний`() {
        val good = "setprop a 1; setprop b 2; echo RT=$(getprop a)"
        assertTrue("ложное срабатывание: ${CommandAudit.missingSeparators(good)}",
            CommandAudit.missingSeparators(good).isEmpty())
    }

    @Test
    fun `многострочная цепочка с переводами строк — не склейка`() {
        val multiline = JitRef.chainScriptForTest(want = true, guard = "")
        assertTrue("ложное срабатывание на многострочной цепочке",
            CommandAudit.missingSeparators(multiline).isEmpty())
    }

    @Test
    fun `истинный литерал скрипта цепочки проходит проверку`() {
        val script = JitRef.chainScriptForTest(want = true, guard = "")
        assertTrue("срабатывание на боевом скрипте: ${CommandAudit.missingSeparators(script)}",
            CommandAudit.missingSeparators(script).isEmpty())
    }

    @Test
    fun `команды без getprop setprop не проверяются`() {
        assertTrue(CommandAudit.missingSeparators("echo a echo b").isEmpty())
    }

    @Test
    fun `строка журнала называет место и запрет отправки`() {
        val line = CommandAudit.suspectLine(
            "setprop dalvik.vm.usejit true 2>/dev/null setprop ctl.restart zygote"
        )
        assertNotNull("нет строки для подозрительной команды", line)
        assertTrue("нет CMD_SUSPECT: $line", line!!.startsWith("CMD_SUSPECT missing_sep="))
        assertTrue("нет места: $line", line.contains("после: "))
        assertTrue("нет запрета отправки: $line", line.contains("action=no_send"))
        // Правильная команда — сообщать нечего.
        assertTrue(CommandAudit.suspectLine("setprop a 1; setprop b 2") == null)
    }

    @Test
    fun `в транспорте склейка не отправляется, а называется`() {
        val src = java.io.File("app/src/main/java/com/example/voiceapp3/tools/AdbClient.kt").readText()
        assertTrue("транспорт не проверяет команду", src.contains("CommandAudit.suspectLine(cmd)"))
        assertTrue("нет честной ошибки", src.contains("ERR_CMD_MISSING_SEPARATOR"))
    }

    @Test
    fun `журнал не усекает setprop и getprop до 120 символов`() {
        val long = "setprop dalvik.vm.usejit true; echo RT=$(getprop dalvik.vm.usejit); " +
            "echo PS=$(getprop persist.dalvik.vm.usejitprofiles); echo DONE_MARKER_AT_END"
        val redacted = LogRedact.cmd(long, 120)
        // fail-before: строка обрывалась на 120 символах и потеря разделителя была не видна.
        assertTrue("команда усечена, хвост потерян: $redacted", redacted.contains("DONE_MARKER_AT_END"))
        assertFalse("командный текст вырезан целиком", redacted.isEmpty())
        // Обычные команды по-прежнему ограничены.
        val plain = LogRedact.cmd("echo " + "x".repeat(400), 120)
        assertTrue("для обычных команд лимит должен остаться", plain.length <= 130)
    }
}
