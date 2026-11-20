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

        override fun exec(command: String, timeoutMs: Int): Pair<Int, String> {
            inline += command
            val bytes = AdbClient.inlinePayloadBytes(command)
            if (bytes > 4_096) {
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
    fun `длинная команда claim уходит файлом, и это видно строкой`() {
        val shell = TransportShell()
        JitOp.click(JitFake.Store(), JitFake.Net(wifi = true), shell, want = true, log = ::log)
        // fail-before: claim шёл через exec и получал ERR_ADB_TOO_LONG (44 454 > 4096),
        // клик останавливался на владении; ни одной доставки скриптом не было.
        val claimScripts = shell.scripts.filter { it.first.startsWith("jit_claim_") }
        assertEquals("claim должен уйти доставкой скриптом: $lines", 1, claimScripts.size)
        val delivery = lines.firstOrNull { it.startsWith("JIT_CLAIM_DELIVERY ") }
        assertTrue("нет строки выбора пути доставки: $lines", delivery != null)
        assertTrue("не назван путь: $delivery", delivery!!.contains("path=script"))
        assertTrue("не назван размер: $delivery", delivery.contains("bytes="))
        assertTrue("не назван порог: $delivery", delivery.contains("limit_inline="))
        assertTrue("в файловом пути не должно быть ERR_ADB_TOO_LONG: $lines",
            lines.none { it.contains("claim_unproven") })
    }

    @Test
    fun `порог доставки делит пакеты на инлайн и файл`() {
        // Решение — функция от РАЗМЕРА ПАКЕТА, и проверяются обе стороны порога.
        assertEquals("inline", JitOwn.deliveryPath(JitOwn.INLINE_SAFE_BYTES))
        assertEquals("script", JitOwn.deliveryPath(JitOwn.INLINE_SAFE_BYTES + 1))
        // Текущий текст claim в один пакет не влезает — значит решение обязано быть script.
        val packet = AdbClient.inlinePayloadBytes(JitOwn.claimCmd(op()))
        assertEquals("script", JitOwn.deliveryPath(packet))
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
        // Красный ДО: 44 134 байта (из них 25 066 — комментарии на русском).
        assertTrue("claimCmd = $bytes байт, предел ${JitOwn.CLAIM_TEXT_LIMIT}",
            bytes <= JitOwn.CLAIM_TEXT_LIMIT)
        // Сторож обязан быть «узким»: комментариев в тексте нет, и он в 2.4 раза меньше прежнего.
        assertTrue("текст всё ещё слишком велик для файлового пути: $bytes",
            bytes < 18_500)
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
        // Пакет больше лимита ОДНОГО пакета — именно поэтому путь script, а не «почти влезло».
        assertTrue("пакет = $packet; ожидалось > 4096 (иначе путь должен быть inline)",
            packet > 4_096)
        assertTrue("текст обязан быть заметно меньше пакета", packet > text.length)
    }
}
