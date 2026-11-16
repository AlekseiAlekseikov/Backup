package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * iter58 §4 — КОД ВОЗВРАТА ДОСТАВКИ: `.done` не должен врать.
 *
 * Дефект iter57 (`ScriptDelivery.kt:288–298`): в скрипте с `terminalPath` код возврата снимался
 * ДВА раза. Второй `_VRC=$?` попадал на собственную строку обёртки (`echo "VAPP_DIRECT_DONE …"`),
 * поэтому при payload с `exit 7` на устройстве оставался `<path>.done` = `rc=0`, а вызывающий
 * видел «завершено с успехом». Это ровно тот случай, когда терминальный факт подменялся
 * собственным выводом обёртки.
 *
 * Здесь исполняется НАСТОЯЩИЙ сгенерированный скрипт настоящим `/bin/sh` — проверяется файл
 * `.done`, stdout-маркеры и внешний код, а не намерение кода.
 */
class Iter58DeliveryRcTest {

    private val dir: File = File.createTempFile("dlv58", "").let { f ->
        f.delete(); f.mkdirs(); f
    }

    private fun run(script: String): Pair<Int, String> {
        val f = File(dir, "direct.sh")
        f.writeText(script)
        val p = ProcessBuilder("/bin/sh", f.absolutePath).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(60, TimeUnit.SECONDS)
        return p.exitValue() to out
    }

    /** Файл, который пишет сам скрипт: `directScript` добавляет к переданному пути суффикс `.done`. */
    private fun terminal(): File = File(dir, "op58.done")
    private fun terminalBase(): String = File(dir, "op58").absolutePath

    /** Тело payload с заданным кодом выхода. */
    private fun payload(rc: Int, extra: String = ""): String =
        "echo payload_ran$extra\nexit $rc\n"

    private fun prepared(rc: Int, extra: String = ""): String {
        val prep = ScriptDelivery.prepare("iter58_rc", payload(rc, extra), "nonce58")
        return ScriptDelivery.directScript(prep.script, prep.nonce, terminalBase())
    }

    @Test
    fun payloadExitSevenIsRecordedAndPropagated() {
        terminal().delete()
        val (rc, out) = run(prepared(7))

        assertTrue("payload действительно исполнялся: $out", out.contains("payload_ran"))
        assertTrue("маркер завершения с настоящим кодом: $out",
            out.contains("VAPP_DIRECT_DONE_nonce58 rc=7"))
        assertTrue("terminal записан: $out",
            out.contains("VAPP_DIRECT_TERMINAL_nonce58 written=1"))
        assertTrue("файл .done существует", terminal().exists())
        assertEquals("в .done лежит КОД PAYLOAD, а не код обёртки", "rc=7",
            terminal().readText().trim())
        assertEquals("внешний код возврата = код payload", 7, rc)
        assertFalse("ни в выводе, ни в файле нет ложного rc=0",
            terminal().readText().contains("rc=0"))
    }

    @Test
    fun exitOneTwentySevenIsNotCollapsedToZero() {
        terminal().delete()
        val (rc, out) = run(prepared(127))

        assertTrue("код 127 назван в маркере: $out", out.contains("VAPP_DIRECT_DONE_nonce58 rc=127"))
        assertEquals("в .done — 127", "rc=127", terminal().readText().trim())
        assertEquals("внешний код 127", 127, rc)
    }

    @Test
    fun successfulPayloadIsZeroAndNothingIsInvented() {
        terminal().delete()
        val (rc, out) = run(prepared(0))

        assertTrue("маркер с rc=0: $out", out.contains("VAPP_DIRECT_DONE_nonce58 rc=0"))
        assertEquals("в .done — 0", "rc=0", terminal().readText().trim())
        assertEquals("внешний код 0", 0, rc)
    }

    @Test
    fun refusedTerminalWriteIsReportedAsNotWritten() {
        // Родительского каталога нет: запись `.done` физически не удаётся.
        val missing = File(dir, "no_such_dir/op58").absolutePath
        val prep = ScriptDelivery.prepare("iter58_rc", payload(7), "nonce58")
        val script = ScriptDelivery.directScript(prep.script, prep.nonce, missing)

        val (rc, out) = run(script)

        assertTrue("отказ записи назван: $out",
            out.contains("VAPP_DIRECT_TERMINAL_nonce58 written=0"))
        assertFalse("успеха записи не заявлено", out.contains("written=1"))
        assertFalse("файла нет", File("$missing.done").exists())
        assertEquals("код payload всё равно возвращён", 7, rc)
    }
}
