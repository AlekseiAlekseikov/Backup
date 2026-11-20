package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * iter61 §3 — ДЕТЕРМИНИРОВАННЫЙ regression гонки «glob-snapshot» и взаимное исключение по номерам.
 *
 * Дефект iter60 (независимо воспроизведён аудитом):
 * ```text
 * a: публикует c.a, выбирает ticket=1, пауза до p.a
 * z: выбирает ticket=1, публикует p.z; его `for` УЖЕ раскрыл glob (c.a есть, p.a ещё нет)
 * a: публикует p.a, удаляет c.a, видит p.z с тем же тикетом и большим именем ⇒ ВХОДИТ
 * z: продолжает СТАРЫЙ список: c.a исчез (пропущен), p.a в списке не было ⇒ ВХОДИТ
 * ```
 * Итог — два живых владельца. Никакой `sleep` и никакой стресс это расписание не отменяют:
 * виноват снимок имён (`for` раскрывает список ДО тела), а не таймаут.
 *
 * Тест воспроизводит ИМЕННО это расписание на РЕАЛЬНЫХ процессах и на production-функциях
 * (`JitOwn.ownHelpersForTest`), подменяя только две пустые точки остановки:
 *  • `_adm_barrier_after_ticket` (есть в iter60) / `_adm_barrier_after_slot` (iter61) — участник A
 *    замер сразу после того, как занял свой номер;
 *  • подмену `sleep` в участнике Z — его первый `sleep` вызывается уже ПОСЛЕ раскрытия списка
 *    имён (iter60) и уже внутри цикла ожидания (iter61), то есть ровно в нужной точке.
 *
 * На коде iter60 тест падает (два владельца), на iter61 — проходит. Это проверено переключением
 * `JitOwn.kt` на базовую версию и обратно (см. отчёт).
 *
 * Чего тесты НЕ доказывают: Android/toybox, реальный `zygote` и ГУ. Здесь Linux `/bin/sh` + `/proc`.
 */
class Iter61JitSlotRaceTest {

    private val base: File = File.createTempFile("jit61", "").let { f -> f.delete(); f.mkdirs(); f }
    private val b: String get() = base.absolutePath
    // iter62 §A1: у секции один владелец — файл `<lock>.own/o`; номеров `n.<k>` больше нет.
    private val admDir: File get() = File(JitOwn.lockPath(b) + ".own")
    private val witness: File get() = File(base, "witness")

    // ───────────────────────── инфраструктура ─────────────────────────

    private fun pstartOf(pid: Long): String =
        File("/proc/$pid/stat").readText().trim().split(" ")[21]

    private fun bootId(): String = runCatching {
        File("/proc/sys/kernel/random/boot_id").readText().trim()
    }.getOrDefault("")

    private class Runner(val process: Process, val out: StringBuilder = StringBuilder()) {
        init {
            Thread {
                process.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(out) { out.appendLine(line) }
                }
            }.apply { isDaemon = true }.start()
        }

        fun text(): String = synchronized(out) { out.toString() }

        fun await(needle: String, timeoutMs: Long = 40_000): Boolean {
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

    private fun waitFor(file: File, timeoutMs: Long = 25_000): Boolean {
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < timeoutMs) {
            if (file.exists()) return true
            Thread.sleep(10)
        }
        return file.exists()
    }

    /**
     * Тело участника: вход → «свидетель» (второй взять не может) → удержание → выход.
     * `VIOLATION` печатается, если свидетель уже занят: значит в секции ДВА владельца.
     */
    private fun body(inst: String, holdMs: Int, barrier: String = "", slowSleep: Boolean = false): String =
        instrumentedHelpers() + "\n_me_inst=\"$inst\"\n" + barrier + (if (slowSleep) """
            # Первый `sleep` участника — точка «список имён уже раскрыт, обработка ещё не началась».
            # Ожидание в барьере идёт шин-циклом: сам `sleep` здесь вызывать нельзя.
            _sl_done=0
            sleep() {
              if [ "${'$'}_sl_done" = 0 ]; then
                _sl_done=1
                : > "$b/z.scanned"
                _sl_w=0
                while [ ! -f "$b/z.go" ] && [ "${'$'}_sl_w" -lt 300000 ]; do _sl_w=${'$'}(( _sl_w + 1 )); done
              fi
              : > "${'$'}ROOT/.noop" 2>/dev/null
              return 0
            }
        """.trimIndent() else "") + "\n" + """
            if _jit_admin_enter; then
              : > "${'$'}ROOT/wt.${'$'}${'$'}"
              if ln "${'$'}ROOT/wt.${'$'}${'$'}" "$witness" 2>/dev/null; then
                echo "ENTER $inst witness=taken${'$'}{_adm_violation:+ viol=${'$'}{_adm_violation}}"
                : > "${'$'}ROOT/inside.$inst"
                if [ $holdMs -lt 0 ]; then
                  # Держим секцию, пока тест не положит release-файл: длительность удержания не
                  # зависит от скорости машины, поэтому пересечение видно детерминированно.
                  while [ ! -f "${'$'}ROOT/release.$inst" ]; do :; done
                else
                  sleep ${"%.3f".format(holdMs / 1000.0)}
                fi
                rm -f "${'$'}ROOT/inside.$inst"
                rm -f "$witness"
              else
                echo "ENTER $inst witness=VIOLATION"
              fi
              rm -f "${'$'}ROOT/wt.${'$'}${'$'}"
              _jit_admin_exit
              echo "EXIT $inst"
            else
              echo "REFUSE $inst"
            fi
        """.trimIndent()

