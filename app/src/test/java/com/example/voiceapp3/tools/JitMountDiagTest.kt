package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Одноразовый read-only сбор данных о монтированиях ([JitMountDiag]).
 *
 * Проверяется ТОТ ЖЕ код, что работает на устройстве: команды, разбор маркеров, статусы и формат
 * лога. Транспорт подставной — на ГУ сбор не запускался, поэтому реальные файлы `/proc` этой машины в
 * тестах не участвуют.
 *
 * ВАЖНО: фейки ≠ Android-интеграция. Ни одна команда здесь не выполнялась на устройстве.
 */
class JitMountDiagTest {

    private val logs = mutableListOf<String>()

    @Before
    fun setUp() {
        logs.clear()
    }

    private fun log(s: String) {
        synchronized(logs) { logs.add(s) }
    }

    /** §5.1 iter41: приёмник с подтверждением записи (модель датированного файла). */
    private fun sink(): JitFake.TestSink = JitFake.TestSink(logs)

    /** Shell, отдающий заранее заданные ответы по номеру вызова. */
    private class ScriptedShell(private val answers: List<Pair<Int, String>>) : JitRef.JitShell {
        val commands = mutableListOf<String>()
        private var i = 0
        override fun exec(command: String, timeoutMs: Int): Pair<Int, String> {
            commands += command
            val a = answers.getOrElse(i) { 0 to "" }
            i++
            return a
        }
        override fun mapsExcerpt(): String? = null
    }

    private val mounts = "/dev/block/sda1 /data/app/com.foo/base.apk ext4 ro 0 0\n" +
        "/dev/block/sda2 /data/app/com.bar/base.apk ext4 ro 0 0\n" +
        "/dev/block/sda3 /data/data ext4 rw 0 0"

    private fun fileAnswer(body: String, wcRc: Int = 0, catRc: Int = 0, extra: String = ""): String {
        val lines = body.split('\n').count { it.isNotBlank() }
        return "${lines.toString().padStart(5)} ${body.length.toString().padStart(7)} /proc/x\n" +
            "__JMD_WC_RC=$wcRc\n$body\n__JMD_CAT_RC=$catRc\n" + extra + "__JMD_EOF=1"
    }

    private fun identityAnswer(uid: Int = 0, idRc: Int = 0): String =
        "uid=$uid(root) gid=$uid(root)\n__JMD_ID_RC=$idRc\n" +
            "mnt:[4026531840]\n__JMD_NSSELF_RC=0\n" +
            "mnt:[4026531840]\n__JMD_NSINIT_RC=0\n" +
            "0 0 4294967295\n__JMD_UIDMAP_RC=0\n__JMD_EOF=1"

    private fun okAnswers(body: String = mounts) = listOf(
        0 to identityAnswer(),
        0 to fileAnswer(body),
        0 to fileAnswer(body),
        0 to fileAnswer(body)
    )

    @Test
    fun commandsAreReadOnlyAndFour() {
        val shell = ScriptedShell(okAnswers())
        JitMountDiag.collect(shell, true, sink(), "test")
        assertEquals(4, shell.commands.size)
        for (cmd in shell.commands) {
            val noQuietRedirect = cmd.replace("2>&1", "")
            for (bad in listOf("umount", "mount -", "setprop", "svc ", "ip rule del", "ndc", "settings",
                "kill", "pkill", "reboot", "rm ", ">", "mv ", "chmod", "setenforce")) {
                assertFalse("команда не содержит «$bad»: $cmd", noQuietRedirect.contains(bad))
            }
            assertTrue("только чтение", cmd.contains("cat /proc/") || cmd.contains("readlink") ||
                cmd.contains("id ") || cmd.contains("wc -lc"))
        }
    }

