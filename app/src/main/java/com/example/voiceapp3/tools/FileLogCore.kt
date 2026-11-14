package com.example.voiceapp3.tools

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock

/**
 * iter46 §8.1/§8.2 — запись «legacy»-файла журнала (`voiceapp3.log`) как единая операция.
 *
 * Дефекты (по коду и по логу владельца 13.11):
 *  • `AppFileLogger` ротировал файл ДО взятия лока на самом файле: при переименовании inode и
 *    отказе лока координация между процессами не гарантировалась;
 *  • ошибки `rename`/`delete`/`sync` глотались (`catch (_: Throwable) {}`) — провал выглядел
 *    как успех;
 *  • в ХВОСТЕ файла лежал блок `0x00` (3296 байт) ровно на границе смерти процесса.
 *    Отсутствие лока и `fsync` — правдоподобный механизм «дырки», но первопричина НЕ доказана
 *    (нет воспроизведения), поэтому здесь убраны оба механизма, а повреждение детектируется и
 *    сообщается, а не замалчивается.
 *
 * Реализация намеренно без Android-API: это позволяет проверять её JVM-тестами на РЕАЛЬНЫХ
 * файлах (конкурирующие «процессы» = потоки с отдельными экземплярами), а не поиском строк.
 *
 * Порядок под локом (лок на ОТДЕЛЬНОМ файле `<name>.lock`, поэтому ротация его не ломает):
 *   1) выбрать активный файл; 2) при необходимости ротировать (с проверкой каждой операции);
 *   3) проверить хвост на `0x00` (до изменения!); 4) записать, `fsync`, освободить лок.
 */
object FileLogCore {

    /** Результат записи: подтверждённый факт, а не «вызов принят». */
    data class Result(
        val written: Boolean,
        val file: File?,
        val reason: String,
        val rotated: Boolean,
        /** Сколько `0x00` было в хвосте ПЕРЕД записью (диагностика повреждения). */
        val tailNulBefore: Int,
        /** Удалось ли взять межпроцессный лок (`false` — писали без него, это сообщается). */
        val locked: Boolean,
        /**
         * §8.1 iter47: строка сохранена в spill-файл процесса (лок был занят другим процессом),
         * а не в основной журнал. Это НЕ «записано в журнал» — и не должно так называться.
         */
        val spilled: Boolean = false,
        /** Файл, куда строка фактически попала (`null` — никуда). */
        val spillFile: File? = null
    ) {
        fun wire(): String =
            "written=$written rotated=$rotated locked=$locked tail_nul_before=$tailNulBefore" +
                (if (spilled) " spilled_to=«${spillFile?.name}»" else "") +
                (if (reason.isEmpty()) "" else " reason=«$reason»")
    }

