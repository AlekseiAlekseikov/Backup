package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * iter51 §3.1 — ДОСТАВКА длинных скриптов звука замка через настоящий production-путь.
 *
 * Дефект на HEAD `a67f568`:
 *   • `LockSound` отправлял bind через `AdbClient.execute(bindScript, 20000)` и restore так же;
 *   • `AdbClient` в ветке `ROOT_5555` отклоняет payload > 4096 байт (`ERR_ADB_TOO_LONG`) ДО
 *     отправки;
 *   • фактическая длина: bind = **8935** байт, restore = **5593** (1 цель) / **12794** (12 целей).
 * Значит на этом канале звук замка не мог включиться вообще, а «mount не выполнено» выглядело
 * как обычная неудача применения.
 *
 * Тест исполняет РЕАЛЬНЫЙ `ScriptDelivery.deliver` (его же вызывает `AdbClient.executeScript`,
 * а его — `LockSound`) против fake-ADB, который:
 *   • воспроизводит лимит 4096 байт на КАЖДУЮ команду (как `ROOT_5555`);
 *   • держит виртуальную файловую систему `/data/local/tmp` с `rm -f`, `: >`, `base64 -d >>`,
 *     `wc -c`, проверкой размера и `sh <path>`;
 *   • умеет ломать конкретный чанк, обрезать запись и отказывать в удалении.
 *
 * Чего тест НЕ доказывает: реальный adb-протокол, поведение `/system/bin/sh` на ГУ, и то, что
 * `mount` на устройстве сработал (это отдельный уровень, см. `Iter50Stand`/`Iter50MountLayerTest`).
 */
class Iter51DeliveryTest {

    /** Модель устройства: лимит payload + виртуальный `/data/local/tmp`. */
    private class FakeDevice(
        /** Номер чанка (0-based), который нужно «уронить» (однократно). */
        var failChunkIndex: Int = -1,
        /** Ронять КАЖДУЮ заливку чанка (постоянный отказ записи). */
        var failAllChunks: Boolean = false,
        /** Обрезать запись на N байт (имитация неполной дозаписи). */
        var truncateBy: Int = 0,
        /** Запретить удаление файлов (модель «нет прав»/ФС только для чтения). */
        var denyDelete: Boolean = false,
        /** Код возврата, который вернёт сам запущенный скрипт. */
        var scriptRc: Int = 0,
        /** Вывод, который «напечатает» скрипт (эмулирует stdout устройства). */
        var scriptOut: String = ""
    ) {
        val files = LinkedHashMap<String, ByteArray>()
        val commands = ArrayList<String>()
        val executed = ArrayList<String>()
        var chunkCalls = 0

        fun exec(cmd: String, timeoutMs: Int, expectRoot: Boolean): Pair<Int, String> {
            commands.add(cmd)
            // ── ветка ROOT_5555: payload > 4096 отклоняется ДО отправки ──
            val len = cmd.toByteArray(StandardCharsets.UTF_8).size
            if (len > 4096) return 1 to "ERR_ADB_TOO_LONG: cmd_len=$len > 4096 (use executeScript for long scripts)"

            if (cmd.startsWith("rm -f '") && cmd.endsWith("; echo RM_DONE")) {
                if (denyDelete) return 1 to ""
                val p = cmd.removePrefix("rm -f '").substringBefore("'")
                files.remove(p)
                return 0 to "RM_DONE"
            }
            if (cmd.contains(": > ")) { // clearCommand
                val p = cmd.substringAfter(": > ").substringBefore(";")
                files[p] = ByteArray(0)
                return 0 to "CLEARED"
            }
            if (cmd.startsWith("echo '") && cmd.contains("| base64 -d >> ")) {
                val b64 = cmd.removePrefix("echo '").substringBefore("' | base64 -d >> ")
                val p = cmd.substringAfter("| base64 -d >> ").trim()
                val idx = chunkCalls++
                if (failAllChunks || idx == failChunkIndex) return 1 to "write error: No space left on device"
                val data = Base64.getDecoder().decode(b64)
                val keep = (data.size - truncateBy).coerceAtLeast(0)
                files[p] = (files[p] ?: ByteArray(0)) + data.copyOfRange(0, keep)
                return 0 to ""
            }
            if (cmd.startsWith("chmod 700 ")) { // verifyAndRunCommand
                val p = cmd.removePrefix("chmod 700 ").substringBefore(";")
                val expected = Regex("EXPECTED=(\\d+)").find(cmd)!!.groupValues[1].toInt()
                val actual = files[p]?.size ?: -1
                if (actual != expected) {
                    files.remove(p)
                    return 99 to "SCRIPT_BYTES=$actual EXPECTED=$expected\n" +
                        "ERR_SCRIPT_UPLOAD expected=$expected actual=$actual"
                }
                val text = files[p]!!.toString(StandardCharsets.UTF_8)
                executed.add(text)
                if (!denyDelete) files.remove(p)
                return scriptRc to ("SCRIPT_BYTES=$actual EXPECTED=$expected\n" + scriptOut)
            }
            return 1 to "fake: unknown command"
        }

        fun leftovers(): Set<String> = files.keys.toSet()
    }

