package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * iter63 §P1-2 — TTL: «файла нет / наш / чужой-нечитаемый» — ТРИ РАЗНЫХ СЛУЧАЯ.
 *
 * Дефект iter62: `_gen_ok` различал только «файла нет» и «значение не совпало». Если файл поколения
 * остался от прежней версии/копии, от другой прошивки или не читается, скрипт уходил в `exit 90`
 * НАВСЕГДА и без объяснения — TTL молча не работал (диагностики в журнале не было).
 *
 * Проверяется РЕАЛЬНЫЙ production-скрипт ([TtlScript.build]) в НАСТОЯЩЕМ `/bin/sh` с поддельным
 * `iptables` (тот же стенд, что в `Iter62TtlGenTest`), а также классификатор
 * [TtlGenDiag.marker] по фактическому выводу.
 */
class Iter63TtlGenStateTest {

    private val dir: File = File.createTempFile("ttl63", "").let { it.delete(); it.mkdirs(); it }
    private val state: File = File(dir, "state").apply { mkdirs() }
    private val genFile: File = File(state, "ttl_slot_gen")
    private val table: File = File(state, "table")

    private fun fakeIptables(): File {
        val bin = File(state, "bin").apply { mkdirs() }
        val f = File(bin, "iptables")
        f.writeText(
            """
            #!/bin/sh
            echo "${'$'}*" >> "$state/calls"
            [ -f "$state/fail" ] && { echo "can't initialize" >&2; exit 3; }
            : >> "$table"
            for a in "${'$'}@"; do
              case "${'$'}a" in
                *" "*|*"	"*) echo "GLUED:<${'$'}a>" >&2; exit 90 ;;
              esac
            done
            case "${'$'}1" in
              -S) echo "-P POSTROUTING ACCEPT"; [ -s "$table" ] && sed "s/^/-A POSTROUTING /" "$table" ;;
              -A) echo "${'$'}3" >> "$table" ;;
              -N) : ;;
              -X|-F|-D) : ;;
            esac
            exit 0
            """.trimIndent() + "\n"
        )
        f.setExecutable(true)
        return bin
    }

    private fun run(script: String, name: String): Pair<Int, String> {
        val bin = fakeIptables()
        val f = File(dir, name).apply { writeText(script) }
        val pb = ProcessBuilder("/bin/sh", f.absolutePath).redirectErrorStream(true)
        pb.environment()["PATH"] = bin.absolutePath + ":" + (System.getenv("PATH") ?: "/usr/bin:/bin")
        pb.environment()["VAPP_STATE"] = state.absolutePath
        pb.environment()["VAPP_GEN_FILE"] = genFile.absolutePath
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(120, TimeUnit.SECONDS)
        return p.exitValue() to out
    }

    private fun script(gen: String, enable: Boolean = true): String = TtlScript.build(
        enable = enable, gen = "install63", guardGen = gen, genFile = genFile.absolutePath
    )

    // ─────────── 1. Файла нет → публикуем и работаем (не отказ) ───────────

    @Test
    fun absentGenerationFileIsInitialisedNotRefused() {
        assertFalse("файла поколения нет", genFile.exists())
        val (rc, out) = run(script("101"), "absent.sh")
        assertEquals("скрипт не должен отказывать из-за отсутствия файла: $out", 0, rc)
        assertTrue("инициализация названа: $out", out.contains("VAPP_TTL_GEN_INIT"))
        assertTrue("в маркере есть ПУТЬ и ПРОЧИТАННОЕ значение: $out",
            out.contains("file=") && out.contains("readback=101"))
        assertEquals("поколение действительно опубликовано", "101", genFile.readText().trim())
    }

    // ─────────── 2. Файл наш → OK ───────────

    @Test
    fun ourGenerationFileIsOk() {
        genFile.writeText("202")
        val (rc, out) = run(script("202"), "ours.sh")
        assertEquals(0, rc)
        assertTrue("состояние «наш» названо: $out", out.contains("VAPP_TTL_GEN_OK"))
        assertFalse("восстановление не требовалось: $out", out.contains("GEN_ADOPT"))
        assertEquals("202", genFile.readText().trim())
    }

    // ─────────── 3. Файл ЧУЖОЙ и ЧИТАЕМЫЙ → отказ ДО мутации, с путём и значением ───────────

    @Test
    fun foreignReadableGenerationRefusesBeforeAnyMutationWithEvidence() {
        genFile.writeText("777")     // значение другого владельца/поколения
        val (rc, out) = run(script("303"), "foreign.sh")
        assertEquals("чужое читаемое поколение — единственный честный отказ: $out", 90, rc)
        assertTrue("причина и путь названы: $out",
            out.contains("VAPP_TTL_SUPERSEDED") && out.contains("file=") && out.contains("cur=777"))
        assertFalse("мутации не было", table.exists() && table.length() > 0L)
    }

    // ─────────── 3b. Боевой порядок: поколение публикуется ДО отправки скрипта ───────────