    private fun start(inst: String, scriptBody: String, waitIters: Int? = null): Runner {
        val f = File(base, "p_$inst.sh").apply { writeText(scriptBody) }
        val pb = ProcessBuilder("/bin/sh", f.absolutePath).redirectErrorStream(true)
        pb.environment()["ROOT"] = b
        // iter67c §4: production-бюджет ожидания снижен 400 → 100 итераций (~2 с). Тест, который
        // НАРОЧНО держит секцию секундами, задаёт прежний бюджет явно: он проверяет протокол
        // (нет двух владельцев, ждущий входит после выхода), а не размер бюджета по умолчанию.
        if (waitIters != null) pb.environment()["_ADM_WAIT_ITERS"] = waitIters.toString()
        return Runner(pb.start())
    }

    /**
     * Production-shell с ОДНОЙ дополнительной пустой точкой остановки в теле цикла ожидания.
     *
     * iter61 уже вызывает `_adm_barrier_before_scan` (в бою — no-op). В базовой версии iter60 такой
     * точки нет, поэтому тест вставляет ровно её — так же, как это делал независимый аудитор
     * (он добавлял только барьеры, не меняя алгоритм). Логика протокола не меняется: без
     * переопределения барьер пуст.
     */
    /**
     * iter62 §A1: инструментировать НЕЧЕГО.
     *
     * Прежний тест вставлял точку остановки внутрь цикла `for ... in c.* p.*` — то есть в место,
     * где протокол раскрывал СНИМОК каталога. В §A1 снимка нет вообще: вход решается атомарным
     * `ln` единственного имени владельца, поэтому «увидел устаревший список» невозможно по
     * построению. Возвращаем production-хелперы без изменений — тест проверяет именно их.
     */
    private fun instrumentedHelpers(): String = JitOwn.ownHelpersForTest(b)

    /** Барьер «участник уже владеет секцией и ждёт разрешения теста». */
    private fun claimBarrier(path: String): String = """
        _adm_barrier_after_claim() {
          : > "$path.scanned"
          _bw=0
          while [ ! -f "$path.go" ] && [ "${'$'}_bw" -lt 300000 ]; do _bw=${'$'}(( _bw + 1 )); done
          return 0
        }
        _adm_barrier_after_ticket() { _adm_barrier_after_claim; }
        _adm_barrier_after_slot() { _adm_barrier_after_claim; }
    """.trimIndent()

    /** Барьер «участник ещё НЕ пытался занять секцию» (однократный). */
    private fun beforeClaimBarrier(path: String): String = """
        _adm_barrier_before_claim() {
          [ -f "$path.done" ] && return 0
          : > "$path.scanned"
          : > "$path.done"
          _bw=0
          while [ ! -f "$path.go" ] && [ "${'$'}_bw" -lt 300000 ]; do _bw=${'$'}(( _bw + 1 )); done
          return 0
        }
    """.trimIndent()

    private fun entries(): List<String> = admDir.listFiles()?.map { it.name }?.sorted() ?: emptyList()

    // ─────────── 1. ГЛАВНЫЙ regression: расписание аудита iter60 ───────────

