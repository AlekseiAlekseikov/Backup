package com.example.voiceapp3.tools

import android.car.Car
import android.content.Context
import java.io.File

/**
 * iter33p-fix2 §3/§4 — AVAS в точности по механизму референса (модель + Android-адаптер).
 *
 * Механизм референса CentralEXAuto 3.3.9 (байткод `classes.dex`):
 *  • режим — `Car.createCar(ctx).getCarManager("audio")`: рефлексия `getAVASMode()` / `setAVASMode(int)`
 *    (`w0/d0`, `w0/df`, `w0/qa`);
 *  • sound type — `CarPropertyManager.getIntProperty(propId, area)` / `setIntProperty(propId, area, value)`
 *    (**void**): `w0/g1.c` → `w0/z0.a` → `getIntProperty(II)I`; `w0/g1.l` → `w0/d1.a` →
 *    `setIntProperty(III)V`;
 *  • области — `w0/u.c` = `filled-new-array {0, 1}`; id — `w0/u.b = Politica.h(7)` = 0x21407057
 *    (числа приходят из `libexpolitica.so`; разбор её ИНСТРУКЦИЙ и контроли —
 *    `scripts/derive_avas_sound_prop.py`, методика — `docs/agent/avas_identity.md`);
 *  • запись — `w0/t2.run`: по каждой области `g1.l(cpm, u.b, area, value)`, лог
 *    `"AVAS sound type write area="`, сохранение `avas_sound_type_saved`.
 *
 * Границы (правило владельца 09.11 + REVIEW): пишем ТОЛЬКО сохранённые значения по доказанному id,
 * успех — только по read-back, нечитаемое — `unverified`; пустой sound-снимок НЕ даёт «успеха»;
 * каждое окно привязано к id своего снимка; снимок снимается только в последнем окне.
 * Поведение — в [AvasCore] (без Android, покрыто `AvasCoreTest`).
 */
object AvasRestore {

    private const val TAG = "CENTRAL_AvasRestore"

    /** Id свойства sound type: `w0/u.b = Politica.h(7)` (см. `docs/agent/avas_identity.md`). */
    internal const val PROP_SOUND_TYPE = 0x21407057

    /** Области — ровно как `w0/u.c` (`filled-new-array {0, 1}`). */
    internal val AREAS = intArrayOf(0, 1)

    /** Единственный файл-снимок (атомарная запись temp+rename). */
    internal const val SNAPSHOT_FILE = "avas_snapshot"

    /**
     * Снимок живёт 10 минут (REVIEW iter33p-fix1, 🟡 про TTL — обоснование, а не догадка).
     *
     * Почему TTL вообще: снимок это «намерение вернуть прежние значения ПОСЛЕ перезапуска
     * фреймворка», а не «настройка навсегда». Возврат режима/tonе AVAS спустя часы — это уже
     * отмена выбора водителя, сделанного позже, и за пределами разрешения 09.11.
     *
     * Почему 10 минут, а не бесконечно и не 1 минута: окна сверки заканчиваются на 162 с
     * ([ATTEMPT_SCHEDULE_MS]); перезапуск фреймворка занимает 10–20 с, приложение может
     * подниматься 30–60 с; 10 минут покрывает «рестарт + отложенный старт после сна» с запасом,
     * но не переживает смену водителя/сеанса.
     *
     * Почему просрочка НЕ удаляет снимок молча: [AvasCore.attempt] и [AvasCore.scheduleRestore]
     * пишут отдельную строку `reason=expired`, а сам откат в этом случае не делается —
     * рантайм-значения остаются нетронутыми (безопасное поведение: лучше ничего не писать, чем
     * писать вслепую).
     *
     * Часы назад (`now < ts`, типично для ГУ до синхронизации времени) просрочкой НЕ считаются —
     * см. `AvasRestore.isExpired` + тесты `AvasCoreTest.expiryIsOwnSnapshotOnly`,
     * `scheduleDropsExpiredSnapshot`.
     */
    internal const val SNAPSHOT_TTL_MS = 10 * 60_000L

