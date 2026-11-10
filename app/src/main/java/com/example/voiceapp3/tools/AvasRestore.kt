package com.example.voiceapp3.tools

import android.car.Car
import android.content.Context
import java.io.File

/**
 * iter33p §3 — возврат AVAS в точности по механизму референса (CentralEXAuto 3.3.9,
 * байткод `w0/qa`, `w0/df`, `w0/d0`).
 *
 * Что делает референс (подтверждено байткодом + jadx `MainActivity.G`):
 *  • режим — `Car.createCar(ctx).getCarManager("audio")`, рефлексия `getAVASMode()` → `Integer`,
 *    запись — `setAVASMode(Integer.TYPE)`;
 *  • sound type — `CarPropertyManager.getInt(propId, area)` по областям `{0, 1}`
 *    (в байткоде `w0/u.c = filled-new-array {0, 1}`), запись — `setIntProperty(propId, area, value)`
 *    (`w0/g1.l`); идентификатор свойства референс берёт из нативной таблицы (`Politica.h(7)`),
 *    в dex его нет — поэтому у нас он определяется на месте (см. [resolveSoundPropId]);
 *  • восстановление после подъёма сервиса — НЕ сразу: `postDelayed` ~5 с и ~12 с, дальше ретраи
 *    (паттерн AEB «до 5 × 30 с»), перед записью читается факт.
 *
 * Границы (правило владельца 09.11, жёсткие):
 *  • пишем ТОЛЬКО то, что сами прочитали: `setAVASMode(savedMode)` и `setIntProperty(savedValue)`
 *    по тем же областям; никаких «0 по умолчанию», чужих значений и sound type «на глаз»;
 *  • ничего не мутим, звуковые файлы и звуковой демон не трогаем;
 *  • метод/Car API недоступны — честная строка `skipped reason=…`, цепочка JIT продолжается.
 */
object AvasRestore {

    private const val TAG = "CENTRAL_AvasRestore"

    /** Файлы-намерения в `filesDir`: режим и снимок sound type (на одну операцию). */
    internal const val MODE_FILE = "avas_mode_saved"
    internal const val SOUND_FILE = "avas_sound_saved"

    /** Закреплённый владельцем id свойства sound type (если подсказан в отчёте/префах). */
    internal const val SOUND_PROP_PREF = "avas_sound_prop_id"

    internal const val AUDIO_SERVICE = "audio"
    internal const val PROPERTY_SERVICE = "property"

    /** Области sound type — ровно как в байткоде референса (`w0/u.c`). */
    internal val AREAS = intArrayOf(0, 1)

    /**
     * §3.2 — расписание попыток восстановления (мс от момента планирования):
     * первая через ~5 с, повтор ~12 с, дальше до 5 повторов по 30 с.
     */
    internal val ATTEMPT_SCHEDULE_MS = longArrayOf(5_000, 12_000, 42_000, 72_000, 102_000, 132_000, 162_000)

    /** Разрешённые точки вызова (§3.4): цепочка тумблера JIT и старт приложения. */
    enum class Where(val label: String) {
        JIT_TOGGLE("jit_toggle"),
        APP_START("app_start");

        companion object {
            fun of(label: String?): Where =
                values().firstOrNull { it.label == label } ?: APP_START
        }
    }

    /** Решение «что делать» с режимом — чистая функция, покрыта `AvasRestoreTest`. */
    enum class Action { NONE, ALREADY_OK, RESTORE, FAIL }

    /**
     * • [Action.NONE]       — сохранённого значения нет: делать нечего;
     * • [Action.ALREADY_OK] — режим уже равен сохранённому: ничего не пишем;
     * • [Action.RESTORE]    — отличается: ТОЛЬКО возврат сохранённого значения;
     * • [Action.FAIL]       — сохранённое есть, а прочитать текущее не смогли (Car API молчит).
     */
    fun decide(saved: Int?, found: Int?): Action = when {
        saved == null -> Action.NONE
        found == null -> Action.FAIL
        found == saved -> Action.ALREADY_OK
        else -> Action.RESTORE
    }

    /** Одно значение sound type: свойство + область + прочитанное значение. */
    data class SoundValue(val propId: Int, val area: Int, val value: Int)

    /** Расписание планируется один раз за процесс (следующий старт — новый процесс). */
    private val scheduledOnce = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Итог одной попытки — строка `result=` в логе. */
    enum class Verdict(val wire: String) { ALREADY_OK("already_ok"), OK("ok"), FAILED("failed") }

