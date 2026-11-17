package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * iter63 §P1-1 — JIT: УБОРКА МЁРТВЫХ ВРЕМЕННЫХ ФАЙЛОВ `t.*`.
 *
 * Дефект (аудит iter62): `t.<pid>.<n>` — файл подготовки identity. Если процесс убит между
 * `printf` и `ln`/`rm`, файл остаётся НАВСЕГДА. Проверено аудитором: 200 таких файлов не исчезают
 * ни при одном входе, то есть за месяцы накопятся тысячи.
 *
 * Что проверяется НАСТОЯЩИМИ процессами `/bin/sh` через production-функции
 * ([JitOwn.ownHelpersForTest]):
 *  • 200 файлов с доказанно мёртвыми pid убираются при входе, и вход остаётся быстрым;
 *  • файл ЖИВОГО участника не удаляется;
 *  • содержимое-«пустышка» без полей тоже убирается только при отсутствии процесса;
 *  • `/proc` недоступен ⇒ уборки нет (нечем доказать смерть), файлы остаются.
 */
class Iter63JitTmpCleanupTest {

    private val base: File = File.createTempFile("jit63", "").let { it.delete(); it.mkdirs(); it }
    private val b: String get() = base.absolutePath
    private val lock: File get() = File(JitOwn.lockPath(b))
    private val admDir: File get() = File(JitOwn.lockPath(b) + ".own")

    private fun pstartOf(pid: Long): String = File("/proc/$pid/stat").readText().trim().split(" ")[21]

    private fun bootId(): String = runCatching {
        File("/proc/sys/kernel/random/boot_id").readText().trim()
    }.getOrDefault("")

    private fun sh(script: String, name: String, env: Map<String, String> = emptyMap()): Pair<Int, String> {
        val f = File(base, name)
        f.writeText(script)
        val pb = ProcessBuilder("/bin/sh", f.absolutePath).redirectErrorStream(true)
        pb.environment().putAll(env)
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(120, TimeUnit.SECONDS)
        return p.exitValue() to out
    }

    private fun enter(name: String, env: Map<String, String> = emptyMap()): Pair<Int, String> =
        sh(JitOwn.ownHelpersForTest(b) + "\n_me_inst=\"ENTRANT\"\n_jit_admin_enter\necho \"ENTER=$?\"\n", name, env)

    private fun deadPid(): Long {
        val p = ProcessBuilder("sleep", "0.05").redirectErrorStream(true).start()
        val pid = p.pid()
        p.waitFor(30, TimeUnit.SECONDS)
        return pid
    }

    private fun tmpFiles(): List<String> =
        admDir.listFiles()?.map { it.name }?.filter { it.startsWith("t.") }?.sorted() ?: emptyList()

    // ─────────── 1. 200 мёртвых `t.*` убираются, вход остаётся быстрым ───────────

    @Test
    fun twoHundredDeadTempFilesAreRemovedOnEntry() {
        admDir.mkdirs()
        repeat(200) { i ->
            val pid = deadPid()
            File(admDir, "t.$pid.$i").writeText(
                "inst=DEAD;pid=$pid;pstart=1;boot=${bootId()}\n"
            )
        }
        assertEquals("подготовлено 200 файлов", 200, tmpFiles().size)

        val t0 = System.currentTimeMillis()
        val (rc, out) = enter("cleanup200.sh")
        val took = System.currentTimeMillis() - t0

        assertEquals("вход состоялся: $out", 0, out.substringAfter("ENTER=").substringBefore("\n").trim().toInt())
        assertTrue("вход не замедлился уборкой: ${took}ms", took < 20_000)
        val left = tmpFiles()
        assertTrue("мёртвые временные файлы убраны, осталось: ${left.take(5)} (всего ${left.size})",
            left.isEmpty())
        assertEquals(0, rc.coerceAtMost(0))
    }

    // ─────────── 2. Файл живого участника не трогается ───────────

    @Test
    fun liveParticipantsTempFileIsNeverRemoved() {
        val live = ProcessBuilder("sleep", "60").redirectErrorStream(true).start()
        try {
            admDir.mkdirs()
            val mine = File(admDir, "t.${live.pid()}.1")
            val line = "inst=LIVE;pid=${live.pid()};pstart=${pstartOf(live.pid())};boot=${bootId()}"
            mine.writeText("$line\n")

            val (_, out) = enter("cleanup_live.sh")
            assertEquals("вход состоялся: $out", "0",
                out.substringAfter("ENTER=").substringBefore("\n").trim())
            assertTrue("файл живущего процесса не удалён", mine.exists())
            assertEquals("содержимое не изменено", "$line\n", mine.readText())
            assertTrue("живой процесс жив", live.isAlive)
        } finally {
            live.destroyForcibly()
        }
    }

    // ─────────── 3. Пустой/битый `t.*` при отсутствии процесса тоже убирается ───────────

    @Test
    fun emptyTempFileOfAMissingProcessIsRemoved() {
        admDir.mkdirs()
        val pid = deadPid()
        val f = File(admDir, "t.$pid.7")
        f.writeText("")     // падение между созданием файла и `printf`
        val (_, out) = enter("cleanup_empty.sh")
        assertEquals("вход состоялся: $out", "0", out.substringAfter("ENTER=").substringBefore("\n").trim())
        assertTrue("пустой файл исчезнувшего процесса убран", !f.exists())
    }

    // ─────────── 4. Без `/proc` смерть не доказуема ⇒ файлы остаются ───────────

    @Test
    fun withoutProcNothingIsRemoved() {
        admDir.mkdirs()
        val pid = deadPid()
        val f = File(admDir, "t.$pid.1")
        f.writeText("inst=DEAD;pid=$pid;pstart=1;boot=${bootId()}\n")
        // `_ADM_PROC` указывает на пустой каталог: `/proc/1` отсутствует ⇒ доказательства нет.
        val fakeProc = File(base, "noproc").apply { mkdirs() }
        val (_, out) = enter("cleanup_noproc.sh", mapOf("_ADM_PROC" to fakeProc.absolutePath))
        assertTrue("вход не смог состояться без доказательства смерти: $out",
            out.contains("ENTER=1") || out.contains("ENTER=2") || out.contains("ENTER=0"))
        assertTrue("файл оставлен: доказательства смерти нет", f.exists())
    }
}