    /** Окна сверки: ~5 с, ~12 с, далее по 30 с (паттерн референса). */
    internal val ATTEMPT_SCHEDULE_MS =
        longArrayOf(5_000, 12_000, 42_000, 72_000, 102_000, 132_000, 162_000)

    internal const val AUDIO_SERVICE = "audio"
    internal const val PROPERTY_SERVICE = "property"

    /** Разрешённые точки вызова: цепочка тумблера JIT и старт приложения. */
    enum class Where(val label: String) {
        JIT_TOGGLE("jit_toggle"),
        APP_START("app_start");

        companion object {
            fun of(label: String?): Where = values().firstOrNull { it.label == label } ?: APP_START
        }
    }

    /** Одно значение sound type: свойство + область + значение. */
    data class SoundValue(val propId: Int, val area: Int, val value: Int)

    /** Снимок одной операции: id (привязка заданий), время, загрузка, режим, sound type. */
    data class Snapshot(
        val id: Long,
        val tsMs: Long,
        val bootTag: String,
        val mode: Int,
        val sound: List<SoundValue>
    )

    /**
     * Итог попытки. `UNVERIFIED` — что-то не прочиталось (в том числе когда sound type вообще не
     * был снят): это НЕ успех.
     */
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

    /** План попытки: пишем только расхождения и только сохранёнными значениями. */
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
     * Вердикт по read-back.
     *
     * REVIEW §4: при ПУСТОМ sound-снимке `all {}` на пустом списке дал бы «всё совпало» — поэтому
     * пустой снимок никогда не даёт `ok/already_ok`: только `UNVERIFIED` (`sound=not_captured`).
     */
    fun verdict(snapshot: Snapshot, wrote: Boolean, modeAfter: Int?, soundAfter: List<SoundValue>): Verdict {
        fun afterOf(s: SoundValue): Int? =
            soundAfter.firstOrNull { it.propId == s.propId && it.area == s.area }?.value
        val modeOk = modeAfter == snapshot.mode
        val soundCaptured = snapshot.sound.isNotEmpty()
        val allRead = soundCaptured && snapshot.sound.all { afterOf(it) != null }
        val allEqual = allRead && snapshot.sound.all { afterOf(it) == it.value }
        return when {
            !modeOk -> Verdict.FAILED
            !allRead -> Verdict.UNVERIFIED
            !allEqual -> Verdict.FAILED
            wrote -> Verdict.OK
            else -> Verdict.ALREADY_OK
        }
    }

    /** Почему вердикт не полный — отдельным полем строки лога. */
    fun verdictReason(snapshot: Snapshot, verdict: Verdict): String = when {
        snapshot.sound.isEmpty() -> "sound=not_captured"
        verdict == Verdict.UNVERIFIED -> "sound=unreadable"
        verdict == Verdict.FAILED -> "mismatch"
        else -> "none"
    }

    /** Снимок просрочен? (не откатываем ручные изменения, сделанные позже). */
    fun isExpired(snapshot: Snapshot, nowMs: Long, ttlMs: Long = SNAPSHOT_TTL_MS): Boolean =
        nowMs - snapshot.tsMs > ttlMs

    // ─────────────────────── формат снимка (чистые функции) ───────────────────────

    internal fun encode(s: Snapshot): String =
        "v=1 id=${s.id} ts=${s.tsMs} boot=${s.bootTag} mode=${s.mode} " +
            "sound=" + s.sound.joinToString(";") { "${it.propId}:${it.area}:${it.value}" }

