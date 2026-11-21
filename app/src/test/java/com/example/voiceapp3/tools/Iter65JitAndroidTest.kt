package com.example.voiceapp3.tools

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** iter65 — адаптеры [JitMechAndroid] и прохождение литерала через транспорт. */
class Iter65JitAndroidTest {

    /** Минимальный `SharedPreferences`: память + флаг «commit не проходит». */
    private class MemPrefs(var failCommit: Boolean = false) : SharedPreferences {
        val m = HashMap<String, Any?>()
        override fun getAll(): MutableMap<String, *> = HashMap(m)
        override fun getString(k: String, d: String?): String? = if (m.containsKey(k)) m[k] as String? else d
        override fun getStringSet(k: String, d: MutableSet<String>?): MutableSet<String>? = d
        override fun getInt(k: String, d: Int): Int = (m[k] as? Int) ?: d
        override fun getLong(k: String, d: Long): Long = (m[k] as? Long) ?: d
        override fun getFloat(k: String, d: Float): Float = (m[k] as? Float) ?: d
        override fun getBoolean(k: String, d: Boolean): Boolean = if (m.containsKey(k)) m[k] as Boolean else d
        override fun contains(k: String): Boolean = m.containsKey(k)
        override fun edit(): SharedPreferences.Editor = Ed()
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        inner class Ed : SharedPreferences.Editor {
            private val puts = HashMap<String, Any?>()
            private val removes = HashSet<String>()
            override fun putString(k: String, v: String?) = apply { puts[k] = v }
            override fun putStringSet(k: String, v: MutableSet<String>?) = apply { puts[k] = v }
            override fun putInt(k: String, v: Int) = apply { puts[k] = v }
            override fun putLong(k: String, v: Long) = apply { puts[k] = v }
            override fun putFloat(k: String, v: Float) = apply { puts[k] = v }
            override fun putBoolean(k: String, v: Boolean) = apply { puts[k] = v }
            override fun remove(k: String) = apply { removes += k }
            override fun clear() = apply { }
            override fun commit(): Boolean {
                if (failCommit) return false
                removes.forEach { m.remove(it) }; m.putAll(puts); return true
            }
            override fun apply() { commit() }
        }
    }

    @Test
    fun dualPrefsWritesBothStoresAndReadsCpsFirst() {
        val cps = MemPrefs(); val dps = MemPrefs()
        val p = JitMechAndroid.DualPrefs({ cps }, { dps })
        assertTrue(p.putBool(JitMech.KEY_PEDIDO, true))
        assertTrue(p.putString(JitMech.KEY_RESTAURAR, "wifi=1"))
        assertEquals(true, cps.m[JitMech.KEY_PEDIDO]); assertEquals(true, dps.m[JitMech.KEY_PEDIDO])
        assertEquals("wifi=1", dps.m[JitMech.KEY_RESTAURAR])
        dps.m[JitMech.KEY_RESTAURAR] = "wifi=0"
        assertEquals("CPS первым", "wifi=1", p.getString(JitMech.KEY_RESTAURAR))
        cps.m.remove(JitMech.KEY_RESTAURAR)
        assertEquals("нет в CPS — берём DPS", "wifi=0", p.getString(JitMech.KEY_RESTAURAR))
        assertTrue(p.remove(JitMech.KEY_RESTAURAR))
        assertFalse(p.contains(JitMech.KEY_RESTAURAR))
        assertNull(p.getString(JitMech.KEY_RESTAURAR))
    }

    @Test
    fun dualPrefsReportsPartialWriteAndWorksWithoutDps() {
        val cps = MemPrefs(); val dps = MemPrefs(failCommit = true)
        val p = JitMechAndroid.DualPrefs({ cps }, { dps })
        assertFalse("одна копия не приняла — не «ok»", p.putBool(JitMech.KEY_AUTO, true))
        assertEquals(true, p.getBool(JitMech.KEY_AUTO))
        val solo = JitMechAndroid.DualPrefs({ cps }, { null })
        assertTrue("без DPS достаточно CPS", solo.putBool(JitMech.KEY_AUTO, false))
        assertEquals(false, solo.getBool(JitMech.KEY_AUTO))
        val none = JitMechAndroid.DualPrefs({ null }, { null })
        assertFalse(none.putBool(JitMech.KEY_AUTO, true))
        assertNull(none.getBool(JitMech.KEY_AUTO))
        assertFalse(none.contains(JitMech.KEY_AUTO))
    }

