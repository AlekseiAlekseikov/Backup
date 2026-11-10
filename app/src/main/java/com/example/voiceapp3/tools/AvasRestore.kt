package com.example.voiceapp3.tools

import android.car.Car
import android.content.Context
import java.io.File

/**
 * iter33p-fix1 §3/§4 — возврат AVAS в точности по механизму референса (CentralEXAuto 3.3.9:
 * `w0/qa` переключатель, `w0/d0`/`w0/df` восстановление, `w0/u`, `w0/g1` доступ к свойствам).
 *
 * Что делает референс (подтверждено байткодом):
 *  • режим — `Car.createCar(ctx).getCarManager("audio")`: рефлексия `getAVASMode()` → `Integer`,
 *    запись — `setAVASMode(Integer.TYPE)`;
 *  • sound type — `CarPropertyManager.getIntProperty(propId, area)` по областям `{0, 1}`
 *    (`w0/u.c = filled-new-array {0, 1}`), запись — `setIntProperty(propId, area, value)`
 *    (**возвращает void**, `w0/d1.a`);
 *  • id свойства sound type — `w0/u.b = Politica.h(7)`; число приходит из нативной таблицы
 *    `libexpolitica.so`, поэтому вычислено разбором .so: см. [PROP_SOUND_TYPE] и
 *    `scripts/derive_avas_sound_prop.py` (скрипт печатает таблицу и самопроверку);
 *  • восстановление после подъёма — НЕ сразу: задержки ~5 с и ~12 с, далее ретраи (паттерн AEB).
 *
 * Жёсткие границы (правило владельца 09.11 + REVIEW iter33p):
 *  • пишем ТОЛЬКО то, что сами прочитали и сохранили: `setAVASMode(saved)` и
 *    `setIntProperty(saved)` по сохранённым областям; никаких «0 по умолчанию» и чужих значений;
 *  • идентичность свойства доказана ([PROP_SOUND_TYPE]); поиск «по имени» и подсказки владельца
 *    не используются — если свойство не читается, звук НЕ пишем и честно логируем;
 *  • успех — только по read-back. Нечитаемое значение — это `unverified`, а НЕ «совпало»;
 *  • снимок (режим + sound type) пишется одним атомарным файлом: смешать два снимка нельзя;
 *  • снимок живёт [SNAPSHOT_TTL_MS]; просроченный — выбрасывается, чтобы не откатывать
 *    ручные изменения владельца, сделанные позже.
 */
object AvasRestore {

    private const val TAG = "CENTRAL_AvasRestore"

    /**
     * Id свойства sound type из референса: `w0/u.b = Politica.h(7)`.
     *
     * Доказательство (`scripts/derive_avas_sound_prop.py`, воспроизводимо):
     *  • `Politica.h(i)` = `arr[i]`, где `arr[i] = buf[i+1]` — так строит массив `Politica.c`
     *    (`new int[buf[0]]`, `arr[i] = f(i*4+4, buf)`);
     *  • `buf` собирает `nativeCall(5, …)` в `libexpolitica.so` (cmd 5 = таблица id);
     *  • методика проверена двумя полевыми id НАШЕГО кода, лежащими подряд:
     *    `h(0x15) = 0x2140A377` (`PROP_AC_AMBIENT_TEMP`) и `h(0x16) = 0x2140A379`
     *    (`PROP_AC_INSIDE_TEMP`, csv:987);
     *  • значит `h(7) = 0x21407057` — id свойства sound type, области {0, 1}.
     */
    internal const val PROP_SOUND_TYPE = 0x21407057

    /** Области sound type — ровно как в байткоде референса (`w0/u.c`). */
    internal val AREAS = intArrayOf(0, 1)

    /** Единственный файл-снимок (атомарная запись, никаких «половинок»). */
    internal const val SNAPSHOT_FILE = "avas_snapshot"

    /** Снимок живёт 10 минут: после этого ручные изменения владельца не откатываются. */
    internal const val SNAPSHOT_TTL_MS = 10 * 60_000L

    /**
     * §3.2 — расписание попыток (мс от начала): ~5 с, ~12 с, далее ретраи по 30 с (до 5 штук).
     * Проверки идут и ПОСЛЕ раннего совпадения — контрольные окна сохраняются; запись — только
     * при обнаружении расхождения.
     */
    internal val ATTEMPT_SCHEDULE_MS = longArrayOf(5_000, 12_000, 42_000, 72_000, 102_000, 132_000, 162_000)

