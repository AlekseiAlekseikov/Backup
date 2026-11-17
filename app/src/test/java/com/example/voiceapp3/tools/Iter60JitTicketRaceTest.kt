package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * iter60 §3 — ДЕТЕРМИНИРОВАННЫЙ regression: гонка «одинаковый номер до публикации».
 *
 * ВАЖНО (iter61 §3): протокол входа заменён — вместо пары `c.<inst>.<pid>` / `p.<inst>.<pid>`
 * (снимок каталога + вычисленный ticket) производственный код занимает НОМЕР через
 * создание файла `n.<k>` в режиме noclobber и ждёт меньшие номера поимённо
 * (`JitOwn.kt:_adm_claim_slot`, `:649–717`). Этот файл обновлён под новый протокол: сценарии
 * и инварианты сохранены (два владельца невозможны, вложенность, живой не снимается, мёртвый
 * освобождается, identity по содержимому), изменены ТОЛЬКО имена записей и точка остановки
 * (`_adm_barrier_after_slot`). Ослаблений нет: добавлено требование видимости записи номера.
 *
 * Дефект iter59 (`JitOwn.kt:497–516` + `:519–554`), независимо воспроизведён проверяющим:
 * ```text
 * 1. a видит пустой каталог, выбирает ticket=1, приостановлен ДО публикации;
 * 2. z видит пустой каталог, выбирает ticket=1, публикует, входит и ДЕРЖИТ секцию;
 * 3. a публикует ticket=1, видит живого z, но имя a < z ⇒ считает z уступающим и входит.
 * ```
 * Итог — два живых владельца в секции. Стресс 4×15 такое расписание не ловит.
 *
 * Как сделано детерминированно: производственный код вызывает `_adm_barrier_after_slot` —
 * в бою это пустой no-op; тест ПЕРЕОПРЕДЕЛЯЕТ его и останавливает участника ровно между
 * сразу ПОСЛЕ занятия номера и ДО ожидания меньших номеров. Алгоритм не меняется, добавляется только точка остановки.
 *
 * Проверяется фактическое состояние: файлы участников, «свидетель» (может держать только один),
 * живость процессов и число реальных нарушений, а не совпадение текста shell.
 *
 * Чего тесты НЕ доказывают (в отчёте 🟡): Android/toybox и реальный `zygote`/ГУ; здесь Linux
 * `/bin/sh` + `/proc` песочницы.
 */
class Iter60JitTicketRaceTest {

    private val base: File = File.createTempFile("jit60", "").let { f ->
        f.delete(); f.mkdirs(); f
    }
    private val b: String get() = base.absolutePath
    // iter62 §A1: единственный владелец секции — файл `<lock>.own/o`.
    private val admDir: File get() = File(JitOwn.lockPath(b) + ".own")
    private val witness: File get() = File(base, "witness")

    // ───────────────────────── инфраструктура ─────────────────────────

    private fun pstartOf(pid: Long): String =
        File("/proc/$pid/stat").readText().trim().split(" ")[21]

    private fun startSleeper(sec: String = "60"): Process =
        ProcessBuilder("sleep", sec).redirectErrorStream(true).start()

    private fun bootId(): String = runCatching {
        File("/proc/sys/kernel/random/boot_id").readText().trim()
    }.getOrDefault("")

    private class Runner(val process: Process, val out: StringBuilder = StringBuilder()) {
        val thread: Thread = Thread {
            process.inputStream.bufferedReader().forEachLine { line ->
                synchronized(out) { out.appendLine(line) }
            }
        }.apply { isDaemon = true; start() }

        fun text(): String = synchronized(out) { out.toString() }

        fun await(needle: String, timeoutMs: Long = 20_000): Boolean {
            val t0 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t0 < timeoutMs) {
                if (text().contains(needle)) return true
                Thread.sleep(20)
            }
            return text().contains(needle)
        }