    /**
     * Вердикт попытки. `ok` — только по read-back: писали и перечитали ровно сохранённое.
     * `already_ok` — до записи факт уже совпал (писать нечего). Ложный успех запрещён.
     */
    fun verdict(
        savedMode: Int?,
        beforeMode: Int?,
        afterMode: Int?,
        soundOk: Boolean,
        wrote: Boolean
    ): Verdict = when {
        savedMode == null -> Verdict.FAILED
        beforeMode == null && afterMode == null -> Verdict.FAILED
        !wrote && beforeMode == savedMode && soundOk -> Verdict.ALREADY_OK
        afterMode == savedMode && soundOk -> Verdict.OK
        else -> Verdict.FAILED
    }

    /** Строка сохранения (§3.1). */
    internal fun saveLine(mode: Int?, sound: List<SoundValue>, where: Where): String = when {
        mode == null -> "AVAS_SAVE skipped reason=$LAST_SAVE_REASON where=${where.label}"
        sound.isEmpty() ->
            "AVAS_SAVE mode=$mode sound_type=unavailable reason=$LAST_SOUND_REASON where=${where.label}"
        else -> "AVAS_SAVE mode=$mode sound_type=${sound.joinToString(",") { "${it.value}@${it.area}" }} " +
            "prop=${sound.first().propId} where=${where.label}"
    }

    /** Строка одной попытки восстановления (§3.2): все поля — факты, результат — [Verdict]. */
    internal fun attemptLine(
        attempt: Int,
        savedMode: Int,
        modeNow: Int?,
        soundSaved: String,
        soundNow: String,
        verdict: Verdict,
        where: Where
    ): String = "AVAS_RESTORE attempt=$attempt mode_saved=$savedMode " +
        "mode_now=${modeNow?.toString() ?: "unknown"} sound_saved=${soundSaved.ifEmpty { "none" }} " +
        "sound_now=${soundNow.ifEmpty { "none" }} result=${verdict.wire} where=${where.label}"

    internal fun skipLine(reason: String, where: Where, mode: Int? = null): String =
        "AVAS_RESTORE skipped reason=$reason" + (mode?.let { " mode=$it" } ?: "") + " where=${where.label}"

    /** Причина последнего неуспешного сохранения/чтения — попадает в лог как есть. */
    @Volatile internal var LAST_SAVE_REASON: String = "no_car_api"
    @Volatile internal var LAST_SOUND_REASON: String = "no_sound_prop"

    // ─────────────────────────────── сохранение ───────────────────────────────

    /**
     * Прочитать режим + sound type и сохранить их (§3.1). Вызывать ДО рестарта фреймворка,
     * только с фонового потока. Возвращает сохранённый режим или null.
     */
    fun save(context: Context, where: Where): Int? {
        attach(context)
        return try {
            val mode = readMode()
            val sound = soundSnapshot(context)
            if (mode == null) {
                AppLog.i(TAG, saveLine(null, sound, where))
                return null
            }
            runCatching {
                File(context.applicationContext.filesDir, MODE_FILE).writeText(mode.toString())
                if (sound.isNotEmpty()) {
                    File(context.applicationContext.filesDir, SOUND_FILE)
                        .writeText(sound.joinToString("\n") { "${it.propId}:${it.area}:${it.value}" })
                }
            }.onFailure { AppLog.w(TAG, "AVAS_SAVE file write failed: ${it.javaClass.simpleName}") }
            AppLog.i(TAG, saveLine(mode, sound, where))
            mode
        } finally {
            release()
        }
    }

    fun savedMode(context: Context): Int? = runCatching {
        val f = File(context.applicationContext.filesDir, MODE_FILE)
        if (!f.isFile) null else f.readText().trim().toIntOrNull()
    }.getOrNull()

    internal fun savedSound(context: Context): List<SoundValue> = runCatching {
        val f = File(context.applicationContext.filesDir, SOUND_FILE)
        if (!f.isFile) emptyList()
        else f.readLines().mapNotNull { l ->
            val p = l.trim().split(":")
            if (p.size != 3) null
            else SoundValue(p[0].toIntOrNull() ?: return@mapNotNull null,
                p[1].toIntOrNull() ?: return@mapNotNull null,
                p[2].toIntOrNull() ?: return@mapNotNull null)
        }
    }.getOrDefault(emptyList())

    internal fun hasPending(context: Context): Boolean =
        File(context.applicationContext.filesDir, MODE_FILE).isFile

    internal fun clearSaved(context: Context) {
        runCatching { File(context.applicationContext.filesDir, MODE_FILE).delete() }
        runCatching { File(context.applicationContext.filesDir, SOUND_FILE).delete() }
    }

    // ─────────────────────────── восстановление по расписанию ───────────────────────────

