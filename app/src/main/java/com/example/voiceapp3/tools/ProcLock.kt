package com.example.voiceapp3.tools

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * iter48 §Г/§Е — межпроцессная сериализация записи через lock-файл.
 *
 * Зачем: схема «прочитать ревизию → записать ревизию+1» внутри одной JVM защищена только
 * JVM-lock. Второй процесс (или второй экземпляр приложения, сервис после перезапуска) этим
 * lock НЕ сериализуется: оба могут прочитать одну и ту же ревизию и записать РАЗНЫЕ значения
 * с одинаковым номером — «конфликт равных ревизий», восстановить порядок которого нельзя.
 *
 * Решение — `FileLock` на lock-файл (межпроцессный примитив ОС, а не оптимизация JVM):
 *  • критическая секция = «прочитать состояние → записать» целиком, под одним lock;
 *  • внутри одного процесса дополнительно [jvmLocks], потому что `FileLock` берётся на канал и
 *    повторный захват из того же JVM даёт `OverlappingFileLockException`;
 *  • ожидание с пределом ([timeoutMs]) — без бесконечного блока: по истечении это
 *    `lock_timeout`, а вызывающий обязан вести себя как при неподтверждённой записи.
 *
 * Чего это НЕ даёт и не заявляет:
 *  • защиты от убийства процесса между записью копии №1 и копии №2 — для этого остаются
 *    ревизии и согласование при чтении (`Toggles45.resolve*`);
 *  • durability при отключении питания: `commit()` + закрытие файла этого не обещают;
 *  • защиты данных, которые пишут другие приложения: наш lock-файл им неизвестен.
 */
internal object ProcLock {

    private val jvmLocks = ConcurrentHashMap<String, ReentrantLock>()

    /** Результат критической секции: `executed=false` — lock не удалось взять за отведённое время. */
    data class Outcome<T>(val executed: Boolean, val value: T?, val waitedMs: Long, val reason: String)

    /**
     * Выполнить [block] под межпроцессным lock-файлом [lockFile].
     *
     * @param timeoutMs предел ожидания; по истечении блок НЕ выполняется (`executed=false`).
     */
    fun <T> withLock(
        lockFile: File,
        timeoutMs: Long = 3_000L,
        block: () -> T
    ): Outcome<T> {
        val key = lockFile.absolutePath
        val jvm = jvmLocks.getOrPut(key) { ReentrantLock() }
        val t0 = System.nanoTime()
        if (!jvm.tryLock(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            return Outcome(false, null, elapsedMs(t0), "jvm_lock_timeout")
        }
        try {
            runCatching { lockFile.parentFile?.mkdirs() }
            val raf = runCatching { RandomAccessFile(lockFile, "rw") }.getOrNull()
                ?: return Outcome(false, null, elapsedMs(t0), "lock_file_unavailable")
            raf.use {
                var lock: FileLock? = null
                val deadline = System.currentTimeMillis() + timeoutMs
                while (lock == null) {
                    lock = runCatching { raf.channel.tryLock() }.getOrNull()
                    if (lock == null) {
                        if (System.currentTimeMillis() >= deadline) {
                            return Outcome(false, null, elapsedMs(t0), "lock_timeout")
                        }
                        runCatching { Thread.sleep(25) }
                    }
                }
                try {
                    return Outcome(true, block(), elapsedMs(t0), "")
                } finally {
                    runCatching { lock?.release() }
                }
            }
        } catch (t: Throwable) {
            return Outcome(false, null, elapsedMs(t0), "lock_error:${t.javaClass.simpleName}")
        } finally {
            jvm.unlock()
        }
    }

    /** Короткая форма: значение или `null`, если lock не взят / блок бросил. */
    fun <T> guarded(lockFile: File, timeoutMs: Long = 3_000L, block: () -> T): T? =
        withLock(lockFile, timeoutMs, block).value

    private fun elapsedMs(t0: Long): Long = (System.nanoTime() - t0) / 1_000_000L
}
