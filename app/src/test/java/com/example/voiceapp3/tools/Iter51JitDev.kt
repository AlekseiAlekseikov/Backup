package com.example.voiceapp3.tools

import java.util.concurrent.ConcurrentHashMap

/**
 * iter51 §7 — ВИРТУАЛЬНОЕ УСТРОЙСТВО для JIT-цепочки.
 *
 * Продакшн-код теперь пишет на устройство claim-файл и terminal-файл операции
 * ([JitOwn]). Чтобы тесты проверяли настоящую логику, а не «пустой ответ транспорта»,
 * подставные shell'ы обращаются к этому объекту: он моделирует ровно те три вещи, которые
 * цепочка реально делает на устройстве, — запись claim, чтение claim/terminal и исполнение
 * payload с гвардией.
 *
 * Это JVM-модель, а не устройство: она не доказывает поведение ни `/system/bin/sh`, ни
 * `setprop`, ни zygote.
 */
/** Только команды PAYLOAD (не служебные claim/terminal-запросы). */
internal fun List<String>.payloadCmds(): List<String> =
    filter { it.contains("setprop ctl.restart zygote") }

internal object JitDev {

    /** Содержимое `/data/local/tmp/voiceapp3_jit.claim` (последняя запись). */
    @Volatile
    var claim: String? = null
        private set

    /** Terminal-файлы по id операции: строки `START …` / `END rc=…`. */
    val terms = ConcurrentHashMap<String, MutableList<String>>()

    /** Тестовый фасад: положить claim «как будто его записал другой процесс». */
    fun claimForTest(line: String) {
        claim = line
    }

    fun reset() {
        claim = null
        terms.clear()
    }

    /** Сколько раз payload РЕАЛЬНО исполнялся (после проверки гвардии). */
    @Volatile
    var payloadRuns: Int = 0
        private set

    private val opInGuard = Regex("JIT_REFUSED op=([A-Za-z0-9_.-]{1,64})")

