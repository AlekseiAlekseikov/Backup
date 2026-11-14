package com.example.voiceapp3.tools

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * iter52 §4 — доставка скриптов: настоящий `sh`, реальная семантика `rm`, реальная сверка байт.
 *
 * Каждый сценарий исполняет РЕАЛЬНЫЕ команды, которые генерирует [ScriptDelivery], и проверяет
 * фактическое состояние файлов «устройства». Fake, возвращающий готовый rc для всей команды,
 * здесь не используется намеренно: именно он скрывал дефект `rm … ; echo RM_DONE`.
 */
class Iter52DeliveryTest {

    private lateinit var dev: RealShellDevice

    @Before
    fun setUp() {
        dev = RealShellDevice()
    }

    @After
    fun tearDown() {
        dev.cleanup()
    }

    private fun run(script: String, name: String = "lock_bind"): ScriptDelivery.Result =
        ScriptDelivery.deliver(name, script, 30_000, true, dev::exec)

    /** Скрипт, факт исполнения которого ВИДЕН в файловой системе «устройства». */
    private fun scriptWithSideEffect(): String =
        (1..250).joinToString("\n") { "# строка $it для размера" } +
            "\ntouch /data/local/tmp/BODY_RAN\nexit 0\n"

    private fun bigScript(targets: Int = 6): String =
        LockMountScripts.bindScript("/data/local/tmp/carlock_custom_g1_deadbeef.wav") +
            (1..targets).joinToString("") { "\necho \"TARGET_$it\"\nexit 0\n" } +
            "\n".repeat(120)

    // ─────────────── §4.1(A1): отказ rm не маскируется ───────────────

    @Test
    fun failedRemoveIsReportedNotSwallowed() {
        val d = File(dev.root, "ro")
        d.mkdirs()
        File(d, "keep.sh").writeText("x")
        d.setWritable(false)

        val (rc, out) = dev.sh(ScriptDelivery.cleanCommand(d.absolutePath + "/keep.sh"))
        assertEquals("команда завершилась", 0, rc)
        assertTrue("отказ назван: $out", out.contains("RM_FAIL"))
        assertTrue("файл действительно на месте", File(d, "keep.sh").exists())
        assertFalse("успех не заявлен", out.contains("RM_OK"))
        d.setWritable(true)
    }

    @Test
    fun successfulRemoveIsProvenByAbsence() {
        val f = File(dev.root, "gone.sh").apply { writeText("x") }
        val (_, out) = dev.sh(ScriptDelivery.cleanCommand(f.absolutePath))
        assertTrue("удаление доказано: $out", out.contains("RM_OK") && out.contains("exists=0"))
        assertFalse(f.exists())
    }

    @Test
    fun alreadyAbsentFileIsNotAFailure() {
        val (_, out) = dev.sh(ScriptDelivery.cleanCommand(File(dev.root, "nope.sh").absolutePath))
        assertTrue("«файла нет» — это не отказ: $out", out.contains("RM_OK"))
    }

    @Test
    fun cleanupFailureSurfacesAsLeftoverInDelivery() {
        // Делаем каталог устройства недоступным для удаления ПОСЛЕ заливки.
        val script = bigScript(1)
        val res = ScriptDelivery.deliver("lock_restore", script, 30_000, true,
            exec = { cmd: String, t: Int, r: Boolean ->
            // Заливка и запуск идут как обычно; команду очистки подменяем на «rm не удался».
            if (cmd.contains("RM_OK")) {
                dev.exec(cmd, t, r)
                val p = Regex("rm -f (\\S+)").find(cmd)!!.groupValues[1]
                dev.exec("echo RM_FAIL rc=1 exists=1", t, r)
            } else dev.exec(cmd, t, r)
            })
        assertEquals(ScriptDelivery.State.RUN_CONFIRMED, res.state)
        assertTrue("отказ удаления виден как leftover: ${res.wire()}", res.leftoverPath.isNotEmpty())
        assertTrue("в планe/log это не «успех»", res.wire().contains("leftover=/data/local/tmp/"))
    }

    // ─────────────── §4.2: фазы и сверка содержимого ───────────────

    @Test
    fun contentIsVerifiedByCksumNotOnlyByLength() {
        val script = bigScript(2)
        val res = run(script)
        assertEquals(ScriptDelivery.State.RUN_CONFIRMED, res.state)
        assertTrue("размер сверен: ${res.wire()}", res.sizeChecked)
        assertTrue("содержимое сверено через cksum: ${res.wire()}", res.contentChecked)
    }