    /**
     * §3.2/§3.4(б): планирование попыток после подъёма фреймворка. Сами попытки идут фоново по
     * [ATTEMPT_SCHEDULE_MS]; каждая следующая — no-op, если файл-намерение уже снят (успех).
     */
    fun scheduleRestore(context: Context, where: Where) {
        val app = context.applicationContext
        if (!hasPending(app)) return
        if (!scheduledOnce.compareAndSet(false, true)) return
        AppLog.i(TAG, "AVAS_RESTORE scheduled attempts=${ATTEMPT_SCHEDULE_MS.size} " +
            "delays_ms=${ATTEMPT_SCHEDULE_MS.joinToString(",")} where=${where.label}")
        ATTEMPT_SCHEDULE_MS.forEachIndexed { idx, delayMs ->
            val attempt = idx + 1
            BgWorker.postDelayed("AvasRestore.restore#$attempt", delayMs) {
                if (!hasPending(app)) return@postDelayed
                runCatching { restoreAttempt(app, where, attempt) }
                    .onFailure { AppLog.w(TAG, "AVAS_RESTORE attempt=$attempt failed: ${it.javaClass.simpleName}") }
            }
        }
    }

    /**
     * Одна попытка (§3.2/§3.3): прочитать факт → при расхождении записать ТОЛЬКО сохранённое →
     * перечитать. Успех снимает намерение (дальше попытки — no-op).
     */
    fun restoreAttempt(context: Context, where: Where, attempt: Int): Boolean {
        val app = context.applicationContext
        val saved = savedMode(app) ?: return false
        val savedSound = savedSound(app)
        attach(app)
        try {
            val before = readMode()
            if (before == null) {
                AppLog.i(TAG, skipLine("no_getAVASMode", where, saved))
                // Последняя попытка — снимаем намерение: иначе каждый старт снова жёг бы
                // все попытки на машине, где метода нет (цепочка JIT при этом не страдает).
                if (attempt >= ATTEMPT_SCHEDULE_MS.size) clearSaved(app)
                return false
            }
            val soundSaved = savedSound.joinToString(",") { "${it.value}@${it.area}" }
            var soundOk = true
            var wrote = false
            if (before != saved) {
                wrote = true
                writeMode(saved)
                // sound type возвращаем тем же проходом — только сохранённые значения.
                for (s in savedSound) {
                    if (!writeSound(s)) soundOk = false
                }
            } else if (savedSound.isNotEmpty()) {
                val now = soundSnapshot(app)
                val diff = savedSound.filter { s -> now.firstOrNull { it.propId == s.propId && it.area == s.area }?.value != s.value }
                if (diff.isNotEmpty()) {
                    wrote = true
                    for (s in diff) if (!writeSound(s)) soundOk = false
                }
            }
            val after = readMode()
            val soundNow = soundSnapshot(app).joinToString(",") { "${it.value}@${it.area}" }
            val v = verdict(saved, before, after, soundOk, wrote)
            AppLog.i(TAG, attemptLine(attempt, saved, after ?: before, soundSaved, soundNow, v, where))
            if (v == Verdict.OK || v == Verdict.ALREADY_OK) clearSaved(app)
            return v != Verdict.FAILED
        } finally {
            release()
        }
    }

    // ─────────────────────────── Car API через рефлексию ───────────────────────────

    /** `Car.createCar(ctx).getCarManager("audio")` — как в референсе; null при любой ошибке. */
    private fun carAudio(context: Context): Any? = runCatching {
        val car = Car.createCar(context.applicationContext) ?: return@runCatching null
        car.getCarManager(AUDIO_SERVICE)
    }.getOrNull()

    /** `Car.createCar(ctx).getCarManager("property")` → CarPropertyManager (sound type). */
    private fun carProperty(context: Context): Any? = runCatching {
        val car = Car.createCar(context.applicationContext) ?: return@runCatching null
        car.getCarManager(PROPERTY_SERVICE)
    }.getOrNull()

    /** `getMethod("getAVASMode", null).invoke(audio)` → Int или null (метода нет / бросил). */
    fun readMode(): Int? = runCatching {
        val audio = audioRef ?: run { LAST_SAVE_REASON = "no_car_audio"; return@runCatching null }
        val m = audio.javaClass.getMethod("getAVASMode")
        val v = m.invoke(audio)
        LAST_SAVE_REASON = "method_ok"
        (v as? Number)?.toInt()
    }.onFailure {
        LAST_SAVE_REASON = when (it) {
            is NoSuchMethodException -> "no_getAVASMode"
            is SecurityException -> "security"
            else -> "reflection_error"
        }
    }.getOrNull()

