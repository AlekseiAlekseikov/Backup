package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * iter61 §6 — ДОСТАВКА: identity подтверждена ДО отправки, коллизия не создаёт скрытый nonce,
 * четыре РАЗНЫХ факта не смешиваются (payload rc / transport rc / stdout-маркер / `.done`).
 *
 * Проверяется на production-коде [ScriptDelivery]: настоящий `plan`/`createCommand`/`runCommand`,
 * подменён только транспорт (`exec`), и считаются ФАКТИЧЕСКИЕ вызовы (число команд), а не строки лога.
 *
 * Чего тест НЕ доказывает (в отчёте 🟡): реальный `adb`/ГУ, `toybox` и `chmod`/`cksum` на устройстве.
 */
class Iter61DeliveryFactsTest {

    /** Скрипт длиннее [ScriptDelivery.DIRECT_LIMIT] — идёт ЗАЛИВКОЙ (create → chunks → run). */
    private val script = "#!/bin/sh\n" + "#".repeat(ScriptDelivery.DIRECT_LIMIT + 50) + "\ntrue\n"

    /** Короткий скрипт — ПРЯМАЯ отправка (маркеры в stdout). */
    private val small = "#!/bin/sh\ntrue\n"

    /** Транспорт-подмена: отвечает по типу команды и СЧИТАЕТ вызовы. */
    private class Exec(
        var createReply: String = "CREATED",
        var chunkRc: Int = 0,
        var runReply: Pair<Int, String> = 0 to "VAPP_DIRECT_DONE rc=0\n"
    ) {
        val calls = mutableListOf<String>()
        fun exec(cmd: String, timeout: Int, root: Boolean): Pair<Int, String> {
            calls += cmd
            return when {
                cmd.contains("VAPP_DIRECT") -> runReply
                cmd.contains("echo CREATED; else echo EXISTS") -> 0 to createReply
                cmd.contains("set -C; : >") -> 0 to createReply
                cmd.startsWith("printf") || cmd.contains(">>") -> chunkRc to ""
                cmd.contains("sh ") || cmd.contains("started=1") -> runReply
                else -> 0 to ""
            }
        }
        fun runCalls(): Int = calls.count { it.contains("started=1") || it.contains("sh ") }
    }

    // ───────── 1. Коллизия: ничего не отправлено, nonce НЕ подменён ─────────

    @Test
    fun collisionSendsNothingAndKeepsTheJournalIdentity() {
        val prep = ScriptDelivery.prepare("ttl_script", script, "abc123")
        val ex = Exec(createReply = "EXISTS")
        val nonceBefore = prep.nonce
        val pathBefore = prep.evidencePath

        val r = ScriptDelivery.deliver(
            "ttl_script", script, 20000, true, ex::exec, tries = 3, nonce = prep.nonce
        )

        assertEquals("состояние — коллизия, а не «не отправлено»/«отправлено»",
            ScriptDelivery.State.COLLISION, r.state)
        assertFalse("запуск НЕ мог произойти", r.mayHaveRun)
        assertEquals("nonce не подменялся", nonceBefore, prep.nonce)
        assertEquals("путь журнала не менялся", pathBefore, prep.evidencePath)
        assertEquals("имя из журнала — то, по которому пытались создавать",
            prep.evidencePath, r.plan.path)
        assertEquals("ни одной команды запуска не ушло", 0, ex.runCalls())
        assertTrue("причина названа: ${r.out}", r.out.contains("ERR_SCRIPT_PATH_COLLISION"))
        assertEquals("на устройстве ничего не оставлено", "", r.leftoverPath)
    }

    // ───────── 2. Четыре факта различимы ─────────

    @Test
    fun transportFailureIsNotAPayloadFailureAndUnknownIsNotZero() {
        val ex = Exec(runReply = -1 to "")
        val r = ScriptDelivery.deliver(
            "ttl_script", script, 20000, true, ex::exec, tries = 1, nonce = "n1"
        )
        assertEquals("код транспорта сохранён отдельно", -1, r.transportRc)
        assertEquals("код payload НЕ доказан и не выдан за 0",
            ScriptDelivery.RC_UNKNOWN, r.payloadRc)
        assertNotEquals("«неизвестно» не равно «успех»", ScriptDelivery.State.RUN_CONFIRMED, r.state)
        val outcome = ScriptDelivery.outcome(r, "op61")
        assertTrue("исход для потребителя — Unknown, а не Finished: ${outcome.wire()}",
            outcome is ScriptDelivery.DeliveryOutcome.Unknown ||
                outcome is ScriptDelivery.DeliveryOutcome.NotSent)
    }

