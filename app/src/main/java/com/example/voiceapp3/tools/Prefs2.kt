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

    /**
     * iter43 §4: DPS БЕЗ скрытого фоллбэка — `null`, если device-protected storage недоступен.
     *
     * Глобальное поведение [dps] остальных функций не меняется (там fallback на CPS нужен),
     * но JIT обязан ЗНАТЬ, что второй копии фактически нет, а не считать CPS за DPS.
     */
    fun dpsOrNull(c: Context): SharedPreferences? = try {
        c.applicationContext.createDeviceProtectedStorageContext()
            .getSharedPreferences(FILE, Context.MODE_PRIVATE)
    } catch (_: Throwable) {
        null
    }

    fun dps(c: Context): SharedPreferences = try {
        c.createDeviceProtectedStorageContext().getSharedPreferences(FILE, Context.MODE_PRIVATE)
    } catch (_: Throwable) {
        cps(c)
    }

    /**
     * §4.2 iter46: РЕВИЗИЯ ЗНАЧЕНИЯ.
     *
     * Дефект iter45 (признан и доказан кодом): чтение было `cps || dps`. Если запись OFF
     * удавалась только в одной копии, старое `true` во второй НАВСЕГДА перебивало
     * пользовательский OFF — в логе владельца это видно как расхождение
     * `master_remember_brightness=reg:true/dps:false`.
     *
     * Теперь рядом со значением пишется монотонная ревизия (`<key>#rev`), и читается значение
     * из копии с БОЛЬШЕЙ ревизией — то есть последняя запись побеждает независимо от того,
     * удалась ли вторая копия. Часы не используются как доказательство порядка: ревизия — это
     * `max(предыдущих)+1`.
     *
     * Совместимость: у старых установок ревизий нет. Тогда действует прежняя семантика
     * (`true`, если true хотя бы в одной копии) — сохранённые настройки владельца не теряются
     * при обновлении. Как только значение записано новым кодом, порядок становится явным.
     */
    private fun revKey(key: String) = key + "#rev"

    private fun readRev(p: SharedPreferences?, key: String): Long =
        runCatching { p?.getLong(revKey(key), -1L) ?: -1L }.getOrDefault(-1L)

    /** Следующая ревизия = max(ревизий обеих копий) + 1 (без опоры на часы). */
    private fun nextRev(app: Context, key: String): Long {
        val c = readRev(runCatching { cps(app) }.getOrNull(), key)
        val d = readRev(runCatching { dps(app) }.getOrNull(), key)
        return maxOf(c, d, 0L) + 1
    }

    /** Записать boolean в ОБА хранилища. Возвращает фактическую ревизию записи. */
    fun putBoolean(c: Context, key: String, value: Boolean) {
        val app = c.applicationContext
        val rev = nextRev(app, key)
        try {
            cps(app).edit().putBoolean(key, value).putLong(revKey(key), rev).commit()
        } catch (_: Throwable) {}
        try {
            dps(app).edit().putBoolean(key, value).putLong(revKey(key), rev).commit()
        } catch (_: Throwable) {}
    }

    /**
     * Читать boolean: значение из копии с большей ревизией; при отсутствии ревизий —
     * прежняя семантика «true в любой копии» (миграция без потери настроек).
     */
    fun getBoolean(c: Context, key: String, default: Boolean = false): Boolean {
        val app = c.applicationContext
        val pc = runCatching { cps(app) }.getOrNull()
        val pd = runCatching { dps(app) }.getOrNull()
        val cv = runCatching { pc?.let { if (it.contains(key)) it.getBoolean(key, default) else null } }
            .getOrNull()
        val dv = runCatching { pd?.let { if (it.contains(key)) it.getBoolean(key, default) else null } }
            .getOrNull()
        return Toggles45.pickBoolean(cv, readRev(pc, key), dv, readRev(pd, key), default)
    }

    fun putString(c: Context, key: String, value: String?) {
        val app = c.applicationContext
        // §4.2 iter46: значение и ревизия пишутся одной транзакцией в каждую копию.
        val rev = nextRev(app, key)
        try {
            val ed = cps(app).edit()
            if (value == null) ed.remove(key) else ed.putString(key, value)
            ed.putLong(revKey(key), rev)
            ed.commit()
        } catch (_: Throwable) {}
        try {
            val ed = dps(app).edit()
            if (value == null) ed.remove(key) else ed.putString(key, value)
            ed.putLong(revKey(key), rev)
            ed.commit()
        } catch (_: Throwable) {}
    }

    fun getString(c: Context, key: String, default: String? = null): String? {
        val app = c.applicationContext
        val pc = runCatching { cps(app) }.getOrNull()
        val pd = runCatching { dps(app) }.getOrNull()
        val cv = runCatching { pc?.getString(key, null) }.getOrNull()
        val dv = runCatching { pd?.getString(key, null) }.getOrNull()
        // Итог: значение из копии с большей ревизией; без ревизий — как раньше (CPS, затем DPS).
        return Toggles45.pickString(cv, readRev(pc, key), dv, readRev(pd, key)) ?: default
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
        // §4.2 iter46: ревизии видны в логе — по ним понятно, какая копия новее и почему
        // итоговое значение именно такое (расхождение копий больше не «объясняется само»).
        val rc = readRev(runCatching { cps(app) }.getOrNull(), key)
        val rd = readRev(runCatching { dps(app) }.getOrNull(), key)
        return "$key=reg:$cv/rev:$rc dps:$dv/rev:$rd"
    }
}
