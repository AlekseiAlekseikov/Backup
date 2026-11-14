package com.example.voiceapp3.tools

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iter53 §6 — ДОСТАВКА: не уничтожать свидетельство `RUN_UNKNOWN`, не запускать непроверенное.
 *
 * Дефекты (аудит §6.1–§6.2):
 *  1. `deliver` при `RUN_UNKNOWN` вызывал `cleanup`, а `cleanCommand` удаляет и `.sh`, и `.done`:
 *     «исход неизвестен, терминал разрешит позже» и «удалить терминал сейчас» — несовместимы;
 *  2. `runCommand` при недоступном `cksum` всё равно запускал скрипт с `checked=0`, то есть
 *     «сверено» означало «не сверяли»;
 *  3. не было маркера `started`, поэтому поздняя сверка не отличала «начал» от «не начал».
 *
 * Метод: настоящий `/bin/sh`, настоящая ФС, настоящие `wc`/`cksum`/`md5sum`/`od`/`awk`.
 * Отсутствие утилиты моделируется РЕАЛЬНЫМ `PATH` без неё — это проверяет fallback, а не фантазию.
 */
class Iter53DeliveryTest {

    private val dev = RealShellDevice()
    private val root: File = File(dev.root, "data/local/tmp")

    @After
    fun tearDown() = dev.cleanup()

    private fun bigScript(marker: Int): String {
        val body = StringBuilder("#!/system/bin/sh\n# marker=$marker\n")
        while (body.length < 6000) body.append("echo \"line $marker ${body.length}\"\n")
        return body.toString()
    }

    private fun pathOf(res: ScriptDelivery.Result): File =
        File(res.plan.path.replace("/data/local/tmp", root.absolutePath))

    /** Каталог «прошивки» с ОГРАНИЧЕННЫМ набором утилит (реальный PATH). */
    private fun toolsDir(include: List<String>): File {
        val dir = File(dev.root, "bin_" + System.nanoTime())
        dir.mkdirs()
        val base = listOf("/bin", "/usr/bin")
        for (name in listOf("sh", "wc", "rm", "chmod", "cat", "awk", "od", "grep") + include) {
            val src = base.map { File(it, name) }.firstOrNull { it.canExecute() } ?: continue
            val dst = File(dir, name)
            runCatching { java.nio.file.Files.createSymbolicLink(dst.toPath(), src.toPath()) }
        }
        return dir
    }

    private fun execWith(path: File?): (String, Int, Boolean) -> Pair<Int, String> =
        { cmd, t, r ->
            if (path == null) dev.exec(cmd, t, r)
            // PATH применяется ко ВСЕЙ цепочке (`;`), а не только к первой команде.
            else dev.exec("PATH=${path.absolutePath}; export PATH; " + cmd, t, r)
        }

    // ───────────── §6.2.2: RUN_UNKNOWN сохраняет свидетельства ─────────────

    @Test
    fun runUnknownKeepsEvidenceAndDoesNotCleanUp() {
        val script = bigScript(7)
        // Ответ на запуск И на чтение terminal теряется ПОСЛЕ фактического исполнения:
        // ровно тот случай, когда «не знаю» нельзя превращать в «не выполнялось».
        val res = ScriptDelivery.deliver("lock_bind", script, 30_000, true,
            exec = { cmd, t, r ->
                if (cmd.contains("SCRIPT_CONTENT_OK") || cmd.contains("DONE=")) {
                    dev.exec(cmd, t, r)
                    137 to "ERR_ADB_TIMEOUT"
                } else dev.exec(cmd, t, r)
            })

        assertEquals(ScriptDelivery.State.RUN_UNKNOWN, res.state)
        assertTrue("запуск мог произойти", res.mayHaveRun)
        assertTrue("свидетельство указано: ${res.wire()}", res.evidencePath.isNotEmpty())
        val cleaned = dev.executed.filter { it.contains("RM_OK") }
        assertTrue("после RUN_UNKNOWN очистка НЕ отправлялась: $cleaned", cleaned.isEmpty())
        val done = File(root, File(res.plan.path).name + ".done")
        assertTrue("terminal-файл остался на устройстве как свидетельство", done.exists())
        assertTrue("в нём ФАКТ завершения: «${done.readText().trim()}»",
            done.readText().contains("rc="))
    }

    @Test
    fun lateDoneIsReadableWithoutSecondRun() {
        val script = bigScript(8)
        val res = ScriptDelivery.deliver("lock_bind", script, 30_000, true,
            exec = { cmd, t, r ->
                if (cmd.contains("SCRIPT_CONTENT_OK") || cmd.contains("DONE=")) {
                    dev.exec(cmd, t, r); 137 to "ERR_ADB_TIMEOUT"
                } else dev.exec(cmd, t, r)
            })
        assertEquals(ScriptDelivery.State.RUN_UNKNOWN, res.state)
        // Поздняя сверка read-only командой: исход читается, повторный запуск не нужен.
        val (rc, out) = dev.exec(ScriptDelivery.evidenceCommand(res.plan.path), 8000, true)
        assertEquals(0, rc)
        assertTrue("файл запуска подтверждён: «$out»", out.contains("STARTED=1") || out.contains("SH=1"))
        assertTrue("исход читается из terminal: «$out»", out.contains("DONE=rc="))
        val second = ScriptDelivery.reconcile(res.plan.path, ::probeExec, expectRoot = true)
        assertTrue("второго запуска не потребовалось", second != null)
    }

    private fun probeExec(cmd: String, t: Int, r: Boolean) = dev.exec(cmd, t, r)

    // ───────────── §6.2.4: без проверки содержимого — нет запуска ─────────────

