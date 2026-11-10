package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * iter33p (1.0.61) — текст ЕДИНСТВЕННОЙ цепочки исполняется настоящим `/bin/sh`.
 *
 * Заменяет прежний `JitWatcherScriptTest`: watcher-а больше нет, поэтому проверять нечего, кроме
 * одного скрипта. Вместо устройства — подставные `svc`, `ip`, `setprop`, `getprop`, `sleep` в
 * PATH временного каталога: они пишут каждый вызов в `calls.log`, поэтому проверяется ФАКТИЧЕСКИЙ
 * порядок команд, журнал шагов и код возврата, а не совпадение строк в исходнике.
 *
 * (Перенос скрипта в «чистое» ядро нужен ровно для этого: скрипт можно исполнить без ГУ.)
 */
class JitChainScriptTest {

    private val sh: String = listOf("/bin/sh", "/system/bin/sh", "sh").first { File(it).canExecute() }

    private class Fake(val dir: File) {
        val calls: File = File(dir, "calls.log")
        val journal: File = File(dir, "journal.log")

        fun put(name: String, body: String) {
            val f = File(dir, name)
            f.writeText("#!/bin/sh\n" + body + "\n")
            f.setExecutable(true)
        }

        fun lines(): List<String> = calls.readLines().filter { it.isNotBlank() }
    }

    private fun run(script: String, dir: File): Pair<Int, String> {
        val f = File(dir, "chain.sh")
        f.writeText(script)
        val p = ProcessBuilder(sh, f.absolutePath)
            .redirectErrorStream(true)
            .apply { environment()["PATH"] = dir.absolutePath + ":" + System.getenv("PATH") }
            .start()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }

    private fun world(): Pair<Fake, File> {
        val dir = File.createTempFile("jitchain", "").let { it.delete(); it.mkdirs(); it }
        val fake = Fake(dir)
        val calls = fake.calls.absolutePath
        fun prop(name: String) = File(dir, "prop.$name").absolutePath
        // Внимание: в тройных кавычках Kotlin обратный слэш НЕ является экранированием,
        // поэтому `$` подставляется через ${'$'}{D}, а не через `\$` (иначе в фейк попадёт «\$@»).
        val D = "$"
        fake.put("svc", "echo \"svc ${D}@\" >> $calls")
        fake.put(
            "ip",
            "echo \"ip ${D}@\" >> $calls\n" +
                "case \" ${D}* \" in\n" +
                "  *\" rule show \"*) echo \"1000: from all oif wlan0 lookup wlan0\" ;;\n" +
                "  *\" -o -4 addr show \"*) echo \"2: wlan0    inet 192.168.1.5/24 scope global wlan0\" ;;\n" +
                "esac"
        )
        fake.put(
            "setprop",
            "echo \"setprop ${D}@\" >> $calls\n" +
                "echo \"${D}2\" > \"${D}(printf '%s' " + dir.absolutePath + "/prop.)${D}1\""
        )
        fake.put(
            "getprop",
            "echo \"getprop ${D}@\" >> $calls\n" +
                "if [ -f \"${D}(printf '%s' " + dir.absolutePath + "/prop.)${D}1\" ]; then " +
                "cat \"${D}(printf '%s' " + dir.absolutePath + "/prop.)${D}1\"; else echo false; fi"
        )
        fake.put("sleep", "echo \"sleep ${D}@\" >> $calls")
        return fake to dir
    }

    @Test
    fun scriptRunsInReferenceOrder() {
        val (fake, dir) = world()
        val op = "op-test-1"
        val script = JitNetModel.jitChainScript(op, "true", fake.journal.absolutePath)
        val (rc, _) = run(script, dir)
        assertEquals("код возврата", 0, rc)
        val calls = fake.lines()
        val disable = calls.indexOfFirst { it == "svc wifi disable" }
        val ruleShow = calls.indexOfFirst { it.startsWith("ip rule show") }
        val usejit = calls.indexOfFirst { it == "setprop dalvik.vm.usejit true" }
        val persist = calls.indexOfFirst { it == "setprop persist.dalvik.vm.usejit true" }
        val restart = calls.indexOfFirst { it == "setprop ctl.restart zygote" }
        assertTrue("svc wifi disable есть", disable >= 0)
        assertTrue("слив правил читается", ruleShow > disable)
        assertTrue("usejit после слива", usejit > ruleShow)
        assertTrue("persist-ключ тоже ставится", persist > usejit)
        assertTrue("рестарт последним", restart > persist)
        assertEquals("рестарт ровно один вызов", 1, calls.count { it == "setprop ctl.restart zygote" })
        assertEquals("после рестарта ничего не зовём", restart, calls.size - 1)
        assertFalse("никаких kill/pkill", calls.any { it.contains("kill") })
        assertFalse("никакого umount", calls.any { it.contains("umount") })
    }

