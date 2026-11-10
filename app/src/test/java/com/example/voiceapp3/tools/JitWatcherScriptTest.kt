package com.example.voiceapp3.tools

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iter33p-fix3/4 §1–§2 — тест НАСТОЯЩЕГО watcher-скрипта, а не подставленной строки.
 *
 * Скрипт из [JitNetModel.watcherScript] пишется во временный файл и ИСПОЛНЯЕТСЯ через `/bin/sh` с
 * подставными командами (`setprop`, `pidof`, `getprop`). Проверяется фактический порядок:
 *  1. `JIT_ZYGOTE_ARMED op=<id>` — ПЕРВАЯ строка, ДО `sleep` и ДО `ctl.restart`;
 *  2. каждая строка несёт `op=<id>`: старый результат не подтвердит новую операцию;
 *  3. отмена учитывает СОДЕРЖИМОЕ файла: `op=<наш id>` отменяет рестарт, чужой `op` — нет;
 *  4. после проверки отмены и непосредственно перед рестартом пишется `JIT_ZYGOTE_RESTARTING op=<id>`
 *     — гонка «отмена пришла между проверкой и рестартом» видна в файле, а не додумывается;
 *  5. отмена без записи в файл = `UNKNOWN` (нельзя утверждать «рестарта не будет»).
 */
class JitWatcherScriptTest {

    private fun shAvailable(): Boolean = listOf("/bin/sh", "/usr/bin/sh").any { File(it).canExecute() }

    private class Sandbox {
        val dir: File = File.createTempFile("jitwatch", "").let { f -> f.delete(); f.mkdirs(); f }
        val result: File get() = File(dir, "result.txt")
        val cancel: File get() = File(dir, "cancel")
        val calls: File get() = File(dir, "calls.log")

        fun content(): String = if (result.isFile) result.readText() else ""
        fun callsText(): String = if (calls.isFile) calls.readText() else ""

        /** Подставные `setprop`/`pidof`/`getprop`; каждый вызов пишется в calls.log. */
        fun installStubs() {
            val bin = File(dir, "bin").also { it.mkdirs() }
            fun exe(name: String, body: String) {
                val f = File(bin, name)
                f.writeText("#!/bin/sh\n$body\n")
                f.setExecutable(true)
            }
            exe("setprop", "echo \"setprop \$1\" >> ${calls.absolutePath}\n" +
                "if [ \"\$1\" = \"ctl.restart\" ]; then echo 'ctl.restart zygote' >> ${calls.absolutePath}; fi")
            // pidof: ДО рестарта 100/200, ПОСЛЕ — 101/201 (иначе цикл ожидания не завершится).
            exe("pidof", """
                if grep -q 'ctl.restart zygote' ${calls.absolutePath} 2>/dev/null; then
                  if [ "${'$'}1" = "zygote" ]; then echo 101; else echo 201; fi
                else
                  if [ "${'$'}1" = "zygote" ]; then echo 100; else echo 200; fi
                fi
            """.trimIndent())
            exe("getprop", "echo true")
            exe("sleep", "exit 0")   // важен порядок, а не время
        }

        /** Скрипт из ядра с подменёнными путями. */
        fun script(): File {
            val body = JitNetModel.watcherScript()
                .replace(JitNetModel.WATCHER_RESULT_PATH, result.absolutePath)
                .replace(JitNetModel.WATCHER_CANCEL_PATH, cancel.absolutePath)
            val f = File(dir, "watch.sh")
            f.writeText(body)
            f.setExecutable(true)
            return f
        }

        fun run(script: File, opId: String, want: String): Int {
            val pb = ProcessBuilder("/bin/sh", script.absolutePath, opId, want, "0", "0")
            pb.directory(dir)
            pb.environment()["PATH"] = File(dir, "bin").absolutePath + ":" + System.getenv("PATH")
            pb.redirectErrorStream(true)
            val p = pb.start()
            val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
            val code = p.waitFor()
            File(dir, "run.out").writeText(out)
            return code
        }

        fun cleanup() = dir.deleteRecursively()
    }