    @Test
    fun sameLengthDifferentBytesIsRejected() {
        val script = bigScript(2)
        val plan = ScriptDelivery.plan("lock_bind", script)
        // Портим РОВНО ОДИН символ, не меняя длину: размер совпадёт, cksum — нет.
        val corrupted = script.replaceRange(0, 1, if (script.first() == '#') " " else "#")
        assertEquals("длина та же", plan.bytes, corrupted.toByteArray().size)
        assertNotEquals("содержимое другое", plan.cksum, ScriptDelivery.cksum(corrupted.toByteArray()))

        // Кладём в файл «устройства» именно испорченную версию и запускаем настоящую проверку.
        val devicePath = "/data/local/tmp/voiceapp3_probe_abc.sh"
        val path = File(dev.root, devicePath.trimStart('/'))
        path.writeText(corrupted)
        val (rc, out) = dev.sh(ScriptDelivery.runCommand(devicePath, plan.bytes, plan.cksum))
        assertEquals("сверка не пропустила запуск", 98, rc)
        assertTrue("причина названа: $out", out.contains("ERR_SCRIPT_UPLOAD content"))
        assertFalse("скрипт НЕ запускался", File(path.absolutePath + ".done").exists())
    }

    @Test
    fun lostResponseIsUnknownAndNotRetried() {
        val script = bigScript(1)
        // Последняя (run) команда реально исполняется, но ответ теряется — как настоящий timeout
        // после фактического выполнения. Повторять запуск нельзя.
        var calls = 0
        val res = ScriptDelivery.deliver("lock_bind", script, 30_000, true,
            exec = { cmd: String, t: Int, r: Boolean ->
            calls++
            val isRun = cmd.contains("SCRIPT_CONTENT_OK")
            val isDone = cmd.contains("DONE=")
            val real = dev.exec(cmd, t, r)
            when {
                isRun -> 137 to "ERR_ADB_TIMEOUT"          // выполнилось, ответ потерян
                isDone -> 137 to "ERR_ADB_TIMEOUT"         // terminal тоже не прочитать
                else -> real
            }
            })
        assertEquals("исход неизвестен", ScriptDelivery.State.RUN_UNKNOWN, res.state)
        assertTrue("факт «мог выполниться» назван: ${res.wire()}", res.mayHaveRun)
        assertEquals("запуск НЕ повторялся", 1, dev.executed.count { it.contains("SCRIPT_CONTENT_OK") })
    }

    @Test
    fun lostResponseIsResolvedByTerminalFile() {
        val script = bigScript(1)
        val res = ScriptDelivery.deliver("lock_bind", script, 30_000, true,
            exec = { cmd: String, t: Int, r: Boolean ->
            if (cmd.contains("SCRIPT_CONTENT_OK")) {
                dev.exec(cmd, t, r)                        // РЕАЛЬНО исполняем
                137 to "ERR_ADB_TIMEOUT"                   // но ответ потерян
            } else dev.exec(cmd, t, r)
            })
        assertEquals("terminal дал факт: ${res.wire()}", ScriptDelivery.State.RUN_CONFIRMED, res.state)
        assertEquals("код скрипта из terminal", 0, res.rc)
        assertFalse("повтор не понадобился", res.mayHaveRun)
    }

    @Test
    fun uploadFailureNeverRunsTheScript() {
        val script = scriptWithSideEffect()
        val res = ScriptDelivery.deliver("lock_bind", script, 30_000, true,
            exec = { cmd: String, t: Int, r: Boolean ->
            if (cmd.contains("| base64 -d")) 1 to "write error" else dev.exec(cmd, t, r)
            })
        assertEquals(ScriptDelivery.State.UPLOAD_INCOMPLETE, res.state)
        assertFalse("запуск не заявлен", res.delivered)
        assertFalse("и не произошёл (побочного эффекта нет)",
            File(dev.root, "data/local/tmp/BODY_RAN").exists())
    }

    @Test
    fun truncatedWriteIsCaughtBySizeGuard() {
        val script = scriptWithSideEffect()
        // Обрезаем заливку: последний чанк принимается «молча», содержимое короче.
        var inAttempt = 0
        val res = ScriptDelivery.deliver("lock_bind", script, 30_000, true,
            exec = { cmd: String, t: Int, r: Boolean ->
            when {
                cmd.contains("set -C") -> { inAttempt = 0; dev.exec(cmd, t, r) }
                cmd.contains("| base64 -d") -> {
                    inAttempt++
                    // ВТОРОЙ чанк теряется в КАЖДОЙ попытке: файл всегда короче ожидаемого.
                    if (inAttempt == 2) 0 to "" else dev.exec(cmd, t, r)
                }
                else -> dev.exec(cmd, t, r)
            }
            })
        assertEquals("не запущено, доказано маркером", ScriptDelivery.State.UPLOAD_INCOMPLETE, res.state)
        assertFalse("побочного эффекта нет",
            File(dev.root, "data/local/tmp/BODY_RAN").exists())
    }

