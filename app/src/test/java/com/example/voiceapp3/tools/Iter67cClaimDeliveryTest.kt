package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * iter67c §1/§2/§3 — команда `claim` влезает в лимит и доставляется тем путём, которым может.
 *
 * Живой факт 20.11.2026 (1.0.63): `VERIFY EXEC tail: ERR_ADB_TOO_LONG: cmd_len=44454 > 4096`,
 * `JIT_CLAIM … failed=«transport_rc=1» action=stop`, клик заблокирован, `JIT_CHAIN_SENT` = 0.
 */
class Iter67cClaimDeliveryTest {

    @Before
    fun setUp() {
        JitOp.resetStateForTest()
        JitDev.reset()
        lines.clear()
    }

    private val lines = java.util.Collections.synchronizedList(mutableListOf<String>())

    private fun log(line: String) { lines += line }

    private fun op(id: String = "mwp6qj6c-1-2nh9p") = JitOwn.Op(
        id, 1_795_191_614_900L, true, JitOwn.State.CREATED,
        identity = JitOwn.ClaimIdentity("$id-1795191614900", "", "", ""), seq = 1
    )

    /**
     * Shell, который ВЕДЁТ СЕБЯ КАК НАСТОЯЩИЙ ТРАНСПОРТ: инлайн-команда длиннее лимита одного
     * пакета не отправляется и получает `ERR_ADB_TOO_LONG` (как `AdbClient.execute`).
     * Файловая доставка лимитом не ограничена.
     */
    private class TransportShell : JitRef.JitShell {
        val inline = java.util.Collections.synchronizedList(mutableListOf<String>())
        val scripts = java.util.Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        /** Команды, которые транспорт ОТБИЛ по размеру (на машине они не исполнились). */
        val rejected = java.util.Collections.synchronizedList(mutableListOf<String>())

        override fun exec(command: String, timeoutMs: Int): Pair<Int, String> {
            inline += command
            val bytes = AdbClient.inlinePayloadBytes(command)
            if (bytes > 4_096) {
                rejected += command
                return 1 to "ERR_ADB_TOO_LONG: cmd_len=$bytes > 4096 (use executeScript for long scripts)"
            }
            return JitDev.exec(command) { 0 to "" }
        }

        /** Настоящая файловая доставка исполняет текст целиком: лимита пакета здесь нет. */
        override fun execScript(name: String, text: String, timeoutMs: Int): Pair<Int, String> {
            scripts += name to text
            return JitDev.exec(text) { 0 to "" }
        }

        override fun mapsExcerpt(): String? = null

        fun payloadCmds(): List<String> = synchronized(inline) {
            inline.filter { it.contains("setprop ctl.restart zygote") }
        }
    }

    // ─────────────────────────── §1: доставка ───────────────────────────

    @Test
    fun `команда claim доходит до устройства, и путь доставки виден строкой`() {
        val shell = TransportShell()
        JitOp.click(JitFake.Store(), JitFake.Net(wifi = true), shell, want = true, log = ::log)
        // fail-before (1.0.63): claim шёл через exec и получал ERR_ADB_TOO_LONG (44 454 > 4096),
        // клик останавливался на владении. После §2.2 по ADB уходит только обвязка (≤ 3500):
        // путь — inline, и транспорт её принимает.
        val delivery = lines.firstOrNull { it.startsWith("JIT_CLAIM_DELIVERY ") }
        assertTrue("нет строки выбора пути доставки: $lines", delivery != null)
        assertTrue("не назван путь: $delivery", delivery!!.contains("path=inline"))
        assertTrue("не назван размер: $delivery", delivery.contains("bytes="))
        assertTrue("не назван порог: $delivery", delivery.contains("limit_inline=3500"))
        val claimSent = shell.inline.filter { it.contains("CL_BEGIN") && it.contains(JitOwn.CLAIM) }
        assertEquals("claim отправлен ровно один раз: $lines", 1, claimSent.size)
        assertTrue("транспорт не должен был отбить claim: ${shell.rejected.size}",
            shell.rejected.none { it.contains("CL_BEGIN") })
        assertTrue("владение не должно остаться неподтверждённым: $lines",
            lines.none { it.contains("claim_unproven") })
        assertTrue("цепочка отправлена: $lines", lines.any { it.startsWith("JIT_CHAIN_SENT ") })
    }

