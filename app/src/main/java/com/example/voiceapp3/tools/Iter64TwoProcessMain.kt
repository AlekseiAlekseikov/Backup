package com.example.voiceapp3.tools

import java.io.File

/**
 * iter64 — ПИСАТЕЛЬ ЖУРНАЛА ДЛЯ ПРОВЕРКИ ДВУМЯ РЕАЛЬНЫМИ ПРОЦЕССАМИ.
 *
 * Зачем отдельный `main`: тест внутри одной JVM («два экземпляра состояния») доказывает
 * уникальность ключа, но НЕ доказывает, что два настоящих процесса не потеряют запись при
 * слиянии. Именно это и было дефектом iter63 (`rid` — счётчик процесса с 0).
 *
 * Запуск (см. `Iter64EventIdTest.twoRealProcessesKeepEveryRecord`):
 *   java -cp <classes>:<stdlib> com.example.voiceapp3.tools.Iter64TwoProcessMain <dir> <name> <prefix> <count>
 *
 * Процесс пишет `count` строк `"<prefix> <i>"` через production-путь [FileLogCore.appendStamped]
 * и печатает свою метку писателя (для отчёта). Ошибки — в stderr и ненулевой код возврата.
 */
object Iter64TwoProcessMain {

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 4) {
            System.err.println("usage: <dir> <name> <prefix> <count>")
            kotlin.system.exitProcess(2)
        }
        val dir = File(args[0])
        val name = args[1]
        val prefix = args[2]
        val count = args[3].toIntOrNull() ?: 0
        var failed = 0
        for (i in 1..count) {
            val r = FileLogCore.appendStamped(dir, name, "$prefix $i")
            if (!r.written) failed++
            // Небольшая пауза перемежает записи двух процессов во времени — как в жизни.
            if (i % 5 == 0) Thread.sleep(1L)
        }
        println("writer=${EventKeyGen.self} written=${count - failed} failed=$failed")
        if (failed > 0) kotlin.system.exitProcess(1)
    }
}