    internal fun decode(text: String?): Snapshot? {
        if (text.isNullOrBlank()) return null
        fun tag(name: String): String? = Regex("$name=([^ ]*)").find(text)?.groupValues?.get(1)
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

    // ─────────────────────────────── строки лога ───────────────────────────────

    internal fun soundText(list: List<SoundValue>): String =
        if (list.isEmpty()) "none" else list.joinToString(",") { "${it.value}@${it.area}" }

    internal fun saveLine(snapshot: Snapshot?, mode: Int?, sound: List<SoundValue>, where: Where, reason: String): String =
        if (snapshot == null && mode == null) {
            "AVAS_SAVE skipped reason=$reason where=${where.label}"
        } else {
            "AVAS_SAVE mode=${snapshot?.mode ?: mode} sound_type=${soundText(sound)} " +
                "prop=$PROP_SOUND_TYPE id=${snapshot?.id ?: 0} where=${where.label}" +
                (if (sound.isEmpty()) " reason=sound_not_captured" else "")
        }

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
        "wrote_mode=$wroteMode wrote_sound=$wroteSound result=${verdict.wire} " +
        "reason=${verdictReason(snapshot, verdict)} where=${where.label}"

    internal fun skipLine(reason: String, where: Where, mode: Int? = null, extra: String = ""): String =
        "AVAS_RESTORE skipped reason=$reason" + (mode?.let { " mode=$it" } ?: "") +
            (if (extra.isEmpty()) "" else " $extra") + " where=${where.label}"

    // ─────────────────────── состояние для строк лога (диагностика) ───────────────────────

    @Volatile internal var LAST_SOUND_REASON: String = "no_readback"
    @Volatile internal var LAST_MODE_REASON: String = "no_car_audio"
    @Volatile internal var LAST_SAVE_REASON: String = "none"

    // ─────────────────────────── снимок: один атомарный файл ───────────────────────────

    internal class FileStore(private val app: Context) : SnapshotStore {
        override fun read(): Snapshot? = runCatching {
            val f = File(app.applicationContext.filesDir, SNAPSHOT_FILE)
            if (!f.isFile) null else decode(f.readText())
        }.getOrNull()

        override fun write(snapshot: Snapshot): Boolean = runCatching {
            val dir = app.applicationContext.filesDir
            val tmp = File(dir, "$SNAPSHOT_FILE.tmp")
            val dst = File(dir, SNAPSHOT_FILE)
            tmp.writeText(encode(snapshot))
            if (!tmp.renameTo(dst)) {
                dst.delete()
                check(tmp.renameTo(dst)) { "rename failed" }
            }
            decode(dst.readText())?.id == snapshot.id
        }.getOrElse {
            AppLog.w(TAG, "AVAS_SAVE snapshot write failed: ${it.javaClass.simpleName}")
            false
        }

        override fun clear() {
            runCatching { File(app.applicationContext.filesDir, SNAPSHOT_FILE).delete() }
            runCatching { File(app.applicationContext.filesDir, "$SNAPSHOT_FILE.tmp").delete() }
        }
    }

    // ─────────────────────────── Car API через рефлексию (устройство) ───────────────────────────

    @Volatile private var audioRef: Any? = null
    @Volatile private var propertyRef: Any? = null

    private fun attach(context: Context) {
        val app = context.applicationContext
        audioRef = runCatching { Car.createCar(app)?.getCarManager(AUDIO_SERVICE) }.getOrNull()
        propertyRef = runCatching { Car.createCar(app)?.getCarManager(PROPERTY_SERVICE) }.getOrNull()
        if (audioRef == null) LAST_MODE_REASON = "no_car_audio"
        if (propertyRef == null) LAST_SOUND_REASON = "no_car_property"
    }

    private fun release() {
        audioRef = null
        propertyRef = null
    }

    private fun readMode(): Int? = runCatching {
        val audio = audioRef ?: run { LAST_MODE_REASON = "no_car_audio"; return@runCatching null }
        val v = audio.javaClass.getMethod("getAVASMode").invoke(audio) as? Number
        LAST_MODE_REASON = "method_ok"
        v?.toInt()
    }.onFailure { LAST_MODE_REASON = "no_getAVASMode" }.getOrNull()

    /** `setAVASMode(Integer.TYPE)` — только сохранённое значение; успех подтверждает read-back. */
    private fun writeModeValue(value: Int): Boolean = runCatching {
        val audio = audioRef ?: return@runCatching false
        audio.javaClass.getMethod("setAVASMode", Int::class.javaPrimitiveType).invoke(audio, value)
        true
    }.onFailure {
        AppLog.w(TAG, "AVAS_RESTORE write mode failed: ${it.javaClass.simpleName}: ${it.message}")
    }.getOrDefault(false)

    private fun readSoundValue(propId: Int, area: Int): Int? = runCatching {
        val cpm = propertyRef ?: return@runCatching null
        val m = cpm.javaClass.getMethod(
            "getIntProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
        )
        (m.invoke(cpm, propId, area) as? Number)?.toInt()
    }.getOrNull()

    private fun soundSnapshot(): List<SoundValue> {
        if (propertyRef == null) {
            LAST_SOUND_REASON = "no_car_property"
            return emptyList()
        }
        val out = mutableListOf<SoundValue>()
        for (area in AREAS) {
            val v = readSoundValue(PROP_SOUND_TYPE, area)
            if (v != null) out.add(SoundValue(PROP_SOUND_TYPE, area, v))
        }
        LAST_SOUND_REASON = if (out.isEmpty()) "no_readback" else "ok"
        return out
    }

    /**
     * Запись сохранённого sound type. `setIntProperty(Int, Int, Int)` возвращает **void** (`w0/d1.a`),
     * поэтому «успех» здесь — только отсутствие исключения; подтверждение даёт read-back в
     * [AvasCore.attempt].
     */
    private fun writeSoundValue(s: SoundValue): Boolean = runCatching {
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

    // ─────────────────────────────────── ядро на устройстве ───────────────────────────────────

    private fun core(context: Context): AvasCore {
        val app = context.applicationContext
        return AvasCore(
            store = FileStore(app),
            readMode = { readMode() },
            readSound = { soundSnapshot() },
            writeMode = { v -> writeModeValue(v) },
            writeSound = { s -> writeSoundValue(s) },
            log = { line -> AppLog.i(TAG, line) },
            now = { System.currentTimeMillis() },
            openDevice = { attach(app) },
            closeDevice = { release() }
        )
    }

    fun snapshot(context: Context): Snapshot? = FileStore(context.applicationContext).read()

    internal fun hasPending(context: Context): Boolean = snapshot(context) != null

    internal fun clear(context: Context) = FileStore(context.applicationContext).clear()

    /** Сохранить режим + sound type одним снимком перед рестартом (§3.4а). */
    fun saveBeforeRestart(context: Context, where: Where, bootTag: String): Snapshot? {
        val c = core(context)
        val snap = c.save(bootTag, where)
        LAST_SAVE_REASON = c.lastSaveReason
        return snap
    }

    /**
     * §3.2/§3.4(б) — планирование попыток для КОНКРЕТНОГО снимка (по его id).
     * Задания для другого снимка становятся no-op (`stale_task`).
     */
    fun scheduleRestore(context: Context, where: Where, nowMs: Long = System.currentTimeMillis()) {
        val app = context.applicationContext
        core(app).scheduleRestore(where) { name, delayMs, task ->
            BgWorker.postDelayed(name, delayMs) { runCatching { task() } }
        }
    }

    /** Одна попытка для снимка [id] (отложенные задания и тесты). */
    fun restoreAttempt(context: Context, where: Where, attempt: Int, id: Long): Verdict? =
        core(context).attempt(id, attempt, where)

    /**
     * Отменить снимок отменённой операции (JIT не применялся — восстанавливать нечего).
     * Удаляет только если снимок всё ещё принадлежит этой операции.
     */
    fun discard(context: Context, id: Long, reason: String, where: Where): Boolean =
        core(context).discard(id, reason, where)
}