    private fun bindScript() = LockMountScripts.bindScript("/data/local/tmp/carlock_custom_1750000000000.wav")

    private fun restoreScript(targets: Int) = LockMountScripts.restoreScript(
        (1..targets).map { "/vendor/etc/carlock/carlock_close_$it.wav" to "/data/local/tmp/carlock_custom_1750000000000.wav" }
    )

    // ── 1. Сам дефект: прямой вызов не проходит лимит ─────────────────────────────────────

    @Test
    fun directTransportIsRejectedByPayloadLimit() {
        val dev = FakeDevice()
        val bind = bindScript()
        val bytes = bind.toByteArray(StandardCharsets.UTF_8).size
        assertTrue("bind-скрипт действительно длиннее лимита: $bytes", bytes > 4096)
        val (rc, out) = dev.exec(bind, 20000, true)
        assertEquals(1, rc)
        assertTrue("именно этот отказ и был причиной: $out", out.contains("ERR_ADB_TOO_LONG"))
        assertTrue("на устройстве ничего не выполнилось", dev.executed.isEmpty())
    }

    // ── 2. Новый путь: bind доезжает и выполняется целиком ───────────────────────────────

    @Test
    fun bindScriptIsDeliveredAndExecutedByteExact() {
        val dev = FakeDevice(scriptOut = "MOUNTED_OK:/vendor/etc/carlock/a.wav|/data/local/tmp/x.wav\n")
        val bind = bindScript()
        val res = ScriptDelivery.deliver("lock_bind", bind, 30000, true, dev::exec, nonce = "n1")
        assertTrue("доставлено: ${res.wire()} out=${res.out}", res.sizeChecked)
        assertFalse("лимита payload больше нет: ${res.out}", res.out.contains("ERR_ADB_TOO_LONG"))
        assertEquals("rc скрипта", 0, res.rc)
        assertEquals("выполнен РОВНО тот скрипт", bind, dev.executed.single())
        assertEquals(ScriptDelivery.Transport.UPLOAD, res.plan.transport)
        assertTrue("чанков больше одного: ${res.plan.wire()}", res.plan.chunks > 1)
        assertTrue("свой файл удалён", dev.leftovers().isEmpty())
        assertEquals("", res.leftoverPath)
        assertTrue("размер сверен с фактическим", res.out.contains("EXPECTED=${bind.toByteArray(StandardCharsets.UTF_8).size}"))
        // Каждая отправленная команда уложилась в лимит.
        assertTrue("ни одна команда не превысила 4096: ${dev.commands.filter { it.toByteArray().size > 4096 }}",
            dev.commands.none { it.toByteArray(StandardCharsets.UTF_8).size > 4096 })
    }

    @Test
    fun restoreScriptForManyTargetsIsDelivered() {
        for (n in listOf(1, 2, 12)) {
            val dev = FakeDevice(scriptOut = "STOCK_OK:x\n")
            val script = restoreScript(n)
            val bytes = script.toByteArray(StandardCharsets.UTF_8).size
            assertTrue("restore($n) длиннее лимита: $bytes", bytes > 4096)
            val res = ScriptDelivery.deliver("lock_restore", script, 30000, true, dev::exec, nonce = "r$n")
            assertTrue("restore($n) доставлен: ${res.wire()}", res.sizeChecked)
            assertEquals("rc", 0, res.rc)
            assertEquals("выполнен ровно тот скрипт ($n целей)", script, dev.executed.single())
            assertEquals("временных файлов не осталось", emptySet<String>(), dev.leftovers())
        }
    }

    // ── 3. Отказ заливки не приводит к выполнению ────────────────────────────────────────

    @Test
    fun transientChunkFailureIsRetriedAndRunsExactlyOnce() {
        // Один сбой чанка — это ПОВОД ПОВТОРИТЬ заливку, а не выполнить обрезанный скрипт.
        val dev = FakeDevice(failChunkIndex = 1)
        val res = ScriptDelivery.deliver("lock_bind", bindScript(), 30000, true, dev::exec, nonce = "n1b")
        assertTrue("повтор удался: ${res.wire()}", res.sizeChecked)
        assertEquals("на устройстве выполнен ровно один раз", 1, dev.executed.size)
        assertEquals(bindScript(), dev.executed.single())
        assertEquals("потребовалась вторая попытка", 2, res.attempts)
    }

    @Test
    fun permanentChunkFailurePreventsAnyExecution() {
        val dev = FakeDevice(failAllChunks = true)
        val bind = bindScript()
        val res = ScriptDelivery.deliver("lock_bind", bind, 30000, true, dev::exec, nonce = "n2")
        assertFalse("доставка не подтверждена: ${res.wire()}", res.sizeChecked)
        assertNotEquals("rc != 0", 0, res.rc)
        assertTrue("mount НЕ выполнялся", dev.executed.isEmpty())
        assertTrue("причина названа: ${res.out}", res.out.contains("ERR_SCRIPT_UPLOAD_CHUNK"))
        assertEquals("свой файл убран", emptySet<String>(), dev.leftovers())
    }