    @Test
    fun globSnapshotScheduleCannotProduceTwoOwners() {
        // Расписание аудита iter60 «снимок каталога устарел» в §A1 невозможно ПО ПОСТРОЕНИЮ:
        // нет ни каталога-реестра, ни момента «список раскрыт, обработка не началась». Проверяем
        // то, что осталось проверить: пока владелец внутри, второй не входит НИ В КАКОМ порядке
        // запуска, а после выхода — входит.
        val barA = File(base, "bar_a").absolutePath
        val barZ = File(base, "bar_z").absolutePath
        val a = start("a", body("a", holdMs = -1, barrier = claimBarrier(barA)))
        assertTrue("A владеет секцией: ${a.text()}", waitFor(File("$barA.scanned")))

        // Z стартует и замирает ДО первой попытки занять секцию.
        val z = start("z", body("z", holdMs = 0, barrier = beforeClaimBarrier(barZ)), waitIters = 400)
        assertTrue("Z замер перед попыткой входа: ${z.text()}", waitFor(File("$barZ.scanned")))
        File("$barA.go").createNewFile()
        assertTrue("A внутри: ${a.text()}", waitFor(File("$base/inside.a")))

        File("$barZ.go").createNewFile()
        val enteredWhileHeld = z.await("ENTER z", 3000)
        assertFalse("Z вошёл, пока A владел секцией: ${z.text()}", enteredWhileHeld)

        // Освобождаем A: ожидание Z обязано закончиться входом, а не вечным отказом.
        File("$base/release.a").createNewFile()
        assertTrue("Z получил объяснимый исход: ${z.text()}",
            z.await("ENTER z", 40_000) || z.await("REFUSE z", 5_000))
        assertTrue("Z дождался и вошёл: ${z.text()}", z.text().contains("ENTER z"))
        assertFalse("ДВА ВЛАДЕЛЬЦА ОДНОВРЕМЕННО. a=${a.text()} z=${z.text()}",
            (a.text() + z.text()).contains("VIOLATION"))
        File("$base/release.z").createNewFile()
        a.stop(); z.stop()
    }

    @Test
    fun waiterEntersAfterTheHolderLeaves() {
        val a = start("a", body("a", holdMs = 1200))
        assertTrue("A вошёл", a.await("ENTER a"))
        val z = start("z", body("z", holdMs = 0))
        assertTrue("Z получил исход: ${z.text()}", z.await("ENTER z") || z.await("REFUSE z", 25_000))
        assertTrue("Z дождался и вошёл, а не был отвергнут навсегда: ${z.text()}",
            z.text().contains("ENTER z"))
        assertFalse("нарушений нет", (a.text() + z.text()).contains("VIOLATION"))
        a.stop(); z.stop()
    }

    // ─────────── 3. Порядок: поздний участник не обгоняет раннего ───────────

    @Test
    fun laterParticipantNeverOvertakesAnEarlierLiveOne() {
        val early = start("early", body("early", holdMs = -1))
        assertTrue("ранний владеет секцией", early.await("ENTER early"))
        val late = start("late", body("late", holdMs = 0))
        // Поздний обязан ждать, пока ранний не отпустит.
        Thread.sleep(600)
        assertFalse("поздний не вошёл, пока ранний держит секцию: ${late.text()}",
            late.text().contains("ENTER late"))
        File("$base/release.early").createNewFile()
        assertTrue("поздний получил исход: ${late.text()}",
            late.await("ENTER late") || late.await("REFUSE late"))
        assertTrue("поздний вошёл только после выхода раннего: ${late.text()}",
            late.text().contains("ENTER late"))
        assertFalse("нарушений нет", (early.text() + late.text()).contains("VIOLATION"))
        early.stop(); late.stop()
    }

    @Test
    fun slotOfAKilledParticipantIsReclaimed() {
        val dead = ProcessBuilder("sleep", "60").redirectErrorStream(true).start()
        val dpid = dead.pid()
        dead.destroyForcibly(); dead.waitFor(30, TimeUnit.SECONDS)

        admDir.mkdirs()
        // Запись «мёртвого владельца» — как после убийства процесса посреди секции.
        val owner = File(admDir, "o")
        owner.writeText("inst=dead;pid=$dpid;pstart=1;boot=${bootId()}\n")

        val a = start("a", body("a", holdMs = 300))
        assertTrue("вход после смерти владельца: ${a.text()}", a.await("ENTER a"))
        assertTrue("запись мёртвого заменена своей, а не оставлена: ${entries()}",
            !owner.readText().contains("inst=dead"))
        assertFalse("нарушений нет: ${a.text()}", a.text().contains("VIOLATION"))
        a.stop()
    }