    internal const val AUDIO_SERVICE = "audio"
    internal const val PROPERTY_SERVICE = "property"

    /** Разрешённые точки вызова (§3.4): цепочка тумблера JIT и старт приложения. */
    enum class Where(val label: String) {
        JIT_TOGGLE("jit_toggle"),
        APP_START("app_start");

        companion object {
            fun of(label: String?): Where = values().firstOrNull { it.label == label } ?: APP_START
        }
    }

    /** Решение «что делать» с режимом — чистая функция. */
    enum class Action { NONE, ALREADY_OK, RESTORE, FAIL }

    fun decide(saved: Int?, found: Int?): Action = when {
        saved == null -> Action.NONE
        found == null -> Action.FAIL
        found == saved -> Action.ALREADY_OK
        else -> Action.RESTORE
    }

    /** Одно значение sound type: свойство + область + прочитанное значение. */
    data class SoundValue(val propId: Int, val area: Int, val value: Int)

    /**
     * Снимок одной операции: id (для планирования конкретной операции), время, тег загрузки,
     * режим и sound type. Всё — одним файлом.
     */
    data class Snapshot(
        val id: Long,
        val tsMs: Long,
        val bootTag: String,
        val mode: Int,
        val sound: List<SoundValue>
    )

    /** Итог одной попытки. `UNVERIFIED` — значение не прочиталось: это НЕ успех. */
    enum class Verdict(val wire: String) {
        OK("ok"), ALREADY_OK("already_ok"), UNVERIFIED("unverified"), FAILED("failed")
    }

    /** Что делать в этой попытке. */
    data class Plan(
        val needMode: Boolean,
        val writeSound: List<SoundValue>,
        val modeUnreadable: Boolean,
        val soundUnreadable: List<SoundValue>
    )

    /**
     * План попытки по факту чтения. Пишем только расхождения; нечитаемое — не повод писать
     * (и не повод считать «совпало»).
     */
    fun plan(snapshot: Snapshot, modeNow: Int?, soundNow: List<SoundValue>): Plan {
        fun nowOf(s: SoundValue): Int? =
            soundNow.firstOrNull { it.propId == s.propId && it.area == s.area }?.value
        val unreadable = snapshot.sound.filter { nowOf(it) == null }
        val diffs = snapshot.sound.filter { s -> nowOf(s) != null && nowOf(s) != s.value }
        return Plan(
            needMode = modeNow != null && modeNow != snapshot.mode,
            writeSound = diffs,
            modeUnreadable = modeNow == null,
            soundUnreadable = unreadable
        )
    }

    /**
     * Вердикт попытки (после возможной записи): `ok` — писали и всё подтвердилось read-back;
     * `already_ok` — не писали и всё подтвердилось; `unverified` — режим совпал, но прочитать
     * sound type не удалось; `failed` — расхождение или режим не читается.
     */
    fun verdict(snapshot: Snapshot, wrote: Boolean, modeAfter: Int?, soundAfter: List<SoundValue>): Verdict {
        fun afterOf(s: SoundValue): Int? =
            soundAfter.firstOrNull { it.propId == s.propId && it.area == s.area }?.value
        val modeOk = modeAfter == snapshot.mode
        val allRead = snapshot.sound.all { afterOf(it) != null }
        val allEqual = snapshot.sound.all { afterOf(it) == it.value }
        return when {
            !modeOk -> Verdict.FAILED
            !allRead -> Verdict.UNVERIFIED
            !allEqual -> Verdict.FAILED
            wrote -> Verdict.OK
            else -> Verdict.ALREADY_OK
        }
    }

    /** Снимок просрочен? (не откатываем ручные изменения, сделанные позже). */
    fun isExpired(snapshot: Snapshot, nowMs: Long, ttlMs: Long = SNAPSHOT_TTL_MS): Boolean =
        nowMs - snapshot.tsMs > ttlMs

    // ─────────────────────────── формат снимка (чистые функции) ───────────────────────────

    internal fun encode(s: Snapshot): String =
        "v=1 id=${s.id} ts=${s.tsMs} boot=${s.bootTag} mode=${s.mode} " +
            "sound=" + s.sound.joinToString(";") { "${it.propId}:${it.area}:${it.value}" }