    /**
     * Дописать [line] в `dir/name`.
     *
     * @param maxBytes порог ротации (по умолчанию 256 КиБ — прежнее поведение).
     * @param lockWaitMs сколько ждать лок (0 — одна попытка, без ожидания).
     */
    fun append(
        dir: File,
        name: String,
        line: String,
        maxBytes: Long = 256L * 1024,
        lockWaitMs: Long = 0L
    ): Result {
        val active = File(dir, name)
        val lockFile = File(dir, "$name.lock")
        // Внутрипроцессная очередь: FileLock в одной JVM не сериализует потоки (он даёт
        // OverlappingFileLockException), поэтому без неё два потока писали бы в один offset.
        val jvmLock = jvmLocks.computeIfAbsent(active.absolutePath) { java.util.concurrent.locks.ReentrantLock() }
        val jvmAcquired = if (lockWaitMs > 0) {
            runCatching { jvmLock.tryLock(lockWaitMs, java.util.concurrent.TimeUnit.MILLISECONDS) }
                .getOrDefault(false)
        } else runCatching { jvmLock.tryLock() }.getOrDefault(false)
        if (!jvmAcquired) {
            // Определённое поведение: не пишем «как получится», а честно сообщаем занятость.
            return Result(false, active, "jvm_lock_busy", false, 0, false)
        }
        try {
            // Межпроцессный лок на ОТДЕЛЬНОМ стабильном файле: ротация активного файла
            // (rename) не ломает координацию, а сами шаги становятся атомарными.
            var raf: RandomAccessFile? = null
            var lock: FileLock? = null
            try {
                raf = runCatching { RandomAccessFile(lockFile, "rw") }.getOrNull()
                if (raf == null) {
                    // Лок-файл создать нельзя (нет прав/ФС не поддерживает). Определённое
                    // поведение: пишем, но это НЕ выдаётся за норму — reason уходит в журнал.
                    return writeUnderLock(dir, active, name, line, maxBytes, false,
                        locked = false, unlockedReason = "no_lock_file")
                }
                lock = runCatching { acquire(raf, lockWaitMs) }.getOrNull()
                if (lock == null) {
                    // §8.1 iter47: файл лока занят другим процессом. В общий offset вне лока не
                    // пишем (именно так появлялись «дыры» — блок `0x00` в логе владельца), но и
                    // НЕ теряем строку: кладём её в spill-файл ЭТОГО процесса. Spill безопасен без
                    // лока, потому что у каждого процесса он свой, а слияние делает владелец лока.
                    val spill = spillFor(active)
                    val saved = runCatching {
                        RandomAccessFile(spill, "rw").use { out ->
                            out.seek(out.length())
                            out.write(line.toByteArray(Charsets.UTF_8))
                            runCatching { out.fd.sync() }.isSuccess
                        }
                    }.getOrDefault(false)
                    return if (saved)
                        Result(true, active, "process_lock_busy", false, 0, false,
                            spilled = true, spillFile = spill)
                    else
                        Result(false, active, "process_lock_busy_spill_failed", false, 0, false)
                }
                return writeUnderLock(dir, active, name, line, maxBytes, false, locked = true)
            } catch (t: Throwable) {
                return Result(false, active, "io:${t.javaClass.simpleName}:${t.message?.take(60) ?: ""}",
                    false, 0, false)
            } finally {
                runCatching { lock?.release() }
                runCatching { raf?.close() }
            }
        } finally {
            runCatching { jvmLock.unlock() }
        }
    }

    private val jvmLocks = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.locks.ReentrantLock>()

    private fun acquire(raf: RandomAccessFile, waitMs: Long): FileLock? {
        val deadline = System.currentTimeMillis() + waitMs
        var spin = 0
        while (true) {
            // tryLock не блокирует поток журнала; ожидание ограничено.
            val got = runCatching { raf.channel.tryLock() }.getOrNull()
            if (got != null) return got
            if (System.currentTimeMillis() >= deadline) return null
            spin++
            Thread.sleep(if (spin > 5) 20L else 5L)
        }
    }

    private fun writeUnderLock(
        dir: File,
        activeIn: File,
        name: String,
        line: String,
        maxBytes: Long,
        rotated: Boolean,
        locked: Boolean,
        unlockedReason: String = ""
    ): Result {
        var active = activeIn
        var didRotate = rotated
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            return Result(false, active, "no_dir:«${dir.absolutePath}»", false, 0, locked)
        }

        if (active.isFile && active.length() >= maxBytes) {
            val bak = File(dir, "$name.1")
            val fresh = File(dir, "$name.new")
            // §8.2 iter47 — ТРАНЗАКЦИОННЫЙ порядок ротации.
            // Было: удалить `.1` → переименовать активный → создать новый. Отказ на любом шаге
            // после первого удаления терял ПРЕЖНЮЮ часть, а строка уже была потеряна при отказе
            // записи в новый файл.
            // Стало: 1) новая часть с этой же строкой пишется в `.new` и fsync; 2) только после
            // успеха активный файл заменяет `.1` (rename поверх существующего атомарен); 3) `.new`
            // становится активным. Любой отказ оставляет данные: либо в `.new`, либо в активном.
            val wroteNew = runCatching {
                RandomAccessFile(fresh, "rw").use { out ->
                    out.setLength(0)
                    out.write(line.toByteArray(Charsets.UTF_8))
                    runCatching { out.fd.sync() }.isSuccess
                }
            }.getOrDefault(false)
            if (!wroteNew) {
                // Новая часть не создана — старую НЕ трогаем вообще.
                val nul = tailNulBytes(active)
                return appendDirect(active, line, nulBefore = nul, locked = locked)
            }
            if (bak.exists() && !bak.delete()) {
                val nul = tailNulBytes(active)
                return appendDirect(active, line, nulBefore = nul, locked = locked)
            }
            if (!active.renameTo(bak)) {
                // Прежняя часть осталась на месте, новая — в `.new` (данные не потеряны).
                return Result(false, active, "rotate_rename_failed", false, 0, locked)
            }
            if (!fresh.renameTo(active)) {
                // Активного файла нет, но содержимое цело в `.new` — честно сообщаем.
                return Result(false, fresh, "rotate_promote_failed", true, 0, locked)
            }
            return Result(true, active, "", true, 0, locked)
        }

