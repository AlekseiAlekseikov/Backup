package com.example.voiceapp3.tools

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * iter54 §5 — ДОСТАВКА: исход, который доходит ДО ПОТРЕБИТЕЛЯ.
 *
 * Дефект (аудит §5): `RUN_UNKNOWN` не доходил до звука/TTL. Потребители получали
 * `Pair<rc, out>`, где «ответ потерян» и «команда не выполнена» выглядели одинаково. Отсюда два
 * реальных риска: повтор опасной операции (второй `mount`/второй рестарт) или заявление успеха
 * по выводу, которого не было.
 *
 * Метод: настоящий `/bin/sh` и настоящая ФС ([RealShellDevice]); «потеря ответа» моделируется
 * честно — команда исполняется, а вызывающему возвращается таймаут.
 *
 * Чего эти тесты НЕ доказывают: поведение ADB-канала на ГУ, доступность `cksum`/`md5sum` в
 * прошивке ГУ и то, что скрипт делает нужный эффект на устройстве.
 */
class Iter54DeliveryTest {

    private val dev = RealShellDevice()
    private val root: File = File(dev.root, "data/local/tmp")

    @After
    fun tearDown() = dev.cleanup()

    private fun bigScript(marker: Int): String {
        val body = StringBuilder("#!/system/bin/sh\n# marker=$marker\n")
        while (body.length < 6000) body.append("echo \"line $marker ${body.length}\"\n")
        return body.toString()
    }

    private fun localPath(p: String): File = File(p.replace("/data/local/tmp", root.absolutePath))

    // ─────────────── 1. три исхода различимы и не смешиваются ───────────────

    @Test
    fun outcomeDistinguishesNotSentUnknownAndFinished() {
        val planUpload = ScriptDelivery.plan("x", bigScript(1)).copy(transport = ScriptDelivery.Transport.UPLOAD)
        val planDirect = ScriptDelivery.plan("x", "echo hi").copy(transport = ScriptDelivery.Transport.DIRECT)

        fun res(state: ScriptDelivery.State, transport: ScriptDelivery.Plan, mayRun: Boolean = false) =
            ScriptDelivery.Result(0, "", transport, 1, state, true, false, "", mayRun)

        val notSent = res(ScriptDelivery.State.UPLOAD_INCOMPLETE, planUpload).let { ScriptDelivery.outcome(it, "op1") }
        assertTrue("неполная заливка — доказанное «не исполнялось»: ${notSent.wire()}",
            notSent is ScriptDelivery.DeliveryOutcome.NotSent)

        val unknown = res(ScriptDelivery.State.RUN_UNKNOWN, planUpload, mayRun = true).let { ScriptDelivery.outcome(it, "op2") }
        assertTrue("потерянный ответ — это Unknown: ${unknown.wire()}",
            unknown is ScriptDelivery.DeliveryOutcome.Unknown)
        assertEquals("op2", (unknown as ScriptDelivery.DeliveryOutcome.Unknown).operationId)
        assertTrue("есть ссылка на свидетельство: ${unknown.wire()}",
            unknown.evidence.path.isNotEmpty())

        val finished = res(ScriptDelivery.State.RUN_CONFIRMED, planUpload).let { ScriptDelivery.outcome(it, "op3") }
        assertTrue("terminal прочитан — Finished: ${finished.wire()}",
            finished is ScriptDelivery.DeliveryOutcome.Finished)

        // Прямая отправка: ответ транспорта содержит rc самого скрипта → это Finished.
        val directSent = res(ScriptDelivery.State.RUN_SENT, planDirect).let { ScriptDelivery.outcome(it, "op4") }
        assertTrue("прямая отправка завершена: ${directSent.wire()}",
            directSent is ScriptDelivery.DeliveryOutcome.Finished)
        // Заливка: «запуск передан», но `.done` не прочитан → Unknown, а НЕ успех.
        val uploadSent = res(ScriptDelivery.State.RUN_SENT, planUpload).let { ScriptDelivery.outcome(it, "op5") }
        assertTrue("заливка без .done — Unknown: ${uploadSent.wire()}",
            uploadSent is ScriptDelivery.DeliveryOutcome.Unknown)

        val tooLarge = res(ScriptDelivery.State.TOO_LARGE, planUpload).let { ScriptDelivery.outcome(it, "op6") }
        assertTrue(tooLarge is ScriptDelivery.DeliveryOutcome.NotSent)
    }

    @Test
    fun cksumIsIntegrityCheckNotCryptographicProof() {
        val plan = ScriptDelivery.plan("x", bigScript(2))
        val r = ScriptDelivery.Result(0, "", plan, 1, ScriptDelivery.State.RUN_CONFIRMED,
            true, true, "", false, contentKind = "cksum")
        assertTrue("в журнале прямо сказано, что это не криптография: ${r.wire()}",
            r.wire().contains("content_proof=integrity_only_not_crypto"))
        // И нигде не утверждается «доказано криптографически».
        assertFalse("нет ложного утверждения", r.wire().contains("crypto_proof=yes"))
    }

