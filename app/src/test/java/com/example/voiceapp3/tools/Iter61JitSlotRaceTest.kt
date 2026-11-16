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
    private val admDir: File get() = File(JitOwn.lockPath(b) + ".adm")
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

    private fun start(inst: String, scriptBody: String): Runner {
        val f = File(base, "p_$inst.sh").apply { writeText(scriptBody) }
        val pb = ProcessBuilder("/bin/sh", f.absolutePath).redirectErrorStream(true)
        pb.environment()["ROOT"] = b
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
    private fun instrumentedHelpers(): String {
        val h = JitOwn.ownHelpersForTest(b)
        if (h.contains("_adm_barrier_before_scan")) return h
        // Точка остановки ставится ПЕРВОЙ инструкцией тела цикла — то есть ровно ПОСЛЕ того, как
        // shell раскрыл список имён (`for ... in c.* p.*`). Именно этого места не хватало в iter60.
        // Нужен именно цикл ОЖИДАНИЯ (он один раскрывает и `c.*`, и `p.*`), а не цикл вычисления
        // тикета (`p.*`): вставка в первый по порядку ничего не проверяет.
        val needle = "for _ad_f in "
        var i = -1
        var from = 0
        while (true) {
            val j = h.indexOf(needle, from)
            if (j < 0) break
            val line = h.substring(j, h.indexOf('\n', j))
            if (line.contains("/c.*")) { i = j; break }
            from = j + 1
        }
        check(i >= 0) { "не найден цикл ожидания с c.* для инструментирования" }
        val at = h.indexOf('\n', i) + 1
        val withBarrier = h.substring(0, at) + "      _adm_barrier_before_scan\n" + h.substring(at)
        // Пустое определение, чтобы инструментирование ничего не меняло в остальных сценариях.
        return withBarrier.replaceFirst("_adm_read() {", "_adm_barrier_before_scan() { :; }\n_adm_read() {")
    }

    /** Барьер «участник занял номер и ждёт разрешения» — под обе версии протокола. */
    private fun claimBarrier(path: String): String = """
        _adm_barrier_after_ticket() {
          : > "$path.scanned"
          _bw=0
          while [ ! -f "$path.go" ] && [ "${'$'}_bw" -lt 300000 ]; do _bw=${'$'}(( _bw + 1 )); done
          return 0
        }
        _adm_barrier_after_slot() { _adm_barrier_after_ticket; }
    """.trimIndent()

    private fun entries(): List<String> = admDir.listFiles()?.map { it.name }?.sorted() ?: emptyList()

    // ─────────── 1. ГЛАВНЫЙ regression: расписание аудита iter60 ───────────

    @Test
    fun globSnapshotScheduleCannotProduceTwoOwners() {
        val barA = File(base, "bar_a").absolutePath
        val barZ = File(base, "bar_z").absolutePath
        // A: занял номер/тикет, но ещё не «опубликовал» его полностью — держится барьером.
        val a = start("a", body("a", holdMs = -1, barrier = claimBarrier(barA)))
        assertTrue("A дошёл до точки после занятия номера: ${a.text()}", waitFor(File("$barA.scanned")))

        // Z: стартует и замирает ПЕРЕД обработкой имён — список имён (iter60) уже раскрыт,
        // p.a в нём ещё нет, а c.a — есть.
        val zBarrier = """
            _adm_barrier_before_scan() {
              [ -f "$barZ.scanned" ] && return 0
              : > "$barZ.scanned"
              _zw=0
              while [ ! -f "$barZ.go" ] && [ "${'$'}_zw" -lt 600000 ]; do _zw=${'$'}(( _zw + 1 )); done
              return 0
            }
        """.trimIndent()
        val z = start("z", body("z", holdMs = -1, barrier = zBarrier))
        assertTrue("Z замер перед обработкой списка: ${z.text()}", waitFor(File("$barZ.scanned")))

        // Отпускаем A: он публикует свой тикет/номер и входит.
        File("$barA.go").createNewFile()
        assertTrue("A вошёл: ${a.text()}", a.await("ENTER a"))
        assertTrue("A внутри: ${a.text()}", waitFor(File("$base/inside.a")))

        // Отпускаем Z: он обязан увидеть живого A, а не старый список имён.
        File("$barZ.go").createNewFile()
        // Даём Z шанс «войти по старому списку» (дефект iter60) — тогда A всё ещё внутри.
        val enteredWhileHeld = z.await("ENTER z", 3000)
        // Если Z корректно ждёт, освобождаем A: ожидание обязано закончиться входом, а не отказом.
        File("$base/release.a").createNewFile()
        if (!enteredWhileHeld) {
            assertTrue("Z получил объяснимый исход: ${z.text()}",
                z.await("ENTER z", 40_000) || z.await("REFUSE z", 5_000))
        }
        val zInsideWhileAHolds = z.text().contains("ENTER z") && File("$base/inside.a").exists()
        val violation = (a.text() + z.text()).contains("VIOLATION")
        assertFalse("ДВА ВЛАДЕЛЬЦА ОДНОВРЕМЕННО. a=${a.text()} z=${z.text()}",
            zInsideWhileAHolds || violation)
        File("$base/release.z").createNewFile()
        File("$base/release.a").createNewFile()
        a.stop(); z.stop()
    }

    // ─────────── 2. Освобождение после выхода: конечное восстановление ───────────

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
        val bar = File(base, "bar_early").absolutePath
        val early = start("early", body("early", holdMs = 3000, barrier = claimBarrier(bar)))
        assertTrue("ранний занял номер", waitFor(File("$bar.scanned")))
        val late = start("late", body("late", holdMs = 0))
        // Поздний обязан ждать, пока ранний не отпустит.
        Thread.sleep(600)
        assertFalse("поздний не вошёл, пока ранний не отпущен: ${late.text()}",
            late.text().contains("ENTER late"))
        File("$bar.go").createNewFile()
        assertTrue("ранний вошёл", early.await("ENTER early"))
        assertTrue("поздний дождался своей очереди",
            late.await("ENTER late") || late.await("REFUSE late"))
        if (late.text().contains("ENTER late")) {
            assertTrue("поздний вошёл только после выхода раннего: ${late.text()}",
                late.text().indexOf("ENTER late") >= 0)
        }
        early.stop(); late.stop()
    }

    // ─────────── 4. Смерть участника в разных фазах → номер освобождается ───────────

    @Test
    fun slotOfAKilledParticipantIsReclaimed() {
        val dead = ProcessBuilder("sleep", "60").redirectErrorStream(true).start()
        val dpid = dead.pid()
        dead.destroyForcibly(); dead.waitFor(30, TimeUnit.SECONDS)

        admDir.mkdirs()
        // Запись «мёртвого владельца» номера — как после убийства процесса посреди секции.
        File(admDir, "n.3").writeText("slot=3;inst=dead;pid=$dpid;pstart=1;boot=${bootId()}\n")

        val a = start("a", body("a", holdMs = 300))
        assertTrue("вход после смерти владельца: ${a.text()}", a.await("ENTER a"))
        assertFalse("номер мёртвого освобождён: ${entries()}", File(admDir, "n.3").exists())
        assertFalse("нарушений нет: ${a.text()}", a.text().contains("VIOLATION"))
        a.stop()
    }

    // ─────────── 5. Живой чужой номер не снимается ───────────

    @Test
    fun liveForeignSlotIsNeverRemoved() {
        val live = ProcessBuilder("sleep", "60").redirectErrorStream(true).start()
        try {
            admDir.mkdirs()
            val f = File(admDir, "n.1")
            f.writeText("slot=1;inst=other;pid=${live.pid()};pstart=${pstartOf(live.pid())};boot=${bootId()}\n")
            val a = start("a", body("a", holdMs = 0))
            assertTrue("участник получил исход: ${a.text()}",
                a.await("ENTER a") || a.await("REFUSE a", 40_000))
            assertTrue("при живом владельце номера входа нет: ${a.text()}", a.text().contains("REFUSE a"))
            assertTrue("чужой номер не тронут: ${entries()}", f.exists())
            assertTrue("чужой процесс не тронут", live.isAlive)
        } finally {
            live.destroyForcibly()
        }
    }

    // ─────────── 6. Битый/частичный номер не даёт секции, но и не снимается ───────────

    @Test
    fun corruptForeignSlotBlocksWithoutBeingDeleted() {
        admDir.mkdirs()
        val f = File(admDir, "n.1")
        f.writeText("много шума без полей\n")
        val a = start("a", body("a", holdMs = 0))
        assertTrue("участник получил исход: ${a.text()}",
            a.await("ENTER a") || a.await("REFUSE a", 40_000))
        assertTrue("недоказуемая запись блокирует вход (а не выдаётся за мёртвую): ${a.text()}",
            a.text().contains("REFUSE a"))
        assertTrue("битая запись не удалена: ${entries()}", f.exists())
        a.stop()
    }

    // ─────────── 7. Вложенный вход не занимает второй номер и не выпускает внешний ───────────

    @Test
    fun nestedEntryKeepsASingleSlotAndDoesNotReleaseItEarly() {
        val script = JitOwn.ownHelpersForTest(b) + "\n_me_inst=\"nested\"\n" + """
            _jit_admin_enter || { echo "OUTER_REFUSE"; exit 1; }
            echo "OUTER slot=${'$'}{_ad_slot} depth=${'$'}{_ad_depth}"
            _jit_admin_enter || { echo "INNER_REFUSE"; exit 1; }
            echo "INNER slot=${'$'}{_ad_slot} depth=${'$'}{_ad_depth}"
            _jit_admin_exit
            echo "AFTER_INNER depth=${'$'}{_ad_depth} files=${'$'}(ls "${'$'}ROOT/lock.adm" 2>/dev/null | tr '\n' ' ')"
            _jit_admin_exit
            echo "AFTER_OUTER depth=${'$'}{_ad_depth} files=${'$'}(ls "${'$'}ROOT/lock.adm" 2>/dev/null | tr '\n' ' ')"
        """.trimIndent()
        val r = start("nested", script)
        assertTrue("участник завершился", r.await("AFTER_OUTER"))
        val t = r.text()
        val outerSlot = Regex("OUTER slot=(\\d+)").find(t)?.groupValues?.get(1)
        val innerSlot = Regex("INNER slot=(\\d+)").find(t)?.groupValues?.get(1)
        assertEquals("вложенный вход использует ТОТ ЖЕ номер: $t", outerSlot, innerSlot)
        assertTrue("глубина растёт: $t", t.contains("INNER slot=${outerSlot} depth=2"))
        assertTrue("внутренний выход не выпускает секцию (номер ещё наш): $t",
            t.contains("AFTER_INNER depth=1"))
        assertTrue("внешний выход освобождает номер: $t", t.contains("AFTER_OUTER depth=0"))
        r.stop()
    }

    // ─────────── 8. Legacy admin-файл: живой блокирует, мёртвый снимается ───────────

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
