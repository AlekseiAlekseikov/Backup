package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * iter59 §5.2 — ADMIN-СЕКЦИЯ JIT: гонки «compare-and-unlink» больше нет по построению.
 *
 * Дефект iter58 (`JitOwn.kt:461–465`): освобождение доказанно мёртвого admin-файла делалось
 * «прочитать → доказать смерть → сравнить содержимое → `rm -f` по ИМЕНИ». Между сравнением и
 * `rm` другой претендент успевал снять тот же файл и создать СВОЙ — и `rm` удалял admin ЖИВОГО
 * участника, после чего в секцию мог войти третий. `test; rm` не атомарен, и третье чтение в
 * этой схеме ничего не меняет.
 *
 * Что проверяется здесь НАСТОЯЩИМИ процессами `/bin/sh` (production-функции из
 * [JitOwn.ownHelpersForTest], реальные `/proc`, реальная сигнализация):
 *  1. старое правило, воспроизведённое в тесте с барьерами, действительно удаляет admin ЖИВОГО
 *     владельца и пускает второго в секцию (дефект подтверждён, а не описан словами);
 *  2. новый протокол (уникальная запись НОМЕРА `n.<k>` + noclobber-захват, iter61 §3) в тех же условиях
 *     второго не пускает и файл живого не трогает;
 *  3. взаимное исключение под 4 реальными конкурентными процессами: каждый вход проверяется
 *     «свидетелем», который может держать только один; считаются ФАКТИЧЕСКИЕ нарушения;
 *  4. crash-release: доказанно мёртвый участник снимается по СВОЕМУ имени; живой — блокирует, и
 *     вход честно отказывает, не оставляя своей записи (нет вечной блокировки и нет утечки);
 *  5. legacy admin-файл прежней версии: мёртвый — миграция, живой — блокировка без удаления;
 *  6. повреждённая запись не удаляется без доказательства смерти (безопасный отказ).
 *
 * Чего тесты НЕ доказывают (в отчёте 🟡/⛔): реальный Android/toybox, реальный `zygote` и ГУ;
 * здесь Linux `/bin/sh` + `/proc` песочницы.
 */
class Iter59JitAdminTest {

    private val base: File = File.createTempFile("jit59", "").let { f ->
        f.delete(); f.mkdirs(); f
    }
    private val b: String get() = base.absolutePath
    private val lock: File get() = File(JitOwn.lockPath(b))
    private val admDir: File get() = File(JitOwn.lockPath(b) + ".adm")
    private val legacyAdmin: File get() = File(JitOwn.lockPath(b) + ".admin")

    // ───────────────────────── инфраструктура ─────────────────────────

    private fun sh(script: String, name: String, env: Map<String, String> = emptyMap()): Pair<Int, String> {
        val f = File(base, name)
        f.writeText(script)
        val pb = ProcessBuilder("/bin/sh", f.absolutePath).redirectErrorStream(true)
        pb.environment().putAll(env)
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(90, TimeUnit.SECONDS)
        return p.exitValue() to out
    }

    private fun withHelpers(name: String, meInst: String, body: String, env: Map<String, String> = emptyMap()) =
        sh(JitOwn.ownHelpersForTest(b) + "\n_me_inst=\"$meInst\"\n" + body, name, env)

    private fun pstartOf(pid: Long): String =
        File("/proc/$pid/stat").readText().trim().split(" ")[21]

    private fun bootId(): String = runCatching {
        File("/proc/sys/kernel/random/boot_id").readText().trim()
    }.getOrDefault("")

    private fun startSleeper(sec: String = "30"): Process =
        ProcessBuilder("sleep", sec).redirectErrorStream(true).start()

    private fun participantFile(slot: Int) = File(admDir, "n.$slot")

    private fun writeParticipant(inst: String, pid: Long, pstart: String, slot: Int = 1) {
        admDir.mkdirs()
        participantFile(slot).writeText(
            "slot=$slot;inst=$inst;pid=$pid;pstart=$pstart;boot=${bootId()}\n"
        )
    }