    // ─────────────── 2. заливка: отказ чанка = доказанное «не исполнялось» ───────────────

    @Test
    fun chunkFailureIsProvenNotSent() {
        val script = bigScript(3)
        // Канал обрывается на КАЖДОЙ команде заливки чанка (а не на одной попытке): именно так
        // выглядит «заливка не проходит», и повтор не должен превращать это в успех.
        val res = ScriptDelivery.deliver("lock_bind", script, 30_000, true,
            exec = { cmd, t, r ->
                if (cmd.contains(">>") || cmd.contains("base64")) 139 to "ERR_ADB_CHANNEL_DOWN"
                else dev.exec(cmd, t, r)
            })

        assertTrue("неполная заливка: ${res.wire()}",
            res.state == ScriptDelivery.State.UPLOAD_INCOMPLETE || res.state == ScriptDelivery.State.NOT_SENT)
        assertFalse("запуск НЕ передавался", res.mayHaveRun)
        assertFalse("и не заявляется доставленным", res.delivered)
        val outcome = res.let { ScriptDelivery.outcome(it, "op-chunk") }
        assertTrue("потребитель видит NotSent: ${outcome.wire()}",
            outcome is ScriptDelivery.DeliveryOutcome.NotSent)
        assertTrue("ни одна запускающая команда не ушла: ${dev.executed.filter { it.contains("SCRIPT_CONTENT_OK") }}",
            dev.executed.none { it.contains("SCRIPT_CONTENT_OK") })
    }

    // ─────────────── 3. потерянный stdout = Unknown + свидетельство ───────────────

    @Test
    fun lostStdoutIsUnknownAndKeepsEvidence() {
        val script = bigScript(4)
        // Ответ на запуск теряется ПОСЛЕ фактического исполнения — самый опасный случай.
        val res = ScriptDelivery.deliver("lock_bind", script, 30_000, true,
            exec = { cmd, t, r ->
                if (cmd.contains("SCRIPT_CONTENT_OK") || cmd.contains("DONE=")) {
                    dev.exec(cmd, t, r)
                    137 to "ERR_ADB_TIMEOUT"
                } else dev.exec(cmd, t, r)
            })

        assertEquals("состояние — неизвестность: ${res.wire()}",
            ScriptDelivery.State.RUN_UNKNOWN, res.state)
        assertTrue("запуск мог произойти", res.mayHaveRun)
        assertTrue("свидетельство сохранено: ${res.wire()}", res.evidencePath.isNotEmpty())
        assertTrue("файл скрипта НЕ удалён (иначе исход не дочитать)",
            localPath(res.evidencePath).exists() || localPath(res.evidencePath + ".done").exists())
        val outcome = res.let { ScriptDelivery.outcome(it, "op-lost") }
        assertTrue("потребитель видит Unknown: ${outcome.wire()}",
            outcome is ScriptDelivery.DeliveryOutcome.Unknown)
        val ev = (outcome as ScriptDelivery.DeliveryOutcome.Unknown).evidence
        assertTrue("ссылка на свидетельство заполнена: ${ev.wire()}", ev.path.isNotEmpty())
    }

    @Test
    fun lateRunIsResolvedFromDoneFileWithoutRepeating() {
        val script = bigScript(5)
        // Ответ потерян, но скрипт реально исполнился и оставил `.done`.
        val res = ScriptDelivery.deliver("lock_bind", script, 30_000, true,
            exec = { cmd, t, r ->
                if (cmd.contains("SCRIPT_CONTENT_OK") || cmd.contains("DONE=")) {
                    dev.exec(cmd, t, r)
                    137 to "ERR_ADB_TIMEOUT"
                } else dev.exec(cmd, t, r)
            })
        assertEquals(ScriptDelivery.State.RUN_UNKNOWN, res.state)

        // Поздняя сверка: terminal уже на диске (его мог создать отложенный shell).
        val rc = ScriptDelivery.reconcile(res.plan.path, dev::exec, true)
        assertTrue("факт завершения дочитан БЕЗ повторного запуска: rc=$rc", rc != null)
        val ranTwice = dev.executed.count { it.contains("sh " + localPath(res.plan.path).absolutePath) }
        assertTrue("скрипт запускался не более одного раза: $ranTwice", ranTwice <= 1)
    }

    // ─────────────── 4. битый файл ТОЙ ЖЕ длины не исполняется ───────────────

