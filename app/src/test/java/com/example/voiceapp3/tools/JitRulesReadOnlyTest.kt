package com.example.voiceapp3.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * iter33p (1.0.61) — `ip rule` в нашей цепочке ТОЛЬКО читается.
 *
 * Заменяет `NetSanitizePlanTest` (план удаления правил `oif wlan0`): удаление не доказано ни
 * референсом (`w0/e2.run` правил не удаляет), ни полем, а «безопасно опознать своё правило»
 * на ГУ нечем. Поэтому осталась read-only диагностика, и этот тест держит границу статически.
 */
class JitRulesReadOnlyTest {

    private fun src(rel: String): String =
        listOf(File(rel), File("app/$rel")).first { it.isFile }.readText()

    @Test
    fun probeIsCountOnly() {
        val probe = JitNetModel.rulesProbeCommand()
        assertTrue("правило только считается: $probe", probe.contains("grep -c 'oif wlan0'"))
        assertFalse("нет удаления", probe.contains("ip rule del"))
        assertFalse("нет flush", probe.contains("flush"))
    }

    @Test
    fun noDeletionAnywhereInJitCode() {
        for (f in listOf("JitNetCore.kt", "JitNet.kt", "AppPerformance.kt")) {
            // Комментарии (KDoc, //, #-строки скрипта) не считаются: в них мы ОБЪЯСНЯЕМ отказ
            // от удаления правил. Проверяется исполняемый код.
            val code = src("src/main/java/com/example/voiceapp3/tools/$f").lines()
                .filterNot { l ->
                    val t = l.trim()
                    t.startsWith("*") || t.startsWith("//") || t.startsWith("/*") || t.startsWith("#")
                }
                .joinToString("\n")
            for (bad in listOf("ip rule del", "ip rule flush", "ndc ", "settings put")) {
                assertFalse("$f не должен содержать «$bad» в коде", code.contains(bad))
            }
        }
    }

    @Test
    fun countingIsSeparateFromRestoreResult() {
        // Число правил — диагностика: оно печатается и не влияет на «возврат выполнен».
        val w = World()
        val core = w.core()
        val op = core.saveOperation("bootA", "true", "op-1")!!
        val res = core.restore(op, "unit", 1)
        assertTrue("поле есть", res.oifWlan0 == 0)
        assertTrue("вывод не «удалено правил»", w.log.none { it.contains("deleted") })
    }

    private class World {
        val prefs = object : NetPrefs {
            val m = LinkedHashMap<String, String?>()
            override fun get(key: String): String? = m[key]
            override fun put(key: String, value: String?): Boolean { m[key] = value; return true }
        }
        val log = mutableListOf<String>()
        fun core() = JitNetCore(
            prefs = prefs, readWifi = { true }, setWifi = {}, apProbe = { "P=0\nOS=down\nA=0" },
            link = { "up" }, readZygotePid = { "1" }, readJournal = { "" }, purgeJournals = { 0 },
            runScript = { script, _ -> ScriptOut(0, if (script.contains("OIF=")) "OIF=0" else "") },
            log = { log += it }, now = { 1L }, sleep = {}, wifiAttempts = 1, wifiPauseMs = 0
        )
    }
}