    @Test
    fun productionOrderPublishesOwnGenerationBeforeScriptSoForeignValueIsOverwritten() {
        // NetworkTtl.applyInternal публикует поколение и подтверждает readback ДО buildScript.
        // Здесь тот же порядок: чужое значение перекрывается своим, скрипт видит GEN_OK, мутация идёт.
        genFile.writeText("777")
        genFile.writeText("303")     // публикация владельца (подтверждена readback в production)
        assertEquals("303", genFile.readText().trim())
        val (rc, out) = run(script("303"), "after_publish.sh")
        assertEquals("после публикации отказа быть не должно: $out", 0, rc)
        assertTrue("состояние названо: $out", out.contains("VAPP_TTL_GEN_OK"))
        val calls = File(state, "calls")
        assertTrue("iptables вызван (попытка мутации состоялась)",
            calls.exists() && calls.readText().lineSequence().any { it.contains(" -A ") })
    }

    // ─────────── 4. Файл нечитаемый (не файл) → тоже восстановление, не вечный отказ ───────────

    @Test
    fun unreadableGenerationPathIsRestoredWithReadback() {
        genFile.mkdirs()             // «файл» есть, но это каталог ⇒ прочитать значение нельзя
        val (rc, out) = run(script("404"), "unreadable.sh")
        assertTrue("причина названа, а не молчание: $out",
            out.contains("VAPP_TTL_GEN_ADOPT") || out.contains("VAPP_TTL_GEN_UNWRITABLE"))
        assertFalse("вечного отказа без причины нет", rc == 90 && !out.contains("VAPP_TTL_GEN_UNWRITABLE"))
    }

    // ─────────── 5. Поколение СМЕНИЛОСЬ (файл наш, но новее) → отказ с доказательством ───────────

    @Test
    fun supersededGenerationRefusesBeforeAnyMutation() {
        genFile.writeText("505")     // владелец уже переключил на новое поколение
        val (rc, out) = run(script("606"), "superseded.sh")
        assertEquals("старый скрипт обязан выйти с 90", 90, rc)
        assertTrue("причина с путём: $out",
            out.contains("VAPP_TTL_SUPERSEDED") && out.contains("file=") && out.contains("cur=505"))
        assertFalse("таблица не тронута",
            File(state, "calls").let { it.exists() && it.readText().lineSequence().any { l -> l.contains(" -A ") } })
    }

    // ─────────── 6. Классификатор для журнала ───────────

    @Test
    fun genMarkerClassificationIsExplicit() {
        val file = "/data/user/0/x/files/ttl_slot_gen"
        assertEquals("VAPP_TTL_GEN_OK gen=9 file=$file",
            TtlGenDiag.marker("noise\nVAPP_TTL_GEN_OK gen=9 file=$file\n", file))
        assertEquals("VAPP_TTL_GEN_ADOPT gen=9 file=$file was=7 readback=9",
            TtlGenDiag.marker("VAPP_TTL_GEN_ADOPT gen=9 file=$file was=7 readback=9", file))
        assertEquals("", TtlGenDiag.marker("обычный вывод без маркеров", file))
    }

    // ─────────── 7. Диагностика БОЕВОЙ публикации: чем был файл ДО записи ───────────

    @Test
    fun publishCommandReportsWhatTheFileWasBeforeOverwrite() {
        val f = File(state, "pub")
        val cmd = TtlGenDiag.publishCommand(909, f.absolutePath)

        // (а) файла нет — первый запуск после установки/обновления
        val (rcA, outA) = sh(cmd)
        val (okA, whyA) = TtlGenDiag.parsePublish(outA, 909, f.absolutePath)
        assertEquals(0, rcA)
        assertTrue("подтверждено чтением: $whyA", okA)
        assertTrue("названо «файла не было»: $whyA", whyA.contains("gen_file_state=absent"))
        assertTrue("в строке есть путь и прочитанное: $whyA",
            whyA.contains("file=") && whyA.contains("readback=909"))

        // (б) файл был ЧУЖИМ — его перекрыли своим, и это видно в журнале
        f.writeText("777")
        val (_, outB) = sh(cmd)
        val (okB, whyB) = TtlGenDiag.parsePublish(outB, 909, f.absolutePath)
        assertTrue(okB)
        assertTrue("названо «файл был чужой»: $whyB", whyB.contains("gen_file_state=foreign"))
        assertEquals("909", f.readText().trim())

        // (в) канал молчит — это ОТКАЗ, а не «применено»
        val (okC, whyC) = TtlGenDiag.parsePublish("", 909, f.absolutePath)
        assertFalse("нет readback ⇒ не подтверждено", okC)
        assertTrue("причина названа: $whyC", whyC.contains("action=not_published"))
    }

    private fun sh(cmd: String): Pair<Int, String> {
        val pb = ProcessBuilder("/bin/sh", "-c", cmd).redirectErrorStream(true)
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(60, TimeUnit.SECONDS)
        return p.exitValue() to out
    }
}