    @Test
    fun goodReadProducesOkSectionsAndFilteredLines() {
        val sections = JitMountDiag.collect(ScriptedShell(okAnswers()), true, sink(), "service.onCreate")
        assertEquals(4, sections.size)
        val identity = sections.first { it.name == "identity" }
        assertEquals(JitMountDiag.STATUS_OK, identity.status)
        assertEquals(0, identity.uid)
        assertEquals(true, identity.root)
        assertEquals("mnt:[4026531840]", identity.nsSelf)
        assertEquals("mnt:[4026531840]", identity.nsInit)
        val procMounts = sections.first { it.name == "proc_mounts" }
        assertEquals(JitMountDiag.STATUS_OK, procMounts.status)
        assertEquals("условие референса ' /data/app/'", 2, procMounts.matchedLines)
        assertTrue(logs.any { it.contains("JIT_MOUNT_DIAG_SECTION name=proc_mounts") && it.contains("matched=2") })
        assertTrue(logs.any { it.contains("JIT_MOUNT_DIAG_END") && it.contains("system_writes=0") })
        assertTrue("данные в логе", logs.any { it.startsWith("JIT_MOUNT_DIAG_DATA") && it.contains("/data/app/com.foo") })
        assertTrue("полнота зафиксирована", logs.any { it.contains("complete=true") })
    }

    @Test
    fun identityWithNonZeroUidIsNotRoot() {
        val answers = okAnswers().toMutableList()
        answers[0] = 0 to identityAnswer(uid = 2000)
        val sections = JitMountDiag.collect(ScriptedShell(answers), true, sink(), "test")
        val identity = sections.first { it.name == "identity" }
        assertEquals("ошибки `id` нет", JitMountDiag.STATUS_OK, identity.status)
        assertEquals(2000, identity.uid)
        assertEquals("успешный id ≠ uid=0", false, identity.root)
        assertTrue(logs.any { it.contains("id_ok_but_not_root uid=2000") })
    }

    @Test
    fun identityFailureIsRootUnknownNotOk() {
        val answers = okAnswers().toMutableList()
        answers[0] = 0 to "id: not found\n__JMD_ID_RC=127\n__JMD_EOF=1"
        val sections = JitMountDiag.collect(ScriptedShell(answers), true, sink(), "test")
        val identity = sections.first { it.name == "identity" }
        assertEquals(JitMountDiag.STATUS_ROOT_UNKNOWN, identity.status)
        assertNull(identity.root)
    }

    @Test
    fun emptyOnlyAfterConfirmedCompleteRead() {
        val body = "rootfs / rootfs ro 0 0\n/dev/x /data rw 0 0"
        val sections = JitMountDiag.collect(ScriptedShell(okAnswers(body)), true, sink(), "test")
        assertEquals(JitMountDiag.STATUS_EMPTY, sections.first { it.name == "proc_mounts" }.status)
    }

    @Test
    fun missingEndMarkerIsUnknownNotEmpty() {
        val body = "rootfs / rootfs ro 0 0"
        val answers = okAnswers(body).toMutableList()
        answers[1] = 0 to fileAnswer(body).replace("__JMD_EOF=1", "")
        val sections = JitMountDiag.collect(ScriptedShell(answers), true, sink(), "test")
        assertEquals("потерянный хвост не превращается в empty",
            JitMountDiag.STATUS_UNKNOWN, sections.first { it.name == "proc_mounts" }.status)
        assertTrue(logs.any { it.contains("name=proc_mounts") && it.contains("complete=false") })
    }

    @Test
    fun catErrorWithShellRcZeroIsNotOk() {
        val answers = okAnswers().toMutableList()
        answers[2] = 0 to fileAnswer(mounts, catRc = 1)
        val sections = JitMountDiag.collect(ScriptedShell(answers), true, sink(), "test")
        assertEquals(JitMountDiag.STATUS_UNKNOWN, sections.first { it.name == "self_mountinfo" }.status)
    }

