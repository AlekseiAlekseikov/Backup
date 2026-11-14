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
        // 1) Запись claim: `printf '%s\n' '<строка>' > /data/local/tmp/voiceapp3_jit.claim … && cat …`
        if (command.startsWith("printf") && command.contains(JitOwn.CLAIM)) {
            val line = Regex("printf '%s[^']*' '([^']*)'").find(command)?.groupValues?.get(1)
                ?: return 1 to ""            // запись не удалась — как «нет прав»
            claim = line
            return 0 to line
        }
        // 2) Чтение claim + terminal одной командой.
        if (command.startsWith("echo CLAIM=")) {
            val op = Regex("voiceapp3_jit_([A-Za-z0-9_.-]{1,64})\\.term")
                .find(command)?.groupValues?.get(1) ?: ""
            val term = terms[op]?.joinToString("|") ?: ""
            return 0 to "CLAIM=${claim ?: ""}\nTERM=$term"
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
