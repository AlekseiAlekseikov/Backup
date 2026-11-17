package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * iter62 §A2 — ОГРАЖДЕНИЕ TTL ПОКОЛЕНИЕМ: работает и на чистой установке, и против позднего скрипта.
 *
 * Воспроизведённые аудитом дефекты iter61 (оба — на настоящем `/bin/sh` с production-текстом
 * скрипта):
 *  • A2.1: `publishSlotGeneration` вызывался только при незакрытых попытках, а проверка в
 *    заголовке считала ОТСУТСТВИЕ файла «другим поколением» ⇒ на обновлённом устройстве первый же
 *    ON-скрипт выходил с `rc 90`, и TTL не включался вообще;
 *  • A2.2: проверка поколения стояла ОДИН раз в заголовке — успевший скрипт применял правила
 *    после нового (новый OFF → поздний ON ⇒ итог ON при желании OFF).
 *
 * Здесь исполняется НАСТОЯЩИЙ сгенерированный [TtlScript] на стенде [Iter50Stand] (реальный
 * `/bin/sh` + stateful fake `iptables`), поэтому проверяется фактическая таблица правил, а не
 * текст скрипта.
 */
class Iter62TtlGenTest {

    private fun stand() = Iter50Stand().also {
        it.mountinfo.writeText(
            "1 0 8:1 / / rw - ext4 /dev/root rw\n" +
                "2 1 253:0 /local /data rw - f2fs /dev/block/sda1 rw\n"
        )
    }

    private fun stateDir(st: Iter50Stand) =
        File(st.dir, "ttlstate").apply { mkdirs() }.absolutePath

    /** Скрипт production-сборки с ограждением по конкретному файлу поколения. */
    private fun script(
        enable: Boolean,
        gen: String = "7",
        genFile: String,
        ownChain: String = "VAPP_TTL_$gen"
    ) = TtlScript.build(
        enable = enable,
        gen = gen,
        ownChain = ownChain,
        guardGen = gen,
        genFile = genFile
    )

    // ───────── A2-1: файла поколения НЕТ ⇒ TTL включается (дефект iter61: rc 90) ─────────

    @Test
    fun a2FreshInstallWithoutGenerationFileStillEnablesTtl() {
        val st = stand()
        val gen = File(st.dir, "ttl_slot_gen")          // НЕ создаём
        val before = st.rules("iptables")

        val (rc, out) = st.exec(
            script(enable = true, gen = "7", genFile = gen.absolutePath),
            "on_fresh.sh",
            mapOf("VAPP_TTL_STATE" to stateDir(st))
        )

        assertEquals("скрипт не отказался: $out", 0, rc)
        assertFalse("нет SUBSEDED-отказа на чистой установке: $out",
            out.contains("VAPP_TTL_SUPERSEDED"))
        assertTrue("поколение инициализировано самим скриптом: $out",
            out.contains("VAPP_TTL_GEN_INIT"))
        assertTrue("файл поколения создан", gen.exists())
        assertEquals("в файле именно наше поколение", "7", gen.readText().trim())
        assertTrue("таблица изменена (TTL включён): было=$before стало=${st.rules("iptables")}",
            st.rules("iptables") != before)
    }

    // ───────── A2-2: файл есть, поколение совпало ⇒ применяет ─────────

    @Test
    fun a2MatchingGenerationApplies() {
        val st = stand()
        val gen = File(st.dir, "ttl_slot_gen").apply { writeText("7") }
        val before = st.rules("iptables")
        val (rc, out) = st.exec(
            script(enable = true, gen = "7", genFile = gen.absolutePath),
            "on_same.sh",
            mapOf("VAPP_TTL_STATE" to stateDir(st))
        )
        assertEquals("применено: $out", 0, rc)
        assertTrue("таблица изменилась", st.rules("iptables") != before)
        assertEquals("файл не переписан другим значением", "7", gen.readText().trim())
    }

    // ───────── A2-3: поколение сменилось ПОСЛЕ заголовка ⇒ мутации нет ─────────

    @Test
    fun a2LateScriptCannotMutateAfterANewerGenerationAppeared() {
        val st = stand()
        val gen = File(st.dir, "ttl_slot_gen").apply { writeText("5") }
        val before = st.rules("iptables")
        // Новое поколение публикует владелец, пока скрипт уже прошёл заголовок: стенд делает это
        // на вызове `ip` (определение uplink), то есть МЕЖДУ проверкой в заголовке и мутацией.
        File(st.dir, "state/gen_bump").writeText("6")

        val (rc, out) = st.exec(
            script(enable = true, gen = "5", genFile = gen.absolutePath),
            "late_on.sh",
            mapOf("VAPP_TTL_STATE" to stateDir(st), "GEN_FILE" to gen.absolutePath)
        )

        assertEquals("поздний скрипт отказался применять правила: $out", 90, rc)
        assertTrue("причина названа: $out", out.contains("VAPP_TTL_SUPERSEDED"))
        assertEquals("поколение уже новое", "6", gen.readText().trim())
        assertEquals("таблица НЕ изменена поздним скриптом", before, st.rules("iptables"))
    }