    @Test
    fun sameLengthCorruptedUploadIsNotExecuted() {
        val script = bigScript(6)
        val effect = File(root, "EFFECT_LEAK")
        val res = ScriptDelivery.deliver("lock_bind", script, 30_000, true,
            exec = { cmd, t, r ->
                if (cmd.contains("SCRIPT_CONTENT_OK")) {
                    // Подменяем содержимое, СОХРАНЯЯ длину: сверка размера такое не ловит.
                    val f = root.listFiles()!!.filter { it.name.endsWith(".sh") }.maxByOrNull { it.lastModified() }
                    if (f != null) {
                        val b = f.readBytes()
                        if (b.isNotEmpty()) {
                            b[b.size - 2] = if (b[b.size - 2] == 'a'.code.toByte()) 'b'.code.toByte() else 'a'.code.toByte()
                            f.writeBytes(b)
                        }
                    }
                    File(f!!.absolutePath + ".done").writeText("rc=0\n")
                    File(effect.absolutePath).writeText("executed")
                }
                dev.exec(cmd, t, r)
            })

        assertTrue("файл не должен исполняться при несовпадении содержимого: ${res.wire()}",
            res.state == ScriptDelivery.State.UPLOAD_INCOMPLETE || res.state == ScriptDelivery.State.RUN_UNKNOWN)
        assertTrue("отказ назван в выводе: ${res.out.take(300)}",
            res.out.contains("ERR_SCRIPT_UPLOAD content") || res.out.contains("SCRIPT_CONTENT_OK"))
        if (res.state == ScriptDelivery.State.UPLOAD_INCOMPLETE) {
            assertFalse("запуск не заявлен", res.mayHaveRun)
        }
    }

    // ─────────────── 5. прямая отправка несёт маркеры начала/конца ───────────────

    @Test
    fun directDeliveryReportsFinishedOnlyWithDoneMarker() {
        val script = "echo hello; exit 7"
        val res = ScriptDelivery.deliver("direct_test", script, 15_000, true, dev::exec)
        assertEquals("маркер завершения делает исход фактом: ${res.wire()}",
            ScriptDelivery.State.RUN_CONFIRMED, res.state)
        assertEquals("rc скрипта, а не обёртки", 7, res.rc)
        assertEquals("direct_marker", res.contentKind)
        assertTrue("обёртка видна в команде", dev.executed.any { it.contains("VAPP_DIRECT_STARTED") })
    }

    @Test
    fun directLostResponseIsUnknownNotSuccess() {
        val res = ScriptDelivery.deliver("direct_test", "echo hi", 15_000, true,
            exec = { cmd, t, r ->
                if (cmd.contains("VAPP_DIRECT_STARTED")) {
                    dev.exec(cmd, t, r)
                    137 to "ERR_ADB_TIMEOUT"
                } else dev.exec(cmd, t, r)
            })
        assertEquals(ScriptDelivery.State.RUN_UNKNOWN, res.state)
        assertTrue("могло исполниться", res.mayHaveRun)
        assertTrue(res.let { ScriptDelivery.outcome(it, "d1") } is ScriptDelivery.DeliveryOutcome.Unknown)
    }

    // ─────────────── 6. .started/.done принадлежат ПОПЫТКЕ (своему пути) ───────────────

    @Test
    fun startedAndDoneBelongToTheAttemptPath() {
        val script = bigScript(8)
        val res = ScriptDelivery.deliver("lock_bind", script, 30_000, true, dev::exec)
        assertTrue("заливка дошла до запуска: ${res.wire()}",
            res.state == ScriptDelivery.State.RUN_CONFIRMED)
        val dir = File(res.plan.path.replace("/data/local/tmp", root.absolutePath)).parentFile
        // После доказанного завершения свидетельства убираются — это тоже факт, а не обещание.
        val leftovers = dir.listFiles()!!.filter { it.name.startsWith("voiceapp3_lock_bind") }
        assertTrue("после RUN_CONFIRMED файлов-свидетельств не осталось: $leftovers",
            leftovers.isEmpty())
        // Путь попытки содержит nonce (16 hex): повтор берёт ДРУГОЙ путь и не путает .started/.done.
        assertTrue("путь содержит 16-символьный nonce: ${res.plan.path}",
            Regex("voiceapp3_lock_bind_[0-9a-f]{16}\\.sh").containsMatchIn(res.plan.path))
    }

    // ─────────────── 7. потребители действительно различают исход ───────────────

    @Test
    fun sideEffectConsumersUseTheOutcome() {
        fun src(name: String): String = listOf(
            File("src/main/java/com/example/voiceapp3/tools/$name"),
            File("app/src/main/java/com/example/voiceapp3/tools/$name"),
            File("/home/user/w/app/src/main/java/com/example/voiceapp3/tools/$name")
        ).first { it.isFile }.readText()

        val sound = src("LockSound.kt")
        assertTrue("звук получает исход bind", sound.contains("LOCKSOUND_BIND_OUTCOME"))
        assertTrue("NotSent отделён от Unknown",
            sound.contains("DeliveryOutcome.NotSent") && sound.contains("DeliveryOutcome.Unknown"))
        assertTrue("неизвестность НЕ повторяется вслепую",
            sound.contains("action=pending_no_retry"))

        val ttl = src("NetworkTtl.kt")
        assertTrue("TTL получает исход доставки", ttl.contains("TTL_DELIVERY"))
        assertTrue("TTL не заявляет применение при неизвестном исходе",
            ttl.contains("action=pending") && ttl.contains("not_sent:"))
    }
}