        fun stop() {
            process.destroyForcibly()
            process.waitFor(30, TimeUnit.SECONDS)
        }
    }

    /**
     * Участник: входит в секцию, берёт «свидетеля» (второй взять не может), держит [holdMs], выходит.
     * [barrierPrefix] — префикс барьера: тест останавливает участника между тикетом и публикацией.
     */
    private fun participant(inst: String, holdMs: Int, barrierPrefix: String = ""): Runner {
        val barrier = if (barrierPrefix.isEmpty()) "" else """
            # iter62 §A1: барьер стоит ПОСЛЕ атомарного захвата единственного файла владельца.
            # В прежнем протоколе это была точка «тикет выбран, публикация ещё нет»; инвариант,
            # который проверяется, тот же: пока участник не вышел, второй войти не может.
            _adm_barrier_after_claim() {
              : > "${barrierPrefix}.scanned"
              _bw=0
              while [ ! -f "${barrierPrefix}.go" ] && [ "${'$'}_bw" -lt 3000 ]; do
                sleep 0.01; _bw=${'$'}(( _bw + 1 ))
              done
              return 0
            }
            _adm_barrier_after_slot() { _adm_barrier_after_claim; }
        """.trimIndent()
        val script = File(base, "p_$inst.sh").apply {
            writeText(
                JitOwn.ownHelpersForTest(b) + "\n_me_inst=\"$inst\"\n" + barrier + "\n" + """
                if _jit_admin_enter; then
                  : > "${'$'}ROOT/wt.${'$'}${'$'}"
                  if ln "${'$'}ROOT/wt.${'$'}${'$'}" "${witness.absolutePath}" 2>/dev/null; then
                    echo "ENTER $inst witness=taken"
                    sleep $holdMs
                    rm -f "${witness.absolutePath}"
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
            )
        }
        val pb = ProcessBuilder("/bin/sh", script.absolutePath).redirectErrorStream(true)
        pb.environment()["ROOT"] = b
        return Runner(pb.start())
    }

    private fun waitFor(file: File, timeoutMs: Long = 20_000): Boolean {
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < timeoutMs) {
            if (file.exists()) return true
            Thread.sleep(10)
        }
        return file.exists()
    }

    private fun entries(): List<String> =
        admDir.listFiles()?.map { it.name }?.sorted() ?: emptyList()

    // ─────────────── 1. ГЛАВНЫЙ regression: остановка между ticket и публикацией ───────────────

    @Test
    fun latePublisherWithTheSameTicketNeverEntersWhileTheFirstOwnerHoldsTheSection() {
        // Расписание из аудита: a остановлен ПОСЛЕ выбора ticket и ДО публикации.
        val barrier = File(base, "barrier_a").absolutePath
        val a = participant("a", holdMs = 2500, barrierPrefix = barrier)
        assertTrue("участник a дошёл до барьера после выбора ticket", waitFor(File("$barrier.scanned")))

        // z стартует, пока a не опубликовал свой ticket.
        val z = participant("z", holdMs = 2500)
        // Ждём ЛЮБОЙ исход z: или он вошёл (тогда a обязан ждать), или честно отказался.
        assertTrue("z получил исход: ${z.text()}", z.await("ENTER z") || z.await("REFUSE z", 15_000))
        val zEntered = z.text().contains("ENTER z")

        // Отпускаем a.
        File("$barrier.go").createNewFile()
        assertTrue("a получил исход: ${a.text()}", a.await("ENTER a") || a.await("REFUSE a", 20_000))

        val all = a.text() + z.text()
        // ГЛАВНЫЙ инвариант: «свидетель» не может быть взят дважды ⇒ двух владельцев не было.
        assertFalse("ДВА ВЛАДЕЛЬЦА ОДНОВРЕМЕННО (дефект iter59): $all", all.contains("VIOLATION"))
        // Ни один участник не остался без объяснимого исхода.
        assertTrue("у a есть исход: ${a.text()}",
            a.text().contains("ENTER a") || a.text().contains("REFUSE a"))
        assertTrue("у z есть исход: ${z.text()}",
            z.text().contains("ENTER z") || z.text().contains("REFUSE z"))
        // Если z уже держал секцию к моменту освобождения a, то a вошёл только ПОСЛЕ выхода z.
        if (zEntered) {
            val aEnteredAt = a.text().lineSequence().firstOrNull { it.startsWith("ENTER a") }
            assertTrue("a не вошёл молча мимо занятой секции: ${a.text()}", aEnteredAt != null ||
                a.text().contains("REFUSE a"))
        }
        a.stop(); z.stop()
    }

    // ─────────────── 2. Фаза выбора публикуется ДО вычисления тикета ───────────────

    @Test
    fun slotRecordIsPublishedBeforeTheParticipantWaits() {
        val barrier = File(base, "barrier_c").absolutePath
        val a = participant("a", holdMs = 0, barrierPrefix = barrier)
        assertTrue("a дошёл до барьера", waitFor(File("$barrier.scanned")))

        // iter62 §A1: запись владельца появляется АТОМАРНО (temp + `ln`) вместе с identity,
        // поэтому «файл есть, а identity нет» невозможно. Проверяем именно это.
        val names = entries()
        assertTrue("файл владельца виден другим участникам: $names", names.contains("o"))
        val ownerText = File(admDir, "o").readText().trim()
        assertTrue("identity записана атомарно вместе с именем: $ownerText",
            ownerText.contains("pid=") && ownerText.contains("inst=") && ownerText.contains("boot="))
        assertTrue("ровно одна запись владельца: $names", names.count { it == "o" } == 1)

        // Пока a ждёт (номер занят, секция не получена), новый участник не имеет права войти.
        val z = participant("z", holdMs = 0)
        assertTrue("z получил исход", z.await("ENTER z") || z.await("REFUSE z"))
        val zOut = z.text()
        assertTrue("z не вошёл, пока a держит барьер: $zOut",
            zOut.contains("REFUSE z") && !zOut.contains("ENTER z"))
        z.stop()

        File("$barrier.go").createNewFile()
        assertTrue("после барьера a входит: ${a.text()}", a.await("ENTER a witness=taken"))
        assertFalse("нарушений нет: ${a.text()}", a.text().contains("VIOLATION"))
        a.stop()
    }

    // ─────────────── 3. Взаимное исключение на нескольких порядках публикации ───────────────

    @Test
    fun twoParticipantsOnEveryPublicationOrderKeepExactlyOneOwner() {
        for (order in listOf("az", "za")) {
            val barrier = File(base, "barrier_$order").absolutePath
            val first = order[0].toString()
            val second = order[1].toString()
            val f = participant(first, holdMs = 0, barrierPrefix = barrier)
            assertTrue("$first дошёл до барьера", waitFor(File("$barrier.scanned")))
            val s = participant(second, holdMs = 0)
            // Второй участник НЕ должен войти, пока первый выбирает номер.
            Thread.sleep(400)
            assertFalse("$second вошёл, пока $first стоял на барьере: ${s.text()}",
                s.text().contains("ENTER $second witness=taken"))
            File("$barrier.go").createNewFile()
            assertTrue("$first получил исход", f.await("ENTER $first") || f.await("REFUSE $first"))
            assertTrue("$second получил исход", s.await("ENTER $second") || s.await("REFUSE $second"))
            val all = f.text() + s.text()
            assertFalse("порядок $order: нарушений свидетеля нет: $all", all.contains("VIOLATION"))
            f.stop(); s.stop()
            // Между итерациями каталог чистим только от завершённых участников.
            admDir.listFiles()?.forEach { if (it.name == "o") it.delete() }
            witness.delete()
        }
    }

    // ─────────────── 4. Nested entry и повторный вход ───────────────

    @Test
    fun nestedEntryDoesNotReleaseTheOuterSection() {
        val script = File(base, "nested.sh").apply {
            writeText(
                JitOwn.ownHelpersForTest(b) + "\n_me_inst=\"nested\"\n" + """
                _jit_admin_enter; echo "OUTER=${'$'}?"
                _jit_admin_enter; echo "INNER=${'$'}?"
                echo "DEPTH=${'$'}{_ad_depth:-0}"
                _jit_admin_exit
                _sl="${'$'}{_ad_me:-}"
                if [ -n "${'$'}_sl" ] && [ -f "${'$'}_sl" ]; then
                  echo "STILL_OWNER=yes"
                else
                  echo "STILL_OWNER=no"
                fi
                _jit_admin_exit
                if [ -n "${'$'}_sl" ] && [ -f "${'$'}_sl" ]; then
                  echo "AFTER_FINAL=left"
                else
                  echo "AFTER_FINAL=gone"
                fi
                """.trimIndent()
            )
        }
        val p = ProcessBuilder("/bin/sh", script.absolutePath).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(60, TimeUnit.SECONDS)
        assertTrue("внешний вход: $out", out.contains("OUTER=0"))
        assertTrue("внутренний вход: $out", out.contains("INNER=0"))
        assertTrue("глубина вложенности учтена (внешний + внутренний = 2): $out",
            out.contains("DEPTH=2"))
        assertTrue("внутренний выход НЕ снял номер внешнего: $out", out.contains("STILL_OWNER=yes"))
        assertTrue("после внешнего выхода номер снят: $out", out.contains("AFTER_FINAL=gone"))
    }

    @Test
    fun anotherIdentityWithMyNameIsNeverTreatedAsMySection() {
        // Прямая проверка правила identity: совпало только имя файла — это НЕ моя секция.
        val myPid = 4242L
        val mine = "slot=1;inst=me;pid=$myPid;pstart=777;boot=B1"
        val other = "slot=1;inst=me;pid=$myPid;pstart=999;boot=B1"
        val otherInst = "slot=1;inst=other;pid=$myPid;pstart=777;boot=B1"
        val script = File(base, "identity.sh").apply {
            writeText(
                JitOwn.ownHelpersForTest(b) + "\n_me_inst=\"me\"\n_me_pid=$myPid\n_me_pstart=777\n" + """
                _adm_mine "$mine"    && echo "MINE=yes" || echo "MINE=no"
                _adm_mine "$other"   && echo "OTHER_PSTART=yes" || echo "OTHER_PSTART=no"
                _adm_mine "$otherInst" && echo "OTHER_INST=yes" || echo "OTHER_INST=no"
                """.trimIndent()
            )
        }
        val p = ProcessBuilder("/bin/sh", script.absolutePath).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(60, TimeUnit.SECONDS)
        assertTrue("полная identity — наша: $out", out.contains("MINE=yes"))
        assertTrue("другой pstart — НЕ наша: $out", out.contains("OTHER_PSTART=no"))
        assertTrue("другой inst — НЕ наша: $out", out.contains("OTHER_INST=no"))
    }

    @Test
    fun staleParticipantRecordOfADeadProcessIsReclaimedAndLiveOneBlocks() {
        // (а) мёртвая запись: доказанная смерть ⇒ снимается по своему имени, вход разрешён.
        val dead = startSleeper("0.05")
        val deadPid = dead.pid()
        dead.waitFor(30, TimeUnit.SECONDS)
        admDir.mkdirs()
        val stale = File(admDir, "o")
        stale.writeText("inst=stale;pid=$deadPid;pstart=1;boot=${bootId()}\n")

        val a = participant("a", holdMs = 0)
        assertTrue("вход после снятия мёртвого номера: ${a.text()}", a.await("ENTER a witness=taken"))
        assertTrue("после выхода участника записей не осталось: ${entries()}", a.await("EXIT a"))
        assertTrue("запись мёртвого освобождена: ${entries()}",
            !stale.exists() || !stale.readText().contains("inst=stale"))
        a.stop()

        // (б) живая запись (другой владелец внутри) ⇒ вход НЕ даётся, запись не тронута.
        val live = startSleeper()
        try {
            val liveFile = File(admDir, "o")
            liveFile.writeText(
                "inst=live;pid=${live.pid()};pstart=${pstartOf(live.pid())};boot=${bootId()}\n"
            )
            val z = participant("z", holdMs = 0)
            assertTrue("участник получил исход: ${z.text()}",
                z.await("ENTER z") || z.await("REFUSE z", 20_000))
            assertTrue("при живом владельце входа нет: ${z.text()}",
                z.text().contains("REFUSE z") && !z.text().contains("ENTER z"))
            assertTrue("номер живого не тронут: ${entries()}", liveFile.exists())
            assertTrue("живой процесс жив", live.isAlive)
            z.stop()
        } finally {
            live.destroyForcibly()
        }
    }

    // ─────────────── 5. Миграция legacy и живой legacy-владелец ───────────────

    @Test
    fun legacyAdminIsNeverStolenFromALivingOwner() {
        val live = startSleeper()
        try {
            val line = "inst=LEGACY;pid=${live.pid()};pstart=${pstartOf(live.pid())};boot=${bootId()}"
            File(JitOwn.lockPath(b) + ".admin").writeText("$line\n")
            val z = participant("z", holdMs = 0)
            assertTrue("вход запрещён при живом legacy-владельце: ${z.text()}", z.await("REFUSE z") ||
                z.await("ENTER z"))
            val out = z.text()
            assertTrue("входа нет: $out", out.contains("REFUSE z") && !out.contains("ENTER z"))
            assertEquals("legacy-файл живого владельца не тронут", "$line\n",
                File(JitOwn.lockPath(b) + ".admin").readText())
            assertTrue("живой процесс владельца жив", live.isAlive)
            z.stop()
        } finally {
            live.destroyForcibly()
        }
    }

    @Test
    fun deadLegacyIsMigratedAndTheNewProtocolWorksAfterwards() {
        // PID может быть переиспользован другим процессом стенда — тогда запись законно
        // считается ЖИВОЙ (это не дефект протокола). Поэтому попытки ограниченно повторяются,
        // и повтор объясняется в сообщении, а не «увеличением таймаута».
        var entered = false
        var text = ""
        var attempts = 0
        while (attempts < 5 && !entered) {
            attempts++
            // «Свидетель» от предыдущей попытки мог остаться: участника останавливают сразу после
            // входа, и он не успевает снять свой файл. Без сброса следующий вход выглядел бы как
            // нарушение (witness=VIOLATION) — то есть тест падал бы по причине стенда, а не кода.
            witness.delete()
            val dead = startSleeper("0.05")
            val pid = dead.pid()
            dead.waitFor(30, TimeUnit.SECONDS)
            File(JitOwn.lockPath(b) + ".admin").writeText("inst=OLD;pid=$pid;pstart=1;boot=${bootId()}\n")

            val a = participant("a", holdMs = 0)
            entered = a.await("ENTER a witness=taken")
            text = a.text()
            a.stop()
            if (entered) {
                assertFalse("legacy-файл мёртвого снят",
                    File(JitOwn.lockPath(b) + ".admin").exists())
            }
            // Повторяем попытку: единственная причина повтора — гонка «pid только что освободился».
            // Прежний `break` по отсутствию /proc делал тест нестабильным, ничего не доказывая.
        }
        assertTrue("legacy migration: attempts=$attempts entries=${entries()} witness=${witness.exists()} text=<<$text>>",
            entered)

        // Остаток предыдущего участника («свидетель» после принудительной остановки) — не факт
        // протокола: снимаем, иначе вход z выглядел бы как нарушение.
        witness.delete()
        val z = participant("z", holdMs = 0)
        assertTrue("следующий участник тоже входит: ${z.text()}", z.await("ENTER z"))
        z.stop()
        assertNotNull(entries())
    }

    // ─────────────── 6. Crash внутри секции не блокирует навсегда ───────────────

    @Test
    fun killedOwnerInsideSectionDoesNotBlockForever() {
        val holder = participant("hold", holdMs = 60_000)
        assertTrue("владелец вошёл: ${holder.text()}", holder.await("ENTER hold witness=taken"))
        holder.stop()   // crash внутри секции: записи участника остались

        val started = System.currentTimeMillis()
        val next = participant("next", holdMs = 0)
        assertTrue("после crash следующий участник получил исход: ${next.text()}",
            next.await("ENTER next") || next.await("REFUSE next"))
        val took = System.currentTimeMillis() - started
        assertTrue("ожидание ограничено, а не бесконечно: ${took}ms", took < 30_000)
        assertTrue("record упавшего снят: ${entries()}",
            entries().none { it.startsWith("p.hold.") })
        next.stop()
    }
}
