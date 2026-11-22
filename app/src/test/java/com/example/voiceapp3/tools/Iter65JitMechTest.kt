package com.example.voiceapp3.tools

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * iter65 — механизм JIT по референсу 3.3.9 ([JitMech]). Литерал сверяется с ТЕКСТОМ ИЗ DEX
 * (`w0/la.run` case 0, jadx), цепочка исполняется настоящим `/bin/sh` с заглушками команд.
 */
class Iter65JitMechTest {

    // ─────────── фейки ───────────

    private class MemPrefs : JitMech.Prefs {
        val m = LinkedHashMap<String, Any>()
        var failWrites = false
        override fun putBool(key: String, value: Boolean): Boolean { if (failWrites) return false; m[key] = value; return true }
        override fun getBool(key: String): Boolean? = m[key] as? Boolean
        override fun putString(key: String, value: String): Boolean { if (failWrites) return false; m[key] = value; return true }
        override fun getString(key: String): String? = m[key] as? String
        override fun remove(key: String): Boolean { m.remove(key); return true }
        override fun contains(key: String): Boolean = m.containsKey(key)
    }

    private class FakeWifi(var on: Boolean?) : JitMech.Wifi {
        val sets = mutableListOf<Boolean>()
        override fun isEnabled(): Boolean? = on
        override fun setEnabled(on: Boolean): Boolean? { sets += on; this.on = on; return true }
    }

    private class FakeShell(val reply: (String) -> Pair<Int, String>) : JitMech.Shell {
        val calls = mutableListOf<Pair<String, Int>>()
        override fun exec(command: String, timeoutMs: Int): Pair<Int, String> {
            synchronized(calls) { calls += command to timeoutMs }
            return reply(command)
        }
    }

    private class FakeScheduler : JitMech.Scheduler {
        val tasks = mutableListOf<Pair<Long, () -> Unit>>()
        override fun postDelayed(delayMs: Long, task: () -> Unit) { tasks += delayMs to task }
    }

    private val lines = java.util.Collections.synchronizedList(mutableListOf<String>())
    private fun log(s: String) { lines += s }

    @Before fun setUp() { JitMech.resetForTest(); lines.clear() }
    @After fun tearDown() { JitMech.resetForTest() }

    // ─────────── 1. литерал ───────────

    /** Строки ровно из jadx `w0/la.run` case 0; `o2.m.V` = Kotlin `trimIndent`. */
    private fun dexLiteral(z4: Boolean): String = dexSource(z4).trimIndent()

    /** Текст StringBuilder ДО `o2.m.V`: именно у него 392 символа (число из промпта). */
    private fun dexSource(z4: Boolean): String {
        val sb = StringBuilder("\n        svc wifi disable\n        _n=0\n        while [ \$_n -lt 20 ]; do\n          [ \"\$(ip rule show | grep -c 'oif wlan0')\" = \"0\" ] && break\n          sleep 1; _n=\$((_n + 1))\n        done\n        for _m in \$(grep ' /data/app/' /proc/mounts | awk '{print \$2}'); do\n          umount -l \"\$_m\" 2>/dev/null\n        done\n        setprop dalvik.vm.usejit ")
        sb.append(if (z4) "true" else "false")
        sb.append("\n        setprop ctl.restart zygote\n    ")
        return sb.toString()
    }

    @Test
    fun chainIsTheDexLiteralByteForByte() {
        assertEquals(dexLiteral(true), JitMech.chainScript(true))
        assertEquals(dexLiteral(false), JitMech.chainScript(false))
        // 392 — длина исходного литерала в DEX (с отступами); после `trimIndent` уходит 298 / 299.
        assertEquals(392, dexSource(true).length)
        assertEquals(298, JitMech.chainScript(true).length)
        assertEquals(299, JitMech.chainScript(false).length)
        assertEquals("меняется только значение usejit",
            JitMech.chainScript(true).replace("usejit true", "usejit false"), JitMech.chainScript(false))
        assertTrue("влезает в один ADB-пакет",
            AdbClient.inlinePayloadBytes(JitMech.chainScript(false)) <= 3_500)
    }