    @Test
    fun dualPrefsForeignTypeIsNull() {
        val cps = MemPrefs().apply { m[JitMech.KEY_PEDIDO] = "true" }
        val p = JitMechAndroid.DualPrefs({ cps }, { null })
        assertNull(p.getBool(JitMech.KEY_PEDIDO))
        assertTrue("ключ есть — сверка его снимет", p.contains(JitMech.KEY_PEDIDO))
    }

    @Test
    fun clickThroughDualPrefsLeavesExactlyThreeKeysInEachStore() {
        JitMech.resetForTest()
        val cps = MemPrefs(); val dps = MemPrefs()
        val wifi = object : JitMech.Wifi {
            override fun isEnabled(): Boolean? = true
            override fun setEnabled(on: Boolean): Boolean? = true
        }
        val sent = ArrayList<String>()
        val shell = object : JitMech.Shell {
            override fun exec(command: String, timeoutMs: Int): Pair<Int, String> { sent += command; return 0 to "" }
        }
        JitMech.click(JitMechAndroid.DualPrefs({ cps }, { dps }), wifi, shell, true) {}
        for (s in listOf(cps, dps)) {
            assertEquals(setOf(JitMech.KEY_PEDIDO, JitMech.KEY_AUTO, JitMech.KEY_RESTAURAR), s.m.keys)
            assertEquals("wifi=1", s.m[JitMech.KEY_RESTAURAR])
        }
        assertEquals(listOf(JitMech.chainScript(true)), sent)
    }

    @Test
    fun commandAuditDoesNotBlockTheLiteral() {
        assertNull(CommandAudit.suspectLine(JitMech.chainScript(true)))
        assertNull(CommandAudit.suspectLine(JitMech.chainScript(false)))
        assertNull(CommandAudit.suspectLine(JitMech.GETPROP_CMD))
    }

    /**
     * Обёртка транспорта (`sh -c '<экранирование>'; echo "__ADB_RC=$?"`) — дословно как в
     * `AdbClient.wrapForRc` — не ломает кавычки литерала: под настоящим `/bin/sh` выполняются
     * те же шаги в том же порядке.
     */
    @Test
    fun literalSurvivesTransportWrapperUnderRealShell() {
        val dir = createTempDir("jitwrap")
        try {
            val log = File(dir, "calls.log")
            fun stub(name: String, body: String) = File(dir, name).apply {
                writeText("#!/bin/sh\n$body\n"); setExecutable(true)
            }
            stub("svc", "echo \"svc \$*\" >> ${log.path}")
            stub("ip", "echo \"ip \$*\" >> ${log.path}")
            stub("sleep", "echo \"sleep \$*\" >> ${log.path}")
            stub("setprop", "echo \"setprop \$*\" >> ${log.path}")
            stub("umount", "echo \"umount \$*\" >> ${log.path}")
            val cmd = JitMech.chainScript(false)
            val wrapped = "sh -c '" + cmd.replace("'", "'\\''") + "'; echo \"__ADB_RC=\$?\""
            val pb = ProcessBuilder("/bin/sh", "-c", wrapped)
            pb.environment()["PATH"] = dir.path + ":" + System.getenv("PATH")
            pb.redirectErrorStream(true)
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText()
            assertTrue(p.waitFor(20, TimeUnit.SECONDS))
            assertTrue(out, out.contains("__ADB_RC=0"))
            val calls = log.readLines()
            assertEquals("svc wifi disable", calls.first())
            assertEquals("ip rule show", calls[1])
            assertEquals(listOf("setprop dalvik.vm.usejit false", "setprop ctl.restart zygote"), calls.takeLast(2))
            assertFalse(calls.any { it.startsWith("sleep") })
        } finally {
            dir.deleteRecursively()
        }
    }
}