    @Test
    fun truncatedUploadIsDetectedAndNeverRuns() {
        // Первый чанк записался неполностью → размер не сойдётся → ERR_SCRIPT_UPLOAD.
        val dev = FakeDevice(truncateBy = 7)
        val res = ScriptDelivery.deliver("lock_bind", bindScript(), 30000, true, dev::exec,
            tries = 3, nonce = "n3")
        assertFalse("неподтверждённая доставка не выдаётся за успех: ${res.wire()}", res.sizeChecked)
        assertEquals("три попытки, как в production", 3, res.attempts)
        assertTrue("обрезанный скрипт НЕ запускался", dev.executed.isEmpty())
        assertTrue("сверка размера сработала: ${res.out}", res.out.contains("ERR_SCRIPT_UPLOAD"))
        assertEquals("файл убран", emptySet<String>(), dev.leftovers())
    }

    // ── 4. Своё имя файла, свои файлы ────────────────────────────────────────────────────

    @Test
    fun concurrentDeliveriesUseDistinctPaths() {
        val dev = FakeDevice()
        val a = ScriptDelivery.deliver("lock_bind", bindScript(), 30000, true, dev::exec, nonce = "aa")
        val b = ScriptDelivery.deliver("lock_bind", restoreScript(2), 30000, true, dev::exec, nonce = "bb")
        assertNotEquals("одно и то же имя файла затирало бы чужую заливку",
            a.plan.path, b.plan.path)
        assertEquals("оба скрипта выполнены", 2, dev.executed.size)
        assertEquals(bindScript(), dev.executed[0])
        assertEquals(restoreScript(2), dev.executed[1])
    }

    @Test
    fun pathIsUniqueEvenForRepeatedSameName() {
        val p1 = ScriptDelivery.pathFor("lock_bind", ScriptDelivery.newNonce())
        val p2 = ScriptDelivery.pathFor("lock_bind", ScriptDelivery.newNonce())
        assertNotEquals("фиксированное имя `voiceapp3_lock_bind.sh` больше не используется", p1, p2)
        assertTrue("имя в разрешённом каталоге", p1.startsWith("/data/local/tmp/voiceapp3_"))
        assertTrue("safe chars only", ScriptDelivery.sanitize("a b/c") == "a_b_c")
    }

    @Test
    fun cleanupFailureIsReportedNotSwallowed() {
        val dev = FakeDevice(denyDelete = true)
        val res = ScriptDelivery.deliver("lock_bind", bindScript(), 30000, true, dev::exec, nonce = "n4")
        assertTrue("скрипт всё же выполнился: ${res.wire()}", res.sizeChecked)
        assertTrue("оставшийся файл назван: ${res.wire()}", res.leftoverPath.isNotEmpty())
        assertTrue("и он действительно остался", dev.leftovers().isNotEmpty())
    }

    @Test
    fun scriptRcIsPropagated() {
        val dev = FakeDevice(scriptRc = 3, scriptOut = "MOUNT_FAIL:/x rc=32\n")
        val res = ScriptDelivery.deliver("lock_bind", bindScript(), 30000, true, dev::exec, nonce = "n5")
        assertEquals(3, res.rc)
        assertTrue(res.out.contains("MOUNT_FAIL"))
    }

    // ── 5. Планировщик ──────────────────────────────────────────────────────────────────

    @Test
    fun transportIsChosenByActualUtf8Length() {
        val small = ScriptDelivery.plan("x", "echo ok\n")
        assertEquals(ScriptDelivery.Transport.DIRECT, small.transport)
        val big = ScriptDelivery.plan("x", "a".repeat(ScriptDelivery.DIRECT_LIMIT + 1))
        assertEquals(ScriptDelivery.Transport.UPLOAD, big.transport)
        // Многобайтовые символы считаются в байтах, а не в символах.
        val cyr = ScriptDelivery.plan("x", "я".repeat(1800)) // 3600 байт > 3500
        assertEquals(ScriptDelivery.Transport.UPLOAD, cyr.transport)
        assertEquals(3600, cyr.bytes)
    }

    @Test
    fun oversizedScriptIsRefusedBeforeTouchingDevice() {
        val dev = FakeDevice()
        val huge = "x".repeat(ScriptDelivery.MAX_SCRIPT_BYTES + 1)
        val res = ScriptDelivery.deliver("x", huge, 30000, true, dev::exec)
        assertEquals(1, res.rc)
        assertTrue(res.out.contains("ERR_SCRIPT_TOO_LARGE"))
        assertTrue("на устройство ничего не отправлялось", dev.commands.isEmpty())
    }
}
