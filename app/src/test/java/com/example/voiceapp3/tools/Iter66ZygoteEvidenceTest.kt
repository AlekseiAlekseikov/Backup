package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * iter66 — доказательство рестарта zygote без shell: родитель процесса приложения (`getppid`) —
 * zygote, из которого его запустили. На ГУ два zygote (`zygote64` — сервис `zygote`, его и
 * перезапускает `ctl.restart zygote`; 32-битный `zygote_secondary` с именем процесса `zygote`
 * остаётся жить), поэтому `pidof zygote` в снимке sh6 показывает НЕ тот процесс.
 */
class Iter66ZygoteEvidenceTest {

    private val dir = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
    private fun src(name: String) = File(dir, "com/example/voiceapp3/tools/$name").readText()

    @Test
    fun restartedVerdictTable() {
        assertEquals(true, JitMech.zygoteRestarted("457", 31000))
        assertEquals(false, JitMech.zygoteRestarted("457", 457))
        assertNull("до клика pid не сохранён", JitMech.zygoteRestarted(null, 457))
        assertNull("мусор в ключе", JitMech.zygoteRestarted("abc", 457))
        assertNull("pid не прочитан", JitMech.zygoteRestarted("457", null))
        assertNull("init/0 — не zygote", JitMech.zygoteRestarted("1", 457))
        assertNull(JitMech.zygoteRestarted("457", 0))
    }

    @Test
    fun keyIsAppPrefNotASystemProperty() {
        assertEquals("jit_zygote_antes", JitMech.KEY_ZYGOTE_ANTES)
        assertFalse("цепочка не знает о ключе", JitMech.chainScript(true).contains("zygote_antes"))
    }

    @Test
    fun androidWrapperRecordsBeforeClickAndComparesAfterRestart() {
        val m = src("JitMechAndroid.kt")
        assertTrue(m.contains("android.system.Os.getppid()"))
        val click = m.substringAfter("fun click(app: Context, want: Boolean)").substringBefore("\n    fun ")
        assertTrue(click, click.contains("JitMech.KEY_ZYGOTE_ANTES") && click.contains("JIT_ZYGOTE before"))
        assertTrue("запись pid — только если клик не будет отклонён busy", click.contains("!JitMech.busy()"))
        assertTrue("цепочка по-прежнему одна и та же", click.contains("JitMech.click("))
        val wd = m.substringAfter("fun watchdogStart(app: Context").substringBefore("\n    fun ")
        assertTrue(wd, wd.contains("JIT_ZYGOTE after") && wd.contains("JitMech.zygoteRestarted("))
        assertTrue("one-shot", wd.contains("remove(JitMech.KEY_ZYGOTE_ANTES)"))
        assertTrue("подтверждение Wi-Fi — только чтение", wd.contains("JIT_WIFI_CONFIRM") && !wd.contains("setEnabled"))
        assertTrue(wd.contains("if (r != null)"))
    }
}
