package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * iter58 §2.1 — ADMIN-LOCK И ЖИЗНЬ ВЛАДЕЛЬЦА (регрессия на дефекты iter57).
 *
 * Дефекты iter57 (независимый аудит, приложение A §1–2):
 *  1. `JitOwn.kt:320–358` — функция с именем `_jit_owner_dead` возвращала **0 для ЖИВОГО**
 *     владельца, а вызывающие проверяли её БЕЗ отрицания ⇒ admin-файл живого владельца
 *     удалялся, и вся сериализация смены lock рушилась;
 *  2. `JitOwn.kt:518–522` — `_jit_release` удалял lock обычным `rm -f` вне admin-секции:
 *     между чтением и удалением претендент успевал поставить СВОЙ lock, и release удалял ЕГО;
 *  3. `JitOwn.kt:1021 vs 1040` — `_lock_self_mark` вызывался раньше своего определения;
 *  4. `JitOwn.kt:394 vs 582/984` — `_jit_may_takeover` существовал, но НЕ вызывался: решение о
 *     перехвате принималось по одному признаку смерти.
 *
 * Здесь всё исполняется НАСТОЯЩИМ `/bin/sh` на тексте production-функций
 * ([JitOwn.ownHelpersForTest]) и настоящими процессами: `sleep` как «живой владелец», реальные
 * `/proc/<pid>/stat` (поле 22 — время старта) — то есть проверяется identity, а не лог.
 *
 * Чего тесты НЕ доказывают: реальный Android/toybox, реальный `zygote` и ГУ. Это Linux-`/bin/sh`
 * + `/proc` песочницы; в отчёте отмечено как 🟡 до испытания владельцем.
 */
class Iter58JitLockTest {

    private val base: File = File.createTempFile("jit58", "").let { f ->
        f.delete(); f.mkdirs(); f
    }
    private val b: String get() = base.absolutePath
    private val lock: File get() = File(JitOwn.lockPath(b))
    private val admin: File get() = File(JitOwn.lockPath(b) + ".admin")

    // ─────────────── инфраструктура: настоящий sh и настоящие процессы ───────────────

    private fun sh(script: String, name: String): Pair<Int, String> {
        val f = File(base, name)
        f.writeText(script)
        val p = ProcessBuilder("/bin/sh", f.absolutePath).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(60, TimeUnit.SECONDS)
        return p.exitValue() to out
    }

    /** Текст production-функций + тело теста, одним настоящим `sh`. */
    private fun withHelpers(name: String, meInst: String, body: String): Pair<Int, String> =
        sh(JitOwn.ownHelpersForTest(b) + "\n_me_inst=\"$meInst\"\n" + body, name)

    private fun pstartOf(pid: Long): String =
        File("/proc/$pid/stat").readText().trim().split(" ")[21]

    private fun startSleeper(sec: String = "5"): Process =
        ProcessBuilder("sleep", sec).redirectErrorStream(true).start()

    private fun lockText(): String = if (lock.exists()) lock.readText().trim() else ""

    private fun adminText(): String = if (admin.exists()) admin.readText().trim() else ""

    private fun writeAdmin(inst: String, pid: Long, pstart: String, boot: String = bootId()) {
        admin.writeText("inst=$inst;pid=$pid;pstart=$pstart;boot=$boot\n")
    }

    private fun bootId(): String = runCatching {
        File("/proc/sys/kernel/random/boot_id").readText().trim()
    }.getOrDefault("")

    // ─────────── 1. Живой владелец admin-файла: файл НЕ снимается ───────────

    @Test
    fun adminFileOfALivingOwnerIsNeverRemoved() {
        val live = startSleeper()
        try {
            val ps = pstartOf(live.pid())
            writeAdmin("OWNER-LIVE", live.pid(), ps)
            val before = adminText()

            val (rc, out) = withHelpers("admin_live.sh", "CONTENDER",
                """
                _jit_admin_enter
                echo "ENTER_RC=${'$'}?"
                """.trimIndent())

            assertTrue("тело теста исполнилось: $out", out.contains("ENTER_RC="))
            assertEquals("admin занят ЖИВЫМ владельцем — вход невозможен", 1,
                out.substringAfter("ENTER_RC=").substringBefore("\n").trim().toInt())
            assertEquals("файл живого владельца не тронут", before, adminText())
            assertTrue("живой процесс действительно жив", live.isAlive)
        } finally {
            live.destroyForcibly()
        }
    }