    @Test
    fun wcErrorIsUnknownNotOk() {
        val answers = okAnswers().toMutableList()
        answers[3] = 0 to fileAnswer(mounts, wcRc = 1)
        val sections = JitMountDiag.collect(ScriptedShell(answers), true, sink(), "test")
        assertEquals(JitMountDiag.STATUS_UNKNOWN, sections.first { it.name == "init_mountinfo" }.status)
    }

    @Test
    fun permissionDeniedIsNotTreatedAsEmpty() {
        val answers = okAnswers().toMutableList()
        answers[3] = 1 to "wc: /proc/1/mountinfo: Permission denied\n__JMD_WC_RC=1\n" +
            "cat: /proc/1/mountinfo: Permission denied\n__JMD_CAT_RC=1\n__JMD_EOF=1"
        val sections = JitMountDiag.collect(ScriptedShell(answers), true, sink(), "test")
        assertEquals(JitMountDiag.STATUS_PERMISSION_DENIED, sections.first { it.name == "init_mountinfo" }.status)
    }

    @Test
    fun timeoutIsReportedAsTimeout() {
        val answers = okAnswers().toMutableList()
        answers[1] = 137 to "ERR_ADB_TIMEOUT after 8000ms"
        val sections = JitMountDiag.collect(ScriptedShell(answers), true, sink(), "test")
        assertEquals(JitMountDiag.STATUS_TIMEOUT, sections.first { it.name == "proc_mounts" }.status)
        assertTrue(logs.any { it.contains("timeout") })
    }

    @Test
    fun shortOutputIsTruncatedByLinesAndBytes() {
        // `wc` сообщает больше строк и байт, чем пришло.
        val answers = okAnswers().toMutableList()
        answers[1] = 0 to "  40  5000 /proc/mounts\n__JMD_WC_RC=0\n$mounts\n__JMD_CAT_RC=0\n__JMD_EOF=1"
        val sections = JitMountDiag.collect(ScriptedShell(answers), true, sink(), "test")
        val s = sections.first { it.name == "proc_mounts" }
        assertEquals(JitMountDiag.STATUS_TRUNCATED, s.status)
        assertTrue(logs.any { it.contains("delta_lines=") && it.contains("delta_bytes=") })
    }

    @Test
    fun middleLossIsDetectedByByteCount() {
        // Строк столько же, а байт меньше — значит часть содержимого потеряна в середине.
        val body = mounts
        val sameLines = body.replace("com.bar/base.apk", "com.bar")   // строки те же, байтов меньше
        val answers = okAnswers(body).toMutableList()
        answers[1] = 0 to "   3 ${body.length} /proc/mounts\n__JMD_WC_RC=0\n$sameLines\n__JMD_CAT_RC=0\n__JMD_EOF=1"
        val sections = JitMountDiag.collect(ScriptedShell(answers), true, sink(), "test")
        assertEquals("потеря байт видна", JitMountDiag.STATUS_TRUNCATED,
            sections.first { it.name == "proc_mounts" }.status)
    }

    @Test
    fun longLineIsSplitWithoutSilentLoss() {
        val long = "/dev/block/sda1 " + "/data/app/com.very.long.name/".repeat(80) + " ext4 ro 0 0"
        val answers = okAnswers(long).toMutableList()
        answers[1] = 0 to fileAnswer(long)
        val sections = JitMountDiag.collect(ScriptedShell(answers), true, sink(), "test")
        assertEquals(JitMountDiag.STATUS_OK, sections.first { it.name == "proc_mounts" }.status)
        val parts = logs.filter { it.contains("name=proc_mounts part=") }
        assertTrue("строка поделена", parts.size > 1)
        assertTrue("деление помечено", parts.any { it.contains("продолжение строки") })
        val joined = parts.joinToString("")
        assertTrue("содержимое не потеряно", joined.contains("com.very.long.name"))
        assertTrue(parts.all { it.length < 2_000 })
    }

