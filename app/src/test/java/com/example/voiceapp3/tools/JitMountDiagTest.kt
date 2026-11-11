package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Одноразовый read-only сбор данных о монтированиях ([JitMountDiag]).
 *
 * Проверяется ТОТ ЖЕ код разбора, что работает на устройстве: команды, статусы разделов и формат
 * строк лога. Транспорт здесь подставной — на ГУ сбор не запускался, поэтому реальные строки
 * файлы `/proc` этой машины участвуют только там, где это безопасно и не зависит от прав.
 */
class JitMountDiagTest {

    private val logs = mutableListOf<String>()

    @Before
    fun setUp() {
        logs.clear()
    }

    private fun log(s: String) { logs.add(s) }

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

    private val mountsBody = """
        rootfs / rootfs ro 0 0
        /dev/block/sda1 /data/app/com.foo/base.apk ext4 ro 0 0
        /dev/block/sda2 /data/app/com.bar/base.apk ext4 ro 0 0
        /dev/block/sda3 /data/data ext4 rw 0 0
    """.trimIndent()

    private val mountinfoBody = """
        25 1 8:1 / /data/app/com.foo/base.apk rw,relatime master:1 - ext4 /dev/block/sda1 rw
        26 1 8:2 / /data/app/com.bar/base.apk rw,relatime master:2 - ext4 /dev/block/sda2 rw
    """.trimIndent()

    private fun okAnswers(mounts: String = mountsBody, selfMi: String = mountinfoBody, initMi: String = mountinfoBody) =
        listOf(
            0 to "uid=0(root) gid=0(root)\n__JMD_ID_RC=0\nmnt:[4026531840]\n__JMD_NSSELF_RC=0\n" +
                "mnt:[4026531840]\n__JMD_NSINIT_RC=0\n0 0 4294967295\n__JMD_UIDMAP_RC=0",
            0 to "4\n__JMD_WC_RC=0\n$mounts\n__JMD_CAT_RC=0",
            0 to "2\n__JMD_WC_RC=0\n$selfMi\n__JMD_CAT_RC=0",
            0 to "2\n__JMD_WC_RC=0\n$initMi\n__JMD_CAT_RC=0"
        )

    @Test
    fun commandsAreReadOnlyAndFour() {
        val shell = ScriptedShell(okAnswers())
        JitMountDiag.collect(shell, ::log, "test")
        assertEquals(4, shell.commands.size)
        for (cmd in shell.commands) {
            // Никаких изменяющих глаголов: только чтение файлов и readlink/id.
            // `2>&1` — перенаправление stderr в тот же поток вывода, не запись в файл.
            val noQuietRedirect = cmd.replace("2>&1", "")
            for (bad in listOf("umount", "mount -", "setprop", "svc ", "ip rule del", "ndc", "settings",
                "kill", "pkill", "reboot", "rm ", ">", "mv ", "chmod", "setenforce")) {
                assertFalse("команда не содержит «$bad»: $cmd", noQuietRedirect.contains(bad))
            }
            assertTrue(
                "только чтение",
                cmd.contains("cat /proc/") || cmd.contains("readlink") || cmd.contains("id ") ||
                    cmd.contains("wc -l")
            )
        }
    }

    @Test
    fun goodReadProducesOkSectionsAndFilteredLines() {
        val shell = ScriptedShell(okAnswers())
        val sections = JitMountDiag.collect(shell, ::log, "service.onCreate")
        assertEquals(4, sections.size)
        val mounts = sections.first { it.name == "proc_mounts" }
        assertEquals(JitMountDiag.STATUS_OK, mounts.status)
        assertEquals("условие референса ' /data/app/'", 2, mounts.matchedLines)
        assertTrue(logs.any { it.contains("JIT_MOUNT_DIAG_SECTION name=proc_mounts") && it.contains("matched=2") })
        assertTrue(logs.any { it.contains("JIT_MOUNT_DIAG_END") && it.contains("system_writes=0") })
        assertTrue("данные в логе", logs.any { it.startsWith("JIT_MOUNT_DIAG_DATA") && it.contains("/data/app/com.foo") })
    }

    @Test
    fun emptyResultOfSuccessfulReadIsEmptyNotDenied() {
        val body = "rootfs / rootfs ro 0 0"
        val answers = okAnswers(mounts = body).toMutableList()
        answers[1] = 0 to "1\n__JMD_WC_RC=0\n$body\n__JMD_CAT_RC=0"
        val sections = JitMountDiag.collect(ScriptedShell(answers), ::log, "test")
        val mounts = sections.first { it.name == "proc_mounts" }
        assertEquals(JitMountDiag.STATUS_EMPTY, mounts.status)
        assertEquals(0, mounts.matchedLines)
    }

    @Test
    fun permissionDeniedIsNotTreatedAsEmpty() {
        val answers = okAnswers().toMutableList()
        answers[3] = 1 to "wc: /proc/1/mountinfo: Permission denied\n__JMD_WC_RC=1\n" +
            "cat: /proc/1/mountinfo: Permission denied\n__JMD_CAT_RC=1"
        val sections = JitMountDiag.collect(ScriptedShell(answers), ::log, "test")
        val init = sections.first { it.name == "init_mountinfo" }
        assertEquals(JitMountDiag.STATUS_PERMISSION_DENIED, init.status)
    }

