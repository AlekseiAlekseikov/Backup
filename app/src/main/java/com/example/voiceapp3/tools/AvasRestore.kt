package com.example.voiceapp3.tools

import android.car.Car
import android.content.Context
import java.io.File

/**
 * iter33o §1 — восстановление режима AVAS по механизму референса `CentralWatchdogService.z`
 * (CentralEXAuto 3.3.9, байткод `w0/d0`, `w0/df`, `w0/qa`).
 *
 * Что делает референс (подтверждено байткодом):
 *  • `Car.createCar` → `getCarManager("audio")` → `CarAudioManager`;
 *  • рефлексия `getAVASMode()` (`getMethod("getAVASMode", null)`), результат — `Integer`;
 *  • сохранение пары «muted/sound type» в prefs (ключи `w0/zd`, `w0/qa`) — нам из этого нужно
 *    только значение режима;
 *  • на загрузке — `setAVASMode(<сохранённое>)` (`getMethod("setAVASMode", Integer.TYPE)`).
 *
 * Зачем нам: перезапуск фреймворка (тумблер JIT / авто-восстановление JIT) сбрасывает AVAS-режим —
 * звук «проваливается» в салон до перезагрузки ГУ. Правило владельца (09.11): разрешён ТОЛЬКО
 * возврат прежде сохранённого значения.
 *
 * ГРАНИЦЫ (§1.3 мандата, жёсткие):
 *  • НЕ мутим AVAS: `setAVASMode` вызывается РОВНО с сохранённым значением, никаких `0`/констант;
 *  • НЕ меняем sound type, не трогаем `audioserver` и звуковые файлы;
 *  • вызовы разрешены только в трёх местах: цепочка тумблера JIT, старт приложения,
 *    boot-restore JIT (§1.5) — это держит `AvasRestoreTest`;
 *  • если Car API/метод недоступен — честная строка `skipped reason=…`, БЕЗ retry-циклов.
 */
object AvasRestore {

    private const val TAG = "CENTRAL_AvasRestore"

    /** Файл в `filesDir` — единственное хранилище «прежнего режима» (одноразовое намерение). */
    internal const val SAVED_FILE = "avas_mode_saved"

    /** Разрешённые точки вызова (§1.3). Значение — поле `where=` в логе. */
    enum class Where(val label: String) {
        JIT_TOGGLE("jit_toggle"),
        APP_START("app_start"),
        BOOT_RESTORE("boot_restore");

        companion object {
            fun of(label: String?): Where =
                values().firstOrNull { it.label == label } ?: APP_START
        }
    }

    /** Решение «что делать» — чистая функция, покрыта `AvasRestoreTest`. */
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

    /** Строка лога сохранения (чистая — чтобы тест держал формат). */
    internal fun saveLine(mode: Int?, where: Where): String =
        if (mode == null) "AVAS_SAVE skipped reason=$LAST_SAVE_REASON where=${where.label}"
        else "AVAS_SAVE mode=$mode where=${where.label}"

    /**
     * Строка лога восстановления. `ok` — только если после записи прочитанное значение совпало
     * с сохранённым (read-back), иначе `failed` (ложный успех запрещён).
     */
    internal fun restoreLine(saved: Int, found: Int?, writeOk: Boolean, after: Int?, where: Where): String {
        val modeFound = found?.toString() ?: "unknown"
        return "AVAS_RESTORE mode_saved=$saved mode_found=$modeFound " +
            "result=${if (writeOk && after == saved) "ok" else "failed"} mode_after=${after?.toString() ?: "unknown"} " +
            "where=${where.label}"
    }

    internal fun restoreSkipLine(reason: String, where: Where, mode: Int? = null): String =
        "AVAS_RESTORE skipped reason=$reason" + (mode?.let { " mode=$it" } ?: "") + " where=${where.label}"

    /** Причина последнего неуспешного сохранения — попадает в лог как есть. */
    @Volatile internal var LAST_SAVE_REASON: String = "no_car_api"

    // ─────────────────────────────── сохранение ───────────────────────────────

