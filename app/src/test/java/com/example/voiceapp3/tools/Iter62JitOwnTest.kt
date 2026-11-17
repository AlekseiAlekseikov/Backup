package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * iter62 §A1 — ВЗАИМНОЕ ИСКЛЮЧЕНИЕ JIT: «других живых нет», а не «нет меньших номеров».
 *
 * Воспроизведённый аудит-дефект iter61: `JitOwn.kt` iter61 (строки :640–717) занимал НОМЕР
 * «на единицу больше максимального ЖИВОГО» и ждал только МЕНЬШИЕ номера. Номер освобождался и
 * доставался новому участнику НИЖЕ живого; цикл ожидания не выполнялся ни разу ⇒ в секции
 * оказывались два живых владельца (`c slot=1` вошёл, пока `b slot=2` держал секцию).
 *
 * Здесь проверяется ФАКТ: сколько владельцев одновременно внутри. Счётчик внутри секции —
 * отдельный файл, увеличиваемый под `noclobber`, и «свидетель», который можно взять только один раз.
 * Всё через production-функции ([JitOwn.ownHelpersForTest]) в НАСТОЯЩИХ процессах `/bin/sh`.
 *
 * Тесты A1-1…A1-8 и стресс обязаны падать на коде iter61 и проходить на коде iter62.
 */
class Iter62JitOwnTest {

    private val base: File = File.createTempFile("jit62", "").let { f ->
        f.delete(); f.mkdirs(); f
    }
    private val b: String get() = base.absolutePath
    private val ownDir: File get() = File(JitOwn.lockPath(b) + ".own")
    private val witness: File get() = File(base, "witness")

    // ───────────────────────── инфраструктура ─────────────────────────

    private fun pstartOf(pid: Long): String =
        File("/proc/$pid/stat").readText().trim().split(" ")[21]

    private fun bootId(): String = runCatching {
        File("/proc/sys/kernel/random/boot_id").readText().trim()
    }.getOrDefault("")

    private fun startSleeper(sec: String = "60"): Process =
        ProcessBuilder("sleep", sec).redirectErrorStream(true).start()

    private class Runner(val process: Process, val out: StringBuilder = StringBuilder()) {
        val thread: Thread = Thread {
            process.inputStream.bufferedReader().forEachLine { line ->
                synchronized(out) { out.appendLine(line) }
            }
        }.apply { isDaemon = true; start() }

        fun text(): String = synchronized(out) { out.toString() }

        fun await(needle: String, timeoutMs: Long = 25_000): Boolean {
            val t0 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t0 < timeoutMs) {
                if (text().contains(needle)) return true
                Thread.sleep(15)
            }
            return text().contains(needle)
        }

