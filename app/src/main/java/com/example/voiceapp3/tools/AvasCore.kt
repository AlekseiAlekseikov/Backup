package com.example.voiceapp3.tools

/**
 * iter33p-fix2 §4 — ЧИСТОЕ ядро AVAS: снимок, попытки, окна, отмена заданий.
 *
 * Зачем отдельный файл: REVIEW iter33p-fix1 (замечание 4) показал, что переходы «старые таймеры
 * при новом снимке», «последнее окно», «пустой sound-снимок», «следующий старт» проверяемы только
 * если они не зависят от Android API. Здесь нет ни одного `android.*` — модель и состояние лежат
 * в [AvasRestore], а всё поведение прогоняется обычными JVM-тестами (`AvasCoreTest`).
 *
 * Инварианты:
 *  1. каждая попытка привязана к id СВОЕГО снимка: чужой id — no-op (`stale_task`), новый снимок
 *     не применяется и НЕ удаляется старым заданием;
 *  2. снимок снимается только когда он всё ещё тот же, всё подтверждено и окно последнее;
 *  3. работа с Car API сериализована ([lock]);
 *  4. пустой sound-снимок не даёт `ok/already_ok` — только `unverified`.
 */

/** Хранилище снимка: в приложении — один атомарный файл, в тестах — память. */
interface SnapshotStore {
    fun read(): AvasRestore.Snapshot?
    fun write(snapshot: AvasRestore.Snapshot): Boolean
    fun clear()
}