    private fun participantNames(): List<String> =
        admDir.listFiles()?.filter { it.name.matches(Regex("n\\.\\d+")) }?.map { it.name }?.sorted()
            ?: emptyList()

    /** Содержимое всех записей номеров — по нему видно, чья запись осталась/снята. */
    private fun participantContents(): List<String> =
        participantNames().map { File(admDir, it).readText().trim() }

    // ───────── 1. Дефект iter58 воспроизведён: «сравнить → rm по имени» удаляет ЖИВОГО ─────────

    /**
     * Старое правило (iter58) целиком, с барьерами ровно между финальным сравнением и `rm`.
     *
     * B (ждёт барьера) читает доказанно мёртвую строку, сравнивает её, и только потом удаляет
     * имя и ставит свою строку. C уже приготовил свой `rm` по тому же имени — и сносит admin B,
     * который в этот момент ЖИВ. После этого D входит вторым.
     */
    @Test
    fun iter58CompareAndUnlinkRemovesALiveAdminAndLetsTwoOwnersIn() {
        val dead = startSleeper("0.05")
        val deadPid = dead.pid()
        dead.waitFor(30, TimeUnit.SECONDS)
        val deadLine = "inst=DEAD;pid=$deadPid;pstart=1;boot=${bootId()}"
        legacyAdmin.writeText("$deadLine\n")

        val bReady = File(base, "b_ready"); val cReady = File(base, "c_ready")
        val cRead = File(base, "c_read"); val go = File(base, "go")

        // C: читает ту же мёртвую строку, доказывает смерть, ФИКСИРУЕТ результат сравнения,
        // затем (после барьера) выполняет уже приготовленный `rm -f` по ИМЕНИ.
        val cScript = JitOwn.ownHelpersForTest(b) + """

            _ad="${legacyAdmin.absolutePath}"
            _cur=${'$'}(cat "${'$'}_ad" 2>/dev/null)
            if _jit_death_proven "${'$'}_cur"; then echo "C_DEATH=proven"; fi
            : > "${bReady.absolutePath}"
            while [ ! -f "${cReady.absolutePath}" ]; do sleep 0.01; done
            _now=${'$'}(cat "${'$'}_ad" 2>/dev/null)
            if [ "${'$'}_now" = "${'$'}_cur" ]; then echo "C_COMPARE=same"; fi
            : > "${cRead.absolutePath}"
            while [ ! -f "${go.absolutePath}" ]; do sleep 0.01; done
            rm -f "${'$'}_ad"            # <- этот rm приготовлен ДО появления живого B
            echo "C_REMOVED=1"
        """.trimIndent()
        val c = ProcessBuilder("/bin/sh", File(base, "c.sh").apply { writeText(cScript) }.absolutePath)
            .redirectErrorStream(true).start()

        // B: дождался, что C прочитал ту же строку, снял её по имени и поставил СВОЮ (живую).
        val liveB = startSleeper("30")
        val bLine = "inst=B;pid=${liveB.pid()};pstart=${pstartOf(liveB.pid())};boot=${bootId()}"
        val bScript = """
            while [ ! -f "${bReady.absolutePath}" ]; do sleep 0.01; done
            : > "${cReady.absolutePath}"
            sleep 0.15
            rm -f "${legacyAdmin.absolutePath}"
            printf '%s\n' "$bLine" > "${legacyAdmin.absolutePath}"
            if [ -f "${legacyAdmin.absolutePath}" ]; then echo "B_TAKEN=1"; fi
            : > "${cRead.absolutePath}"
            sleep 2
        """.trimIndent()
        val bs = ProcessBuilder("/bin/sh", File(base, "b.sh").apply { writeText(bScript) }.absolutePath)
            .redirectErrorStream(true).start()
        val bOut = bs.inputStream.bufferedReader().readText()

        Thread.sleep(300)                    // B уже держит admin
        assertTrue("B считает, что взял admin: $bOut", bOut.contains("B_TAKEN=1"))
        assertTrue("admin сейчас принадлежит ЖИВОМУ B",
            legacyAdmin.readText().contains("inst=B") && liveB.isAlive)

        go.createNewFile()                   // C выполняет приготовленный rm
        val cOut = c.inputStream.bufferedReader().readText()
        c.waitFor(30, TimeUnit.SECONDS)

        assertTrue("C подтвердил смерть прежнего владельца: $cOut", cOut.contains("C_DEATH=proven"))
        assertTrue("C сравнил ровно ту строку: $cOut", cOut.contains("C_COMPARE=same"))
        assertTrue("C выполнил rm: $cOut", cOut.contains("C_REMOVED=1"))
        assertFalse("admin ЖИВОГО B удалён — взаимное исключение разрушено",
            legacyAdmin.exists() && legacyAdmin.readText().contains("inst=B"))
        assertTrue("B всё ещё жив и считает себя владельцем", liveB.isAlive)

        bs.destroyForcibly(); liveB.destroyForcibly()
    }

