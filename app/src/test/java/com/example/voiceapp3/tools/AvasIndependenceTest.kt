package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * AVAS — самостоятельные функции референса, не часть JIT (уточнение владельца).
 *
 * По коду референса: mute — `w0/qa`/`w0/t2`/`w0/f2`/`CentralWatchdogService.z()`, выбор типа —
 * `w0/r1`/`CentralWatchdogService.A()`, планирование `H0()`/`I0()` — 12000 мс, отдельный путь
 * wake/CarService — 5000 мс. В JIT-цепочке (`w0/j9`, `w0/la`, `w0/e2`, `w0/bb`, `w0/r7`)
 * AVAS не упоминается: `la` не содержит AVAS-снимка, `jit_restaurar`/`e2` относятся к Wi-Fi/AP.
 *
 * У нас: `AvasCore`/`AvasRestore` удалены целиком, JIT-код на AVAS не ссылается, старый снимок
 * только фиксируется строкой и не применяется.
 */
class AvasIndependenceTest {

    private fun file(rel: String): File? =
        listOf(File(rel), File("app/$rel")).firstOrNull { it.isFile }

    private fun code(rel: String): String = file(rel)!!.readLines()
        .filterNot {
            val t = it.trim()
            t.startsWith("*") || t.startsWith("//") || t.startsWith("/*") || t.startsWith("#")
        }.joinToString("\n")

    @Test
    fun avasMechanismIsGone() {
        assertTrue(file("src/main/java/com/example/voiceapp3/tools/AvasCore.kt") == null)
        assertTrue(file("src/main/java/com/example/voiceapp3/tools/AvasRestore.kt") == null)
    }

    @Test
    fun jitCodeDoesNotTouchAvas() {
        for (f in listOf("JitNetCore.kt", "JitNet.kt")) {
            val c = code("src/main/java/com/example/voiceapp3/tools/$f")
            for (bad in listOf("AvasCore", "AvasRestore", "audioserver", "0x21407057", "avas_")) {
                assertFalse("$f не содержит «$bad»", c.contains(bad))
            }
        }
    }

    @Test
    fun chainHasNoAvasOrAudioCommands() {
        val stand = JitIsoStand(rules = 0)
        stand.exec(JitRef.chainScript(true))
        val text = stand.callsText()
        for (bad in listOf("audio", "avas", "am ", "cmd car", "service call")) {
            assertFalse("в цепочке нет «$bad»", text.contains(bad))
        }
        assertEquals(
            "только сеть/правила/свойства/монтирования",
            setOf("svc", "ip", "setprop", "umount"),
            stand.calls().map { it.substringBefore(' ') }.toSet()
        )
    }

    @Test
    fun leftoverAvasSnapshotIsNotApplied() {
        val ap = code("src/main/java/com/example/voiceapp3/tools/AppPerformance.kt")
        assertTrue("остаток фиксируется строкой", ap.contains("AVAS_LEGACY checked="))
        assertTrue("проверка только читает файл", ap.contains("fun reportLegacyAvasSnapshot"))
        assertTrue("значения не меняются", ap.contains("action=none write=none"))
        assertTrue("и требует решения владельца", ap.contains("need=owner_decision"))
        assertTrue("проверяются оба реальных имени (1.0.60 — avas_mode_saved)",
            ap.contains("\"avas_mode_saved\""))
        assertTrue("значение не читается", ap.contains("value=not_read"))
        assertFalse("файл прежней версии не удаляется", ap.contains("avas_snapshot\").delete()"))
        assertFalse("JIT-код не лезет в AVAS", ap.contains("AvasRestore") || ap.contains("AvasCore"))
    }

    @Test
    fun independentAvasFunctionsKeepTheirCode() {
        // Независимые функции (звук замков) не тронуты: файл на месте и с прежними вызовами.
        val lock = code("src/main/java/com/example/voiceapp3/tools/LockSound.kt")
        assertTrue(lock.isNotEmpty())
        assertTrue(lock.contains("usage 29") || lock.contains("USAGE_ASSISTANCE_SONIFICATION") ||
            lock.contains("playExternalSound"))
        assertFalse("в JIT-код AVAS не переносится", code("src/main/java/com/example/voiceapp3/tools/JitNet.kt")
            .contains("LockSound"))
    }
}
