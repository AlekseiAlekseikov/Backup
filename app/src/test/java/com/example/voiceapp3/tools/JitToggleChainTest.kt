package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * iter33p §2/§6 — цепочка тумблера JIT ровно по референсу (`w0/la.run` → `w0/e2.run`).
 *
 * Порядок строгий: сохранить состояние сети → `svc wifi disable` → дождаться, пока уйдут правила
 * `ip rule … oif wlan0` (≤20 с) → `setprop dalvik.vm.usejit` → рестарт фреймворка (watcher).
 * Если рестарт уйдёт раньше слива правил, после подъёма останутся «висящие» `oif wlan0`
 * и получится «Wi-Fi подключён, страницы не грузятся» (баг 1.0.60).
 */
class JitToggleChainTest {

    private fun src(rel: String): String =
        listOf(File(rel), File("app/$rel")).first { it.isFile }.readText()

    private fun bodyOf(source: String, fn: String): String {
        val i = source.indexOf(fn)
        if (i < 0) return ""
        val j = source.indexOf('{', i)
        if (j < 0) return ""
        var depth = 0
        for (k in j until source.length) {
            when (source[k]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(i, k + 1)
                }
            }
        }
        return source.substring(i)
    }

    // 1. Скрипт подготовки: порядок «wifi disable → drain → setprop».
    @Test
    fun preScriptOrderIsStrict() {
        val script = JitNet.preRestartScript("true")
        val disable = script.indexOf("svc wifi disable")
        val drain = script.indexOf("oif wlan0")
        val setprop = script.indexOf("setprop dalvik.vm.usejit")
        val profiles = script.indexOf("setprop dalvik.vm.usejitprofiles")
        assertTrue("в скрипте нет svc wifi disable", disable >= 0)
        assertTrue("в скрипте нет проверки правил oif wlan0", drain >= 0)
        assertTrue("в скрипте нет setprop usejit", setprop >= 0)
        assertTrue("Wi-Fi гасим ДО слива правил", disable < drain)
        assertTrue("props ставим ТОЛЬКО после слива правил", drain < setprop)
        assertTrue("usejitprofiles — рядом с usejit", setprop < profiles)
    }

    // 2. Ожидание слива ограничено 20 с и логируется честно.
    @Test
    fun drainIsBoundedAndLogged() {
        assertEquals("окно ожидания как в la.run", 20, JitNet.DRAIN_TIMEOUT_S)
        val script = JitNet.preRestartScript("true")
        assertTrue("цикл ограничен DRAIN_TIMEOUT_S", script.contains("while [ \$i -lt 20 ]"))
        assertTrue("итог слива пишется в лог", script.contains("ip_rule_wait="))
        assertTrue("нужен признак drained=yes", script.contains("drained="))
    }

    // 3. Рестарт — только ПОСЛЕ подготовки: в jitApplyWithZygote сеть идёт раньше watcher-а.
    @Test
    fun restartHappensAfterNetworkPrep() {
        val perf = src("src/main/java/com/example/voiceapp3/tools/AppPerformance.kt")
        val apply = bodyOf(perf, "fun jitApplyWithZygote")
        assertTrue("цепочка обязана готовить сеть", apply.contains("JitNet.runPreRestart"))
        assertTrue("сеть ДО записи watcher-а",
            apply.indexOf("JitNet.runPreRestart") < apply.indexOf("jitZygoteWatcherScript()"))
        assertTrue("watcher запускается последним",
            apply.indexOf("jitZygoteWatcherScript()") < apply.indexOf("val launch"))
        // Сам рестарт живёт только в скрипте watcher-а.
        val watcher = bodyOf(perf, "internal fun jitZygoteWatcherScript")
        assertTrue("рестарт обязан быть в watcher-е", watcher.contains("ctl.restart zygote"))
    }

    // 4. Состояние сети сохраняется ДО рестарта (иначе возвращать нечего).
    @Test
    fun netStateSavedBeforeRestart() {
        val perf = src("src/main/java/com/example/voiceapp3/tools/AppPerformance.kt")
        val apply = bodyOf(perf, "fun jitApplyWithZygote")
        val save = apply.indexOf("JitNet.saveBeforeRestart")
        assertTrue("нужно сохранение сети", save >= 0)
        assertTrue("сохранение ДО подготовки сети", save < apply.indexOf("JitNet.runPreRestart"))
        assertTrue("сохранение ДО watcher-а", save < apply.indexOf("jitZygoteWatcherScript()"))
        // Формат хранения — как `jit_restaurar` референса (+ метка времени для TTL).
        assertEquals("wifi=1,ap=0,ts=0", JitNet.State(true, false, 0L).wire())
        assertEquals(JitNet.State(true, false, 0L), JitNet.parse("wifi=1,ap=0"))
        assertEquals(JitNet.State(false, true, 0L), JitNet.parse("wifi=0,ap=1"))
    }

    // 5. Сетевые команды не выходят за цепочку тумблера: единственный потребитель — AppPerformance.
    @Test
    fun netCallsStayInsideToggleChain() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val users = root.walkTopDown()
            .filter {
                it.isFile && it.extension == "kt" &&
                    (it.readText().contains("JitNet.") || it.readText().contains("svc wifi disable"))
            }
            .map { it.name }.sorted().toList()
        assertEquals("сеть живёт только в JitNet.kt и цепочке тумблера", listOf("AppPerformance.kt", "JitNet.kt"), users)
    }
}