    // ───────── 2. Новый протокол: в тех же условиях второго не пускает ─────────

    @Test
    fun newProtocolKeepsTheLiveParticipantAndRefusesTheSecond() {
        val live = startSleeper()
        try {
            writeParticipant("LIVE", live.pid(), pstartOf(live.pid()), slot = 1)
            val before = participantFile(1).readText()

            val (_, out) = withHelpers("new_enter.sh", "CONTENDER",
                """
                _jit_admin_enter
                echo "ENTER=${'$'}?"
                """.trimIndent())

            val rc = out.substringAfter("ENTER=").substringBefore("\n").trim()
            assertEquals("вход при живом участнике запрещён", "1", rc)
            assertEquals("запись живого участника не тронута", before, participantFile(1).readText())
            assertFalse("своей записи после отказа не осталось: ${participantContents()}",
                participantContents().any { it.contains("inst=CONTENDER") })
            assertEquals("лишних номеров не появилось: ${participantNames()}", 1, participantNames().size)
            assertTrue("живой процесс жив", live.isAlive)
        } finally {
            live.destroyForcibly()
        }
    }

    // ───────── 3. Взаимное исключение на реальных конкурентных процессах ─────────

    @Test
    fun concurrentParticipantsNeverHoldTheSectionTogether() {
        val witness = File(base, "witness")
        val env = mapOf("ROOT" to b)
        val body = """
            _me_inst="${'$'}{_me_inst}"
            i=0; bad=0; ok=0
            while [ "${'$'}i" -lt 15 ]; do
              i=${'$'}(( i + 1 ))
              if _jit_admin_enter; then
                ok=${'$'}(( ok + 1 ))
                : > "${'$'}ROOT/wtmp.${'$'}${'$'}"
                if ! ln "${'$'}ROOT/wtmp.${'$'}${'$'}" "${witness.absolutePath}" 2>/dev/null; then
                  bad=${'$'}(( bad + 1 ))
                fi
                sleep 0.005
                rm -f "${witness.absolutePath}" "${'$'}ROOT/wtmp.${'$'}${'$'}"
                _jit_admin_exit
              fi
            done
            echo "RESULT inst=${'$'}{_me_inst} ok=${'$'}ok bad=${'$'}bad"
        """.trimIndent()

        val procs = listOf("A", "B", "C", "D").map { inst ->
            val f = File(base, "w_$inst.sh").apply {
                writeText(JitOwn.ownHelpersForTest(b) + "\n_me_inst=\"$inst\"\n" + body)
            }
            ProcessBuilder("/bin/sh", f.absolutePath).apply { environment().putAll(env) }
                .redirectErrorStream(true).start()
        }
        val outs = procs.map { p ->
            val o = p.inputStream.bufferedReader().readText()
            p.waitFor(120, TimeUnit.SECONDS)
            assertTrue("участник завершился: $o", p.exitValue() == 0)
            o
        }
        val total = outs.sumOf { o -> Regex("ok=(\\d+)").find(o)!!.groupValues[1].toInt() }
        val violations = outs.sumOf { o -> Regex("bad=(\\d+)").find(o)!!.groupValues[1].toInt() }

        assertEquals("два участника в секции одновременно — ФАКТИЧЕСКИХ нарушений нет", 0, violations)
        assertEquals("все входы состоялись (реальные побочные эффекты посчитаны)", 60, total)
        assertTrue("после работы не осталось записей участников: ${participantNames()}",
            participantNames().isEmpty())
    }