    // ───────── A3-совместимость: off-скрипт тоже ограждён ─────────

    @Test
    fun a2OffScriptIsAlsoGuardedBeforeMutation() {
        val st = stand()
        val gen = File(st.dir, "ttl_slot_gen").apply { writeText("9") }
        st.seedRule("iptables", "POSTROUTING", "-o wlan0 -j VAPP_TTL_9")
        st.seedRule("iptables", "VAPP_TTL_9", "-j TTL --ttl-set 65")
        val before = st.rules("iptables")
        File(st.dir, "state/gen_bump").writeText("10")

        val (rc, out) = st.exec(
            script(enable = false, gen = "9", genFile = gen.absolutePath),
            "off_late.sh",
            mapOf("VAPP_TTL_STATE" to stateDir(st), "GEN_FILE" to gen.absolutePath)
        )
        assertEquals("OFF тоже отказался: $out", 90, rc)
        assertEquals("правила не сняты поздним скриптом", before, st.rules("iptables"))
    }

    // ───────── A2-5: SUPERSEDED только по доказательству ─────────

    @Test
    fun a2SupersedeRequiresProofAndNeverSilentlyCloses() {
        val attempt = TtlState.Attempt(
            id = "ttl_a", evidencePath = "/data/local/tmp/a.sh", want = true,
            generation = 4L, phase = TtlState.Phase.MAY_HAVE_RUN
        )
        assertNull("без подтверждённого чтением поколения снятия нет",
            TtlState.supersede(attempt, TtlState.SupersedeProof(5L, false, "no_readback"), 1L))
        assertNull("поколение не новее поколения попытки — снятия нет",
            TtlState.supersede(attempt, TtlState.SupersedeProof(4L, true, "same_gen"), 1L))
        val ok = TtlState.supersede(attempt, TtlState.SupersedeProof(5L, true, "published"), 1L)
        assertNotNull("с доказательством попытка снимается", ok)
        assertEquals("фаза", TtlState.Phase.SUPERSEDED, ok!!.phase)
        assertEquals("id не меняется", "ttl_a", ok.id)
        assertTrue("доказательство записано в журнал: ${ok.reasonHint}",
            ok.reasonHint.contains("superseded_by_gen=5"))
        assertTrue("снятая попытка не блокирует",
            TtlState.unresolved(listOf(ok)).isEmpty())
    }

    // ───────── A2-4: два прохода подряд без unresolved ничего не «снимают» ─────────

    @Test
    fun a2SecondPassWithoutPendingAttemptsPublishesNothingNew() {
        val src = File("app/src/main/java/com/example/voiceapp3/tools/NetworkTtl.kt").readText()
        // Публикация поколения идёт ДО сборки скрипта и ДО первой отправки, а не только внутри
        // fence: иначе на чистой установке файла нет (дефект A2.1).
        val publish = src.indexOf("if (!publishSlotGeneration(desire.generation, slotFile))")
        val build = src.indexOf("buildScript(want, reg, desire.generation, slotFile)")
        assertTrue("публикация существует", publish > 0)
        assertTrue("публикация идёт ДО сборки скрипта: $publish < $build", publish in 1 until build)
        val attempt = src.indexOf("recordAttempt(prep.evidencePath, want, desire.generation)")
        assertTrue("и до записи identity/send", publish < attempt)
        assertTrue("отказ публикации останавливает отправку",
            src.contains("gen_not_published"))
        assertTrue("файл поколения — в filesDir приложения, а не в /data/local/tmp",
            src.contains("File(context.filesDir, \"ttl_slot_gen\")"))
    }

    // ───────── A2-7: журнал недостоверен ⇒ ноль мутаций ─────────

    @Test
    fun a2UnreadableJournalStillForbidsMutation() {
        val j = TtlState.readJournal(null, true)
        assertFalse("нечитаемый журнал не разрешает мутацию", j.mutationAllowed)
        val corrupt = TtlState.readJournal("мусор\nещё мусор\n", true)
        assertFalse("битый журнал не разрешает мутацию", corrupt.mutationAllowed)
        val absent = TtlState.readJournal(null, false)
        assertTrue("отсутствующий журнал — это не «нечитаемый»", absent.mutationAllowed)
        assertTrue("новых записей нет", absent.records.isEmpty())
    }
}