    internal fun decode(text: String?): Snapshot? {
        if (text.isNullOrBlank()) return null
        fun tag(name: String): String? =
            Regex("$name=([^ ]*)").find(text)?.groupValues?.get(1)
        val id = tag("id")?.toLongOrNull() ?: return null
        val ts = tag("ts")?.toLongOrNull() ?: return null
        val mode = tag("mode")?.toIntOrNull() ?: return null
        val boot = tag("boot").orEmpty()
        val sound = tag("sound").orEmpty().split(";").mapNotNull { part ->
            if (part.isBlank()) return@mapNotNull null
            val p = part.split(":")
            if (p.size != 3) return@mapNotNull null
            val prop = p[0].toIntOrNull() ?: return@mapNotNull null
            val area = p[1].toIntOrNull() ?: return@mapNotNull null
            val v = p[2].toIntOrNull() ?: return@mapNotNull null
            SoundValue(prop, area, v)
        }
        return Snapshot(id, ts, boot, mode, sound)
    }

    /** Строка лога сохранения. */
    internal fun saveLine(snapshot: Snapshot?, mode: Int?, sound: List<SoundValue>, where: Where, reason: String): String = when {
        snapshot == null && mode == null -> "AVAS_SAVE skipped reason=$reason where=${where.label}"
        else -> "AVAS_SAVE mode=${snapshot?.mode ?: mode} " +
            "sound_type=" + (if (sound.isEmpty()) "unavailable" else sound.joinToString(",") { "${it.value}@${it.area}" }) +
            " prop=$PROP_SOUND_TYPE id=${snapshot?.id ?: 0} where=${where.label}" +
            (if (sound.isEmpty()) " reason=$LAST_SOUND_REASON" else "")
    }

    /** Строка попытки восстановления: все поля — факты, итог — [Verdict]. */
    internal fun attemptLine(
        attempt: Int,
        snapshot: Snapshot,
        modeNow: Int?,
        soundNow: List<SoundValue>,
        verdict: Verdict,
        where: Where,
        wroteMode: Boolean,
        wroteSound: Int
    ): String = "AVAS_RESTORE attempt=$attempt id=${snapshot.id} mode_saved=${snapshot.mode} " +
        "mode_now=${modeNow?.toString() ?: "unreadable"} " +
        "sound_saved=${soundText(snapshot.sound)} sound_now=${soundText(soundNow)} " +
        "wrote_mode=$wroteMode wrote_sound=$wroteSound result=${verdict.wire} where=${where.label}"

    private fun soundText(list: List<SoundValue>): String =
        if (list.isEmpty()) "none" else list.joinToString(",") { "${it.value}@${it.area}" }

    internal fun skipLine(reason: String, where: Where, mode: Int? = null, extra: String = ""): String =
        "AVAS_RESTORE skipped reason=$reason" + (mode?.let { " mode=$it" } ?: "") +
            (if (extra.isEmpty()) "" else " $extra") + " where=${where.label}"

    @Volatile internal var LAST_SOUND_REASON: String = "no_readback"

    /** Почему сохранение не удалось (для строки `JIT_APPLIED … reason_avas=`). */
    @Volatile internal var LAST_SAVE_REASON: String = "none"

    // ─────────────────────────────────── сохранение ───────────────────────────────────

    /**
     * §3.1 — прочитать режим + sound type и записать ОДИН атомарный снимок. Только чтение с
     * устройства. Возвращает снимок или null (тогда восстанавливать нечего, и цепочка JIT
     * продолжается без AVAS).
     */
    fun save(context: Context, where: Where, bootTag: String, nowMs: Long = System.currentTimeMillis()): Snapshot? {
        attach(context)
        try {
            val mode = readMode()
            val sound = soundSnapshot()
            if (mode == null) {
                LAST_SAVE_REASON = LAST_MODE_REASON
                AppLog.i(TAG, saveLine(null, mode, sound, where, LAST_MODE_REASON))
                return null
            }
            val snapshot = Snapshot(
                id = nowMs,
                tsMs = nowMs,
                bootTag = bootTag,
                mode = mode,
                sound = sound
            )
            val ok = writeSnapshot(context, snapshot)
            LAST_SAVE_REASON = if (ok) "ok" else "write_failed"
            AppLog.i(TAG, saveLine(if (ok) snapshot else null, mode, sound, where, if (ok) "" else "write_failed"))
            return if (ok) snapshot else null
        } finally {
            release()
        }
    }

