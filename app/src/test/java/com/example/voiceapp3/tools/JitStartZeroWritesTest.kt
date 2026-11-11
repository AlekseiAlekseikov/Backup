package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Обычный старт приложения / загрузка ГУ: **ничего не делаем**.
 *
 * В референсе JIT применяется только по клику (`w0/j9.onClick`); `CentralWatchdogService.onCreate`
 * не рестартует фреймворк и не ставит свойство — он лишь возвращает сеть, если в префах осталась
 * строка `jit_restaurar`. Значит, при обычном старте без этой строки не должно быть ни одной
 * записи, ни одной команды и ни одного сетевого запроса.
 */
class JitStartZeroWritesTest {

    @Test
    fun plainStartWritesNothingAndSendsNoCommands() {
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true, ap = true)
        val log = mutableListOf<String>()
        val out = JitOp.onProcessStart(store, net, JitFake.Waiter(), JitFake.Scheduler(), log = { log += it })
        assertEquals(null, out.snapshot)
        assertEquals("ноль записей в префы", 0, store.writeCount())
        assertTrue("ноль сетевых запросов", net.requests.isEmpty())
        assertTrue(log.any { it.contains("snapshot=none") })
        assertTrue("ни рестарта, ни применения не заявлено",
            log.none { it.contains("restart=claimed") })
    }

    @Test
    fun stalePedidoWithoutSnapshotIsNotTouched() {
        // Остаток старых версий: `jit_pedido=true` в префах, строки снимка нет.
        val store = JitFake.Store()
        store.set(JitRef.KEY_PEDIDO, true)
        val net = JitFake.Net(wifi = true, ap = false)
        JitOp.onProcessStart(store, net, JitFake.Waiter(), JitFake.Scheduler())
        assertEquals("на старте мы не пишем даже «снять намерение»", 0, store.writeCount())
        assertEquals(true, store.getBool(JitRef.KEY_PEDIDO))
        assertTrue(net.requests.isEmpty())
    }

    @Test
    fun startSendsNoCommandsToDevice() {
        // Старт процесса не должен звать transport вообще: снимка нет — нечего делать.
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true, ap = false)
        val shell = JitFake.AnswerShell()
        JitOp.verifyApplied(store, shell) // сверка без jit_pedido: remove не выполняется
        assertEquals("сверка без намерения не удаляет ключи", 0, store.writeCount())
        val scheduler = JitFake.Scheduler()
        JitOp.onProcessStart(store, net, JitFake.Waiter(), scheduler)
        assertTrue("ни одной сетевой команды", shell.scriptCommands().isEmpty())
        assertTrue("и ничего не запланировано", scheduler.scheduled.isEmpty())
    }

    @Test
    fun noAutomaticJitAnywhereInCode() {
        fun code(rel: String): String =
            listOf(File(rel), File("app/$rel")).first { it.isFile }.readLines()
                .filterNot {
                    val s = it.trim()
                    s.startsWith("*") || s.startsWith("//") || s.startsWith("/*")
                }
                .joinToString("\n")

        val core = code("src/main/java/com/example/voiceapp3/tools/JitNetCore.kt")
        // рестарт фреймворка — только в тексте скрипта клика
        assertEquals("ровно одно упоминание команды рестарта", 1,
            Regex("ctl\\.restart zygote").findAll(core).count())
        val afterClick = core.substring(core.indexOf("fun onProcessStart"))
        assertFalse("в продолжении рестарта нет", afterClick.contains("ctl.restart"))

        for (f in listOf("AppPerformance.kt", "JitNet.kt", "JitNetCore.kt", "VoiceAssistantService.kt")) {
            val src = runCatching { code("src/main/java/com/example/voiceapp3/tools/$f") }
                .getOrElse { code("src/main/java/com/example/voiceapp3/$f") }
            for (bad in listOf("bootRestoreJit", "jitPersistBackfill", "jit_pending_verify",
                "jit_verified", "sleep 8", "WatchService", "FileObserver", "cancelFile", "op_ttl")) {
                assertFalse("$f: нет «$bad»", src.contains(bad))
            }
        }
    }

    @Test
    fun bootProbeIsReadOnly() {
        val src = File("src/main/java/com/example/voiceapp3/tools/AppPerformance.kt")
            .let { if (it.isFile) it else File("app/src/main/java/com/example/voiceapp3/tools/AppPerformance.kt") }
            .readText()
        val probe = src.substring(
            src.indexOf("fun jitBootProbe"),
            src.indexOf("fun jitBootProbe") + 4_000
        )
        assertTrue("проба только читает свойства", probe.contains("getprop"))
        assertFalse("проба не пишет свойства", probe.contains("setprop"))
        assertTrue("остатки старых версий фиксируются отдельно", src.contains("JIT_BOOT_LEGACY"))
    }
}
