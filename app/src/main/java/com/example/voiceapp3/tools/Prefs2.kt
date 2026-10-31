package com.example.voiceapp3.tools

import android.content.Context
import android.content.SharedPreferences

/**
 * iter17: единая точка доступа к SharedPreferences.
 *
 * Проблема: при DIRECT-BOOT (до разблокировки экрана) Android отдаёт только
 * device-protected storage (DPS); после разблокировки код по умолчанию читает
 * обычное credential-protected storage (CPS). В разных точках приложения
 * использовались ОБЕ версии — из-за чего toggle, выставленный в UI (CPS),
 * не читался в boot-ветке (DPS) и наоборот.
 *
 * Решение:
 *  1. Запись делается в ОБА хранилища сразу (commit в CPS и commit в DPS).
 *  2. Чтение возвращает true, если значение true в ЛЮБОМ из двух (семантика
 *     "хотя бы раз включили → должно работать").
 *  3. Один файл ("voiceapp3") на все модули.
 */
object Prefs2 {
    const val FILE = "voiceapp3"

    fun cps(c: Context): SharedPreferences =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun dps(c: Context): SharedPreferences = try {
        c.createDeviceProtectedStorageContext().getSharedPreferences(FILE, Context.MODE_PRIVATE)
    } catch (_: Throwable) {
        cps(c)
    }

    /** Записать boolean в ОБА хранилища. */
    fun putBoolean(c: Context, key: String, value: Boolean) {
        val app = c.applicationContext
        try { cps(app).edit().putBoolean(key, value).commit() } catch (_: Throwable) {}
        try { dps(app).edit().putBoolean(key, value).commit() } catch (_: Throwable) {}
    }

    /** Читать boolean: true если хотя бы в одном хранилище true. */
    fun getBoolean(c: Context, key: String, default: Boolean = false): Boolean {
        val app = c.applicationContext
        val c = try { cps(app).getBoolean(key, default) } catch (_: Throwable) { default }
        val d = try { dps(app).getBoolean(key, default) } catch (_: Throwable) { default }
        return c || d
    }

    fun putString(c: Context, key: String, value: String?) {
        val app = c.applicationContext
        try {
            val ed = cps(app).edit()
            if (value == null) ed.remove(key) else ed.putString(key, value)
            ed.commit()
        } catch (_: Throwable) {}
        try {
            val ed = dps(app).edit()
            if (value == null) ed.remove(key) else ed.putString(key, value)
            ed.commit()
        } catch (_: Throwable) {}
    }

    fun getString(c: Context, key: String, default: String? = null): String? {
        val app = c.applicationContext
        return try { cps(app).getString(key, null) } catch (_: Throwable) { null }
            ?: try { dps(app).getString(key, null) } catch (_: Throwable) { null }
            ?: default
    }

    fun putInt(c: Context, key: String, value: Int) {
        val app = c.applicationContext
        try { cps(app).edit().putInt(key, value).commit() } catch (_: Throwable) {}
        try { dps(app).edit().putInt(key, value).commit() } catch (_: Throwable) {}
    }

    fun getInt(c: Context, key: String, default: Int = 0): Int {
        val app = c.applicationContext
        val c = try { cps(app).getInt(key, default) } catch (_: Throwable) { default }
        val d = try { dps(app).getInt(key, default) } catch (_: Throwable) { default }
        // если совпадают -> ок, если нет — берём не-дефолтное
        return if (c != default) c else d
    }

    fun putLong(c: Context, key: String, value: Long) {
        val app = c.applicationContext
        try { cps(app).edit().putLong(key, value).commit() } catch (_: Throwable) {}
        try { dps(app).edit().putLong(key, value).commit() } catch (_: Throwable) {}
    }

    fun getLong(c: Context, key: String, default: Long = 0L): Long {
        val app = c.applicationContext
        val c = try { cps(app).getLong(key, default) } catch (_: Throwable) { default }
        val d = try { dps(app).getLong(key, default) } catch (_: Throwable) { default }
        return if (c != default) c else d
    }

    /** Диагностическая строка: показать значения из обоих хранилищ. */
    fun diag(c: Context, key: String): String {
        val app = c.applicationContext
        val cv = try { cps(app).getBoolean(key, false) } catch (_: Throwable) { false }
        val dv = try { dps(app).getBoolean(key, false) } catch (_: Throwable) { false }
        return "$key=reg:$cv/dps:$dv"
    }
}