    // ─────────── 2. Доказанно мёртвый владелец: admin снимается, вход есть ───────────

    @Test
    fun adminFileOfADeadOwnerIsTakenOver() {
        val dead = startSleeper("0.05")
        val pid = dead.pid()
        dead.waitFor(30, TimeUnit.SECONDS)
        assertFalse("процесс владельца завершился", dead.isAlive)
        writeAdmin("OWNER-DEAD", pid, "1")   // pstart намеренно не совпадает: pid уже чужой

        val (_, out) = withHelpers("admin_dead.sh", "NEWCOMER",
            """
            _jit_admin_enter
            echo "ENTER_RC=${'$'}?"
            """.trimIndent())

        assertEquals("вход под доказанно мёртвым владельцем разрешён", 0,
            out.substringAfter("ENTER_RC=").substringBefore("\n").trim().toInt())
        // iter59 §5.2: admin-секция больше не один общий файл, а каталог с УНИКАЛЬНЫМИ записями
        // участников. Мёртвый legacy-файл при этом переносится (а не удаляется по имени), то есть
        // проверка «вход есть» остаётся, а «в файле теперь наша строка» становится «наша запись
        // участника существует внутри секции».
        assertFalse("legacy-файл мёртвого владельца больше не блокирует", admin.exists())
        // iter62 §A1: владелец секции — файл `<lock>.own/o` (номеров `n.<k>` больше нет).
        val own = File(JitOwn.lockPath(b) + ".own")
        assertTrue("каталог владельца создан", own.isDirectory)
    }

    // ─────────── 3. PID reuse ≠ живой владелец; живой PID с той же identity ≠ мёртвый ───────────

    @Test
    fun pidReuseIsDeathButTheSameStartTimeIsALivingOwner() {
        val live = startSleeper()
        try {
            val addr = "inst=OWNER;pid=${live.pid()};pstart=${pstartOf(live.pid())};boot=${bootId()}"

            // (а) identity совпала целиком → владелец ЖИВ: rc 1 = «смерть НЕ доказана».
            val (_, alive) = withHelpers("dead_proof_live.sh", "X",
                """
                if _jit_death_proven "$addr"; then echo "PROVEN=yes"; else echo "PROVEN=no"; fi
                """.trimIndent())

            assertTrue("вывод получен: $alive", alive.contains("PROVEN="))
            assertEquals("живой владелец НЕ признан мёртвым", "no",
                alive.substringAfter("PROVEN=").substringBefore("\n").trim())

            // (б) тот же pid, но время старта другое → это ДРУГОЙ процесс (pid переиспользован).
            val reused = "inst=OWNER;pid=${live.pid()};pstart=1;boot=${bootId()}"
            val (_, deadOut) = withHelpers("dead_proof_reuse.sh", "X",
                """
                if _jit_death_proven "$reused"; then echo "PROVEN=yes"; else echo "PROVEN=no"; fi
                """.trimIndent())

            assertEquals("переиспользованный pid = смерть доказана", "yes",
                deadOut.substringAfter("PROVEN=").substringBefore("\n").trim())

            // (в) другая загрузка — смерть доказана, даже если pid существует.
            val otherBoot = "inst=OWNER;pid=${live.pid()};pstart=${pstartOf(live.pid())};boot=other-boot"
            val (_, bootOut) = withHelpers("dead_proof_boot.sh", "X",
                """
                if _jit_death_proven "$otherBoot"; then echo "PROVEN=yes"; else echo "PROVEN=no"; fi
                """.trimIndent())

            assertEquals("другая загрузка = смерть доказана", "yes",
                bootOut.substringAfter("PROVEN=").substringBefore("\n").trim())
        } finally {
            live.destroyForcibly()
        }
    }

    // ─────────── 4. release не удаляет чужой живой lock ───────────

    @Test
    fun releaseNeverDeletesAnotherOwnersLock() {
        lock.writeText("op=OPA;ts_ms=1;want=1;inst=OWNER;pid=1;pstart=1;boot=x\n")
        val before = lockText()

        val (_, out) = withHelpers("release_foreign.sh", "STRANGER",
            """
            _jit_release
            echo "REL_RC=${'$'}?"
            """.trimIndent())

        assertEquals("release не считает чужой lock своим", "1",
            out.substringAfter("REL_RC=").substringBefore("\n").trim())
        assertEquals("чужой lock не тронут", before, lockText())
        assertTrue("lock всё ещё принадлежит владельцу", lockText().contains("inst=OWNER"))
    }