    /** Атомарная запись: temp-файл + rename (никаких «половинных» снимков). */
    internal fun writeSnapshot(context: Context, snapshot: Snapshot): Boolean = runCatching {
        val dir = context.applicationContext.filesDir
        val tmp = File(dir, "$SNAPSHOT_FILE.tmp")
        val dst = File(dir, SNAPSHOT_FILE)
        tmp.writeText(encode(snapshot))
        if (!tmp.renameTo(dst)) {
            dst.delete()
            check(tmp.renameTo(dst)) { "rename failed" }
        }
        true
    }.getOrElse {
        AppLog.w(TAG, "AVAS_SAVE snapshot write failed: ${it.javaClass.simpleName}")
        false
    }

    fun snapshot(context: Context): Snapshot? = runCatching {
        val f = File(context.applicationContext.filesDir, SNAPSHOT_FILE)
        if (!f.isFile) null else decode(f.readText())
    }.getOrNull()

    internal fun hasPending(context: Context): Boolean = snapshot(context) != null

    internal fun clear(context: Context) {
        runCatching { File(context.applicationContext.filesDir, SNAPSHOT_FILE).delete() }
        runCatching { File(context.applicationContext.filesDir, "$SNAPSHOT_FILE.tmp").delete() }
    }

    // ─────────────────────── восстановление по расписанию (контрольные окна) ───────────────────────

    /** Какая операция уже запланирована — планируем на каждый снимок (по его id). */
    @Volatile private var scheduledId: Long = 0L

    /**
     * §3.2/§3.4(б) — планирование попыток для КОНКРЕТНОГО снимка. Просроченный снимок
     * выбрасывается (чтобы не откатывать ручные изменения владельца).
     */
    fun scheduleRestore(context: Context, where: Where, nowMs: Long = System.currentTimeMillis()) {
        val app = context.applicationContext
        val snap = snapshot(app) ?: return
        if (isExpired(snap, nowMs)) {
            clear(app)
            AppLog.i(TAG, skipLine("expired", where, snap.mode, "age_ms=${nowMs - snap.tsMs}"))
            return
        }
        if (scheduledId == snap.id) return
        scheduledId = snap.id
        AppLog.i(TAG, "AVAS_RESTORE scheduled id=${snap.id} attempts=${ATTEMPT_SCHEDULE_MS.size} " +
            "delays_ms=${ATTEMPT_SCHEDULE_MS.joinToString(",")} where=${where.label}")
        ATTEMPT_SCHEDULE_MS.forEachIndexed { idx, delayMs ->
            val attempt = idx + 1
            BgWorker.postDelayed("AvasRestore.restore#$attempt", delayMs) {
                runCatching { restoreAttempt(app, where, attempt) }
                    .onFailure { AppLog.w(TAG, "AVAS_RESTORE attempt=$attempt failed: ${it.javaClass.simpleName}") }
            }
        }
    }

    /**
     * Одна попытка: прочитать факт → записать ТОЛЬКО расхождения (сохранёнными значениями) →
     * перечитать. Снимок снимается только когда (а) всё подтверждено и (б) это последнее
     * контрольное окно, либо (в) снимок просрочен.
     */
    fun restoreAttempt(context: Context, where: Where, attempt: Int, nowMs: Long = System.currentTimeMillis()): Verdict? {
        val app = context.applicationContext
        val snap = snapshot(app) ?: return null
        if (isExpired(snap, nowMs)) {
            clear(app)
            AppLog.i(TAG, skipLine("expired", where, snap.mode, "age_ms=${nowMs - snap.tsMs}"))
            return null
        }
        attach(app)
        try {
            val modeBefore = readMode()
            val soundBefore = soundSnapshot()
            val p = plan(snap, modeBefore, soundBefore)
            if (p.modeUnreadable && snap.sound.isEmpty()) {
                // Ничего не читается — честный skip, без записи и без «успеха».
                AppLog.i(TAG, skipLine("no_getAVASMode", where, snap.mode))
                return Verdict.UNVERIFIED
            }
            var wroteMode = false
            var wroteSound = 0
            if (p.needMode) {
                wroteMode = writeMode(snap.mode)
            }
            for (s in p.writeSound) {
                if (writeSound(s)) wroteSound++
            }
            val modeAfter = readMode()
            val soundAfter = soundSnapshot()
            val v = verdict(snap, wroteMode || wroteSound > 0, modeAfter, soundAfter)
            AppLog.i(TAG, attemptLine(attempt, snap, modeAfter ?: modeBefore, soundAfter, v, where, wroteMode, wroteSound))
            val lastWindow = attempt >= ATTEMPT_SCHEDULE_MS.size
            if (v == Verdict.OK || v == Verdict.ALREADY_OK) {
                if (lastWindow) clear(app)
            } else if (v == Verdict.FAILED && lastWindow) {
                // Финальный честный fail: снимок остаётся до TTL — следующая операция/старт
                // попробует ещё раз, но просроченный уже не применится.
                AppLog.i(TAG, skipLine("final_failed", where, snap.mode, "attempt=$attempt"))
            }
            return v
        } finally {
            release()
        }
    }