    // ───────── 4. Crash-release и отсутствие утечки при отказе ─────────

    @Test
    fun deadParticipantIsReclaimedByItsOwnNameOnly() {
        val dead = startSleeper("0.05")
        val deadPid = dead.pid()
        dead.waitFor(30, TimeUnit.SECONDS)
        // pstart намеренно не тот: pid уже переиспользован/освобождён → смерть доказана.
        writeParticipant("DEAD", deadPid, "1", slot = 1)

        val (_, out) = withHelpers("reclaim.sh", "NEW",
            """
            _jit_admin_enter
            echo "ENTER=${'$'}?"
            _ad_me="${'$'}{_ad_me:-}"
            [ -n "${'$'}_ad_me" ] && [ -f "${'$'}_ad_me" ] && echo "OWN_FILE=during"
            _jit_admin_exit
            [ -n "${'$'}_ad_me" ] && [ -f "${'$'}_ad_me" ] && echo "OWN_FILE=after" || echo "OWN_FILE=gone"
            """.trimIndent())

        assertEquals("вход под доказанно мёртвым разрешён", "0",
            out.substringAfter("ENTER=").substringBefore("\n").trim())
        assertTrue("своя запись существует внутри секции: $out", out.contains("OWN_FILE=during"))
        assertTrue("своя запись снята на выходе: $out", out.contains("OWN_FILE=gone"))
        assertFalse("запись мёртвого снята по своему имени: ${participantContents()}",
            participantContents().any { it.contains("inst=DEAD") })
    }

    @Test
    fun killedParticipantInsideSectionDoesNotBlockForever() {
        // Участник входит в секцию и «падает» (его убивают). Следующий должен доказать смерть и
        // войти — без ручной чистки и без вечной блокировки.
        val holder = File(base, "holder.sh").apply {
            writeText(JitOwn.ownHelpersForTest(b) + "\n_me_inst=\"HOLDER\"\n" +
                """
                _jit_admin_enter
                echo "HOLDER_IN=${'$'}?"
                sleep 60
                """.trimIndent())
        }
        val p = ProcessBuilder("/bin/sh", holder.absolutePath).redirectErrorStream(true).start()
        val holderOut = p.inputStream.bufferedReader()
        val firstLine = holderOut.readLine()
        assertEquals("владелец вошёл", "HOLDER_IN=0", firstLine)
        Thread.sleep(100)
        assertTrue("участник держит секцию: ${participantContents()}",
            participantContents().any { it.contains("inst=HOLDER") })

        p.destroyForcibly()                       // crash внутри секции: файл участника остался
        p.waitFor(30, TimeUnit.SECONDS)

        val started = System.currentTimeMillis()
        val (_, out) = withHelpers("after_crash.sh", "NEXT",
            """
            _jit_admin_enter
            echo "ENTER=${'$'}?"
            _jit_admin_exit
            """.trimIndent())
        val took = System.currentTimeMillis() - started

        assertEquals("после crash вход состоялся", "0",
            out.substringAfter("ENTER=").substringBefore("\n").trim())
        assertTrue("ожидание ограничено, а не бесконечно: ${took}ms", took < 30_000)
        assertFalse("запись упавшего снята: ${participantContents()}",
            participantContents().any { it.contains("inst=HOLDER") })
    }