    @Test
    fun channelNotReadyProducesNoCommandsAndHonestStatus() {
        val shell = ScriptedShell(okAnswers())
        val sections = JitMountDiag.collect(shell, false, sink(), "service.onCreate")
        assertTrue("ни одной команды", shell.commands.isEmpty())
        assertTrue(sections.all { it.status == JitMountDiag.STATUS_CHANNEL_NOT_READY })
        assertTrue(logs.any { it.contains("status=channel_not_ready") && it.contains("НЕ отсутствие root") })
    }

    @Test
    fun filtersOnlyTheSameConditionAsReference() {
        assertEquals(2, JitMountDiag.filterDataApp(mounts).size)
        assertEquals(0, JitMountDiag.filterDataApp("/dev/x /data/app ext4 ro 0 0").size)
        assertEquals(0, JitMountDiag.filterDataApp("/dev/x /data/appfoo/a ext4 ro 0 0").size)
        assertEquals(1, JitMountDiag.filterDataApp("/dev/x /data/app/a/b ext4 ro 0 0").size)
    }

    @Test
    fun wcCountsParsesLinesAndBytes() {
        val (lines, bytes) = JitMountDiag.wcCounts("  12  3456 /proc/mounts\n__JMD_WC_RC=0")
        assertEquals(12, lines)
        assertEquals(3456, bytes)
    }

    @Test
    fun oneShotPerProcessIsEnforced() {
        JitNet.resetDiagForTest()
        assertTrue("первая попытка разрешена", JitNet.markDiagAttempted())
        assertFalse("вторая — уже нет", JitNet.markDiagAttempted())
        JitNet.resetDiagForTest()
        assertTrue("сброс (новый процесс) снова разрешает", JitNet.markDiagAttempted())
        JitNet.resetDiagForTest()
    }

    @Test
    fun adapterRunsOffMainThreadAndWithoutWaiting() {
        val src = File("src/main/java/com/example/voiceapp3/tools/JitNet.kt")
            .let { if (it.isFile) it else File("app/src/main/java/com/example/voiceapp3/tools/JitNet.kt") }
            .readText()
        val fn = src.substring(src.indexOf("fun requestDiagOnce"), src.indexOf("internal fun markDiagAttempted"))
        assertTrue("работа уходит в фон", fn.contains("BgWorker.post"))
        assertTrue("и проверяется, что это не UI-поток", fn.contains("assertNotMain"))
        assertTrue("готовность канала проверяется без блокирующего ожидания",
            fn.contains("isChannelReady") && !fn.contains("awaitReady"))
        assertTrue("одноразовый обработчик события готовности", fn.contains("onChannelReadyOnce"))
        // §5.2 iter41: вход один раз за процесс (`markDiagAttempted`), а сами попытки сбора
        // ограничены и учитываются отдельно (`JitRef.DiagOnce`).
        assertTrue("вход один раз за процесс", fn.contains("markDiagAttempted()"))
        assertTrue("успех фиксируется по факту завершения", fn.contains("diagOnce.finish("))
        assertTrue("число попыток ограничено", fn.contains("diagOnce.tryRun()"))
        assertTrue("честный статус при неготовом канале", fn.contains("channel_not_ready"))
        assertFalse("никаких циклов ожидания", fn.contains("while ("))
        val sched = src.substring(src.indexOf("fun requestDiagOnce"), src.indexOf("internal fun resetDiagForTest"))
        // iter40 §4.2: при неготовом канале Android API-часть всё равно читается
        // (`InternetDiag.collect` с `channelReady=false`), поэтому отдельного
        // `collectChannelNotReady` в этом пути больше нет.
        assertTrue("у каждой части свои метки", sched.contains("JitMountDiag.collect") &&
            sched.contains("InternetDiag.collect"))
        assertTrue("Android-часть собирается и без shell", sched.contains("DiagShell, false"))
    }

    @Test
    fun collectionDoesNotDependOnFindmnt() {
        val shell = ScriptedShell(okAnswers(""))
        JitMountDiag.collect(shell, true, sink(), "test")
        assertTrue("findmnt не используется", shell.commands.none { it.contains("findmnt") })
    }
}
