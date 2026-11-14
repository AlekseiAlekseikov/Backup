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
            // §Е iter48: раньше строка при занятом JVM-локе просто ТЕРЯЛАСЬ (Result с
            // jvm_lock_busy и всё). Теперь она уходит в spill ЭТОГО процесса — он пишется под
            // отдельным коротким локом и не требует общего lock-файла.
            val saved = spillAppend(active, line)
            return Result(
                false, active,
                if (saved) "jvm_lock_busy:spilled" else "jvm_lock_busy:spill_failed",
                false, 0, false
            )
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
            // §Е iter48: 1) `.new` НЕ обнуляется — если в нём остались данные от прошлой
            // неудачной ротации, они сохраняются (дописываем, а не setLength(0)).
            // iter49 §8: состояние `.new` ДО нашей дозаписи. Если там остались данные прошлой
            // неудачной ротации, класть эту же строку ещё и в активный файл нельзя — при
            // следующем успешном повышении `.new` строка попала бы в журнал дважды.
            val freshHadData = runCatching { fresh.isFile && fresh.length() > 0L }
                .getOrDefault(false)
            val wroteNew = runCatching {
                RandomAccessFile(fresh, "rw").use { out ->
                    out.seek(out.length())
                    out.write(line.toByteArray(Charsets.UTF_8))
                    runCatching { out.fd.sync() }.isSuccess
                }
            }.getOrDefault(false)
            if (!wroteNew) {
                // Новая часть не создана — старую НЕ трогаем вообще.
                val nul = tailNulBytes(active)
                return appendDirect(active, line, nulBefore = nul, locked = locked)
            }
            // §Е iter48: `.1` НЕ удаляется перед переименованием. Прежний код сначала делал
            // `delete(.1)`, и отказ следующего шага (rename) означал ПОТЕРЮ предыдущей части.
            // `Files.move(..., REPLACE_EXISTING, ATOMIC_MOVE)` заменяет `.1` одной атомарной
            // операцией: либо старое `.1`, либо новое, но никогда «ни одного».
            val moved = runCatching {
                java.nio.file.Files.move(
                    active.toPath(), bak.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE
                )
                true
            }.getOrElse {
                // ФС не поддержала ATOMIC_MOVE/REPLACE — пробуем обычный move без предудаления.
                runCatching {
                    java.nio.file.Files.move(
                        active.toPath(), bak.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING
                    )
                    true
                }.getOrDefault(false)
            }
            if (!moved) {
                // Прежняя часть осталась на месте (например, `.1` — непустой каталог).
                // iter49 §8: раньше здесь строка дозаписывалась в АКТИВНЫЙ файл, хотя `.new`
                // уже содержит НОВУЮ часть (с этой же строкой). При следующей удачной ротации
                // `.new` становится активным — и эти строки оказывались в журнале ДВАЖДЫ.
                // Теперь при непустом `.new` дозапись идёт только в него (duplicate=false),
                // а в активный файл пишем лишь когда `.new` пуст (там нечего потерять).
                if (freshHadData) {
                    return Result(true, fresh, "rotate_rename_failed:kept_in_new", false, 0,
                        locked)
                }
                runCatching { fresh.delete() }
                val nul = tailNulBytes(active)
                val direct = appendDirect(active, line, nulBefore = nul, locked = locked)
                return direct.copy(
                    rotated = false,
                    reason = if (direct.reason.isEmpty()) "rotate_rename_failed:appended_to_active"
                    else direct.reason
                )
            }
            val promoted = runCatching {
                java.nio.file.Files.move(
                    fresh.toPath(), active.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE
                )
                true
            }.getOrElse { fresh.renameTo(active) }
            if (!promoted) {
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
        // Идентификатор ПРОЦЕССА без Android/Java-management API (на Android
        // `java.lang.management` отсутствует): `/proc/self` есть и на JVM, и на устройстве.
        return File(active.parentFile, active.name + "." + processTag + ".spill")
    }

    /** Лок на запись spill ВНУТРИ процесса (у каждого процесса свой файл). */
    private val spillLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    /**
     * §Е iter48: дозаписать строку в spill этого процесса.
     *
     * Возврат `false` — spill тоже недоступен; тогда и только тогда строка потеряна, и это
     * видно в `reason` (`spill_failed`). `fsync` не заявляется обязательным: при отключении
     * питания последние строки могут не дойти до диска — это ограничение записи на Android.
     */
    fun spillAppend(active: File, line: String): Boolean {
        val spill = spillFor(active)
        val gate = spillLocks.computeIfAbsent(spill.absolutePath) { Any() }
        return synchronized(gate) {
            runCatching {
                spill.parentFile?.mkdirs()
                RandomAccessFile(spill, "rw").use { out ->
                    out.seek(out.length())
                    out.write(line.toByteArray(Charsets.UTF_8))
                    runCatching { out.fd.sync() }
                }
                true
            }.getOrDefault(false)
        }
    }

    /** Стабильный в пределах процесса тег (pid из `/proc/self`, иначе — случайный). */
    private val processTag: String by lazy {
        runCatching { File("/proc/self").canonicalFile.name }.getOrNull()
            ?.takeIf { it.isNotEmpty() && it.all { ch -> ch.isDigit() } }
            ?: ("p" + Integer.toHexString(System.identityHashCode(Any())))
    }

    /**
     * §8.1: перенести накопленный spill в основной журнал под локом. Вызывается при следующей
     * успешной записи. Возвращает число перенесённых строк (`-1` — не удалось, spill оставлен).
     */
    fun drainSpill(dir: File, name: String): Int {
        val active = File(dir, name)
        val spill = spillFor(active)
        if (!spill.isFile) return 0
        // §Е iter48: слияние и удаление spill — одна операция под локом spill. Раньше чтение,
        // запись и удаление шли врозь: строка, дописанная другим потоком МЕЖДУ чтением и
        // удалением, терялась; а при отказе записи spill всё равно мог быть удалён вызывающим.
        val gate = spillLocks.computeIfAbsent(spill.absolutePath) { Any() }
        return synchronized(gate) {
            // Сначала переименовываем spill в «свой» файл слияния: новые записи пойдут в
            // свежий spill и не потеряются, даже если слияние не удастся.
            val work = File(spill.parentFile, spill.name + ".drain")
            if (!runCatching { spill.renameTo(work) }.getOrDefault(false) && !work.isFile) {
                // Переименовать нельзя (чужой процесс/ФС) — читаем как есть, но НЕ удаляем при
                // неудачной записи.
                val text = runCatching { spill.readText() }.getOrNull() ?: return@synchronized -1
                if (text.isEmpty()) {
                    runCatching { spill.delete() }
                    return@synchronized 0
                }
                val r = append(dir, name, text, maxBytes = Long.MAX_VALUE)
                if (!r.written || r.spilled) return@synchronized -1
                runCatching { spill.delete() }
                return@synchronized text.count { it == '\n' }
            }
            val text = runCatching { work.readText() }.getOrNull()
            if (text.isNullOrEmpty()) {
                runCatching { work.delete() }
                return@synchronized 0
            }
            val r = append(dir, name, text, maxBytes = Long.MAX_VALUE)
            if (!r.written || r.spilled) {
                // Не удалось — возвращаем данные обратно в spill, ничего не теряем.
                val restored = runCatching {
                    RandomAccessFile(spill, "rw").use { out ->
                        out.seek(out.length())
                        out.write(text.toByteArray(Charsets.UTF_8))
                        runCatching { out.fd.sync() }.isSuccess
                    }
                }.getOrDefault(false)
                // iter49 §8: если вернуть данные не удалось, `.drain` НЕ удаляем — иначе строки
                // журнала исчезают вместе с файлом при скрытом отказе переноса.
                if (restored) runCatching { work.delete() }
                return@synchronized -1
            }
            runCatching { work.delete() }
            text.count { it == '\n' }
        }
    }

    /**
     * iter49 §8: `0x00` ВНУТРИ файла, а не только в хвосте.
     *
     * Прежняя проверка смотрела лишь последние 64 КиБ: блок нулей в середине (после чего журнал
     * дописывался) не обнаруживался вовсе. Возвращает {@code -1}, если файл прочитать нельзя —
     * это НЕ «нулей нет».
     */
    fun nulInside(f: File): Int = runCatching {
        if (!f.isFile) return@runCatching 0
        var count = 0
        java.io.FileInputStream(f).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                for (i in 0 until n) if (buf[i] == 0.toByte()) count++
            }
        }
        count
    }.getOrDefault(-1)

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