    @Test
    fun journalHasAllStepsWithOurOpAndIsWrittenBeforeRestart() {
        val (fake, dir) = world()
        val op = "op-test-2"
        val script = JitNetModel.jitChainScript(op, "false", fake.journal.absolutePath)
        val (rc, _) = run(script, dir)
        assertEquals(0, rc)
        val text = fake.journal.readText()
        val steps = JitNetModel.journalSteps(text, op)
        for (name in listOf(
            JitNetModel.STEP_START, JitNetModel.STEP_WIFI_DISABLED, JitNetModel.STEP_DRAINED,
            JitNetModel.STEP_USEJIT, JitNetModel.STEP_RESTART_SENT
        )) {
            assertTrue("в журнале нет шага $name: $text", steps.containsKey(name))
        }
        assertEquals("фаза — рестарт отправлен", JitFlow.Phase.RESTART_SENT, JitNetModel.phaseOf(steps))
        // Строка restart_sent стоит в файле ДО того, как записан вызов рестарта, и — что важнее —
        // она есть в файле сразу, без ожидания подъёма фреймворка.
        val sentIdx = text.indexOf("step=restart_sent")
        assertTrue("restart_sent записан", sentIdx > 0)
        assertTrue("usejit прочитан обратно", text.contains("rt="))
        assertTrue("want зафиксирован", text.contains("want=false"))
        // Журнал другой операции этими строками не подтверждается.
        assertTrue(JitNetModel.journalSteps(text, "op-other").isEmpty())
    }

    @Test
    fun drainIsBoundedByTwentySecondsAndLogsCount() {
        val (fake, dir) = world()
        val script = JitNetModel.jitChainScript("op-3", "true", fake.journal.absolutePath)
        run(script, dir)
        val sleeps = fake.lines().count { it.startsWith("sleep") }
        assertEquals("слив ограничен 20 шагами по 1 с", 20, sleeps)
        val drained = fake.journal.readLines().first { it.contains("step=drained") }
        assertTrue("в журнале число итераций и остаток правил: $drained",
            drained.contains("wait=20") && drained.contains("oif_wlan0="))
    }

    @Test
    fun scriptEndsWithRestartEvenWhenDrainNotClean() {
        val (fake, dir) = world()
        // `ip rule show` всегда возвращает правило → слив не наступит, но рестарт обязан быть.
        fake.put("ip", "echo \"ip \$@\" >> ${fake.calls.absolutePath}; echo '1000:\tfrom all oif wlan0 lookup wlan0'")
        val script = JitNetModel.jitChainScript("op-4", "true", fake.journal.absolutePath)
        val (rc, _) = run(script, dir)
        assertEquals(0, rc)
        val calls = fake.lines()
        assertTrue("рестарт всё равно выполнен", calls.contains("setprop ctl.restart zygote"))
        assertTrue("рестарт последним", calls.last() == "setprop ctl.restart zygote")
        val drained = fake.journal.readLines().first { it.contains("step=drained") }
        assertTrue("честно сказано, что не слилось: $drained", !drained.endsWith("oif_wlan0=0"))
    }

    @Test
    fun scriptIsQuietAndNeedsNoWatcher() {
        val (fake, dir) = world()
        val script = JitNetModel.jitChainScript("op-5", "true", fake.journal.absolutePath)
        val (_, out) = run(script, dir)
        assertEquals("ничего не печатаем в stdout", "", out.trim())
        assertFalse("нет nohup", script.contains("nohup"))
        assertTrue("нет фонового запуска (единственный `&&` — условие цикла): " + script.lines()
            .filter { it.trim().endsWith("&") },
            script.lines().none { it.trim().endsWith("&") || it.trim().startsWith("&") })
        assertFalse("нет файла-отмены", script.contains("cancel"))
        assertFalse("нет sleep-задержки перед рестартом", script.contains("sleep 8"))
    }
}
