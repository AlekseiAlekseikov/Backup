package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * iter33p-fix2 §3/§4 — ГРАНИЦЫ и точки вызова AVAS (поведение — в [AvasCoreTest]).
 *
 * Здесь только то, что нельзя проверить ядром: какие методы Car API вообще вызываются, какими
 * значениями и из каких файлов. Это защита от возврата к «муте» и от записи предполагаемых
 * свойств.
 */
class AvasContractTest {

    private fun root(): File =
        listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }

    private fun src(rel: String): String =
        listOf(File(rel), File("app/$rel")).first { it.isFile }.readText()

    private val rel = "src/main/java/com/example/voiceapp3/tools/AvasRestore.kt"

    // 1. Режим пишется ТОЛЬКО через AvasRestore и только сохранённым значением.
    @Test
    fun modeIsWrittenOnlyFromSavedValue() {
        val users = root().walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.readText().contains("setAVASMode") }
            .map { it.name }.sorted().toList()
        assertEquals("только AvasRestore.kt", listOf("AvasRestore.kt"), users)

        val s = src(rel)
        assertTrue("setAVASMode принимает значение из снимка",
            s.contains("getMethod(\"setAVASMode\", Int::class.javaPrimitiveType).invoke(audio, value)"))
        for (bad in listOf("setAVASMode(0)", "setAVASMode(1)", "setAVASMode(2)", "setAVASMode(-1)")) {
            assertFalse("константа в setAVASMode запрещена: $bad", s.contains(bad))
        }
    }

    // 2. Доказанный id и области; никакого поиска свойства по имени и никакой «муты».
    @Test
    fun soundTypeUsesProvenIdOnly() {
        val s = src(rel)
        assertEquals(0x21407057, AvasRestore.PROP_SOUND_TYPE)
        assertTrue("id записан константой", s.contains("PROP_SOUND_TYPE = 0x21407057"))
        assertTrue("области {0,1}", s.contains("AREAS = intArrayOf(0, 1)"))
        assertTrue("доказательство в docs", s.contains("docs/agent/avas_identity.md"))

        val code = s.lineSequence()
            .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }
            .joinToString("\n")
        for (bad in listOf("getPropertyList", "audioserver", "setSoundType", ".wav", "avas_muted")) {
            assertFalse("запрещено: $bad", code.contains(bad))
        }
        assertFalse("id больше не «подсказывается» префами", code.contains("avas_sound_prop_id"))
    }

    // 3. void-API: успех не по возврату setIntProperty, а по read-back.
    @Test
    fun voidPropertyApiIsNotTreatedAsResult() {
        val s = src(rel)
        assertTrue("вызов по доказанному id/области/значению",
            s.contains("\"setIntProperty\"") && s.contains("invoke(cpm, s.propId, s.area, s.value)"))
        assertTrue("в KDoc зафиксировано, что возвращает void", s.contains("**void**"))
        assertFalse("Boolean-возврат у void-метода не парсится", s.contains("as? Boolean ?: false"))
        assertTrue("подтверждение — read-back в ядре", s.contains("AvasCore.attempt"))
    }

    // 4. Точки вызова: цепочка тумблера (save) и старт приложения (schedule).
    @Test
    fun callSitesAreStillTwo() {
        val hits = root().walkTopDown()
            .filter {
                it.isFile && it.extension == "kt" &&
                    (it.readText().contains("AvasRestore.saveBeforeRestart(") ||
                        it.readText().contains("AvasRestore.scheduleRestore(") ||
                        it.readText().contains("AvasRestore.discard("))
            }
            .map { it.name }.distinct().sorted().toList()
        assertEquals("AVAS дёргается только из цепочки тумблера/старта", listOf("AppPerformance.kt"), hits)

        val perf = src("src/main/java/com/example/voiceapp3/tools/AppPerformance.kt")
        val apply = perf.substringAfter("fun jitApplyWithZygote").substringBefore("JIT_ZYGOTE_END")
        assertTrue("снимок делается в цепочке тумблера", apply.contains("AvasRestore.saveBeforeRestart"))
        assertTrue("снимок ПОСЛЕ снимка операции (REVIEW §4)",
            apply.indexOf("JitNet.saveOperation") < apply.indexOf("AvasRestore.saveBeforeRestart"))
        assertTrue("снимок ДО единственного скрипта цепочки",
            apply.indexOf("AvasRestore.saveBeforeRestart") < apply.indexOf("JitNet.runChain"))
        // iter33p (1.0.61): отмены операции больше нет — рестарт это последняя команда скрипта,
        // поэтому и «отменять» снимок в цепочке нечем; неудачная операция разбирается на старте.
        assertTrue("в цепочке нет отмены снимка", !apply.contains("AvasRestore.discard"))
        val probe = perf.substringAfter("fun jitBootProbe(").substringBefore("JIT_ZYGOTE_BEGIN")
        assertTrue("на обычном старте — только планирование", probe.contains("AvasRestore.scheduleRestore"))
    }

    // 5. Снимок — один файл, атомарная запись (temp+rename), чтение подтверждает id.
    @Test
    fun snapshotIsSingleAtomicFile() {
        val s = src(rel)
        assertTrue(s.contains("SNAPSHOT_FILE = \"avas_snapshot\""))
        assertTrue("запись через temp", s.contains("\$SNAPSHOT_FILE.tmp"))
        assertTrue("rename", s.contains("renameTo(dst)"))
        assertTrue("запись подтверждается чтением", s.contains("decode(dst.readText())?.id == snapshot.id"))
        assertFalse("отдельного файла под sound type нет", s.contains("avas_sound_snapshot"))
    }
}