    // ─────────── 5. release × takeover: ровно один владелец (барьер) ───────────

    @Test
    fun releaseAndTakeoverLeaveExactlyOneOwner() {
        lock.writeText("op=OPA;ts_ms=1;want=1;inst=OWNER;pid=1;pstart=1;boot=x\n")
        val barrier = File(base, "barrier").apply { createNewFile() }

        // Претендент ждёт освобождения имени, затем ставит СВОЙ lock (production `_jit_take_lock`).
        val contender = ProcessBuilder("/bin/sh", "-c",
            "while [ -f '$barrier' ]; do sleep 0.01; done; exit 0").redirectErrorStream(true).start()
        val script = File(base, "contender.sh")
        script.writeText(JitOwn.ownHelpersForTest(b) + "\n_me_inst=\"CONTENDER\"\n" +
            """
            while [ -f "$barrier" ]; do sleep 0.01; done
            if _jit_take_lock "op=OPB;inst=CONTENDER"; then echo "TOOK=yes"; else echo "TOOK=no"; fi
            """.trimIndent())
        val taking = ProcessBuilder("/bin/sh", script.absolutePath)
            .redirectErrorStream(true).start()
        Thread.sleep(50)

        // Владелец освобождает СВОЙ lock и снимает барьер ровно после этого.
        val (_, relOut) = withHelpers("release_own.sh", "OWNER",
            """
            _jit_release
            echo "REL_RC=${'$'}?"
            rm -f "$barrier"
            """.trimIndent())
        assertEquals("свой lock снят владельцем", "0",
            relOut.substringAfter("REL_RC=").substringBefore("\n").trim())

        val takeOut = taking.inputStream.bufferedReader().readText()
        taking.waitFor(60, TimeUnit.SECONDS)
        contender.waitFor(60, TimeUnit.SECONDS)

        assertEquals("претендент поставил ровно один lock", "yes",
            takeOut.substringAfter("TOOK=").substringBefore("\n").trim())
        assertEquals("после гонки владелец ровно один", "op=OPB;inst=CONTENDER", lockText())
        assertNotEquals("lock не остался пустым/старым", "op=OPA;ts_ms=1;want=1;inst=OWNER;pid=1;pstart=1;boot=x",
            lockText())
        assertFalse("admin-файл не остался висеть", admin.exists())
    }

    // ─────────── 6. Crash после захвата lock не блокирует следующий claim ───────────

    @Test
    fun crashAfterTakingTheLockDoesNotBlockTheNextClaim() {
        // Настоящий shell берёт lock и падает ДО записи своего terminal (kill -9 себя).
        val crashScript = File(base, "crash.sh")
        crashScript.writeText(JitOwn.ownHelpersForTest(b) + "\n_me_inst=\"CRASHED\"\n" +
            """
            _jit_take_lock "op=OPX;ts_ms=1;want=1;inst=CRASHED;pid=999999;pstart=1;boot=died" || exit 3
            kill -9 ${'$'}${'$'}
            """.trimIndent())
        val crashed = ProcessBuilder("/bin/sh", crashScript.absolutePath)
            .redirectErrorStream(true).start()
        crashed.waitFor(30, TimeUnit.SECONDS)
        assertTrue("lock остался от упавшего владельца", lockText().contains("inst=CRASHED"))
        assertFalse("terminal упавшего не создан", File(JitOwn.termPath("OPX", b)).exists())

        // Новая операция идёт production-путём claim: смерть доказана (pid 999999 нет) → перехват.
        val ts = 1_700_900_000_000L
        val op = JitOwn.Op("OPNEW", ts, true, JitOwn.State.CLAIMED,
            identity = JitOwn.ClaimIdentity(JitOwn.newInstance("OPNEW", ts, nonce = 7), "", "", ""),
            seq = 2L)
        val (_, out) = sh(JitOwn.claimCmd(op, b), "claim_after_crash.sh")

        assertTrue("claim после crash принят: $out", out.contains("CL_STATUS=taken"))
        assertTrue("перехват назван причиной: $out",
            out.contains("CL_TAKEOVER") && out.contains("owner_dead"))
        // Claim-команда держит lock только на время записи и снимает свой же (см. `_jit_release`
        // в конце claimCmd), поэтому доказательство — записанный claim, а не оставшийся lock.
        val claimNow = File(JitOwn.claimPath(b)).let { if (it.exists()) it.readText().trim() else "" }
        assertTrue("claim принадлежит новому владельцу: $claimNow", claimNow.contains("op=OPNEW"))
        assertFalse("чужая блокировка не осталась висеть: ${lockText()}", lock.exists())
        assertFalse("доказательств мёртвого не осталось в admin", admin.exists())
    }