class AvasCore(
    private val store: SnapshotStore,
    private val readMode: () -> Int?,
    private val readSound: () -> List<AvasRestore.SoundValue>,
    private val writeMode: (Int) -> Boolean,
    private val writeSound: (AvasRestore.SoundValue) -> Boolean,
    private val log: (String) -> Unit,
    private val now: () -> Long,
    private val openDevice: () -> Unit = {},
    private val closeDevice: () -> Unit = {},
    private val ttlMs: Long = AvasRestore.SNAPSHOT_TTL_MS,
    private val scheduleMs: LongArray = AvasRestore.ATTEMPT_SCHEDULE_MS
) {
    private val lock = Any()

    /** Уже запланированные id: планируем на каждый снимок, но не дважды на один и тот же. */
    private val scheduledIds = java.util.Collections.synchronizedSet(mutableSetOf<Long>())

    @Volatile var lastModeReason: String = "no_car_audio"
        private set

    @Volatile var lastSoundReason: String = "no_readback"
        private set

    @Volatile var lastSaveReason: String = "none"
        private set

    /** Монотонный id снимка: две операции в одну миллисекунду не должны получить один id. */
    private var lastId: Long = 0L

    private fun nextId(): Long {
        val id = maxOf(now(), lastId + 1)
        lastId = id
        return id
    }

    /** Сколько заданий реально запускалось — для тестов и диагностики. */
    @Volatile var startedTasks: Int = 0
        private set

    fun pending(): AvasRestore.Snapshot? = store.read()

    fun noteModeReason(reason: String) { lastModeReason = reason }

    fun noteSoundReason(reason: String) { lastSoundReason = reason }

    /** Прочитать режим + sound type и записать ОДИН атомарный снимок. */
    fun save(bootTag: String, where: AvasRestore.Where, id: Long = 0L): AvasRestore.Snapshot? {
        synchronized(lock) {
            openDevice()
            val snapId = if (id > 0L) id else nextId()
            try {
                val mode = readMode()
                val sound = runCatching { readSound() }.getOrDefault(emptyList())
                lastSoundReason = if (sound.isEmpty()) "no_readback" else "ok"
                if (mode == null) {
                    lastSaveReason = lastModeReason
                    log(AvasRestore.saveLine(null, null, sound, where, lastModeReason))
                    return null
                }
                val snapshot = AvasRestore.Snapshot(snapId, snapId, bootTag, mode, sound)
                val ok = store.write(snapshot)
                lastSaveReason = if (ok) "ok" else "write_failed"
                log(AvasRestore.saveLine(if (ok) snapshot else null, mode, sound, where, if (ok) "" else "write_failed"))
                return if (ok) snapshot else null
            } finally {
                closeDevice()
            }
        }
    }

    /**
     * Запланировать окна для ТЕКУЩЕГО снимка (по его id). Просроченный снимок выбрасывается.
     * [post] — планировщик `post(name, delayMs, task)` (в приложении — `BgWorker.postDelayed`).
     */
    fun scheduleRestore(
        where: AvasRestore.Where,
        post: (String, Long, () -> Unit) -> Unit
    ): Long? {
        val snap = store.read() ?: return null
        if (AvasRestore.isExpired(snap, now(), ttlMs)) {
            store.clear()
            log(AvasRestore.skipLine("expired", where, snap.mode, "age_ms=${now() - snap.tsMs}"))
            return null
        }
        if (!scheduledIds.add(snap.id)) return snap.id
        log("AVAS_RESTORE scheduled id=${snap.id} attempts=${scheduleMs.size} " +
            "delays_ms=${scheduleMs.joinToString(",")} where=${where.label}")
        val id = snap.id
        scheduleMs.forEachIndexed { idx, delay ->
            val attempt = idx + 1
            post("AvasRestore.restore#$attempt", delay) { attempt(id, attempt, where) }
        }
        return id
    }

    /**
     * Одна попытка для снимка [id]. Если текущий снимок другой — no-op: старое задание НЕ применяет
     * и НЕ удаляет новый снимок.
     */
    fun attempt(id: Long, attempt: Int, where: AvasRestore.Where): AvasRestore.Verdict? {
        synchronized(lock) {
            startedTasks += 1
            val snap = store.read() ?: return null
            if (snap.id != id) {
                log(AvasRestore.skipLine("stale_task", where, snap.mode,
                    "task_id=$id current_id=${snap.id} attempt=$attempt"))
                return null
            }
            if (AvasRestore.isExpired(snap, now(), ttlMs)) {
                store.clear()
                log(AvasRestore.skipLine("expired", where, snap.mode, "age_ms=${now() - snap.tsMs}"))
                return null
            }
            val lastWindow = attempt >= scheduleMs.size
            openDevice()
            try {
                val modeBefore = readMode()
                val soundBefore = runCatching { readSound() }.getOrDefault(emptyList())
                val p = AvasRestore.plan(snap, modeBefore, soundBefore)
                // Неизвестное ≠ выключенное: если режим не читается, НИЧЕГО не пишем (слепая запись
                // недоказуема), снимок остаётся и следующее окно попробует снова.
                if (p.modeUnreadable) {
                    log(AvasRestore.skipLine("no_getAVASMode", where, snap.mode,
                        "attempt=$attempt reason=mode_unreadable sound_unreadable=${p.soundUnreadable.size}"))
                    return AvasRestore.Verdict.UNVERIFIED
                }
                var wroteMode = false
                var wroteSound = 0
                if (p.needMode) wroteMode = writeMode(snap.mode)
                for (s in p.writeSound) if (writeSound(s)) wroteSound++
                val modeAfter = readMode()
                val soundAfter = runCatching { readSound() }.getOrDefault(emptyList())
                val v = AvasRestore.verdict(snap, wroteMode || wroteSound > 0, modeAfter, soundAfter)
                log(AvasRestore.attemptLine(attempt, snap, modeAfter ?: modeBefore, soundAfter, v, where, wroteMode, wroteSound))
                val stillMine = store.read()?.id == snap.id
                if ((v == AvasRestore.Verdict.OK || v == AvasRestore.Verdict.ALREADY_OK) && lastWindow && stillMine) {
                    store.clear()
                    log(AvasRestore.skipLine("cleared", where, snap.mode, "attempt=$attempt reason=confirmed"))
                } else if (v == AvasRestore.Verdict.FAILED && lastWindow) {
                    log(AvasRestore.skipLine("final_failed", where, snap.mode, "attempt=$attempt"))
                }
                return v
            } finally {
                closeDevice()
            }
        }
    }

    /**
     * Отменить снимок конкретной операции (рестарт не состоялся — JIT не применялся).
     * Удаляет только если снимок всё ещё принадлежит этой операции.
     */
    fun discard(id: Long, reason: String, where: AvasRestore.Where): Boolean {
        synchronized(lock) {
            val snap = store.read() ?: return false
            if (snap.id != id) {
                log(AvasRestore.skipLine("discard_skip", where, snap.mode,
                    "task_id=$id current_id=${snap.id} reason=$reason"))
                return false
            }
            store.clear()
            log(AvasRestore.skipLine("discarded", where, snap.mode, "reason=$reason"))
            return true
        }
    }
}