    @Test
    fun `цепочка payload доходит до устройства, а не отбивается транспортом`() {
        // Сквозной: клик под транспортом с НАСТОЯЩИМ лимитом пакета. Строка JIT_CHAIN_SENT
        // печатается ДО отправки, поэтому сама по себе ничего не доказывает — проверяем, что
        // текст с `setprop ctl.restart zygote` реально принят транспортом.
        val shell = TransportShell()
        JitOp.click(JitFake.Store(), JitFake.Net(wifi = true), shell, want = true, log = ::log)
        val bounced = shell.rejected.map { AdbClient.inlinePayloadBytes(it) }
        assertTrue("транспорт отбил по размеру ${bounced.size} команд(ы), байт: $bounced", bounced.isEmpty())
        val chainViaScript = shell.scripts.any { it.second.contains("setprop ctl.restart zygote") }
        val chainInline = shell.payloadCmds().any { AdbClient.inlinePayloadBytes(it) <= 4_096 }
        assertTrue("цепочка не доставлена ни инлайн, ни файлом: $lines", chainViaScript || chainInline)
        val d = lines.firstOrNull { it.startsWith("JIT_CHAIN_DELIVERY ") }
        assertTrue("нет строки выбора пути доставки цепочки: $lines", d != null)
        assertTrue("не назван размер: $d", d!!.contains("bytes="))
    }

    @Test
    fun `порог доставки делит пакеты на инлайн и файл`() {
        // Решение — функция от РАЗМЕРА ПАКЕТА, и проверяются обе стороны порога.
        assertEquals("inline", JitOwn.deliveryPath(JitOwn.INLINE_SAFE_BYTES))
        assertEquals("script", JitOwn.deliveryPath(JitOwn.INLINE_SAFE_BYTES + 1))
        // После §2.2 обвязка claim влезает в один пакет — решение обязано быть inline.
        val packet = AdbClient.inlinePayloadBytes(JitOwn.claimCmd(op()))
        assertEquals("пакет claim = $packet", "inline", JitOwn.deliveryPath(packet))
    }

    @Test
    fun `диагностический транспорт скрипты не доставляет`() {
        val diag = JitNet.DiagShell.execScript("jit_claim_x", "echo hi", 1_000)
        assertEquals("пассивный канал обязан отказать кодом", AdbPassive.RC_UNAVAILABLE, diag.first)
        assertTrue("отказ обязан называть причину: ${diag.second}",
            diag.second.contains("ERR_ADB_PASSIVE_UNAVAILABLE") &&
                diag.second.contains("script_not_supported"))
    }

    @Test
    fun `пакет с обёрткой транспорта считается той же формулой, что и отправка`() {
        // Обёртка `wrapForRc` = `sh -c '<текст с экранированием>'; echo "__ADB_RC=$?"`,
        // плюс префикс `shell:` и завершающий NUL — ровно как в AdbClient.execute.
        fun wrap(cmd: String) = "sh -c '" + cmd.replace("'", "'\\''") + "'; echo \"__ADB_RC=$?\""
        fun packet(cmd: String) = ("shell:" + wrap(cmd) + "\u0000").toByteArray(Charsets.UTF_8).size
        val cmd = "echo 'a' 'b'"
        assertEquals("формула размера разошлась с отправкой", packet(cmd),
            AdbClient.inlinePayloadBytes(cmd))
        // Кавычки УВЕЛИЧИВАЮТ пакет: порог нельзя считать по «сырому» тексту.
        assertTrue("экранирование кавычек должно учитываться",
            AdbClient.inlinePayloadBytes(cmd) > cmd.toByteArray(Charsets.UTF_8).size + 10)
        // Тот же расчёт на РЕАЛЬНОМ тексте claim: решение о пути принимается по нему.
        val claim = JitOwn.claimCmd(JitOwn.Op("x-1", 1_795_191_614_900L, true, JitOwn.State.CREATED,
            identity = JitOwn.ClaimIdentity("x-1", "", "", ""), seq = 1))
        assertEquals("inlinePayloadBytes обязан считать тем же правилом", packet(claim),
            AdbClient.inlinePayloadBytes(claim))
    }

    // ─────────────────────────── §2: сторож размера ───────────────────────────

    @Test
    fun `текст claim укладывается в сторож размера`() {
        val bytes = JitOwn.claimCmd(op()).toByteArray(Charsets.UTF_8).size
        // Красный ДО: 44 134 байта (из них 25 066 — комментарии на русском), 18 184 после §2.
        assertTrue("claimCmd = $bytes байт, предел ${JitOwn.CLAIM_TEXT_LIMIT}",
            bytes <= JitOwn.CLAIM_TEXT_LIMIT)
        assertEquals("сторож claim = инлайн-порог", JitOwn.INLINE_SAFE_BYTES, JitOwn.CLAIM_TEXT_LIMIT)
    }