    // ───────── 5. Legacy admin-файл и повреждённая запись ─────────

    @Test
    fun legacyAdminFileIsMigratedWhenDeadAndKeptWhenLive() {
        // (а) мёртвый владелец: файл уходит, вход есть
        val dead = startSleeper("0.05")
        val deadPid = dead.pid()
        dead.waitFor(30, TimeUnit.SECONDS)
        legacyAdmin.writeText("inst=OLD;pid=$deadPid;pstart=1;boot=${bootId()}\n")
        val (_, deadOut) = withHelpers("legacy_dead.sh", "NEW",
            """
            _jit_admin_enter
            echo "ENTER=${'$'}?"
            _jit_admin_exit
            """.trimIndent())
        assertEquals("вход под мёртвым legacy-владельцем", "0",
            deadOut.substringAfter("ENTER=").substringBefore("\n").trim())
        assertFalse("legacy-файл мёртвого не остался блокировать", legacyAdmin.exists())

        // (б) живой владелец: файл не трогаем, вход запрещён
        val live = startSleeper()
        try {
            val line = "inst=OLDLIVE;pid=${live.pid()};pstart=${pstartOf(live.pid())};boot=${bootId()}"
            legacyAdmin.writeText("$line\n")
            val (_, liveOut) = withHelpers("legacy_live.sh", "NEW2",
                """
                _jit_admin_enter
                echo "ENTER=${'$'}?"
                """.trimIndent())
            assertEquals("вход при живом legacy-владельце запрещён", "1",
                liveOut.substringAfter("ENTER=").substringBefore("\n").trim())
            assertEquals("legacy-файл живого владельца не тронут", "$line\n", legacyAdmin.readText())
        } finally {
            live.destroyForcibly()
        }
    }

    @Test
    fun corruptParticipantEntryIsNeverRemovedWithoutProofOfDeath() {
        admDir.mkdirs()
        val corrupt = File(admDir, "n.1").apply { writeText("") }

        val (_, out) = withHelpers("corrupt.sh", "NEW3",
            """
            _jit_admin_enter
            echo "ENTER=${'$'}?"
            """.trimIndent())

        assertEquals("вход при недоказуемой записи запрещён", "1",
            out.substringAfter("ENTER=").substringBefore("\n").trim())
        assertTrue("повреждённая запись не удалена «наугад»", corrupt.exists())
        assertFalse("своей записи не осталось: ${participantContents()}",
            participantContents().any { it.contains("inst=NEW3") })
    }

    // ───────── 6. Production-путь: guard не идёт к опасному действию без владения ─────────

    @Test
    fun guardRefusesWhenSelfMarkCannotBeEstablished() {
        val guard = JitOwn.guard(
            JitOwn.Op(
                id = "op59x", tsMs = System.currentTimeMillis(), want = true,
                state = JitOwn.State.CLAIMED
            ),
            b
        )
        assertNotNull(guard)
        assertTrue("отметка identity payload проверяется, а не глушится",
            guard.contains("if ! _lock_self_mark; then"))
        assertTrue("отказ назван причиной", guard.contains("self_mark_failed"))
        assertTrue("опасная часть идёт ПОСЛЕ проверки",
            guard.indexOf("if ! _lock_self_mark; then") < guard.indexOf("START ts="))
        // Админ-секция — уникальные имена участников, а не общий файл, удаляемый по имени.
        val helpers = JitOwn.ownHelpersForTest(b)
        assertTrue("номер занимается атомарным созданием noclobber: n.<k>",
            helpers.contains("n.\${'$'}{_acs_k}") || helpers.contains("${'$'}{_adm_dir}/n."))
        assertFalse("общий admin-файл больше не удаляется по имени",
            Regex("rm -f \"?\\$\\{lock\\}\\.admin").containsMatchIn(helpers))
    }
}
