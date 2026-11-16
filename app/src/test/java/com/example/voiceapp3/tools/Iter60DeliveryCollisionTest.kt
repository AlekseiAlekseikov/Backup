package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * iter60 §6 — DELIVERY COLLISION: имя, подтверждённое durable-журналом, не меняется внутри отправки.
 *
 * Дефект iter59 (`ScriptDelivery.kt:582–591`): при `EXISTS` отправка генерировала НОВЫЙ nonce и
 * исполняла скрипт по пути B, тогда как вызывающий записал в журнал путь A. Свидетельство
 * указывало не туда, и «исход неизвестен» становился неразрешимым (а поздний shell — невидимым).
 *
 * Правильный порядок: `prepare A → durable journal A → upload A`; при коллизии отправка A
 * НИЧЕГО не делает и сообщает `COLLISION` (доказанное «не отправлено»), а вызывающий готовит B.
 *
 * Здесь же проверяется различение четырёх источников кода:
 *  • `payloadRc` — код скрипта;
 *  • `transportRc` — код канала (ADB);
 *  • маркер stdout `SCRIPT_RUN_DONE rc=…`;
 *  • durable `<path>.done`.
 *
 * Чего тесты НЕ доказывают (⛔): поведение toybox `set -C`/`: >` на конкретной прошивке ГУ.
 */
class Iter60DeliveryCollisionTest {

    private val nonceA = "aaaa0000aaaa0000"
    private val nonceB = "bbbb1111bbbb1111"
    private val pathA = ScriptDelivery.pathFor("lock_restore", nonceA)
    private val pathB = ScriptDelivery.pathFor("lock_restore", nonceB)

    /** Скрипт крупнее DIRECT_LIMIT (3500) — иначе отправка пойдёт прямым путём без файлов. */
    private fun expectedBytes(): Int = body().toByteArray().size

    private fun body(): String =
        "#!/system/bin/sh\necho hello\n" + (1..300).joinToString("\n") { "# padding line $it" } + "\n"

    /** Устройство, где имя A уже занято ЧУЖИМ файлом. */
    private class FakeDevice(private val expectedBytes: Int) {
        val commands = ArrayList<String>()
        var existing = HashSet<String>()
        var failCreateAlways = false
        var doneRc: String? = null
        var runMarker: Boolean = true
        /** Код канала для команды запуска (не код скрипта). */
        var runRc: Int = 0
        /** Код канала для read-only чтения `.done`. */
        var doneReadRc: Int = 0

        val results = ArrayList<String>()

        fun exec(cmd: String, timeoutMs: Int, root: Boolean): Pair<Int, String> {
            commands.add(cmd)
            val r = exec0(cmd)
            results.add(r.first.toString() + "/" + r.second.take(40))
            return r
        }

        private fun exec0(cmd: String): Pair<Int, String> {
            // Порядок важен: команда запуска тоже содержит `.done` (пишет terminal), поэтому
            // распознаём её РАНЬШЕ, чем read-only чтение `.done`.
            when {
                cmd.contains("set -C") -> {
                    val name = Regex("(/data/local/tmp/\\S+\\.sh)").find(cmd)?.groupValues?.get(1)
                    if (failCreateAlways) return 1 to "EXISTS"
                    if (name != null && name in existing) return 1 to "EXISTS"
                    if (name != null) existing.add(name)
                    return 0 to "CREATED"
                }
                cmd.contains("SCRIPT_CONTENT_KIND") -> {
                    var out = "SCRIPT_BYTES=$expectedBytes " +
                        "EXPECTED=$expectedBytes SCRIPT_CONTENT_OK checked=1 kind=cksum"
                    if (runMarker) out += " SCRIPT_RUN_DONE rc=0"
                    return runRc to out
                }
                cmd.contains("base64") || cmd.contains("printf") -> return 0 to "OK"
                cmd.trimStart().startsWith("echo \"DONE=") -> {
                    val rc = doneRc
                    return doneReadRc to (if (rc == null) "DONE=\nPRESENT=0" else "DONE=$rc\nPRESENT=1")
                }
                cmd.contains("rm -f") -> {
                    existing.clear()
                    return 0 to "RM_OK"
                }
                else -> return 1 to "UNEXPECTED_COMMAND:" + cmd.take(40)
            }
        }

        fun ranAnything(): Boolean = commands.any {
            it.contains(" SCRIPT_RUN_DONE") || Regex("(?:^|; )sh /data/local/tmp").containsMatchIn(it)
        }
    }

    // ─────────────── 1. коллизия не подменяет identity и ничего не запускает ───────────────

    @Test
    fun collisionIsReportedWithoutChangingThePreparedIdentity() {
        val dev = FakeDevice(expectedBytes()).apply { existing.add(pathA) }
        val res = ScriptDelivery.deliver("lock_restore", body(), 30_000, true, dev::exec, nonce = nonceA)

        assertEquals("исход — коллизия; res=" + dev.results.joinToString(" | "),
            ScriptDelivery.State.COLLISION, res.state)
        assertEquals("путь остался тем, что журналирован", pathA, res.plan.path)
        assertFalse("запуска не было", dev.ranAnything())
        assertTrue("создание ровно одно",
            dev.commands.count { it.contains("set -C") } == 1)
        assertFalse("чужой файл не удалялся", dev.commands.any { it.contains("rm -f") && it.contains(nonceA) })
        assertFalse("исход не выдаётся за доставку", res.delivered)
        assertEquals("payload не доказан", ScriptDelivery.RC_UNKNOWN, res.payloadRc)
        // Потребитель видит доказанное «не отправлено», а не Unknown.
        val outcome = ScriptDelivery.outcome(res, "op_60")
        assertTrue("outcome=NotSent: ${outcome.wire()}", outcome is ScriptDelivery.DeliveryOutcome.NotSent)
    }