        // §8.2: состояние проверяется ДО записи, иначе наш же новый хвост маскирует повреждение.
        val nulBefore = tailNulBytes(active)

        return try {
            RandomAccessFile(active, "rw").use { out ->
                out.seek(out.length())
                out.write(line.toByteArray(Charsets.UTF_8))
                runCatching { out.fd.sync() }.onFailure {
                    return Result(false, active, "fsync_failed:${it.javaClass.simpleName}",
                        didRotate, nulBefore, locked)
                }
            }
            Result(true, active, if (locked) "" else "unlocked:$unlockedReason",
                didRotate, nulBefore, locked)
        } catch (t: Throwable) {
            Result(false, active, "write_failed:${t.javaClass.simpleName}:${t.message?.take(60) ?: ""}",
                didRotate, nulBefore, locked)
        }
    }

    /** §8.2: обычная дозапись в уже существующий/созданный активный файл. */
    private fun appendDirect(f: File, line: String, nulBefore: Int, locked: Boolean): Result {
        return try {
            RandomAccessFile(f, "rw").use { out ->
                out.seek(out.length())
                out.write(line.toByteArray(Charsets.UTF_8))
                runCatching { out.fd.sync() }.onFailure {
                    return Result(false, f, "fsync_failed:${it.javaClass.simpleName}", false, nulBefore, locked)
                }
            }
            Result(true, f, "", false, nulBefore, locked)
        } catch (t: Throwable) {
            Result(false, f, "write_failed:${t.javaClass.simpleName}", false, nulBefore, locked)
        }
    }

    /**
     * §8.1: spill-файл ЭТОГО процесса. Отдельный на процесс — поэтому его запись не требует
     * межпроцессного лока (общий файл без лока как раз и давал «дыры»).
     */
    fun spillFor(active: File): File {
        val pid = runCatching { java.lang.management.ManagementFactory.getRuntimeMXBean().name }
            .getOrDefault("p")
        return File(active.parentFile, active.name + "." + pid + ".spill")
    }

    /**
     * §8.1: перенести накопленный spill в основной журнал под локом. Вызывается при следующей
     * успешной записи. Возвращает число перенесённых строк (`-1` — не удалось, spill оставлен).
     */
    fun drainSpill(dir: File, name: String): Int {
        val active = File(dir, name)
        val spill = spillFor(active)
        if (!spill.isFile) return 0
        val text = runCatching { spill.readText() }.getOrNull() ?: return -1
        if (text.isEmpty()) {
            runCatching { spill.delete() }
            return 0
        }
        val r = append(dir, name, text, maxBytes = Long.MAX_VALUE)
        if (!r.written || r.spilled) return -1
        runCatching { spill.delete() }
        return text.count { it == '\n' }
    }

    /** Сколько `0x00` подряд в самом хвосте файла (0 — хвост чистый). */
    fun tailNulBytes(f: File): Int = runCatching {
        if (!f.isFile) return@runCatching 0
        RandomAccessFile(f, "r").use { raf ->
            val len = raf.length()
            if (len <= 0) return@runCatching 0
            val probe = kotlin.math.min(len, 64 * 1024L).toInt()
            val buf = ByteArray(probe)
            raf.seek(len - probe)
            raf.readFully(buf)
            Toggles45.trailingNulBytes(buf)
        }
    }.getOrDefault(0)
}