    /** `getMethod("setAVASMode", Integer.TYPE).invoke(audio, savedMode)` — только сохранённое значение. */
    private fun writeMode(value: Int): Boolean = runCatching {
        val audio = audioRef ?: return@runCatching false
        val m = audio.javaClass.getMethod("setAVASMode", Int::class.javaPrimitiveType)
        m.invoke(audio, value)
        true
    }.onFailure {
        AppLog.w(TAG, "AVAS_RESTORE write mode failed: ${it.javaClass.simpleName}: ${it.message}")
    }.getOrDefault(false)

    /**
     * Снимок sound type по областям {0, 1} (`getIntProperty`), как в референсе.
     * Идентификатор свойства: закреплённый в префах → найденный в списке свойств → нет данных.
     */
    internal fun soundSnapshot(context: Context): List<SoundValue> {
        val cpm = propertyRef ?: carProperty(context) ?: run {
            LAST_SOUND_REASON = "no_car_property"
            return emptyList()
        }
        val propId = resolveSoundPropId(context, cpm) ?: return emptyList()
        val out = mutableListOf<SoundValue>()
        for (area in AREAS) {
            val v = readSound(cpm, propId, area)
            if (v != null) out.add(SoundValue(propId, area, v))
        }
        if (out.isEmpty()) LAST_SOUND_REASON = "no_readback"
        return out
    }

    /**
     * Id свойства sound type. В референсе число приходит из нативной таблицы (`Politica.h(7)`),
     * в dex его нет; поэтому: (а) значение, закреплённое в префах `avas_sound_prop_id`;
     * (б) поиск в `getPropertyList()` по имени «avas» (если прошивка отдаёт имена);
     * (в) иначе — null и честный `reason=no_sound_prop` (без записи чего-либо).
     */
    internal fun resolveSoundPropId(context: Context, cpm: Any?): Int? {
        val pinned = runCatching {
            Prefs2.cps(context.applicationContext).getInt(SOUND_PROP_PREF, -1)
        }.getOrDefault(-1)
        if (pinned >= 0) { LAST_SOUND_REASON = "pinned"; return pinned }
        val mgr = cpm ?: return null
        val list = runCatching { mgr.javaClass.getMethod("getPropertyList").invoke(mgr) as? List<*> }
            .getOrNull() ?: run {
            LAST_SOUND_REASON = "no_property_list"
            return null
        }
        for (cfg in list) {
            if (cfg == null) continue
            val text = runCatching { cfg.toString() }.getOrDefault("").lowercase()
            if (!text.contains("avas")) continue
            val id = runCatching {
                cfg.javaClass.getMethod("getPropertyId").invoke(cfg) as? Int
            }.getOrNull() ?: continue
            LAST_SOUND_REASON = "discovered"
            return id
        }
        LAST_SOUND_REASON = "no_sound_prop"
        return null
    }

    private fun readSound(cpm: Any, propId: Int, area: Int): Int? = runCatching {
        val m = cpm.javaClass.getMethod("getIntProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        (m.invoke(cpm, propId, area) as? Number)?.toInt()
    }.getOrNull()

    private fun writeSound(s: SoundValue): Boolean = runCatching {
        val cpm = propertyRef ?: return@runCatching false
        val m = cpm.javaClass.getMethod(
            "setIntProperty",
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
        )
        val ok = m.invoke(cpm, s.propId, s.area, s.value) as? Boolean ?: false
        AppLog.i(TAG, "restoreSavedAvasSoundType area=${s.area} prop=${s.propId} value=${s.value} ok=$ok")
        ok
    }.onFailure {
        AppLog.w(TAG, "restoreSavedAvasSoundType area=${s.area} failed: ${it.javaClass.simpleName}: ${it.message}")
    }.getOrDefault(false)

    /**
     * `CarAudioManager`/`CarPropertyManager` текущей операции. Обычные переменные, а не ThreadLocal:
     * «сохранить» и «восстановить» живут в разных вызовах (после рестарта — в разных процессах).
     */
    @Volatile private var audioRef: Any? = null
    @Volatile private var propertyRef: Any? = null

    /** Захватить Car-менеджеры для текущей операции (один раз, из [save]/[restoreAttempt]). */
    fun attach(context: Context) {
        val app = context.applicationContext
        audioRef = carAudio(app)
        propertyRef = carProperty(app)
        if (audioRef == null) LAST_SAVE_REASON = "no_car_audio"
    }

    fun release() {
        audioRef = null
        propertyRef = null
    }

    /** Обёртка «сохранить перед рестартом фреймворка» (§3.4а). */
    fun saveBeforeRestart(context: Context, where: Where): Int? = save(context, where)
}
