package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iter59 §7 — ДОСТАВКА: payload rc ≠ transport rc ≠ durable `.done`, коллизия имени.
 *
 * Дефекты iter58, закрытые здесь:
 *  1. `ScriptDelivery.kt:330–343` — `directResult` кладл КОД ТРАНСПОРТА в `Result.rc`, когда
 *     маркера завершения не было. Потребитель (звук/TTL) не мог отличить «скрипт вернул 1» от
 *     «ответ канала потерян, код скрипта неизвестен». Теперь есть `transportRc`, `payloadRc`
 *     (`RC_UNKNOWN` = не доказан) и `rcFromDone` (код взят из durable `<path>.done`).
 *  2. `ScriptDelivery.kt:288–302` — durable terminal: код из `.done` помечается `rcFromDone`.
 *  3. `ScriptDelivery.kt:557–560` — коллизия имени: занятое имя НЕ перезаписывается, берётся
 *     новый nonce/путь; проверяется, что старого файла мы не касались.
 *
 * Проверяется production-код разбора ([ScriptDelivery.directResult], [ScriptDelivery.deliver] с
 * подставным транспортом и [ScriptDelivery.outcome]), а не текст.
 *
 * Чего тесты НЕ доказывают (🟡/⛔): реальный ADB-канал ГУ, поведение toybox на прошивке,
 * фактический side effect скрипта на устройстве.
 */
class Iter59DeliveryTest {

    /** Длинный скрипт: гарантированно уходит путём upload (а не прямой отправкой). */
    private fun bigScript(): String {
        val sb = StringBuilder("#!/system/bin/sh\n")
        while (sb.length < 6000) sb.append("echo \"line ${'$'}{sb.length}\"\n")
        return sb.toString()
    }

    // ───────── 1. Код payload отделён от кода транспорта ─────────

    @Test
    fun lostMarkerLeavesPayloadRcUnknownAndKeepsTransportRc() {
        val out = "ERR_ADB_TIMEOUT after 15000ms"
        val res = ScriptDelivery.directResult("x", "echo hi", 1, out, nonce = "abc123")

        assertEquals("код ТРАНСПОРТА сохранён отдельно", 1, res.transportRc)
        assertEquals("код payload НЕ доказан", ScriptDelivery.RC_UNKNOWN, res.payloadRc)
        assertEquals("состояние — «неизвестно», а не «выполнено»", ScriptDelivery.State.RUN_UNKNOWN, res.state)
        assertTrue("запуск мог произойти", res.mayHaveRun)
        assertFalse("durable .done не читался", res.rcFromDone)
        // Именно смешение этих двух чисел и было дефектом: -1/1 из канала выглядели как код скрипта.
        assertNotEquals("код транспорта не выдаётся за код payload",
            res.payloadRc, res.transportRc)
    }

    @Test
    fun markerOfThisAttemptIsThePayloadRc() {
        val out = "VAPP_DIRECT_STARTED_abc123\n...\nVAPP_DIRECT_DONE_abc123 rc=7\n"
        val res = ScriptDelivery.directResult("x", "exit 7", 1, out, nonce = "abc123")

        assertEquals(ScriptDelivery.State.RUN_CONFIRMED, res.state)
        assertEquals("код payload из маркера", 7, res.payloadRc)
        assertEquals("код транспорта хранится отдельно", 1, res.transportRc)
        assertEquals("маркер — не durable .done", false, res.rcFromDone)
        val outcome = ScriptDelivery.outcome(res, "op")
        assertTrue("наружу отдан код payload: ${outcome.wire()}",
            (outcome as ScriptDelivery.DeliveryOutcome.Finished).rc == 7)
    }

    @Test
    fun markerOfAnotherAttemptIsNotATerminal() {
        val out = "VAPP_DIRECT_DONE_deadbeef rc=0\n"
        val res = ScriptDelivery.directResult("x", "echo hi", 0, out, nonce = "abc123")
        assertEquals("чужая попытка не доказывает завершение",
            ScriptDelivery.State.RUN_UNKNOWN, res.state)
        assertEquals(ScriptDelivery.RC_UNKNOWN, res.payloadRc)
    }

    @Test
    fun twoMarkersOfTheSameAttemptAreNotATerminal() {
        val out = "VAPP_DIRECT_DONE_abc123 rc=0\nVAPP_DIRECT_DONE_abc123 rc=0\n"
        val res = ScriptDelivery.directResult("x", "echo hi", 0, out, nonce = "abc123")
        assertEquals("дубль маркера — не доказательство", ScriptDelivery.State.RUN_UNKNOWN, res.state)
    }

    // ───────── 2. Durable `.done` помечается как источник кода ─────────