    // ─────────────── §4.5: уникальность и принадлежность файла ───────────────

    @Test
    fun existingFileIsNeverOverwritten() {
        val script = bigScript(1)
        val nonce = "deadbeefdeadbeef"
        val path = ScriptDelivery.pathFor("lock_bind", nonce)
        val existing = File(dev.root, path.trimStart('/')).apply {
            parentFile.mkdirs(); writeText("ЧУЖИЕ ДАННЫЕ")
        }
        val res = ScriptDelivery.deliver("lock_bind", script, 30_000, true, dev::exec, nonce = nonce)
        assertEquals("доставка прошла под другим именем", ScriptDelivery.State.RUN_CONFIRMED, res.state)
        assertEquals("чужой файл не тронут", "ЧУЖИЕ ДАННЫЕ", existing.readText())
        assertNotEquals("взято другое имя", path, res.plan.path)
    }

    @Test
    fun realBindAndRestoreScriptsAreDelivered() {
        val bind = LockMountScripts.bindScript("/data/local/tmp/carlock_custom_g1_aa.wav")
        val restore = LockMountScripts.restoreScript(
            listOf("/vendor/etc/carlock/carlock_close_1.wav" to "/data/local/tmp/carlock_custom_g1_aa.wav")
        )
        assertTrue("bind > 4096 (реальный размер ${ScriptDelivery.plan("b", bind).bytes})",
            ScriptDelivery.plan("b", bind).bytes > ScriptDelivery.MAX_PAYLOAD)
        val rb = run(bind, "lock_bind")
        val rr = run(restore, "lock_restore")
        assertTrue("bind доставлен: ${rb.wire()}", rb.delivered)
        assertTrue("restore доставлен: ${rr.wire()}", rr.delivered)
        // На «устройстве» не осталось файлов: очистка доказана удалением.
        val leftovers = File(dev.root, "data/local/tmp").listFiles()?.map { it.name } ?: emptyList()
        assertTrue("мусора не осталось: $leftovers", leftovers.isEmpty())
    }

    @Test
    fun cksumMatchesRealPosixTool() {
        // Проверка реализации CRC-32 против НАСТОЯЩЕГО cksum, а не против самой себя.
        val samples = listOf("", "a", "abc\n", "Привет, мир\n", bigScript(1))
        for (s in samples) {
            val f = File(dev.root, "cksum_probe_${s.hashCode()}.bin").apply { writeText(s) }
            val (_, out) = dev.sh("cksum < '${f.absolutePath}'")
            val real = out.trim().split(Regex("\\s+")).first().toLong()
            assertEquals("cksum для «${s.take(12)}»", real, ScriptDelivery.cksum(s.toByteArray()))
        }
    }

    @Test
    fun directTransportIsUsedOnlyForSmallScripts() {
        val small = "echo hello"
        val res = run(small, "tiny")
        assertEquals(ScriptDelivery.Transport.DIRECT, res.plan.transport)
        assertEquals(ScriptDelivery.State.RUN_SENT, res.state)
        assertEquals("прямая команда выполнена", 1, dev.executed.size)
    }

    @Test
    fun tooLargeScriptNeverTouchesTheDevice() {
        val huge = "x".repeat(ScriptDelivery.MAX_SCRIPT_BYTES + 10)
        val res = run(huge, "huge")
        assertEquals(ScriptDelivery.State.TOO_LARGE, res.state)
        assertEquals(0, dev.executed.size)
    }

    @Test
    fun nonceIsNotReusedBetweenDeliveries() {
        val a = ScriptDelivery.newNonce()
        val b = ScriptDelivery.newNonce()
        assertNotEquals(a, b)
        assertEquals("16 hex", 16, a.length)
        assertNull(Regex("[^0-9a-f]").find(a))
    }

    @Test
    fun scriptRcIsPropagatedToTheCaller() {
        // Скрипт, который реально завершается с кодом 3 (bind-скрипт сам делает `exit`, поэтому
        // берём собственный текст, а не его).
        val script = (1..250).joinToString("\n") { "# строка $it для размера" } +
            "\necho RUNNING\nexit 3\n"
        val res = run(script, "rc_test")
        assertEquals(ScriptDelivery.State.RUN_CONFIRMED, res.state)
        assertEquals("код скрипта не подменён", 3, res.rc)
    }

    @Test
    fun concurrentDeliveriesUseDistinctPaths() {
        val a = ScriptDelivery.plan("lock_bind", bigScript(1))
        val b = ScriptDelivery.plan("lock_bind", bigScript(1))
        assertNotEquals("параллельные вызовы не делят файл", a.path, b.path)
        assertTrue(a.path.startsWith("/data/local/tmp/voiceapp3_lock_bind_"))
    }
}