    /**
     * Обслужить «служебные» команды цепочки.
     *
     * @return ответ устройства, либо `null` — команда не служебная, пусть решает сам fake.
     */
    fun handle(command: String): Pair<Int, String>? {
        // 1) Запись claim: iter53 §5.2 — она идёт ПОД lock и отвечает протоколом CL_*.
        //    `printf '%s\n' '<строка>' > /data/local/tmp/voiceapp3_jit.claim`.
        if (command.contains("CL_BEGIN") && command.contains(JitOwn.CLAIM)) {
            // iter54 §4: запись claim теперь несёт ПОЛНУЮ идентичность экземпляра
            // (`inst/pid/pstart/boot`) и выполняется атомарной заменой (tmp+mv). Fake обязан
            // отвечать тем же протоколом, иначе он проверяет формат, которого нет на устройстве.
            val head = Regex(
                "op=([A-Za-z0-9_.-]{1,64});ts_ms=(\\d{1,19});ts_s=\\d{1,19};want=([01])" +
                    "(?:;seq=(\\d{1,19}))?"
            ).find(command)
                ?: return 0 to "CL_BEGIN\nCL_STATUS=failed\nCL_END"
            val op = head.groupValues[1]
            val ts = head.groupValues[2].toLong()
            val w = head.groupValues[3]
            val inst = Regex(";inst=([A-Za-z0-9_.:\\-]{1,96});")
                .find(command)?.groupValues?.get(1) ?: "$op-$ts-fake"
            val seq = head.groupValues[4].toLongOrNull() ?: 0L
            val cur = claim
            val curOp = cur?.let { Regex("^op=([A-Za-z0-9_.-]{1,64})").find(it)?.groupValues?.get(1) }
            val curTs = cur?.let { Regex("ts_ms=(\\d{1,19})").find(it)?.groupValues?.get(1)?.toLongOrNull() }
            val curSeq = cur?.let { Regex("(?:^|;)seq=(\\d{1,19})").find(it)?.groupValues?.get(1)?.toLongOrNull() }
            // iter56 §4.3: порядок намерений — по МОНОТОННОМУ поколению; время только как
            // легаси-правило, если поколения нет хотя бы с одной стороны.
            val newerExists = when {
                seq > 0L && curSeq != null && curSeq > 0L -> curSeq >= seq
                curTs != null -> curTs >= ts
                else -> false
            }
            val rule = if (seq > 0L && curSeq != null && curSeq > 0L) "seq" else "ts_legacy"
            if (curOp != null && curOp != op && newerExists) {
                val verdict = if (rule == "seq" && curSeq == seq) "seq_tie" else "older_intent"
                return 0 to "CL_BEGIN\nCL_ORDER seen_op=$curOp seen_seq=$curSeq seen_ts_ms=$curTs " +
                    "me_seq=$seq rule=$rule verdict=$verdict\n" +
                    "CL_STATUS=stale\nCL_WHY=newer_intent_$verdict\nCL_OWNER=$cur\nCL_END"
            }
            val line = "op=$op;ts_ms=$ts;ts_s=${ts / 1000};want=$w" +
                (if (seq > 0L) ";seq=$seq" else "") +
                ";inst=$inst;pid=1;pstart=0;boot=fake"
            claim = line
            return 0 to "CL_BEGIN\nCL_STATUS=taken\nCL_VALUE=$line\nCL_END"
        }
        // 2) Чтение claim + terminal одной командой.
        //
        // iter53 §4.1: fake-устройство ОБЯЗАНО отвечать в том же протоколе, что и настоящий
        // `readCmd` (`CS_BEGIN…CS_END`, `CLAIM_STATUS`/`CLAIM_VALUE`, `TERM_STATUS`/`TERM_VALUE`),
        // иначе тест снова начнёт проверять формат, которого нет на устройстве.
        if (command.contains("CLAIM_STATUS=") && command.contains("CS_BEGIN")) {
            val op = Regex("voiceapp3_jit_([A-Za-z0-9_.-]{1,64})\\.term")
                .find(command)?.groupValues?.get(1) ?: ""
            val term = terms[op]?.joinToString("|") ?: ""
            val cur = claim
            val b = StringBuilder()
            b.append("CS_BEGIN\n")
            if (cur.isNullOrBlank()) b.append("CLAIM_STATUS=absent\n")
            else b.append("CLAIM_STATUS=present\nCLAIM_VALUE=").append(cur).append("\n")
            if (term.isEmpty()) b.append("TERM_STATUS=absent\n")
            else b.append("TERM_STATUS=present\nTERM_VALUE=").append(term).append("\n")
            b.append("CS_END\n")
            return 0 to b.toString()
        }
        return null
    }

    /**
     * Исполнить/не исполнить payload и записать terminal-факты.
     *
     * Гвардия внутри самого скрипта сравнивает claim со своим id: при чужом claim печатается
     * `JIT_REFUSED` и НИЧЕГО не выполняется. Здесь это моделируется ровно так же.
     */
    fun payloadRan(command: String) {
        val op = opInGuard.find(command)?.groupValues?.get(1) ?: return
        val cur = claim
        if (cur == null || !cur.startsWith("op=$op;")) return      // гвардия отказала бы
        payloadRuns++
        terms.getOrPut(op) { mutableListOf() }.apply {
            add("START ts=1 op=$op")
            add("END rc=0 op=$op")
        }
    }

    /**
     * Обёртка для fake-shell'а: служебные команды обслуживаются моделью, остальные — [inner];
     * terminal-факты пишутся только если [inner] действительно выполнил payload (rc=0, без ERR_).
     */
    fun exec(command: String, inner: () -> Pair<Int, String>): Pair<Int, String> {
        handle(command)?.let { return it }
        val res = inner()
        if (command.contains("setprop ctl.restart zygote") &&
            res.first == 0 && !res.second.contains("ERR_")
        ) {
            payloadRan(command)
        }
        return res
    }
}