    @Test
    fun callerPreparedSecondIdentityIsSentSeparatelyAndItsOwnTerminalIsRead() {
        val dev = FakeDevice(expectedBytes()).apply { existing.add(pathA) }

        // Шаг 1: A → коллизия (durable journal A остаётся как есть, ничего не переписано).
        val a = ScriptDelivery.deliver("lock_restore", body(), 30_000, true, dev::exec, nonce = nonceA)
        assertEquals(ScriptDelivery.State.COLLISION, a.state)

        // Шаг 2: вызывающий готовит B (prepare B), записывает B и отправляет B.
        val res2nonce = "bbbb1111bbbb1111"
        val b = ScriptDelivery.deliver("lock_restore", body(), 30_000, true, dev::exec, nonce = res2nonce)
        assertEquals("B доставлен и подтверждён", ScriptDelivery.State.RUN_CONFIRMED, b.state)
        assertEquals("запуск был ровно один", 1, dev.commands.count { it.contains("SCRIPT_RUN_DONE") })
        assertTrue("путь B отличается от A", b.plan.path != pathA)
        assertTrue("terminal B прочитан из stdout", b.payloadRc == 0)
    }

    // ─────────────── 2. четыре источника кода различимы ───────────────

    @Test
    fun payloadRcTransportRcStdoutMarkerAndDoneAreDistinguished() {
        // (а) маркер stdout есть ⇒ payload = код скрипта, transport — код канала.
        val dev = FakeDevice(expectedBytes())
        val r1 = ScriptDelivery.deliver("lock_restore", body(), 30_000, true, dev::exec, nonce = nonceB)
        assertEquals(ScriptDelivery.State.RUN_CONFIRMED, r1.state)
        assertEquals("payload из маркера", 0, r1.payloadRc)
        assertFalse("не из .done", r1.rcFromDone)

        // (б) маркер потерян, но durable `.done` читается ⇒ payload берётся из `.done`.
        // Канал команды запуска ответил ошибкой (1), но durable `.done` читается ⇒ payload = 7.
        val dev2 = FakeDevice(expectedBytes()).apply {
            runMarker = false; doneRc = "rc=7"; runRc = 1; doneReadRc = 0
        }
        val r2 = ScriptDelivery.deliver("lock_restore", body(), 30_000, true, dev2::exec, nonce = nonceB)
        assertEquals("исход подтверждён по .done", ScriptDelivery.State.RUN_CONFIRMED, r2.state)
        assertEquals("payload из .done", 7, r2.payloadRc)
        assertTrue("источник назван", r2.rcFromDone)
        assertEquals("transport rc отделён от payload", 1, r2.transportRc)
        assertNotEquals("не путать канал с payload", r2.transportRc, r2.payloadRc)

        // (в) ни маркера, ни `.done` ⇒ исход НЕИЗВЕСТЕН, а не «выполнено с ошибкой».
        val dev3 = FakeDevice(expectedBytes()).apply {
            runMarker = false; doneRc = null; runRc = 1; doneReadRc = 0
        }
        val r3 = ScriptDelivery.deliver("lock_restore", body(), 30_000, true, dev3::exec, nonce = nonceB)
        assertEquals(ScriptDelivery.State.RUN_UNKNOWN, r3.state)
        assertEquals("payload не доказан", ScriptDelivery.RC_UNKNOWN, r3.payloadRc)
        assertTrue("повтор запрещён: могло исполниться", r3.mayHaveRun)
        assertTrue("свидетельство сохранено: ${r3.evidencePath}", r3.evidencePath.isNotEmpty())
    }

    // ─────────────── 3. production: внутри send нет смены nonce ───────────────

    @Test
    fun productionNeverRotatesTheNonceInsideDelivery() {
        val src = File("app/src/main/java/com/example/voiceapp3/tools/ScriptDelivery.kt").readText()
        val deliver = src.substring(src.indexOf("fun deliver("))
        assertFalse("внутри отправки больше нет генерации нового nonce",
            deliver.contains("currentNonce = newNonce()"))
        assertTrue("коллизия — отдельное состояние", src.contains("COLLISION"))
        assertTrue("коллизия = доказанное «не отправлено»",
            src.contains("path_collision_requires_new_identity"))
        assertTrue("caller получает явную инструкцию",
            src.contains("caller готовит B (prepare B → journal B → upload B)"))
        // identity по-прежнему приходит снаружи (prepare) и не пересоздаётся при отправке.
        assertTrue("отправка берёт nonce подготовленной identity",
            File("app/src/main/java/com/example/voiceapp3/tools/AdbClient.kt").readText()
                .contains("nonce = prep.nonce"))
    }
}
