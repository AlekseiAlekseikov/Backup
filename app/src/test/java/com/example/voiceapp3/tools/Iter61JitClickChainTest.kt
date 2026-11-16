package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * iter61 §3.4 — ПОЛНЫЙ КЛИК JIT: снимок сети → одна payload-команда (санация `ip rule`
 * `oif wlan0` → свойства → `ctl.restart zygote` ПОСЛЕДНИМ) → порядок намерений seq/epoch.
 *
 * Проверяется ЧИСЛО фактических побочных эффектов (исполнений payload, вызовов Wi-Fi, записей в
 * хранилища, число команд транспорта), а не текст лога: строка «действие выполнено» ничего не
 * доказывает. Порядок намерений проверяется НАСТОЯЩЕЙ production-функцией `_jit_intent_order`
 * из [JitOwn.ownHelpersForTest], исполняемой `/bin/sh`, — с переводом часов НАЗАД.
 *
 * Чего тест НЕ доказывает (в отчёте 🟡/⛔): реальный `zygote`, `svc wifi`, `ip rule` и наличие
 * интернета на ГУ; здесь JVM + fake shell + `/bin/sh` песочницы. Побочных эффектов на ГУ нет.
 */
class Iter61JitClickChainTest {

    private val logs = java.util.Collections.synchronizedList(mutableListOf<String>())
    private fun log(s: String) { logs += s }
    private fun lines(): String = synchronized(logs) { logs.joinToString("\n") }

    @Before
    fun clean() {
        JitDev.reset()
        logs.clear()
        JitOp.resetStateForTest()
    }

    private val PROP = "dalvik.vm.usejit"

    /** Ничего, кроме JIT-свойства и перезапуска zygote, уходить не должно. */
    private val forbidden = listOf(
        "startSoftAp", "hostapd", "softap", "AVAS", "avas", "CarplayMonitor",
        "connadaptor", "adb.tcp", "5555", "setenforce", "pm install", "reboot"
    )

    private class RecShell : JitRef.JitShell {
        val executed = java.util.Collections.synchronizedList(mutableListOf<String>())
        override fun exec(command: String, timeoutMs: Int): Pair<Int, String> {
            executed += command
            return JitDev.exec(command) { 0 to "" }
        }
        override fun mapsExcerpt(): String? = null
        fun payloadCmds(): List<String> = synchronized(executed) {
            executed.filter { it.contains("setprop ctl.restart zygote") }
        }
        fun all(): List<String> = synchronized(executed) { executed.toList() }
    }

    // ───────── 1. Число побочных эффектов одного клика ─────────

    @Test
    fun oneClickIsExactlyOnePayloadOneCommandAndNoDirectWifiCall() {
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true)
        val shell = RecShell()