    /**
     * Прочитать текущий режим AVAS и сохранить его (§1.1). Возвращает сохранённое значение или null.
     * Вызывать ДО рестарта фреймворка, только с фонового потока.
     */
    fun save(context: Context, where: Where): Int? {
        val mode = readMode()
        if (mode == null) {
            AppLog.i(TAG, saveLine(null, where))
            return null
        }
        runCatching {
            File(context.applicationContext.filesDir, SAVED_FILE).writeText(mode.toString())
        }.onFailure { AppLog.w(TAG, "AVAS_SAVE file write failed: ${it.javaClass.simpleName}") }
        AppLog.i(TAG, saveLine(mode, where))
        return mode
    }

    /**
     * Вернуть сохранённый режим (§1.2). Одноразовое намерение: после успешного восстановления
     * (или `already_ok`) файл удаляется — иначе следующая смена режима владельцем откатывалась бы
     * на каждом старте.
     */
    fun restore(context: Context, where: Where): Boolean {
        val app = context.applicationContext
        val saved = savedMode(app)
        val found = if (saved == null) null else readMode()
        when (decide(saved, found)) {
            Action.NONE -> {
                // Ничего не сохранено — тишина: на обычном старте это норма, лог не засоряем.
                return false
            }
            Action.FAIL -> {
                AppLog.i(TAG, restoreSkipLine("no_getAVASMode", where, saved))
                return false
            }
            Action.ALREADY_OK -> {
                AppLog.i(TAG, restoreSkipLine("already_ok", where, found))
                clearSaved(app)
                return true
            }
            Action.RESTORE -> {
                val writeOk = writeMode(saved!!)
                val after = if (writeOk) readMode() else null
                AppLog.i(TAG, restoreLine(saved, found, writeOk, after, where))
                if (writeOk && after == saved) clearSaved(app)
                return writeOk && after == saved
            }
        }
    }

    fun savedMode(context: Context): Int? = runCatching {
        val f = File(context.applicationContext.filesDir, SAVED_FILE)
        if (!f.isFile) null else f.readText().trim().toIntOrNull()
    }.getOrNull()

    fun clearSaved(context: Context) {
        runCatching { File(context.applicationContext.filesDir, SAVED_FILE).delete() }
    }

    // ─────────────────────────── Car API через рефлексию ───────────────────────────

    /** `Car.createCar(ctx).getCarManager("audio")` — как в референсе; null при любой ошибке. */
    private fun carAudio(context: Context): Any? = runCatching {
        val car = Car.createCar(context.applicationContext) ?: return@runCatching null
        car.getCarManager(AUDIO_SERVICE)
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

    /** `getMethod("setAVASMode", Integer.TYPE).invoke(audio, value)` — только сохранённое значение. */
    private fun writeMode(value: Int): Boolean = runCatching {
        val audio = audioRef ?: return@runCatching false
        val m = audio.javaClass.getMethod("setAVASMode", Int::class.javaPrimitiveType)
        m.invoke(audio, value)
        true
    }.onFailure {
        AppLog.w(TAG, "AVAS_RESTORE write failed: ${it.javaClass.simpleName}: ${it.message}")
    }.getOrDefault(false)

    internal const val AUDIO_SERVICE = "audio"

    /**
     * `CarAudioManager` текущей операции. Обычная переменная, а не ThreadLocal: «сохранить»
     * и «восстановить» живут в разных вызовах (и после рестарта — в разных процессах),
     * поэтому держим ссылку до [release] и сразу её снимаем.
     */
    @Volatile private var audioRef: Any? = null

    /** Захватить CarAudioManager для текущей операции (один раз, из [save]/[restore]). */
    fun attach(context: Context) {
        audioRef = carAudio(context)
        if (audioRef == null) LAST_SAVE_REASON = "no_car_audio"
    }

    fun release() { audioRef = null }

    /**
     * Обёртка «сохранить перед рестартом фреймворка»: attach → save → release.
     * Возвращает сохранённое значение (или null) для лога вызывающего.
     */
    fun saveBeforeRestart(context: Context, where: Where): Int? {
        attach(context)
        val mode = try { save(context, where) } finally { release() }
        return mode
    }

    /** Обёртка «восстановить после подъёма»: attach → restore → release. */
    fun restoreAfterRestart(context: Context, where: Where): Boolean {
        attach(context)
        return try { restore(context, where) } finally { release() }
    }
}