    @Test
    fun chainOrder() {
        val l = JitMech.chainScript(true).lines()
        assertEquals("svc wifi disable", l.first())
        assertEquals("setprop ctl.restart zygote", l.last())
        val s = JitMech.chainScript(true)
        val drain = s.indexOf("ip rule show")
        val umount = s.indexOf("umount -l")
        val usejit = s.indexOf("setprop dalvik.vm.usejit")
        val restart = s.indexOf("setprop ctl.restart zygote")
        assertTrue(s.indexOf("svc wifi disable") < drain && drain < umount && umount < usejit && usejit < restart)
        assertEquals(1, Regex("ctl\\.restart").findAll(s).count())
        assertTrue(s.contains("while [ \$_n -lt 20 ]"))
        assertTrue(s.contains("grep ' /data/app/' /proc/mounts"))
    }

    @Test
    fun chainHasNoForbiddenCommands() {
        for (want in listOf(true, false)) {
            val s = JitMech.chainScript(want)
            for (bad in listOf("persist.", "usejitprofiles", "rule del", "flush", "ndc", "settings",
                    "setenforce", "kill", "pkill", "route add", "iptables", "startSoftAp", "ap0")) {
                assertFalse("в цепочке «$bad»", s.contains(bad))
            }
            val cmds = s.lines().map { it.trim().split(' ').first() }.toSet()
            assertEquals(setOf("svc", "_n=0", "while", "[", "sleep", "done", "for", "umount", "setprop"), cmds)
        }
    }

    // ─────────── 1b. цепочка под настоящим /bin/sh ───────────

