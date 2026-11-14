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
            // §8.2 iter50: РАЗДЕЛЯЕМ «записано» и «синхронизировано». Раньше результат
            // `fsync` был ВСЕМ результатом записи в `.new`: при отказе fsync (сама запись уже
            // сделана) код уходил в `appendDirect(active, …)` и та же строка оказывалась и в
            // `.new`, и в активном файле — дубль после следующего повышения `.new`.
            var newWritten = false
            var newSynced = false
            runCatching {
                RandomAccessFile(fresh, "rw").use { out ->
                    out.seek(out.length())
                    out.write(line.toByteArray(Charsets.UTF_8))
                    newWritten = true
                    newSynced = runCatching { out.fd.sync() }.isSuccess
                }
            }
            val wroteNew = newWritten
            if (wroteNew && !newSynced) {
                // Строка лежит в `.new`; в активный файл её НЕ дублируем — честно сообщаем,
                // что подтверждения записи на носитель нет.
                return Result(true, fresh, "new_fsync_failed:kept_in_new", false, 0, locked)
            }
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
                // §8.2 iter50: результат удаления `.new` больше не игнорируется. Если удалить
                // не удалось, а строка дописана в активный файл, при следующем повышении `.new`
                // она может появиться дважды — это сказано в причине, а не скрыто.
                val freshRemoved = runCatching { fresh.delete() }.getOrDefault(false)
                val nul = tailNulBytes(active)
                val direct = appendDirect(active, line, nulBefore = nul, locked = locked)
                val base = when {
                    freshRemoved -> "rotate_rename_failed:appended_to_active"
                    else -> "rotate_rename_failed:new_not_removed_dup_possible"
                }
                return direct.copy(rotated = false, reason = if (direct.reason.isEmpty()) base else direct.reason)
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

        // §8.2 iter50 — ВОССТАНОВЛЕНИЕ после падения между `move(active→.1)` и повышением `.new`.
        // Тогда активного файла нет, а новая часть лежит в `.new`. Раньше `.new` повышался только
        // при следующей ротации (а она требует непустой активный файл), поэтому часть журнала
        // могла остаться в `.new` навсегда. Повышаем только когда терять нечего (active нет).
        if (!active.isFile) {
            val pending = File(dir, "$name.new")
            if (pending.isFile && pending.length() > 0L) {
                val promoted = runCatching {
                    java.nio.file.Files.move(
                        pending.toPath(), active.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE
                    )
                    true
                }.getOrElse { pending.renameTo(active) }
                if (!promoted) {
                    return Result(false, pending, "recover_new_promote_failed", false, 0, locked)
                }
            }
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

    /** §8.1 iter50: честная заметка о последнем слиянии (пусто — всё прошло штатно). */
    @Volatile
    private var drainNote: String = ""

    /** Заметка о последнем [drainSpill]: почему не всё снялось/что могло задвоиться. */
    fun lastDrainNote(): String = drainNote

    /** Счётчик уникальных имён work-файлов в пределах процесса. */
    private val drainSeq = java.util.concurrent.atomic.AtomicInteger(0)

    /** Является ли файл work-файлом слияния spill (`.drain`, `.drain.<...>`, без `.lock`). */
    private fun isDrainOf(f: File, spillName: String): Boolean =
        f.isFile && f.name.startsWith("$spillName.drain") && !f.name.endsWith(".lock")

    /**
     * §8.1 iter50: УНИКАЛЬНОЕ имя work-файла.
     *
     * Раньше work всегда был `<spill>.drain`, а `spill.renameTo(work)` на файловых системах,
     * разрешающих замену, МОЛЧА заменял уже сохранённый work-файл (например, оставшийся от
     * процесса, умершего между rename и слиянием) — сохранённые строки исчезали вместе с ним.
     */
    private fun uniqueWork(spill: File): File =
        File(spill.parentFile, "${spill.name}.drain.${processTag}-${drainSeq.incrementAndGet()}")

    /**
     * §7 iter51: сколько времени после последней записи pid считается живым.
     * 30 минут: дольше процесса, который просто висит без записи в журнал, но заведомо меньше
     * времени жизни «осиротевшего» файла, который надо забрать.
     */
    private val ORPHAN_GRACE_MS = 30L * 60L * 1000L

    /** §7 iter51: где искать `/proc/<pid>` (подменяется в тестах). */
    internal var procRoot: File = File("/proc")

    /** §7 iter51: часы (подменяются в тестах). */
    internal var clock: () -> Long = { System.currentTimeMillis() }

    /**
     * §7 iter51: считаем ли процесс ЦИФРОВОГО pid живым.
     *
     * PID ПЕРЕИСПОЛЬЗУЕТСЯ: `/proc/<pid>` может существовать, но принадлежать уже ДРУГОМУ
     * процессу. Поэтому одного наличия каталога недостаточно — живым считаем только если файл
     * журнала этого pid изменялся недавно ([ORPHAN_GRACE_MS]). Обратная ошибка тоже недопустима:
     * пока процесс жив и ЕЩЁ может дописать, забирать его spill нельзя (получим дубли и гонку),
     * поэтому «не уверены → не трогаем».
     */
    private fun pidLooksAlive(pid: String, files: List<File>): Boolean {
        if (pid == processTag) return true
        if (!pid.all { it.isDigit() }) return true   // `p<hash>` без pid — своим не считаем
        if (!File(procRoot, pid).exists()) return false
        val newest = files.maxOfOrNull { it.lastModified() } ?: 0L
        return clock() - newest <= ORPHAN_GRACE_MS
    }

    /**
     * §7 iter51: осиротевшие (`spill`/`.drain`) файлы ВСЕХ наших pid для этого журнала.
     *
     * Дефект: восстановление смотрело только на `spill` СВОЕГО pid (`<log>.<pid>.spill`).
     * Умерший процесс оставлял `<log>.<pid>.spill` и `.drain.<pid>-N`, которые больше никто и
     * никогда не забирал — строки журнала оставались на диске навсегда. Здесь они находятся, а
     * живые и чужие (другое имя журнала) не затрагиваются.
     *
     * @return пары «файл → его pid», отсортированные по имени (детерминированный порядок).
     */
    internal fun orphanFilesOf(dir: File, name: String): List<Pair<File, String>> {
        val re = Regex("^" + Regex.escape(name) + "\\.(\\d+|p[0-9a-f]+)\\.spill(\\.drain.*)?$")
        val all = runCatching { dir.listFiles()?.toList() ?: emptyList() }.getOrDefault(emptyList())
        val byPid = mutableMapOf<String, MutableList<File>>()
        for (f in all) {
            if (!f.isFile) continue
            if (f.name.endsWith(".lock")) continue
            val m = re.find(f.name) ?: continue
            byPid.getOrPut(m.groupValues[1]) { mutableListOf() }.add(f)
        }
        val out = mutableListOf<Pair<File, String>>()
        for ((pid, files) in byPid) {
            if (pid == processTag) continue               // свой — штатным путём ниже
            if (pidLooksAlive(pid, files)) {
                // Живой (или неотличимый от живого) — не трогаем; факт виден в заметке.
                continue
            }
            files.sortedBy { it.name }.forEach { out.add(it to pid) }
        }
        return out
    }

    /**
     * §8.1: перенести накопленный spill в основной журнал под локом. Возвращает число
     * перенесённых строк (`-1` — не удалось целиком, данные не удалены).
     *
     * Порядок iter50:
     *  1. Сначала восстанавливаются ВСЕ оставшиеся work-файлы (`.drain*`) — независимо от того,
     *     есть ли новый spill. Раньше `drainSpill` выходил по `if (!spill.isFile) return 0`, и
     *     `.drain`, оставшийся после смерти процесса, не восстанавливался уже никогда.
     *  2. Слияние каждого work-файла идёт под МЕЖПРОЦЕССНЫМ lock-файлом `<work>.lock`: если
     *     владелец жив и уже сливает — мы не вмешиваемся (`skipped_locked`), а не дублируем.
     *  3. Свой spill переименовывается в УНИКАЛЬНОЕ имя (чужие/старые work-файлы не затираются).
     *  4. Исходник удаляется только после подтверждённой дозаписи; неудача `delete` не скрывается,
     *     а попадает в [lastDrainNote] (возможные дубли честно обозначены).
     */
    fun drainSpill(dir: File, name: String): Int {
        val active = File(dir, name)
        val spill = spillFor(active)
        drainNote = ""
        var merged = 0
        var failed = false
        var skipped = 0
        val notes = mutableListOf<String>()

        // 0) §7 iter51: сначала забираем файлы УМЕРШИХ наших процессов (свои — ниже, штатно).
        var orphans = 0
        for ((f, pid) in orphanFilesOf(dir, name)) {
            val w: File
            if (f.name.contains(".drain")) {
                w = f
            } else {
                // Чужой по pid, но наш по журналу spill: переносим в уникальное имя и сливаем
                // штатным путём. Если переименовать нельзя — не читаем «на месте и не удаляя»:
                // иначе строки могли бы задвоиться.
                val t = uniqueWork(f)
                if (!runCatching { f.renameTo(t) }.getOrDefault(false)) {
                    notes += "orphan_rename_failed:«${f.name}»pid=$pid"
                    continue
                }
                w = t
            }
            val n = mergeWork(active, dir, name, w, notes)
            when {
                n == Int.MIN_VALUE -> {
                    skipped++
                    notes += "orphan_locked:«${w.name}»pid=$pid"
                }
                n < 0 -> {
                    failed = true
                    notes += "orphan_not_merged:«${w.name}»pid=$pid"
                }
                else -> {
                    orphans++
                    merged += n
                }
            }
        }
        if (orphans > 0) notes += "orphans_recovered:$orphans"

        // 1) Восстановление оставшихся work-файлов — всегда, даже если нового spill нет.
        val leftovers = runCatching {
            dir.listFiles()?.filter { isDrainOf(it, spill.name) } ?: emptyList()
        }.getOrDefault(emptyList())
        for (w in leftovers.sortedBy { it.name }) {
            val n = mergeWork(active, dir, name, w, notes)
            when {
                // Порядок важен: Int.MIN_VALUE — это «занято живым владельцем», а НЕ отказ.
                n == Int.MIN_VALUE -> skipped++
                n < 0 -> failed = true
                else -> merged += n
            }
        }

        if (spill.isFile) {
            val gate = spillLocks.computeIfAbsent(spill.absolutePath) { Any() }
            synchronized(gate) {
                val work = uniqueWork(spill)
                val renamed = runCatching { spill.renameTo(work) }.getOrDefault(false)
                val n = if (renamed) {
                    val r = mergeWork(active, dir, name, work, notes)
                    if (r == Int.MIN_VALUE) {
                        // Переименовали, но слияние занято живым владельцем: вернуть данные
                        // обратно в spill, чтобы строки не остались «нигде».
                        val text = runCatching { work.readText() }.getOrNull()
                        val back = if (text.isNullOrEmpty()) true else runCatching {
                            RandomAccessFile(spill, "rw").use { out ->
                                out.seek(out.length())
                                out.write(text.toByteArray(Charsets.UTF_8))
                                runCatching { out.fd.sync() }.isSuccess
                            }
                        }.getOrDefault(false)
                        if (back) {
                            if (!deleteVerified(work)) {
                                notes += "work_dup_possible:«${work.name}»"
                            }
                            0
                        } else {
                            notes += "work_not_merged_and_not_restored:«${work.name}»"
                            -1
                        }
                    } else r
                } else {
                    // Переименовать нельзя (чужой процесс/ФС) — читаем на месте, НЕ удаляя при
                    // неудачной записи.
                    val text = runCatching { spill.readText() }.getOrNull()
                    if (text.isNullOrEmpty()) {
                        // §7: пустой spill, который не удалось удалить, — не «успех по умолчанию».
                        if (!deleteVerified(spill)) notes += "empty_spill_kept:«${spill.name}»"
                        0
                    } else {
                        val r = append(dir, name, text, maxBytes = Long.MAX_VALUE)
                        if (!r.written || r.spilled) -1 else {
                            val del = deleteVerified(spill)
                            if (!del) notes += "spill_delete_failed:«${spill.name}»"
                            text.count { it == '\n' }
                        }
                    }
                }
                when {
                    n < 0 -> failed = true
                    else -> merged += n
                }
            }
        }

        if (skipped > 0) notes += "skipped_locked:$skipped"
        drainNote = notes.joinToString(",")
        return when {
            failed -> -1
            merged > 0 -> merged
            skipped > 0 -> 0
            merged == 0 && notes.isEmpty() -> 0
            else -> merged
        }
    }

    /**
     * §7 iter51: удаление проверяется по ВОЗВРАЩЁННОМУ boolean, а не по «не было исключения».
     *
     * `runCatching { f.delete() }.isSuccess` истинно и тогда, когда `delete()` вернул `false`
     * (файл не удалён: занят, нет прав, уже подменён). Из-за этого «удалено» заявлялось для
     * файлов, которые оставались на диске, и журнал терял след дублей.
     *
     * @return true — файла больше нет (удалён сейчас или отсутствовал); false — остался.
     */
    internal fun deleteVerified(f: File): Boolean {
        val ok = runCatching { f.delete() }.getOrDefault(false)
        if (ok) return true
        // `delete()` == false может означать и «файла уже нет» — это НЕ отказ.
        return !f.exists()
    }

    /**
     * Слить ОДИН work-файл в основной журнал.
     *
     * @return число строк; `-1` — запись не удалась (work-файл сохранён);
     *   [Int.MIN_VALUE] — файл занят живым владельцем (не влезаем).
     */
    private fun mergeWork(
        active: File,
        dir: File,
        name: String,
        work: File,
        notes: MutableList<String>
    ): Int {
        if (!work.isFile) return 0
        val lockFile = File(work.parentFile, work.name + ".lock")
        val outcome = ProcLock.withLock(lockFile, timeoutMs = 500L) {
            val text = runCatching { work.readText() }.getOrNull()
            if (text.isNullOrEmpty()) {
                if (!deleteVerified(work)) notes += "empty_work_kept:«${work.name}»"
                return@withLock 0
            }
            val r = append(dir, name, text, maxBytes = Long.MAX_VALUE)
            if (!r.written || r.spilled) {
                // Не удалось — work остаётся как есть (данные не теряются).
                return@withLock -1
            }
            // §8.1(2): исходник удаляется только после подтверждённой дозаписи; неудача не
            // скрывается — при повторе возможны дубли, и это сказано прямо.
            if (!deleteVerified(work)) {
                notes += "work_delete_failed:«${work.name}»( возможны дубли при повторе )"
            }
            if (!deleteVerified(lockFile)) notes += "lockfile_kept:«${lockFile.name}»"
            text.count { it == '\n' }
        }
        if (!outcome.executed) return Int.MIN_VALUE
        return outcome.value ?: -1
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
