package com.example.voiceapp3.tools

import java.io.File

/**
 * iter64/iter65 — ПИСАТЕЛЬ ЖУРНАЛА ДЛЯ ПРОВЕРКИ ДВУМЯ РЕАЛЬНЫМИ ПРОЦЕССАМИ.
 *
 * Зачем отдельный `main`: тест внутри одной JVM («два экземпляра состояния») доказывает
 * уникальность ключа, но НЕ доказывает, что два настоящих процесса не потеряют запись при
 * одновременном слиянии/ротации. Именно это и был дефект iter63 (`rid` — счётчик процесса с 0).
 *
 * Запуск:
 *   java [-Dvoiceapp3.noProc=1] -cp <classes>:<stdlib> \
 *        com.example.voiceapp3.tools.Iter64TwoProcessMain <dir> <name> <prefix> <count> [maxBytes]
 *
 * iter65 §1.2: добавлены необязательный `maxBytes` (чтобы ротация гарантированно случалась в
 * пределах теста) и печать интервала `start=`/`end=` — без него нельзя доказать, что процессы
 * работали ОДНОВРЕМЕННО, а не по очереди (дефект iter64: тест был последовательным).
 *
 * `-Dvoiceapp3.noProc=1` заставляет считать `/proc` недоступным: проверяется fallback-метка
 * писателя ([EventKeyGen.randomTag]) — та самая, что работает там, где предсказуемости нет.
 */
object Iter64TwoProcessMain {

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 4) {
            System.err.println("usage: <dir> <name> <prefix> <count> [maxBytes]")
            kotlin.system.exitProcess(2)
        }
        val dir = File(args[0])
        val name = args[1]
        val prefix = args[2]
        val count = args[3].toIntOrNull() ?: 0
        val maxBytes = args.getOrNull(4)?.toLongOrNull() ?: (256L * 1024)

        val start = System.currentTimeMillis()
        var failed = 0
        var spilled = 0
        for (i in 1..count) {
            val r = FileLogCore.appendStamped(dir, name, "$prefix $i", maxBytes = maxBytes)
            // Строка в spill-файле процесса — это СОХРАНЁННАЯ строка (production-путь
            // [AppFileLogger] вливает её следующей записью), а не потеря.
            if (!r.written && !r.reason.endsWith(":spilled")) failed++
            if (r.spilled) spilled++
            if (i % 5 == 0) Thread.sleep(1L)
        }
        // Финальное слияние собственного spill — как это делает AppFileLogger после записи.
        val drained = runCatching { FileLogCore.drainSpill(dir, name) }.getOrDefault(-1)
        val end = System.currentTimeMillis()
        println(
            "writer=${EventKeyGen.self} start=$start end=$end written=${count - failed} " +
                "failed=$failed spilled=$spilled drained=$drained " +
                "no_proc=${EventKeyGen.procDisabled()} max_bytes=$maxBytes"
        )
        if (failed > 0) kotlin.system.exitProcess(1)
    }
}