        val c = JitOp.click(store, net, shell, want = true, log = ::log)
        assertEquals("клик не заблокирован: ${c.blocked} / ${lines()}", null, c.blocked)
        assertEquals("payload исполнен РОВНО один раз", 1, JitDev.payloadRuns)
        assertEquals("транспорту ушла РОВНО одна payload-команда: ${shell.all().size}",
            1, shell.payloadCmds().size)
        assertEquals("клик сам НЕ трогает Wi-Fi (возврат делает продолжение после restart)",
            0, net.requests.size)
        assertTrue("снимок Wi-Fi снят ДО отправки", c.snap != null)
        assertTrue("прежнее состояние прочитано: ${c.read}", c.read != null)
    }

    @Test
    fun secondClickIsRefusedWhileThePreviousCommandIsUnfinishedAndAddsNoSideEffect() {
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true)
        val shell = RecShell()

        val on = JitOp.click(store, net, shell, want = true, log = ::log)
        assertEquals("ON отправлен", null, on.blocked)
        val off = JitOp.click(store, net, shell, want = false, log = ::log)
        assertEquals("пока terminal прошлой команды не прочитан, второй клик НЕ отправляет",
            "previous_unfinished", off.blocked)
        assertFalse("и честно помечен как неотправленный", off.sent)
        assertEquals("payload исполнялся РОВНО один раз (побочных эффектов второго клика нет)",
            1, JitDev.payloadRuns)
        assertEquals("вторая payload-команда не уходила", 1, shell.payloadCmds().size)
        assertEquals("Wi-Fi кликом не трогали", 0, net.requests.size)
    }

    @Test
    fun offClickCarriesItsOwnValueInTheSameSinglePayload() {
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true)
        val shell = RecShell()

        val off = JitOp.click(store, net, shell, want = false, log = ::log)
        assertEquals("OFF не заблокирован: ${off.blocked}", null, off.blocked)
        assertEquals("одна payload-команда", 1, shell.payloadCmds().size)
        assertTrue("OFF несёт своё значение: ${shell.payloadCmds()[0].takeLast(200)}",
            shell.payloadCmds()[0].contains("setprop $PROP false"))
        assertEquals("payload исполнялся ровно один раз", 1, JitDev.payloadRuns)
    }

    @Test
    fun theSinglePayloadSaneiesRulesBeforeRestartingZygoteLast() {
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true)
        val shell = RecShell()

        JitOp.click(store, net, shell, want = true, log = ::log)
        val s = shell.payloadCmds().single()

        assertTrue("санация правил `ip rule oif wlan0` входит в ту же команду",
            s.contains("ip rule show | grep -c 'oif wlan0'"))
        assertTrue("свойство выставляется ДО перезапуска",
            s.indexOf("setprop $PROP true") < s.indexOf("setprop ctl.restart zygote"))
        assertTrue("перезапуск zygote — ПОСЛЕДНИЙ шаг: ${s.takeLast(120)}",
            s.trimEnd().endsWith("setprop ctl.restart zygote"))
        assertTrue("снятие своих слоёв объявлено шагом скрипта",
            s.contains("grep ' /data/app/' /proc/mounts"))
        for (bad in forbidden) {
            assertFalse("запрещённое «$bad» не уходило: ${s.takeLast(200)}", s.contains(bad))
        }
    }

    // ───────── 2. Last-intent-wins: seq, а не часы ─────────

    private fun order(seenLine: String, mySeq: Long, myTs: Long, myOp: String): String {
        val script = File(
            File.createTempFile("jit61ord", "").let { it.delete(); it.parentFile }, "ord.sh"
        ).apply {
            writeText(
                JitOwn.ownHelpersForTest(absoluteFile.parent + "/root") +
                    "\n_jit_intent_order " + "'" + seenLine + "' " +
                    "$mySeq $myTs $myOp\n" +
                    "echo \"VERDICT=\${_ord_verdict} RULE=\${_ord_rule}\"\n"
            )
        }
        val p = ProcessBuilder("/bin/sh", script.absolutePath).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(30, TimeUnit.SECONDS)
        return out.lines().firstOrNull { it.startsWith("VERDICT=") }?.trim()
            ?: error("нет вердикта: $out")
    }

    @Test
    fun clockMovedBackwardsNeverOverridesAHigherIntentGeneration() {
        // Часы переведены назад: у МОЕГО намерения seq больше, а ts_ms МЕНЬШЕ, чем у чужого.
        val seen = "op=other;ts_ms=9999999999999;ts_s=9999999999;want=1;seq=5"
        val mine = order(seen, mySeq = 7L, myTs = 1000L, myOp = "mine")
        assertEquals("порядок задаёт поколение намерения, а не часы: $mine",
            "VERDICT=newer_intent RULE=seq", mine)
    }

    @Test
    fun lowerIntentGenerationLosesEvenWithAHigherClock() {
        val seen = "op=other;ts_ms=1000;ts_s=1;want=1;seq=9"
        val mine = order(seen, mySeq = 8L, myTs = 9_999_999_999_999L, myOp = "mine")
        assertEquals("меньшее поколение уступает: $mine", "VERDICT=older_intent RULE=seq", mine)
    }

    @Test
    fun emptyOrEqualGenerationFallsBackToTheNamedLegacyClockRule() {
        val legacySeen = "op=other;ts_ms=2000;ts_s=2;want=1"
        val mine = order(legacySeen, mySeq = 0L, myTs = 1000L, myOp = "mine")
        assertEquals("без поколения — правило названо ts_legacy честно: $mine",
            "VERDICT=older_intent RULE=ts_legacy", mine)

        val newerMine = order(legacySeen, mySeq = 0L, myTs = 5000L, myOp = "mine")
        assertEquals("по часам новее — берём верх: $newerMine",
            "VERDICT=newer_intent RULE=ts_legacy", newerMine)
    }

    @Test
    fun intentWithoutGenerationInTheJournalIsNeverProvenNewer() {
        val seen = "op=other;want=1"
        val mine = order(seen, mySeq = 3L, myTs = 10L, myOp = "mine")
        assertEquals("нет ни seq, ни ts — исход не доказуем, верх не берём: $mine",
            "VERDICT=older_intent RULE=unprovable", mine)
    }
}