    @Test
    fun noChecksumToolMeansNoRunAtAll() {
        val path = File(root, "voice_53_nosum.sh")
        File(root, "DATA101").writeText("PAYLOAD\n")
        val script = "touch ${File(root, "EFFECT_NOSUM").absolutePath}\n"
        path.writeText(script)
        val bin = toolsDir(emptyList())          // нет ни cksum, ни md5sum
        val cmd = ScriptDelivery.runCommand(
            path.absolutePath.replace(root.absolutePath, "/data/local/tmp"),
            script.toByteArray().size, ScriptDelivery.cksum(script.toByteArray())
        )
        val (rc, out) = execWith(bin)(cmd, 10_000, true)
        assertEquals("без сверки содержимого запускать нельзя: $out", 97, rc)
        assertTrue("причина названа: $out", out.contains("ERR_SCRIPT_UPLOAD content kind=none"))
        assertFalse("опасное действие НЕ выполнено",
            File(root, "EFFECT_NOSUM").exists())
        assertTrue("и это видно в протоколе: $out", out.contains("SCRIPT_CONTENT_KIND=none"))
    }

    @Test
    fun md5FallbackVerifiesContentWhenCksumMissing() {
        val script = bigScript(9)
        val bytes = script.toByteArray()
        val path = File(root, "voice_53_md5.sh")
        path.writeText(script)
        val bin = toolsDir(listOf("md5sum"))
        val cmd = ScriptDelivery.runCommand(
            path.absolutePath.replace(root.absolutePath, "/data/local/tmp"),
            bytes.size, ScriptDelivery.cksum(bytes), ScriptDelivery.md5Hex(bytes),
            ScriptDelivery.odsum(bytes)
        )
        val (rc, out) = execWith(bin)(cmd, 10_000, true)
        assertEquals("запуск прошёл: $out", 0, rc)
        assertTrue("сверка сделана md5: $out", out.contains("SCRIPT_CONTENT_OK checked=1 kind=md5"))
    }

    @Test
    fun odsumFallbackVerifiesContentWithoutCksumAndMd5sum() {
        val script = bigScript(10)
        val bytes = script.toByteArray()
        val path = File(root, "voice_53_od.sh")
        path.writeText(script)
        val bin = toolsDir(emptyList())          // есть только od/awk
        val cmd = ScriptDelivery.runCommand(
            path.absolutePath.replace(root.absolutePath, "/data/local/tmp"),
            bytes.size, ScriptDelivery.cksum(bytes), ScriptDelivery.md5Hex(bytes),
            ScriptDelivery.odsum(bytes)
        )
        val (rc, out) = execWith(bin)(cmd, 10_000, true)
        assertEquals("запуск прошёл: $out", 0, rc)
        assertTrue("сверка сделана od|awk: $out", out.contains("SCRIPT_CONTENT_OK checked=1 kind=odsum"))
    }

    @Test
    fun corruptedSameLengthContentIsNotRun() {
        val script = bigScript(11)
        val bytes = script.toByteArray()
        val path = File(root, "voice_53_bad.sh")
        // Та же длина, ДРУГИЕ байты.
        val corrupted = script.replaceRange(20, 21, if (script[20] == 'x') "y" else "x")
        assertEquals("длина та же", bytes.size, corrupted.toByteArray().size)
        path.writeText(corrupted)
        val effect = File(root, "EFFECT_BAD")
        val cmd = ScriptDelivery.runCommand(
            path.absolutePath.replace(root.absolutePath, "/data/local/tmp"),
            bytes.size, ScriptDelivery.cksum(bytes), ScriptDelivery.md5Hex(bytes),
            ScriptDelivery.odsum(bytes)
        ) + "\ntouch ${effect.absolutePath}\n"
        val (rc, out) = execWith(null)(cmd, 10_000, true)
        assertNotEquals("запуск запрещён (rc≠0): $out", 0, rc)
        assertTrue("это отказ по содержимому: $out", out.contains("ERR_SCRIPT_UPLOAD content"))
        assertTrue("удаление подтверждено: $out", out.contains("CLEANUP_PROVEN not_started=1"))
        assertFalse("файл удалён, а не оставлен", path.exists())
    }

    @Test
    fun startedMarkerDistinguishesStartedFromNotStarted() {
        val script = "true\n"
        val bytes = script.toByteArray()
        val path = File(root, "voice_53_started.sh")
        path.writeText(script)
        val cmd = ScriptDelivery.runCommand(
            path.absolutePath.replace(root.absolutePath, "/data/local/tmp"),
            bytes.size, ScriptDelivery.cksum(bytes), ScriptDelivery.md5Hex(bytes),
            ScriptDelivery.odsum(bytes)
        )
        val (rc, out) = execWith(null)(cmd, 10_000, true)
        assertEquals("скрипт выполнен: $out", 0, rc)
        assertTrue("маркер старта записан", File(root, "voice_53_started.sh.started").exists())
        assertTrue("terminal записан", File(root, "voice_53_started.sh.done").exists())
    }

    // ───────────── доказанное удаление vs «rm + echo» ─────────────

    @Test
    fun cleanupReportsFailureHonestly() {
        val path = File(root, "voice_53_keep.sh")
        path.writeText("x")
        val dir = File(root, "voice_53_keep.sh.done")
        dir.mkdirs()                             // каталог: rm -f файла не удалит
        val local = path.absolutePath.replace(root.absolutePath, "/data/local/tmp")
        val (rc, out) = dev.exec(ScriptDelivery.cleanCommand(local), 8000, true)
        assertEquals(0, rc)
        assertTrue("неудача видна: $out", out.contains("RM_FAIL"))
    }
}