    private fun runChain(rules: Int, mounts: List<String>, want: Boolean = true): List<String> {
        val dir = File.createTempFile("jit65", "").let { it.delete(); it.mkdirs(); it }
        try {
            val calls = File(dir, "calls.log")
            val realGrep = listOf("/bin/grep", "/usr/bin/grep").first { File(it).exists() }
            val fakeMounts = File(dir, "mounts").apply { writeText(mounts.joinToString("\n", postfix = "\n")) }
            fun stub(name: String, body: String) = File(dir, name).apply {
                writeText("#!/bin/sh\necho \"$name \$*\" >> '${calls.path}'\n$body\n"); setExecutable(true)
            }
            stub("svc", "")
            stub("sleep", "")
            stub("setprop", "")
            stub("umount", "")
            stub("ip", "i=0; while [ \$i -lt $rules ]; do echo \"\$i: from all oif wlan0 lookup wlan0\"; i=\$((i+1)); done")
            File(dir, "grep").apply {
                writeText("#!/bin/sh\ncase \"\$*\" in *'/proc/mounts'*) exec $realGrep ' /data/app/' '${fakeMounts.path}';; esac\n" +
                    "exec $realGrep \"\$@\"\n")
                setExecutable(true)
            }
            val script = File(dir, "chain.sh").apply { writeText(JitMech.chainScript(want)) }
            val pb = ProcessBuilder("/bin/sh", script.path).redirectErrorStream(true)
            pb.environment()["PATH"] = dir.path + ":" + (System.getenv("PATH") ?: "/usr/bin:/bin")
            val p = pb.start()
            p.inputStream.bufferedReader().readText()
            assertTrue(p.waitFor(30, TimeUnit.SECONDS))
            return if (calls.exists()) calls.readLines() else emptyList()
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun drainStopsAtOnceWhenNoRules() {
        val c = runChain(rules = 0, mounts = emptyList())
        assertEquals("svc wifi disable", c.first())
        assertEquals(1, c.count { it.startsWith("ip rule show") })
        assertEquals(0, c.count { it.startsWith("sleep") })
        assertEquals("setprop ctl.restart zygote", c.last())
        assertEquals(listOf("setprop dalvik.vm.usejit true", "setprop ctl.restart zygote"),
            c.filter { it.startsWith("setprop") })
    }

    @Test
    fun drainGivesUpAfterTwentyIterations() {
        val c = runChain(rules = 99, mounts = emptyList(), want = false)
        assertEquals(20, c.count { it.startsWith("ip rule show") })
        assertEquals(20, c.count { it == "sleep 1" })
        assertEquals(1, c.count { it == "setprop ctl.restart zygote" })
        assertEquals("setprop dalvik.vm.usejit false", c[c.size - 2])
        assertEquals("setprop ctl.restart zygote", c.last())
    }

    @Test
    fun umountOnlyDataAppMountsAndBeforeSetprop() {
        val c = runChain(rules = 0, mounts = listOf(
            "/dev/block/dm-2 /data/app/com.a-1==/base.apk ext4 ro 0 0",
            "/dev/block/dm-2 /data/data/com.b ext4 rw 0 0",
            "tmpfs /data/app/com.c-2==/lib tmpfs rw 0 0",
            "/dev/block/dm-0 /system ext4 ro 0 0"))
        val um = c.filter { it.startsWith("umount") }
        assertEquals(listOf("umount -l /data/app/com.a-1==/base.apk", "umount -l /data/app/com.c-2==/lib"), um)
        assertTrue(c.indexOfLast { it.startsWith("umount") } < c.indexOf("setprop dalvik.vm.usejit true"))
    }

    // ─────────── 3. maps / формула ───────────

    @Test
    fun mapsNeedsExecutableRights() {
        val x = "7f00-7f10 r-xp 00000000 00:05 123 /data/dalvik-cache/arm64/jit-cache"
        val rw = "7f00-7f10 rw-p 00000000 00:05 123 /dev/ashmem/dalvik-jit-code-cache (deleted)"
        assertEquals(true, JitMech.cacheExecutable("$rw\n$x\n"))
        assertEquals(true, JitMech.cacheExecutable("7f00-7f10 rwxp 0 0:0 1 [anon:dalvik-jit-code-cache]"))
        assertEquals(true, JitMech.cacheExecutable("7f00-7f10 r-xs 0 0:0 1 /dev/ashmem/dalvik-jit-code-cache"))
        assertEquals(false, JitMech.cacheExecutable(rw))
        assertNull("нет строк кэша — неизвестно, не false",
            JitMech.cacheExecutable("7f00-7f10 r-xp 0 0:0 1 /system/lib64/libc.so"))
        assertNull(JitMech.cacheExecutable(null))
    }

    @Test
    fun verdictTable() {
        // (pedido, getprop, maps) → ok, как `w0/r7`.
        val t = listOf(
            Triple(true, true, true) to true, Triple(true, true, null) to true,
            Triple(true, true, false) to false, Triple(true, false, true) to false,
            Triple(true, false, null) to false, Triple(true, false, false) to false,
            Triple(false, false, true) to true, Triple(false, false, null) to true,
            Triple(false, false, false) to true, Triple(false, true, true) to false,
            Triple(false, true, null) to false, Triple(false, true, false) to false)
        for ((k, ok) in t) assertEquals("$k", ok, JitMech.verdictOk(k.first, k.second, k.third))
    }

    @Test
    fun restaurarFormat() {
        assertEquals("wifi=1", JitMech.restaurarValue(true))
        assertEquals("wifi=0", JitMech.restaurarValue(false))
        assertEquals(JitMech.Restaurar(true), JitMech.parseRestaurar("wifi=1"))
        assertEquals(JitMech.Restaurar(false), JitMech.parseRestaurar("wifi=0"))
        assertEquals("ap игнорируется", JitMech.Restaurar(true), JitMech.parseRestaurar("wifi=1,ap=1"))
        for (bad in listOf(null, "", "wifi=2", " wifi=1", "wifi=1 ", "wifi=1,ap=2", "undefined", "ap=1"))
            assertNull("«$bad»", JitMech.parseRestaurar(bad))
    }

    // ─────────── 1. клик ───────────

    @Test
    fun clickWritesThreeKeysLogsAndSendsOneCommand() {
        val prefs = MemPrefs(); val wifi = FakeWifi(true)
        val shell = FakeShell { 0 to "" }
        val r = JitMech.click(prefs, wifi, shell, want = true, log = ::log)
        assertEquals(mapOf<String, Any>("jit_pedido" to true, "perf_auto_otimizar" to true,
            "jit_restaurar" to "wifi=1"), prefs.m)
        assertTrue(lines.contains("dalvik.vm.usejit -> true; reiniciando o framework"))
        assertEquals(listOf(JitMech.chainScript(true) to 40_000), shell.calls)
        assertTrue(r.sent && r.prefsOk)
        assertFalse("флаг снят после возврата", JitMech.busy())
    }

    @Test
    fun clickOffWithWifiUnreadableSavesWifi0() {
        val prefs = MemPrefs()
        JitMech.click(prefs, FakeWifi(null), FakeShell { 0 to "" }, want = false, log = ::log)
        assertEquals("wifi=0", prefs.m["jit_restaurar"])
        assertEquals(false, prefs.m["jit_pedido"])
        assertTrue(lines.contains("dalvik.vm.usejit -> false; reiniciando o framework"))
    }

    /** 1.0.66: транспорт, который умеет доказать доставку (adbd ответил OKAY на OPEN). */
    private class SentShell(val opened: Boolean, val rc: Int = 0) : JitMech.Shell {
        val calls = mutableListOf<String>()
        override fun exec(command: String, timeoutMs: Int): Pair<Int, String> { calls += command; return rc to "" }
        override fun execNotify(command: String, timeoutMs: Int, onSent: () -> Unit): Pair<Int, String> {
            calls += command
            if (opened) onSent()
            return rc to (if (opened) "" else "ERR_ADB_IO")
        }
    }

    @Test
    fun chainSentIsLoggedWhenTransportConfirmsDeliveryBeforeAnyReply() {
        val shell = SentShell(opened = true)
        val r = JitMech.click(MemPrefs(), FakeWifi(true), shell, want = true, log = ::log)
        assertEquals("одна команда — литерал", listOf(JitMech.chainScript(true)), shell.calls)
        val sent = lines.filter { it.startsWith("JIT_CHAIN_SENT ") }
        assertEquals(1, sent.size)
        assertTrue(sent[0], sent[0].startsWith("JIT_CHAIN_SENT want=true "))
        val iFramework = lines.indexOf("dalvik.vm.usejit -> true; reiniciando o framework")
        val iSent = lines.indexOf(sent[0])
        val iRet = lines.indexOfFirst { it.startsWith("JIT_CHAIN_RETURNED ") }
        assertTrue("порядок: reiniciando → SENT → RETURNED", iFramework in 0 until iSent && iSent < iRet)
        assertTrue(r.delivered)
        assertTrue(lines[iRet], lines[iRet].contains("delivered=true"))
    }

    @Test
    fun noChainSentWhenTransportNeverOpenedTheShell() {
        val r = JitMech.click(MemPrefs(), FakeWifi(true), SentShell(opened = false, rc = 1), want = false, log = ::log)
        assertTrue(lines.none { it.startsWith("JIT_CHAIN_SENT") })
        assertFalse(r.delivered)
        assertTrue(lines.single { it.startsWith("JIT_CHAIN_RETURNED ") }.contains("delivered=false"))
    }

    @Test
    fun secondClickWhileFirstInFlightIsRefused() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val shell = FakeShell { entered.countDown(); release.await(5, TimeUnit.SECONDS); 0 to "" }
        val t = Thread { JitMech.click(MemPrefs(), FakeWifi(true), shell, true, ::log) }.apply { start() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val second = JitMech.click(MemPrefs(), FakeWifi(true), shell, false, ::log)
        release.countDown(); t.join(5_000)
        assertEquals("busy", second.refused)
        assertEquals(1, shell.calls.size)
    }

    // ─────────── 2. watchdog ───────────

    @Test
    fun watchdogRestoresWifiAfter15sAndRemovesKey() {
        val prefs = MemPrefs().apply { m["jit_restaurar"] = "wifi=1" }
        val wifi = FakeWifi(false); val sch = FakeScheduler()
        val r = JitMech.watchdogStart(prefs, wifi, sch, alive = { true }, log = ::log)
        assertEquals(JitMech.Restaurar(true), r)
        assertFalse("one-shot", prefs.contains("jit_restaurar"))
        assertEquals(listOf(15_000L), sch.tasks.map { it.first })
        assertTrue("до 15 с сеть не трогаем", wifi.sets.isEmpty())
        sch.tasks.single().second()
        assertEquals(listOf(true), wifi.sets)
        assertTrue(lines.any { it == "JIT: restaurado após o restart (wifi=true); wifi agora=true" })
    }

    @Test
    fun watchdogDoesNotTouchWifiWhenItWasOffOrAlreadyOn() {
        for ((snap, now) in listOf("wifi=0" to false, "wifi=1" to true)) {
            val prefs = MemPrefs().apply { m["jit_restaurar"] = snap }
            val wifi = FakeWifi(now); val sch = FakeScheduler()
            JitMech.watchdogStart(prefs, wifi, sch, { true }, ::log)
            sch.tasks.single().second()
            assertTrue("$snap/$now", wifi.sets.isEmpty())
        }
    }

    @Test
    fun watchdogIgnoresBadFormatAndDeadService() {
        val bad = MemPrefs().apply { m["jit_restaurar"] = "undefined" }
        val sch = FakeScheduler()
        assertNull(JitMech.watchdogStart(bad, FakeWifi(false), sch, { true }, ::log))
        assertTrue("ключ не трогается, как в DEX", bad.contains("jit_restaurar"))
        assertTrue(sch.tasks.isEmpty())
        assertNull(JitMech.watchdogStart(MemPrefs(), FakeWifi(false), sch, { true }, ::log))
        val prefs = MemPrefs().apply { m["jit_restaurar"] = "wifi=1" }
        val wifi = FakeWifi(false); val sch2 = FakeScheduler()
        JitMech.watchdogStart(prefs, wifi, sch2, alive = { false }, log = ::log)
        sch2.tasks.single().second()
        assertTrue(wifi.sets.isEmpty())
    }

    // ─────────── 3. сверка ───────────

    private val execMaps = "1-2 r-xp 0 0:0 1 /dev/ashmem/dalvik-jit-code-cache"
    private val rwMaps = "1-2 rw-p 0 0:0 1 /dev/ashmem/dalvik-jit-code-cache"

    @Test
    fun verifyOkRemovesPedido() {
        val prefs = MemPrefs().apply { m["jit_pedido"] = true }
        val v = JitMech.verify(prefs, FakeShell { 0 to "true\n" }, { execMaps }, ::log)
        assertEquals(true, v.ok); assertFalse(v.notApplied)
        assertFalse(prefs.contains("jit_pedido"))
        assertTrue(lines.contains("JIT pedido=true getprop=true code-cache-executavel=true"))
    }

    @Test
    fun verifyCacheWithoutExecIsNotApplied() {
        val prefs = MemPrefs().apply { m["jit_pedido"] = true }
        val v = JitMech.verify(prefs, FakeShell { 0 to "true" }, { rwMaps }, ::log)
        assertEquals(false, v.ok); assertTrue(v.notApplied); assertFalse(prefs.contains("jit_pedido"))
    }

    @Test
    fun verifyPropNotSetIsNotApplied_offRequestHasNoToast() {
        val on = MemPrefs().apply { m["jit_pedido"] = true }
        assertTrue(JitMech.verify(on, FakeShell { 0 to "false" }, { null }, ::log).notApplied)
        val off = MemPrefs().apply { m["jit_pedido"] = false }
        val v = JitMech.verify(off, FakeShell { 0 to "true" }, { execMaps }, ::log)
        assertEquals(false, v.ok); assertFalse("тост только для pedido=true", v.notApplied)
    }

    @Test
    fun verifyKeepsPedidoWhenGetpropUnreadOrClickInFlight() {
        val prefs = MemPrefs().apply { m["jit_pedido"] = true }
        val v = JitMech.verify(prefs, FakeShell { 1 to "ERR_ADB_TIMEOUT" }, { execMaps }, ::log)
        assertEquals("getprop_unread", v.skipped); assertTrue(prefs.contains("jit_pedido"))

        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val slow = FakeShell { entered.countDown(); release.await(5, TimeUnit.SECONDS); 0 to "" }
        val t = Thread { JitMech.click(MemPrefs(), FakeWifi(true), slow, true, ::log) }.apply { start() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val v2 = JitMech.verify(prefs, FakeShell { 0 to "false" }, { null }, ::log)
        release.countDown(); t.join(5_000)
        assertEquals("click_in_flight", v2.skipped); assertTrue(prefs.contains("jit_pedido"))
    }

    @Test
    fun verifyWithoutPedidoOnlyReportsFact() {
        val prefs = MemPrefs()
        val v = JitMech.verify(prefs, FakeShell { 0 to "true" }, { execMaps }, ::log)
        assertEquals(true, v.getprop); assertEquals("no_pedido", v.skipped); assertTrue(lines.isEmpty())
    }
}
