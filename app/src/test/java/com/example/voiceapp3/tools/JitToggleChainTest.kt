package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * iter33p (1.0.61) — цепочка тумблера JIT по референсу (`w0/j9.onClick` → `w0/la.run` → `w0/e2.run`).
 *
 * Поведение ядра — в [JitNetCoreTest], исполнение скрипта настоящим `sh` — в [JitChainScriptTest].
 * Здесь статические границы: порядок внутри скрипта, отсутствие лишних механизмов и то, что
 * сетевые команды живут только в трёх файлах цепочки.
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

    private fun chain(): String =
        JitNetModel.jitChainScript("op-x", "true", JitNetModel.journalPath("op-x"))

    // 1. Порядок: disable → слив ≤20 с → setprop usejit → рестарт.
    @Test
    fun chainOrderIsStrict() {
        val s = chain()
        val disable = s.indexOf("svc wifi disable")
        val drain = s.indexOf("grep -c 'oif wlan0'")
        val setprop = s.indexOf("setprop dalvik.vm.usejit ")
        val restart = s.indexOf("setprop ctl.restart zygote")
        assertTrue("нет svc wifi disable", disable >= 0)
        assertTrue("нет слива правил", drain > disable)
        assertTrue("props только после слива", setprop > drain)
        assertTrue("рестарт после props", restart > setprop)
        assertTrue("рестарт ровно один", s.split("setprop ctl.restart zygote").size == 2)
        assertTrue("рестарт — последняя команда",
            s.substring(restart).lines().last { it.isNotBlank() }.trim() == "setprop ctl.restart zygote")
    }

    // 2. Чего в цепочке нет и почему (референс + границы владельца).
    @Test
    fun chainHasNoForeignMechanisms() {
        val s = chain()
        assertEquals("окно слива как в la.run", 20, JitNet.DRAIN_TIMEOUT_S)
        assertTrue("цикл ограничен константой", s.contains("lt 20"))
        for (bad in listOf(
            "umount",              // не копируем umount -l точек /data/app
            "usejitprofiles",      // в цепочке la.run его нет
            "nohup",               // отдельный watcher не нужен
            "cancel",              // файла-отмены нет
            "pkill", "kill ",      // убийство процессов запрещено
            "setenforce",          // SELinux не трогаем
            "pm install",          // установка из автоматики запрещена
            "ip rule del",         // правила не удаляем
            "sleep 8"              // прежняя 8-секундная задержка убрана
        )) {
            assertFalse("в цепочке не должно быть «$bad»", s.contains(bad))
        }
    }

    // 3. Снимок операции пишется ДО единственного скрипта, а не после.
    @Test
    fun snapshotPrecedesChain() {
        val perf = src("src/main/java/com/example/voiceapp3/tools/AppPerformance.kt")
        val apply = bodyOf(perf, "fun jitApplyWithZygote")
        val save = apply.indexOf("JitNet.saveOperation")
        val run = apply.indexOf("JitNet.runChain")
        assertTrue("снимок операции обязателен", save > 0)
        assertTrue("скрипт идёт после снимка", run > save)
        assertTrue("AVAS снимается тоже до рестарта",
            apply.indexOf("AvasRestore.saveBeforeRestart") < run)
        assertTrue("нет ожидания watcher-а", !apply.contains("waitWatcherArmed"))
        assertTrue("нет файла-отмены", !apply.contains("cancelWatcherAndVerify"))
        assertTrue("сеть возвращается на следующем старте",
            apply.contains("net_return=post_zygote"))
    }

    // 4. Сетевые команды не выходят за цепочку: единственный потребитель — AppPerformance.
    @Test
    fun netCallsStayInsideToggleChain() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val users = root.walkTopDown()
            .filter {
                it.isFile && it.extension == "kt" &&
                    (it.readText().contains("JitNet.") || it.readText().contains("svc wifi disable"))
            }
            .map { it.name }.sorted().toList()
        assertEquals(
            "сеть живёт только в JitNet.kt, его чистом ядре и цепочке тумблера",
            listOf("AppPerformance.kt", "JitNet.kt", "JitNetCore.kt"), users
        )
    }

    // 5. Клик тумблера — единственная точка рестарта фреймворка.
    @Test
    fun onlyToggleClickRestartsFramework() {
        val perf = src("src/main/java/com/example/voiceapp3/tools/AppPerformance.kt")
        assertEquals("вызовов jitApplyWithZygote ровно 1",
            2, Regex("jitApplyWithZygote\\(").findAll(perf).count())
        assertTrue("вызов из тумблера с параметрами",
            perf.contains("jitApplyWithZygote(context, enabled, rtBefore, psBefore)"))
    }
}