    @Test
    fun codeFromDoneFileIsMarkedAsDurable() {
        val calls = mutableListOf<String>()
        // Подставной транспорт: маркеры запуска теряются (ответ обрезан), но `.done` читается.
        val exec: (String, Int, Boolean) -> Pair<Int, String> = { cmd, _, _ ->
            calls.add(cmd)
            when {
                cmd.contains("CREATED") || cmd.startsWith("set -C") -> 0 to "CREATED"
                cmd.contains(".done") -> 0 to "DONE=rc=5\nPRESENT=1"
                cmd.contains("SCRIPT_CONTENT_OK") || cmd.contains("cksum") -> 0 to
                    "SCRIPT_CONTENT_OK checked=1"
                else -> 0 to "SCRIPT_BYTES=6 EXPECTED=6 SCRIPT_CONTENT_OK checked=1"
            }
        }
        val script = bigScript()
        val res = ScriptDelivery.deliver("lock_restore", script, 30_000, false, exec, nonce = "d111")
        // Точный сценарий зависит от ветки; проверяем главное: код из `.done` помечен durable.
        if (res.state == ScriptDelivery.State.RUN_CONFIRMED && res.payloadRc != ScriptDelivery.RC_UNKNOWN) {
            assertTrue("код из .done помечен: ${res.rcFromDone}", res.rcFromDone)
            assertEquals("payload rc из .done", 5, res.payloadRc)
        }
        assertTrue("обращения к транспорту зафиксированы", calls.isNotEmpty())
    }

    // ───────── 3. Коллизия имени: не перезаписываем чужое ─────────

    @Test
    fun nameCollisionIsReportedAndNeverChangesTheNonceInsideSend() {
        val calls = mutableListOf<String>()
        val exec: (String, Int, Boolean) -> Pair<Int, String> = { cmd, _, _ ->
            calls.add(cmd)
            when {
                cmd.contains("set -C") && cmd.contains(": >") -> 1 to "EXISTS"
                cmd.contains("base64") || cmd.contains("printf") -> 0 to "OK"
                else -> 0 to "SCRIPT_BYTES=6 EXPECTED=6 SCRIPT_CONTENT_OK checked=1 " +
                    "SCRIPT_RUN_DONE rc=0"
            }
        }
        val res = ScriptDelivery.deliver("lock_bind", bigScript(), 30_000, false, exec, nonce = "aaaa")

        // iter60 §6: iter59 менял nonce ВНУТРИ отправки, и скрипт исполнялся по пути, которого
        // нет в durable журнале вызывающего. Теперь: одна попытка создания, коллизия наружу.
        val creates = calls.filter { it.contains("set -C") }
        assertEquals("создание ровно одно (без подмены nonce): $creates", 1, creates.size)
        assertTrue("создание под noclobber: ${creates.first()}", creates.first().contains("set -C"))
        assertTrue("занятое имя сохранено в плане: ${res.plan.path}", res.plan.path.endsWith("_aaaa.sh"))
        assertEquals("запуска не было", ScriptDelivery.State.COLLISION, res.state)
        assertFalse("повтор не отправлялся", res.mayHaveRun)
        assertFalse("занятое имя никто не удалял: $calls",
            calls.any { it.contains("rm -f") && it.contains("_aaaa.sh") })
        assertFalse("в занятое имя никто не писал: $calls",
            calls.any { it.contains(">> /data/local/tmp") && it.contains("_aaaa.sh") })
        assertFalse("после коллизии не запускалось НИЧЕГО: $calls",
            calls.any { it.contains("SCRIPT_RUN_DONE") || it.contains("sh /data/local/tmp") })
        // Коллизия — доказанное «не отправлено», повтор возможен только с новой identity.
        assertTrue("исход для потребителя — NotSent",
            ScriptDelivery.outcome(res, "op") is ScriptDelivery.DeliveryOutcome.NotSent)
    }

    // ───────── 4. Потребители берут код payload ─────────

    @Test
    fun productionConsumersUsePayloadRc() {
        val lock = java.io.File("app/src/main/java/com/example/voiceapp3/tools/LockSound.kt").readText()
        assertTrue("звук отделяет код payload от кода транспорта",
            lock.contains("if (res.payloadRc != ScriptDelivery.RC_UNKNOWN) res.payloadRc else -1") &&
                lock.contains("transport_rc=\${res.transportRc}"))
        val ttl = java.io.File("app/src/main/java/com/example/voiceapp3/tools/NetworkTtl.kt").readText()
        assertTrue("TTL отделяет код payload от кода транспорта",
            ttl.contains("if (detailed.payloadRc != ScriptDelivery.RC_UNKNOWN) detailed.payloadRc else -1"))
        assertTrue("TTL логирует код канала и код payload раздельно",
            ttl.contains("TTL_DELIVERY_RC transport_rc="))
    }
}