    // 1. ARMED — первая строка файла, с op id; рестарт после неё; итог DONE с тем же id.
    @Test
    fun armedIsWrittenBeforeSleepAndBeforeRestart() {
        if (!shAvailable()) return
        val sb = Sandbox()
        try {
            sb.installStubs()
            val op = JitFlow.opId(1_000, "b1", "aaa111")
            val script = sb.script()
            assertEquals(0, sb.run(script, op, "true"))

            val text = sb.content()
            val first = text.substringBefore("\n")
            assertTrue("первая строка — ARMED: $first", first.contains(JitNetModel.WATCHER_ARMED))
            assertTrue("в ARMED есть id операции", first.contains("op=$op"))
            assertTrue("есть RESTARTING с нашим id",
                text.lineSequence().any { it.contains(JitNetModel.WATCHER_RESTARTING) && it.contains("op=$op") })
            assertTrue("есть DONE с нашим id",
                text.lineSequence().any { it.contains(JitNetModel.WATCHER_DONE) && it.contains("op=$op") })
            assertFalse("ARMED раньше RESTARTING",
                text.indexOf(JitNetModel.WATCHER_ARMED) > text.indexOf(JitNetModel.WATCHER_RESTARTING))

            assertTrue("ctl.restart вызван", sb.callsText().contains("ctl.restart zygote"))
            assertTrue("адаптер видит нашу операцию", JitFlow.armedFor(text, op))
            assertTrue(JitFlow.doneFor(text, op))
            assertEquals(JitFlow.CancelOutcome.RESTARTED, JitFlow.cancelOutcome(text, op))
        } finally {
            sb.cleanup()
        }
    }

    // 2. Файл-отмена с НАШИМ op → рестарта нет, ABORTED с нашим id.
    @Test
    fun cancelFileWithOurOpPreventsRestart() {
        if (!shAvailable()) return
        val sb = Sandbox()
        try {
            sb.installStubs()
            val op = JitFlow.opId(2_000, "b1", "bbb222")
            val script = sb.script()
            sb.cancel.writeText("op=$op")
            assertEquals(0, sb.run(script, op, "true"))

            assertFalse("рестарта быть не должно", sb.callsText().contains("ctl.restart zygote"))
            val text = sb.content()
            assertTrue(text.contains(JitNetModel.WATCHER_ABORTED))
            assertFalse("DONE/RESTARTING писать нечего",
                text.contains(JitNetModel.WATCHER_DONE) || text.contains(JitNetModel.WATCHER_RESTARTING))
            assertEquals(JitFlow.CancelOutcome.CANCELLED, JitFlow.cancelOutcome(text, op))
            assertFalse(JitFlow.restartStillPossible(JitFlow.CancelOutcome.CANCELLED))
        } finally {
            sb.cleanup()
        }
    }

    // 3. Файл-отмена с ЧУЖИМ op (или пустой) — отменой не считается: рестарт выполняется.
    @Test
    fun cancelFileWithForeignOpDoesNotCancel() {
        if (!shAvailable()) return
        val sb = Sandbox()
        try {
            sb.installStubs()
            val op = JitFlow.opId(3_000, "b1", "ccc333")
            val other = JitFlow.opId(4_000, "b1", "ddd444")
            val script = sb.script()
            sb.cancel.writeText("op=$other")
            assertEquals(0, sb.run(script, op, "true"))

            assertTrue("чужой op не отменяет рестарт", sb.callsText().contains("ctl.restart zygote"))
            val text = sb.content()
            assertFalse(text.contains(JitNetModel.WATCHER_ABORTED))
            assertEquals(JitFlow.CancelOutcome.RESTARTED, JitFlow.cancelOutcome(text, op))
        } finally {
            sb.cleanup()
        }
    }

    // 4. Старый результат от прошлой операции не подтверждает новую (реальный файл).
    @Test
    fun oldResultDoesNotConfirmNewOperation() {
        if (!shAvailable()) return
        val sb = Sandbox()
        try {
            sb.installStubs()
            val oldOp = JitFlow.opId(5_000, "b1", "old555")
            val newOp = JitFlow.opId(6_000, "b1", "new666")
            val script = sb.script()
            sb.run(script, oldOp, "true")
            val afterFirst = sb.content()
            assertTrue(JitFlow.doneFor(afterFirst, oldOp))
            assertFalse("старый итог не подтверждает новый запуск", JitFlow.armedFor(afterFirst, newOp))
            assertEquals(JitFlow.CancelOutcome.UNKNOWN, JitFlow.cancelOutcome(afterFirst, newOp))
        } finally {
            sb.cleanup()
        }
    }
}