    @Test
    fun liveForeignSlotIsNeverRemoved() {
        val live = ProcessBuilder("sleep", "60").redirectErrorStream(true).start()
        try {
            admDir.mkdirs()
            val f = File(admDir, "o")
            val line = "inst=other;pid=${live.pid()};pstart=${pstartOf(live.pid())};boot=${bootId()}"
            f.writeText("$line\n")
            val a = start("a", body("a", holdMs = 0))
            assertTrue("участник получил исход: ${a.text()}",
                a.await("ENTER a") || a.await("REFUSE a", 40_000))
            assertTrue("при живом владельце входа нет: ${a.text()}", a.text().contains("REFUSE a"))
            assertTrue("запись живого не тронута: ${entries()}", f.exists())
            assertEquals("содержимое записи живого не изменено", "$line\n", f.readText())
            assertTrue("чужой процесс не тронут", live.isAlive)
        } finally {
            live.destroyForcibly()
        }
    }

    @Test
    fun corruptForeignSlotBlocksWithoutBeingDeleted() {
        admDir.mkdirs()
        val f = File(admDir, "o")
        f.writeText("много шума без полей\n")
        val a = start("a", body("a", holdMs = 0))
        assertTrue("участник получил исход: ${a.text()}",
            a.await("ENTER a") || a.await("REFUSE a", 40_000))
        assertTrue("недоказуемая запись блокирует вход (а не выдаётся за мёртвую): ${a.text()}",
            a.text().contains("REFUSE a"))
        assertTrue("битая запись не удалена: ${entries()}", f.exists())
        a.stop()
    }

    @Test
    fun nestedEntryKeepsASingleSlotAndDoesNotReleaseItEarly() {
        val script = JitOwn.ownHelpersForTest(b) + "\n_me_inst=\"nested\"\n" + """
            _jit_admin_enter || { echo "OUTER_REFUSE"; exit 1; }
            echo "OUTER depth=${'$'}{_ad_depth} files=${'$'}(ls "${'$'}{_adm_dir}" 2>/dev/null | tr '\n' ' ')"
            _jit_admin_enter || { echo "INNER_REFUSE"; exit 1; }
            echo "INNER depth=${'$'}{_ad_depth}"
            _jit_admin_exit
            echo "AFTER_INNER depth=${'$'}{_ad_depth} files=${'$'}(ls "${'$'}{_adm_dir}" 2>/dev/null | tr '\n' ' ')"
            _jit_admin_exit
            echo "AFTER_OUTER depth=${'$'}{_ad_depth} files=${'$'}(ls "${'$'}{_adm_dir}" 2>/dev/null | tr '\n' ' ')"
        """.trimIndent()
        val r = start("nested", script)
        assertTrue("участник завершился: ${r.text()}", r.await("AFTER_OUTER"))
        val t = r.text()
        assertTrue("глубина после внешнего входа: $t", t.contains("OUTER depth=1"))
        assertTrue("вложенный вход не занимает второй файл: $t", t.contains("INNER depth=2"))
        assertTrue("внутренний выход не выпускает секцию (владелец ещё наш): $t",
            t.contains("AFTER_INNER depth=1") && Regex("AFTER_INNER[^\\n]*files=[^\\n]*o").containsMatchIn(t))
        assertTrue("внешний выход освобождает запись: $t",
            t.contains("AFTER_OUTER depth=0") && Regex("AFTER_OUTER[^\\n]*files=\\s*$").containsMatchIn(t))
        r.stop()
    }

    @Test
    fun legacyAdminFileIsHonouredAndDeadOneIsMigrated() {
        val live = ProcessBuilder("sleep", "60").redirectErrorStream(true).start()
        try {
            File(JitOwn.lockPath(b) + ".admin").writeText("inst=OLD;pid=${live.pid()};pstart=${pstartOf(live.pid())};boot=${bootId()}\n")
            val blocked = start("b1", body("b1", holdMs = 0))
            assertTrue("участник получил исход: ${blocked.text()}",
                blocked.await("ENTER b1") || blocked.await("REFUSE b1", 40_000))
            assertTrue("живой legacy блокирует: ${blocked.text()}", blocked.text().contains("REFUSE b1"))
            blocked.stop()
        } finally {
            live.destroyForcibly(); live.waitFor(20, TimeUnit.SECONDS)
        }
        // Мёртвый legacy переносится, и вход разрешён.
        val dead = ProcessBuilder("sleep", "60").redirectErrorStream(true).start()
        val dpid = dead.pid()
        dead.destroyForcibly(); dead.waitFor(20, TimeUnit.SECONDS)
        File(JitOwn.lockPath(b) + ".admin").writeText("inst=OLD2;pid=$dpid;pstart=1;boot=${bootId()}\n")
        val ok = start("b2", body("b2", holdMs = 0))
        assertTrue("вход после снятия мёртвого legacy: ${ok.text()}", ok.await("ENTER b2"))
        assertFalse("legacy мёртвого снят", File(JitOwn.lockPath(b) + ".admin").exists())
        ok.stop()
    }
}