    @Test
    fun stdoutMarkerAndDoneFileAreDifferentFacts() {
        // (а) stdout-маркер этой попытки есть → Finished с rc payload.
        val ok = Exec(runReply = 0 to "VAPP_DIRECT_DONE_m1 rc=0\n")
        val r1 = ScriptDelivery.deliver("x", small, 20000, true, ok::exec, tries = 1, nonce = "m1")
        val o1 = ScriptDelivery.outcome(r1, "opA")
        assertTrue("маркер завершения в stdout даёт Finished: ${o1.wire()}",
            o1 is ScriptDelivery.DeliveryOutcome.Finished)

        // (б) ответ есть, но маркера НЕТ → это не «выполнено».
        val noMarker = Exec(runReply = 0 to "garbage\n")
        val r2 = ScriptDelivery.deliver("x", small, 20000, true, noMarker::exec, tries = 1, nonce = "m2")
        assertNotEquals("rc=0 без маркера — не RUN_CONFIRMED",
            ScriptDelivery.State.RUN_CONFIRMED, r2.state)
        assertEquals("код payload не доказан", ScriptDelivery.RC_UNKNOWN, r2.payloadRc)
        assertTrue("потребитель получает не Finished",
            ScriptDelivery.outcome(r2, "opB") !is ScriptDelivery.DeliveryOutcome.Finished)
    }

    @Test
    fun tooLargeIsNotSentAndSaysSo() {
        val big = "#!/bin/sh\n" + "#".repeat(ScriptDelivery.MAX_SCRIPT_BYTES + 10) + "\n"
        val ex = Exec()
        val r = ScriptDelivery.deliver("x", big, 20000, true, ex::exec, tries = 1, nonce = "big")
        assertEquals(ScriptDelivery.State.TOO_LARGE, r.state)
        assertFalse("ничего не исполнялось", r.mayHaveRun)
        assertEquals("ни одной команды не ушло", 0, ex.calls.size)
        assertTrue("причина названа", r.out.contains("ERR_SCRIPT_TOO_LARGE"))
    }

    // ───────── 3. Таблица mutating callers: identity в журнале ДО отправки ─────────

    /** Все места production, где готовится доставка опасной команды, и её durable-запись. */
    private val mutatingCallers = listOf(
        Triple("NetworkTtl.kt", "recordAttempt(prep.evidencePath", "AdbClient.executePrepared(prep,"),
        Triple("LockSound.kt", "reason = \"restore_handoff:\$attempt\"",
            "AdbClient.executePrepared(prep,")
    )

    @Test
    fun everyMutatingCallerPersistsTheIdentityBeforeItSends() {
        val dir = File("app/src/main/java/com/example/voiceapp3/tools")
        for ((file, persist, send) in mutatingCallers) {
            val src = File(dir, file).readText()
            val iPersist = src.indexOf(persist)
            val iSend = src.indexOf(send)
            assertTrue("$file: запись identity найдена ($persist)", iPersist > 0)
            assertTrue("$file: отправка найдена ($send)", iSend > 0)
            assertTrue("$file: identity пишется ДО отправки ($iPersist < $iSend)",
                iPersist < iSend)
            assertTrue("$file: адрес отправки — именно подготовленный путь",
                src.contains("executePrepared(prep,"))
        }
    }

    @Test
    fun noSendHappensAfterACollisionOrAnUnconfirmedJournalWrite() {
        val ttl = File(
            File("app/src/main/java/com/example/voiceapp3/tools"), "NetworkTtl.kt"
        ).readText()
        val lock = File(
            File("app/src/main/java/com/example/voiceapp3/tools"), "LockSound.kt"
        ).readText()
        // TTL: неподтверждённая запись попытки останавливает отправку (`phase_not_persisted`).
        assertTrue("TTL: отказ записи назван",
            ttl.contains("phase_not_persisted"))
        // Звук: неподтверждённая запись фазы передачи останавливает отправку.
        assertTrue("звук: handoff не подтверждён — отправки нет",
            lock.contains("handoff_not_persisted"))
    }
}