    @Test
    fun timeoutIsReportedAsTimeout() {
        val answers = okAnswers().toMutableList()
        answers[1] = 137 to "ERR_ADB_TIMEOUT after 8000ms"
        val sections = JitMountDiag.collect(ScriptedShell(answers), ::log, "test")
        assertEquals(JitMountDiag.STATUS_TIMEOUT, sections.first { it.name == "proc_mounts" }.status)
        assertTrue(logs.any { it.contains("timeout=") })
    }

    @Test
    fun unknownFailureIsUnknownNotOk() {
        val answers = okAnswers().toMutableList()
        answers[2] = 1 to "cat: /proc/self/mountinfo: I/O error\n__JMD_WC_RC=1\nx\n__JMD_CAT_RC=1"
        val sections = JitMountDiag.collect(ScriptedShell(answers), ::log, "test")
        val self = sections.first { it.name == "self_mountinfo" }
        assertTrue(self.status == JitMountDiag.STATUS_UNKNOWN || self.status == JitMountDiag.STATUS_EMPTY)
        assertFalse(sections.any { it.name == "self_mountinfo" && it.status == JitMountDiag.STATUS_OK })
    }

    @Test
    fun shortOutputIsMarkedTruncated() {
        // `wc -l` сообщает 40 строк, а пришло 3 — значит транспорт/лог обрезал.
        val answers = okAnswers().toMutableList()
        answers[1] = 0 to "40\n__JMD_WC_RC=0\n$mountsBody\n__JMD_CAT_RC=0"
        val sections = JitMountDiag.collect(ScriptedShell(answers), ::log, "test")
        assertEquals(JitMountDiag.STATUS_TRUNCATED, sections.first { it.name == "proc_mounts" }.status)
        assertTrue(logs.any { it.contains("truncated=true") })
    }

    @Test
    fun longOutputIsSplitIntoNumberedParts() {
        val many = (1..120).joinToString("\n") { "/dev/block/sda$it /data/app/com.app$it/base.apk ext4 ro 0 0" }
        val answers = okAnswers(mounts = many).toMutableList()
        answers[1] = 0 to "120\n__JMD_WC_RC=0\n$many\n__JMD_CAT_RC=0"
        val sections = JitMountDiag.collect(ScriptedShell(answers), ::log, "test")
        val mounts = sections.first { it.name == "proc_mounts" }
        assertEquals(120, mounts.matchedLines)
        assertTrue("частей больше одной", mounts.chunks > 1)
        val parts = logs.filter { it.contains("name=proc_mounts part=") }
        assertEquals(mounts.chunks, parts.size)
        assertTrue(parts.first().contains("part=1/${mounts.chunks}"))
        assertTrue("часть не разрывает строку", parts.all { it.length < 2_000 })
    }

    @Test
    fun filtersOnlyTheSameConditionAsReference() {
        assertEquals(2, JitMountDiag.filterDataApp(mountsBody).size)
        assertEquals(0, JitMountDiag.filterDataApp("/dev/x /data/app ext4 ro 0 0").size)
        assertEquals(0, JitMountDiag.filterDataApp("/dev/x /data/appfoo/a ext4 ro 0 0").size)
        assertEquals(1, JitMountDiag.filterDataApp("/dev/x /data/app/a/b ext4 ro 0 0").size)
    }

    @Test
    fun chunksNeverExceedLimit() {
        val text = (1..50).joinToString("\n") { "line-$it-" + "x".repeat(80) }
        val parts = JitMountDiag.chunks(text, 500)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.length <= 600 })
    }

    @Test
    fun oneShotPerProcessIsEnforced() {
        JitNet.resetMountDiagForTest()
        assertTrue("первая попытка разрешена", JitNet.markMountDiagAttempted())
        assertFalse("вторая — уже нет", JitNet.markMountDiagAttempted())
        assertFalse("третья — тоже нет", JitNet.markMountDiagAttempted())
        JitNet.resetMountDiagForTest()
        assertTrue("сброс (новый процесс) снова разрешает", JitNet.markMountDiagAttempted())
        JitNet.resetMountDiagForTest()
    }

    @Test
    fun adapterRunsOffMainThreadAndWithoutWaiting() {
        val src = File("src/main/java/com/example/voiceapp3/tools/JitNet.kt")
            .let { if (it.isFile) it else File("app/src/main/java/com/example/voiceapp3/tools/JitNet.kt") }
            .readText()
        val fn = src.substring(
            src.indexOf("fun requestMountDiagOnce"),
            src.indexOf("fun resetMountDiagForTest")
        )
        assertTrue("работа уходит в фон", fn.contains("BgWorker.post"))
        assertTrue("и проверяется, что это не UI-поток", fn.contains("assertNotMain"))
        assertTrue("готовность канала проверяется без ожидания",
            fn.contains("isChannelReady") && !fn.contains("awaitReady"))
        assertTrue("не чаще одной попытки за процесс", fn.contains("compareAndSet(false, true)"))
        assertTrue("честный статус при недоступном канале", fn.contains("root_unavailable"))
        assertFalse("никаких повторов/циклов", fn.contains("while ("))
    }

    @Test
    fun collectionDoesNotDependOnFindmnt() {
        val shell = ScriptedShell(okAnswers(mounts = "", selfMi = "", initMi = ""))
        JitMountDiag.collect(shell, ::log, "test")
        assertTrue("findmnt не используется", shell.commands.none { it.contains("findmnt") })
    }
}