    // ─────────── 7. Два настоящих shell одновременно: один победитель, один side effect ───────

    @Test
    fun twoRealShellsWithABarrierYieldExactlyOneOwner() {
        val gate = File(base, "gate").apply { createNewFile() }
        fun racer(name: String, tag: String) = ProcessBuilder("/bin/sh", "-c",
            "while [ -f '$gate' ]; do sleep 0.005; done; " +
                "if ln '$b/start.$tag' '$b/claim.$tag' 2>/dev/null; then " +
                "printf 'op=$tag;inst=$tag' > '$b/via.$tag'; fi; exit 0"
        ).redirectErrorStream(true).start()
        val a = racer("A", "A")
        val c = racer("C", "C")
        Thread.sleep(60)
        gate.delete()
        a.waitFor(60, TimeUnit.SECONDS); c.waitFor(60, TimeUnit.SECONDS)

        // Барьер снят одновременно; имя `start.*` разное, поэтому проверяем эксклюзивность иначе:
        // оба претендуют на ОДИН файл-«lock» через `ln` (атомарное создание) — выигрывает один.
        val mine = File(base, "exclusive")
        val s1 = ProcessBuilder("/bin/sh", "-c",
            "if ln -s x '$mine' 2>/dev/null; then echo WIN; else echo LOSE; fi").start()
        val s2 = ProcessBuilder("/bin/sh", "-c",
            "if ln -s x '$mine' 2>/dev/null; then echo WIN; else echo LOSE; fi").start()
        val o1 = s1.inputStream.bufferedReader().readText().trim()
        val o2 = s2.inputStream.bufferedReader().readText().trim()
        s1.waitFor(); s2.waitFor()
        assertEquals("ровно один победитель на атомарном создании: $o1 / $o2", 1,
            listOf(o1, o2).count { it == "WIN" })

        // И production-путь: два claim одновременно — один taken, второй busy.
        val ts = 1_700_910_000_000L
        fun op(id: String) = JitOwn.Op(id, ts, true, JitOwn.State.CLAIMED,
            identity = JitOwn.ClaimIdentity(JitOwn.newInstance(id, ts, nonce = id.hashCode()), "", "", ""),
            seq = 5L)
        val p1 = ProcessBuilder("/bin/sh", File(base, "c1.sh").apply {
            writeText(JitOwn.claimCmd(op("OPA"), b))
        }.absolutePath).redirectErrorStream(true).start()
        val p2 = ProcessBuilder("/bin/sh", File(base, "c2.sh").apply {
            writeText(JitOwn.claimCmd(op("OPC"), b))
        }.absolutePath).redirectErrorStream(true).start()
        val r1 = p1.inputStream.bufferedReader().readText()
        val r2 = p2.inputStream.bufferedReader().readText()
        p1.waitFor(60, TimeUnit.SECONDS); p2.waitFor(60, TimeUnit.SECONDS)

        val taken = listOf(r1, r2).count { it.contains("CL_STATUS=taken") }
        assertEquals("одновременные claim: ровно один taken ($r1 | $r2)", 1, taken)
        // Проигравший обязан назвать причину явно и НЕ заявлять успех. Допустимые исходы:
        // busy (занято живым владельцем), stale (под lock обнаружено более новое намерение),
        // failed (запись/чтение не подтвердились). «Тихого успеха» быть не может.
        val loser = if (r1.contains("CL_STATUS=taken")) r2 else r1
        assertTrue("проигравший назвал причину без успеха: $loser",
            loser.contains("CL_STATUS=busy") || loser.contains("CL_STATUS=stale") ||
                loser.contains("CL_STATUS=failed"))
        assertFalse("проигравший не писал свой claim: $loser",
            loser.contains("CL_STATUS=taken"))
        val claimNow = File(JitOwn.claimPath(b)).let { if (it.exists()) it.readText().trim() else "" }
        val winnerOp = if (r1.contains("CL_STATUS=taken")) "op=OPA" else "op=OPC"
        assertTrue("claim принадлежит победителю ($winnerOp): $claimNow", claimNow.contains(winnerOp))
        assertFalse("транзиентный lock снят после claim: ${lockText()}", lock.exists())
    }
}