    // ─────────────────────────── Car API через рефлексию ───────────────────────────

    private fun carAudio(context: Context): Any? = runCatching {
        val car = Car.createCar(context.applicationContext) ?: return@runCatching null
        car.getCarManager(AUDIO_SERVICE)
    }.getOrNull()

    private fun carProperty(context: Context): Any? = runCatching {
        val car = Car.createCar(context.applicationContext) ?: return@runCatching null
        car.getCarManager(PROPERTY_SERVICE)
    }.getOrNull()

    @Volatile internal var LAST_MODE_REASON: String = "no_car_audio"

    /** `getAVASMode()` → Int или null (метода нет / бросил / не число). */
    fun readMode(): Int? = runCatching {
        val audio = audioRef ?: run { LAST_MODE_REASON = "no_car_audio"; return@runCatching null }
        val v = audio.javaClass.getMethod("getAVASMode").invoke(audio)
        LAST_MODE_REASON = "method_ok"
        (v as? Number)?.toInt()
    }.onFailure {
        LAST_MODE_REASON = when (it) {
            is NoSuchMethodException -> "no_getAVASMode"
            is SecurityException -> "security"
            else -> "reflection_error"
        }
    }.getOrNull()

    /** `setAVASMode(Integer.TYPE)` — только сохранённое значение; успех подтверждает read-back. */
    private fun writeMode(value: Int): Boolean = runCatching {
        val audio = audioRef ?: return@runCatching false
        audio.javaClass.getMethod("setAVASMode", Int::class.javaPrimitiveType).invoke(audio, value)
        true
    }.onFailure {
        AppLog.w(TAG, "AVAS_RESTORE write mode failed: ${it.javaClass.simpleName}: ${it.message}")
    }.getOrDefault(false)

    /** Снимок sound type по областям {0, 1} (`getIntProperty`), как в референсе. */
    internal fun soundSnapshot(): List<SoundValue> {
        val cpm = propertyRef ?: run { LAST_SOUND_REASON = "no_car_property"; return emptyList() }
        val out = mutableListOf<SoundValue>()
        for (area in AREAS) {
            val v = readSound(cpm, PROP_SOUND_TYPE, area)
            if (v != null) out.add(SoundValue(PROP_SOUND_TYPE, area, v))
        }
        LAST_SOUND_REASON = if (out.isEmpty()) "no_readback" else "ok"
        return out
    }

    private fun readSound(cpm: Any, propId: Int, area: Int): Int? = runCatching {
        val m = cpm.javaClass.getMethod(
            "getIntProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
        )
        (m.invoke(cpm, propId, area) as? Number)?.toInt()
    }.getOrNull()

    /**
     * Запись сохранённого sound type. `setIntProperty(Int, Int, Int)` в этом API возвращает
     * **void** (см. `w0/d1.a`), поэтому «успех» здесь — только отсутствие исключения;
     * фактическое подтверждение даёт read-back в [restoreAttempt].
     */
    private fun writeSound(s: SoundValue): Boolean = runCatching {
        val cpm = propertyRef ?: return@runCatching false
        cpm.javaClass.getMethod(
            "setIntProperty",
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
        ).invoke(cpm, s.propId, s.area, s.value)
        AppLog.i(TAG, "restoreSavedAvasSoundType area=${s.area} prop=${s.propId} value=${s.value} requested=yes")
        true
    }.onFailure {
        AppLog.w(TAG, "restoreSavedAvasSoundType area=${s.area} failed: ${it.javaClass.simpleName}: ${it.message}")
    }.getOrDefault(false)

    @Volatile private var audioRef: Any? = null
    @Volatile private var propertyRef: Any? = null

    fun attach(context: Context) {
        val app = context.applicationContext
        audioRef = carAudio(app)
        propertyRef = carProperty(app)
        if (audioRef == null) LAST_MODE_REASON = "no_car_audio"
    }

    fun release() {
        audioRef = null
        propertyRef = null
    }

    /** Обёртка «сохранить перед рестартом фреймворка» (§3.4а). */
    fun saveBeforeRestart(context: Context, where: Where, bootTag: String): Snapshot? =
        save(context, where, bootTag)
}