    @Test
    fun `в отправляемом тексте нет ни одного комментария shell`() {
        val h = JitOwn.ownHelpersForTest()
        val bad = h.lines().filter { it.trimStart().startsWith("#") }
        assertTrue("в отправляемом тексте ${bad.size} комментариев, первый: ${bad.firstOrNull()}",
            bad.isEmpty())
        val bytes = h.toByteArray(Charsets.UTF_8).size
        assertTrue("helper'ы = $bytes байт, предел ${JitOwn.HELPERS_TEXT_LIMIT}",
            bytes <= JitOwn.HELPERS_TEXT_LIMIT)
        // Форма, уходящая на устройство, обязана быть и в гвардии payload — иначе формы разойдутся.
        val guard = JitOwn.guard(op("GUARD-1"))
        val guardComments = guard.lines().count { it.trimStart().startsWith("#") }
        assertTrue("в гвардии payload $guardComments комментариев", guardComments == 0)
        assertTrue("в гвардии нет самого кода helper'ов", guard.contains("_me_psof()"))
    }

    @Test
    fun `размер пакета claim измерен той же формулой, что и отправка`() {
        val text = JitOwn.claimCmd(op())
        val packet = AdbClient.inlinePayloadBytes(text)
        // Пакет обвязки ≤ порога инлайн-пути — запас до 4096 остаётся.
        assertTrue("пакет = $packet; ожидалось ≤ ${JitOwn.INLINE_SAFE_BYTES}",
            packet <= JitOwn.INLINE_SAFE_BYTES)
        assertTrue("текст обязан быть заметно меньше пакета", packet > text.length)
    }

    // ─────────────────────────── §3: честный отказ ───────────────────────────

    @Test
    fun `отказ по длине называется, а не сводится к transport_rc`() {
        val w = JitOwn.claimResult(
            1, "ERR_ADB_TOO_LONG: cmd_len=44454 > 4096 (use executeScript for long scripts)"
        )
        // Красный ДО: ClaimWrite.Failed("transport_rc=1")
        assertEquals(JitOwn.ClaimWrite.TooLong(44_454, 4_096), w)
    }

    @Test
    fun `отказ по длине не разрешает отправку и имеет свой код`() {
        val gate = JitOwn.claimGate(JitOwn.ClaimWrite.TooLong(44_454, 4_096))
        assertFalse("отправка запрещена", gate.send)
        assertEquals("код причины обязан отличать длину от «не подтверждено»",
            "claim_cmd_too_long", gate.code)
        assertTrue("причина обязана называть числа: ${gate.why}",
            gate.why.contains("44454") && gate.why.contains("4096"))
    }

    @Test
    fun `в журнале отказ по длине печатается своей строкой и кодом`() {
        // Отказ обязан доехать ДО разбора, каким бы путём команда ни шла: моделируем транспорт,
        // который отказывает по размеру на обоих путях (инлайн и файл).
        val shell = object : JitRef.JitShell {
            override fun exec(command: String, timeoutMs: Int): Pair<Int, String> =
                if (command.contains("CL_BEGIN")) 1 to "ERR_ADB_TOO_LONG: cmd_len=44454 > 4096"
                else JitDev.exec(command) { 0 to "" }
            override fun execScript(name: String, text: String, timeoutMs: Int): Pair<Int, String> =
                Pair(1, "ERR_ADB_TOO_LONG: cmd_len=44454 > 4096 (use executeScript for long scripts)")
            override fun mapsExcerpt(): String? = null
        }
        val res = JitOp.click(JitFake.Store(), JitFake.Net(wifi = true), shell, want = true, log = ::log)
        assertEquals("код блокировки", "claim_cmd_too_long", res.blocked)
        assertNotEquals("нельзя выдавать это за «владение не подтверждено»",
            "claim_unproven", res.blocked)
        assertTrue("нет строки too_long: $lines", lines.any { it.contains("too_long=1") })
        val line = lines.first { it.contains("too_long=1") }
        assertTrue("не названы байты: $line", line.contains("bytes=44454"))
        assertTrue("не назван лимит: $line", line.contains("limit=4096"))
        assertTrue("не сказано, что это ошибка сборки: $line", line.contains("сборки команды"))
    }
}