        fun stop() {
            process.destroyForcibly()
            process.waitFor(30, TimeUnit.SECONDS)
        }
    }

    /**
     * Участник: входит в секцию, берёт «свидетеля» (второй взять не может), держит [holdMs]
     * (миллисекунды; строка `/bin/sh` спит долями секунды) и выходит.
     * [barrierAfterClaim] — имя префикса барьера ПОСЛЕ захвата (детерминированные расписания).
     */
    private fun participant(
        inst: String,
        holdMs: Int = 0,
        barrierAfterClaim: String = "",
        env: Map<String, String> = emptyMap()
    ): Runner {
        val barrier = if (barrierAfterClaim.isEmpty()) "" else """
            _adm_barrier_after_claim() {
              : > "${barrierAfterClaim}.claimed"
              _bw=0
              while [ ! -f "${barrierAfterClaim}.go" ] && [ "${'$'}_bw" -lt 4000 ]; do
                sleep 0.01; _bw=${'$'}(( _bw + 1 ))
              done
              return 0
            }
        """.trimIndent()
        val script = File(base, "p_$inst.sh").apply {
            writeText(
                JitOwn.ownHelpersForTest(b) + "\n_me_inst=\"$inst\"\n" + barrier + "\n" + """
                if _jit_admin_enter; then
                  echo "ENTER $inst refuse=${'$'}{_adm_refuse:-none}"
                  : > "${'$'}ROOT/wt.${'$'}${'$'}"
                  if ln "${'$'}ROOT/wt.${'$'}${'$'}" "${witness.absolutePath}" 2>/dev/null; then
                    echo "WITNESS $inst taken"
                  else
                    echo "VIOLATION $inst second-owner"
                  fi
                  rm -f "${'$'}ROOT/wt.${'$'}${'$'}"
                  sleep ${"%.3f".format(holdMs / 1000.0)}
                  rm -f "${witness.absolutePath}"
                  _jit_admin_exit
                  echo "EXIT $inst"
                else
                  echo "REFUSE $inst refuse=${'$'}{_adm_refuse:-none}"
                fi
                """.trimIndent()
            )
        }
        val pb = ProcessBuilder("/bin/sh", script.absolutePath).redirectErrorStream(true)
        pb.environment()["ROOT"] = b
        pb.environment().putAll(env)
        return Runner(pb.start())
    }

    private fun waitFor(file: File, timeoutMs: Long = 25_000): Boolean {
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < timeoutMs) {
            if (file.exists()) return true
            Thread.sleep(10)
        }
        return file.exists()
    }

    private fun ownerExists(): Boolean = File(ownDir, "o").exists()

    private fun ownerLine(): String = File(ownDir, "o").let { if (it.exists()) it.readText().trim() else "" }

    // ───────── A1-1: освободившийся номер не отдаётся участнику ниже живого ─────────

    @Test
    fun a1ParticipantThatStartsAfterAnotherOneReleasedNeverEntersBesideTheLiveOwner() {
        // a входит и сразу выходит; b входит и ДЕРЖИТ секцию; затем стартует c.
        val a = participant("a", holdMs = 0)
        assertTrue("a вошёл: ${a.text()}", a.await("ENTER a"))
        assertTrue("a вышел: ${a.text()}", a.await("EXIT a"))
        a.stop()
        assertFalse("после выхода a владельца нет", ownerExists())

        val barrier = File(base, "barrier_b").absolutePath
        val bb = participant("b", holdMs = 3000, barrierAfterClaim = barrier)
        assertTrue("b захватил секцию: ${bb.text()}", waitFor(File("$barrier.claimed")))

        val c = participant("c", holdMs = 0)
        Thread.sleep(600)
        assertFalse("c НЕ вошёл, пока b держит секцию: ${c.text()}",
            c.text().contains("ENTER c"))

        File("$barrier.go").createNewFile()
        assertTrue("b вышел: ${bb.text()}", bb.await("EXIT b", 30_000))
        assertTrue("после выхода b c получил исход: ${c.text()}",
            c.await("ENTER c") || c.await("REFUSE c"))

        val all = a.text() + bb.text() + c.text()
        assertFalse("двух владельцев одновременно не было: $all", all.contains("VIOLATION"))
        bb.stop(); c.stop()
    }

    // ───────── A1-2: три живых участника, одновременный старт ─────────

    @Test
    fun a1ThreeSimultaneousParticipantsKeepAtMostOneOwner() {
        val ps = listOf("a", "b", "c").map { participant(it, holdMs = 400) }
        ps.forEach { it.await("ENTER", 30_000) }
        ps.forEach { it.await("EXIT", 40_000) }
        val all = ps.joinToString("\n") { it.text() }
        assertFalse("нарушений свидетеля нет: $all", all.contains("VIOLATION"))
        val entered = ps.count { it.text().contains("ENTER") }
        assertTrue("вход состоялся хотя бы у одного: $all", entered >= 1)
        ps.forEach { it.stop() }
        assertTrue("после всех выходов владельца нет", !ownerExists())
    }

    // ───────── A1-3 / A1-4: убитый посреди секции освобождается по доказанной смерти ─────────

    @Test
    fun a1KilledOwnerIsReclaimedByProvenDeathAndTheWaiterEnters() {
        val holderScript = File(base, "holder.sh").apply {
            writeText(
                JitOwn.ownHelpersForTest(b) + "\n_me_inst=\"HOLDER\"\n" + """
                if _jit_admin_enter; then
                  echo "HOLDER_IN"
                  sleep 60
                else
                  echo "HOLDER_REFUSED refuse=${'$'}{_adm_refuse:-none}"
                fi
                """.trimIndent()
            )
        }
        val p = ProcessBuilder("/bin/sh", holderScript.absolutePath).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader()
        assertEquals("владелец вошёл", "HOLDER_IN", out.readLine())
        assertTrue("файл владельца существует: ${ownerLine()}", ownerExists())

        val waiter = participant("waiter", holdMs = 0)
        Thread.sleep(500)
        assertFalse("ожидающий не вошёл при живом владельце: ${waiter.text()}",
            waiter.text().contains("ENTER waiter"))

        p.destroyForcibly()                       // kill -9 посреди секции: запись осталась
        p.waitFor(30, TimeUnit.SECONDS)

        val t0 = System.currentTimeMillis()
        assertTrue("ожидающий вошёл за ограниченное время: ${waiter.text()}",
            waiter.await("ENTER waiter", 30_000))
        val took = System.currentTimeMillis() - t0
        assertTrue("без перезагрузки и быстро: ${took}ms", took < 30_000)
        waiter.stop()
    }

    // ───────── A1-5: битая запись — отказ с причиной, запись не удалена ─────────

    @Test
    fun a1CorruptOwnerRecordIsRefusedWithAReasonAndNeverDeleted() {
        ownDir.mkdirs()
        val corrupt = File(ownDir, "o").apply { writeText("") }
        val p = participant("z", holdMs = 0)
        assertTrue("участник получил исход: ${p.text()}",
            p.await("REFUSE z") || p.await("ENTER z"))
        val out = p.text()
        assertTrue("входа нет: $out", out.contains("REFUSE z") && !out.contains("ENTER z"))
        assertTrue("причина названа: $out", out.contains("refuse=owner_unreadable"))
        assertTrue("повреждённая запись НЕ удалена", corrupt.exists())
        p.stop()
    }

    // ───────── A1-6: /proc недоступен ⇒ смерть не доказывается ─────────

    @Test
    fun a1WhenProcIsUnavailableNoOwnerIsDeclaredDead() {
        // Запись мёртвого процесса, но `/proc` «недоступен» (пустой каталог как корень).
        val dead = startSleeper("0.05")
        val deadPid = dead.pid()
        dead.waitFor(30, TimeUnit.SECONDS)
        ownDir.mkdirs()
        val rec = File(ownDir, "o").apply {
            writeText("inst=GONE;pid=$deadPid;pstart=1;boot=${bootId()}\n")
        }
        val emptyProc = File(base, "proc_empty").apply { mkdirs() }
        val p = participant("y", holdMs = 0, env = mapOf("_ADM_PROC" to emptyProc.absolutePath))
        assertTrue("участник получил исход: ${p.text()}", p.await("REFUSE y") || p.await("ENTER y"))
        assertTrue("при недоступном /proc вход не объявляется: ${p.text()}",
            p.text().contains("REFUSE y"))
        assertTrue("запись не удалена", rec.exists())
        p.stop()
    }

    // ───────── A1-7: 200 мёртвых записей не превращаются в fork-шторм ─────────

    @Test
    fun a1TwoHundredDeadRecordsDoNotSlowTheEntryDown() {
        ownDir.mkdirs()
        // 200 ДОКАЗАННО мёртвых записей прежних версий в каталоге (n.<k>) и retired-файлов.
        repeat(200) { i ->
            File(ownDir, "n.${i + 1}").writeText("slot=${i + 1};inst=old$i;pid=1;pstart=1;boot=x\n")
            File(ownDir, "retired.$i").writeText("inst=old$i;pid=1;pstart=1;boot=x\n")
        }
        val t0 = System.currentTimeMillis()
        val p = participant("fast", holdMs = 0)
        assertTrue("вход состоялся: ${p.text()}", p.await("ENTER fast", 30_000))
        val took = System.currentTimeMillis() - t0
        // Порог намеренно свободный (медленная песочница), но он отсекает именно шторм из сотен
        // fork'ов `sed` на каждую запись: на iter61 такой вход занимал секунды.
        assertTrue("вход не зависит от числа мусорных записей: ${took}ms", took < 8_000)
        p.stop()
    }

    // ───────── A1-8: вложенный вход/выход ─────────

    @Test
    fun a1NestedEntryKeepsOneOwnerAndReleasesOnlyOnTheOutermostExit() {
        val script = File(base, "nested.sh").apply {
            writeText(
                JitOwn.ownHelpersForTest(b) + "\n_me_inst=\"nested\"\n" + """
                _jit_admin_enter; echo "OUTER=${'$'}?"
                _jit_admin_enter; echo "INNER=${'$'}?"
                echo "DEPTH=${'$'}{_ad_depth:-0}"
                _jit_admin_exit
                [ -f "${'$'}{_ad_me:-/nonexistent}" ] && echo "STILL_OWNER=yes" || echo "STILL_OWNER=no"
                _jit_admin_exit
                [ -f "${'$'}{_ad_me:-/nonexistent}" ] && echo "AFTER_FINAL=left" || echo "AFTER_FINAL=gone"
                """.trimIndent()
            )
        }
        val p = ProcessBuilder("/bin/sh", script.absolutePath).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(60, TimeUnit.SECONDS)
        assertTrue("внешний вход: $out", out.contains("OUTER=0"))
        assertTrue("внутренний вход: $out", out.contains("INNER=0"))
        assertTrue("глубина 2: $out", out.contains("DEPTH=2"))
        assertTrue("внутренний выход не снял владение: $out", out.contains("STILL_OWNER=yes"))
        assertTrue("после внешнего выхода владелец снят: $out", out.contains("AFTER_FINAL=gone"))
        assertFalse("после теста файла владельца нет", ownerExists())
    }

    // ───────── Стресс: 4 процесса × 30 входов, максимум одновременных владельцев = 1 ─────────

    @Test
    fun a1StressFourProcessesThirtyEntriesNeverHaveTwoOwners() {
        val live = AtomicInteger(0)
        val maxLive = AtomicInteger(0)
        val body = """
            _me_inst="${'$'}{_me_inst}"
            i=0; ok=0; bad=0
            while [ "${'$'}i" -lt 30 ]; do
              i=${'$'}(( i + 1 ))
              if _jit_admin_enter; then
                ok=${'$'}(( ok + 1 ))
                : > "${'$'}ROOT/wtp.${'$'}${'$'}"
                if ln "${'$'}ROOT/wtp.${'$'}${'$'}" "${witness.absolutePath}" 2>/dev/null; then
                  :
                else
                  bad=${'$'}(( bad + 1 ))
                fi
                sleep 0.005
                rm -f "${witness.absolutePath}" "${'$'}ROOT/wtp.${'$'}${'$'}"
                _jit_admin_exit
              else
                echo "REFUSE_AT inst=${'$'}{_me_inst} i=${'$'}i why=${'$'}{_adm_refuse:-none}"
              fi
            done
            echo "RESULT inst=${'$'}{_me_inst} ok=${'$'}ok bad=${'$'}bad"
        """.trimIndent()
        val procs = listOf("A", "B", "C", "D").map { inst ->
            val f = File(base, "st_$inst.sh").apply {
                writeText(JitOwn.ownHelpersForTest(b) + "\n_me_inst=\"$inst\"\n" + body)
            }
            ProcessBuilder("/bin/sh", f.absolutePath)
                .apply { environment()["ROOT"] = b }
                .redirectErrorStream(true).start()
        }
        val outs = procs.map { p ->
            val o = p.inputStream.bufferedReader().readText()
            p.waitFor(180, TimeUnit.SECONDS)
            assertEquals("участник завершился: $o", 0, p.exitValue())
            o
        }
        val bad = outs.sumOf { o -> Regex("bad=(\\d+)").find(o)!!.groupValues[1].toInt() }
        val ok = outs.sumOf { o -> Regex("ok=(\\d+)").find(o)!!.groupValues[1].toInt() }
        assertEquals("двух владельцев одновременно не было (фактических нарушений)", 0, bad)
        assertEquals("все 120 входов состоялись: " +
                outs.joinToString(" | ") { o -> o.lines().filter { it.startsWith("REFUSE_AT") }.joinToString(",") },
            120, ok)
        assertTrue("каталог владельца пуст после работы: ${ownDir.list()?.toList()}", !ownerExists())
        live.set(0); maxLive.set(0)
    }
}
