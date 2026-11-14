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

    /** §6.1 iter47: tombstone-отметка «ключ удалён намеренно» (ревизия удаления). */
    private fun delKey(key: String) = key + "#del"

    /** §6.1 iter47: кто записал ревизию — чтобы отличить конфликт двух писателей от одной записи. */
    private fun ownerKey(key: String) = key + "#who"

    private fun readRev(p: SharedPreferences?, key: String): Long =
        runCatching { p?.getLong(revKey(key), -1L) ?: -1L }.getOrDefault(-1L)

    private fun readDel(p: SharedPreferences?, key: String): Boolean =
        runCatching { p?.getBoolean(delKey(key), false) ?: false }.getOrDefault(false)

    /**
     * §6.1 iter47 — факт сохранения. Раньше `putBoolean` возвращал `Unit`, а результат `commit()`
     * игнорировался: «сохранено» заявлялось даже когда ни одна копия не записалась.
     */
    data class Save(
        /** Подтверждено чтением хотя бы в одной копии с ожидаемой ревизией. */
        val confirmed: Boolean,
        /** Сколько копий фактически записалось. */
        val stores: Int,
        /** Ревизия записи (`-1` — запись не удалась/неизвестна). */
        val rev: Long,
        /** В другой копии обнаружена РАВНАЯ ревизия с другим значением. */
        val conflict: Boolean,
        /** Причина отказа/частичности (пусто — всё подтверждено). */
        val reason: String
    ) {
        val partial: Boolean get() = confirmed && stores < 2
        fun wire(): String =
            "confirmed=$confirmed stores=$stores rev=$rev conflict=$conflict" +
                (if (reason.isEmpty()) "" else " reason=«$reason»")
    }

    /** Идентификатор писателя: pid+время старта процесса. Служит только для диагностики конфликта. */
    private val who: String by lazy {
        val pid = runCatching { android.os.Process.myPid() }.getOrDefault(-1)
        "$pid@${System.currentTimeMillis()}"
    }

    /** Следующая ревизия = max(ревизий обеих копий) + 1 (без опоры на часы). */
    private fun nextRev(app: Context, key: String): Long {
        val c = readRev(runCatching { cps(app) }.getOrNull(), key)
        val d = readRev(runCatching { dps(app) }.getOrNull(), key)
        return maxOf(c, d, 0L) + 1
    }

    /**
     * iter48 §Г — межпроцессный lock на ключ.
     *
     * Раньше «прочитать ревизию → записать ревизию+1» держалась только JVM-замком (его здесь и
     * не было), поэтому два процесса могли прочитать одну ревизию и записать РАЗНЫЕ значения
     * с одинаковым номером — тот самый «конфликт равных ревизий», порядок которого восстановить
     * нельзя. Теперь read-modify-write целиком идёт под [ProcLock] (lock-файл ОС, не JVM).
     *
     * Ограничения (записаны честно): убийство процесса между записью копии №1 и №2 остаётся
     * возможным — для этого есть ревизии и согласование при чтении; lock-файл не мешает другим
     * приложениям писать в свои prefs.
     */
    private fun lockFile(app: Context, key: String): java.io.File =
        java.io.File(java.io.File(app.filesDir, "locks"), "prefs_" + key.replace('/', '_') + ".lock")

    private fun writeBoth(
        app: Context,
        key: String,
        rev: Long,
        put: (SharedPreferences.Editor) -> Unit
    ): Int {
        var ok = 0
        for (p in listOf(runCatching { cps(app) }.getOrNull(), runCatching { dps(app) }.getOrNull())) {
            if (p == null) continue
            val committed = try {
                val ed = p.edit()
                put(ed)
                ed.putLong(revKey(key), rev)
                ed.putString(ownerKey(key), who)
                ed.commit()
            } catch (_: Throwable) {
                false
            }
            if (committed) ok++
        }
        return ok
    }

    /**
     * §6.1 iter47: записать boolean и вернуться с ФАКТОМ сохранения.
     *
     * Значение, ревизия, владелец и снятие tombstone пишутся ОДНОЙ транзакцией в каждую копию.
     * Если ни одна копия не записалась — `confirmed=false` (а не тихий «успех»).
     */
    fun putBooleanEx(c: Context, key: String, value: Boolean): Save {
        val app = c.applicationContext
        val locked = ProcLock.withLock(lockFile(app, key)) {
            val rev = nextRev(app, key)
            val stores = writeBoth(app, key, rev) { ed ->
                ed.putBoolean(key, value)
                ed.putBoolean(delKey(key), false)
            }
            val pc = runCatching { cps(app) }.getOrNull()
            val pd = runCatching { dps(app) }.getOrNull()
            val rc = readRev(pc, key)
            val rd = readRev(pd, key)
            val cv = runCatching { pc?.let { if (it.contains(key)) it.getBoolean(key, value) else null } }.getOrNull()
            val dv = runCatching { pd?.let { if (it.contains(key)) it.getBoolean(key, value) else null } }.getOrNull()
            val conflict = rc == rd && rc >= 0L && cv != null && dv != null && cv != dv
            // §Г iter48: при конфликте равных ревизий запись НЕ подтверждена, даже если наша
            // копия содержит наше значение: во второй копии лежит другое, и порядок неизвестен.
            val storeConfirmed = (rc == rev && cv == value) || (rd == rev && dv == value)
            val confirmed = storeConfirmed && !conflict
            val why = when {
                conflict -> "conflict_same_rev"
                confirmed && stores == 2 -> ""
                confirmed -> "partial_stores:$stores"
                else -> "not_persisted"
            }
            Save(confirmed, stores, if (confirmed) rev else -1L, conflict, why)
        }
        if (!locked.executed) {
            return Save(false, 0, -1L, false,
                "lock_${locked.reason} (межпроцессный lock не взят: запись не заявляется подтверждённой)")
        }
        return locked.value!!
    }

    /**
     * §6.1 iter47: удаление ключа как ЯВНОЕ действие (tombstone), чтобы старое значение из второй
     * копии не воскресло. `value=null` в старом `putString` именно это и ломало.
     */
    fun removeEx(c: Context, key: String): Save {
        val app = c.applicationContext
        val locked = ProcLock.withLock(lockFile(app, key)) {
            val rev = nextRev(app, key)
            val stores = writeBoth(app, key, rev) { ed ->
                ed.remove(key)
                ed.putBoolean(delKey(key), true)
            }
            val pc = runCatching { cps(app) }.getOrNull()
            val pd = runCatching { dps(app) }.getOrNull()
            val rc = readRev(pc, key)
            val rd = readRev(pd, key)
            // §Г iter48: удаление подтверждено не «ревизией», а ФАКТОМ: в копии нет значения,
            // tombstone стоит, и ревизия — наша. Иначе «удалено» могло означать лишь то, что
            // запись дошла до одной копии, а во второй значение живо.
            val goneC = pc == null || !pc.contains(key)
            val goneD = pd == null || !pd.contains(key)
            val delC = readDel(pc, key)
            val delD = readDel(pd, key)
            val okC = rc == rev && goneC && delC
            val okD = rd == rev && goneD && delD
            val confirmed = okC || okD
            val why = when {
                confirmed && stores == 2 && okC && okD -> ""
                confirmed -> "partial_stores:$stores"
                else -> "not_persisted"
            }
            Save(confirmed, stores, if (confirmed) rev else -1L, false, why)
        }
        if (!locked.executed) {
            return Save(false, 0, -1L, false, "lock_${locked.reason}")
        }
        return locked.value!!
    }

    fun putStringEx(c: Context, key: String, value: String?): Save =
        if (value == null) removeEx(c, key) else {
            val app = c.applicationContext
            ProcLock.withLock(lockFile(app, key)) { putStringLocked(app, key, value) }
                .let { if (it.executed) it.value!! else Save(false, 0, -1L, false, "lock_${it.reason}") }
        }

    /** Внутренняя запись строки — ВЫЗЫВАТЬ ТОЛЬКО под [ProcLock]. */
    private fun putStringLocked(app: Context, key: String, value: String): Save {
            val rev = nextRev(app, key)
            val stores = writeBoth(app, key, rev) { ed ->
                ed.putString(key, value)
                ed.putBoolean(delKey(key), false)
            }
            val pc = runCatching { cps(app) }.getOrNull()
            val pd = runCatching { dps(app) }.getOrNull()
            val rc = readRev(pc, key)
            val rd = readRev(pd, key)
            val cv = runCatching { pc?.getString(key, null) }.getOrNull()
            val dv = runCatching { pd?.getString(key, null) }.getOrNull()
            val conflict = rc == rd && rc >= 0L && cv != null && dv != null && cv != dv
            val confirmed = (rc == rev && cv == value) || (rd == rev && dv == value)
            return Save(confirmed, stores, if (confirmed) rev else -1L, conflict,
                when {
                    conflict -> "conflict_same_rev"
                    confirmed && stores == 2 -> ""
                    confirmed -> "partial_stores:$stores"
                    else -> "not_persisted"
                })
    }

    /** Оставить для совместимости: `true`, если запись подтверждена хотя бы в одной копии. */
    fun putBoolean(c: Context, key: String, value: Boolean): Boolean =
        putBooleanEx(c, key, value).confirmed

    /** Записать строку; `null` = удаление (tombstone). Возвращает `true` при подтверждении. */
    fun putString(c: Context, key: String, value: String?): Boolean =
        putStringEx(c, key, value).confirmed

    /** Полное чтение ключа с состоянием (значение/удалён/конфликт/неизвестно). */
    fun readBoolean(c: Context, key: String): Toggles45.Resolved<Boolean> {
        val app = c.applicationContext
        val pc = runCatching { cps(app) }.getOrNull()
        val pd = runCatching { dps(app) }.getOrNull()
        val cv = runCatching { pc?.let { if (it.contains(key)) it.getBoolean(key, false) else null } }
            .getOrNull()
        val dv = runCatching { pd?.let { if (it.contains(key)) it.getBoolean(key, false) else null } }
            .getOrNull()
        return Toggles45.resolveBoolean(
            cv, readRev(pc, key), readDel(pc, key),
            dv, readRev(pd, key), readDel(pd, key)
        )
    }

    /**
     * iter48 §Г — ТРЁХЗНАЧНОЕ чтение тумблера: `null` = «неизвестно» (копии в конфликте или
     * значение прочитать нельзя). Именно это значение обязано доходить до UI и до логики
     * применения: подставлять `default` нельзя, иначе интерфейс показывает выдуманное
     * состояние, а автоматика действует «вслепую».
     */
    fun getBooleanOrNull(c: Context, key: String): Boolean? =
        when (val r = readBoolean(c, key)) {
            is Toggles45.Resolved.Value -> r.v
            Toggles45.Resolved.Deleted -> false
            Toggles45.Resolved.Conflict, Toggles45.Resolved.Unknown -> null
        }

    /**
     * Двузначное чтение для НЕкритичных мест (совместимость).
     *
     * §Г iter48: неизвестность здесь ОБЯЗАТЕЛЬНО становится видимой в журнале — молчаливая
     * подстановка `default` и была дефектом («тумблер сам выключился»). Всё, что решает
     * применять/не применять, должно использовать [getBooleanOrNull] (или `readBoolean`).
     */
    fun getBoolean(c: Context, key: String, default: Boolean = false): Boolean =
        when (val r = readBoolean(c, key)) {
            is Toggles45.Resolved.Value -> r.v
            Toggles45.Resolved.Deleted -> false
            Toggles45.Resolved.Conflict -> {
                AppLog.w("CENTRAL_Prefs2", "PREFS_UNKNOWN key=$key reason=conflict_same_rev " +
                    "fallback=$default (порядок записей неизвестен: значение НЕ выдаётся за решение владельца)")
                default
            }
            Toggles45.Resolved.Unknown -> {
                AppLog.w("CENTRAL_Prefs2", "PREFS_UNKNOWN key=$key reason=unreadable fallback=$default")
                default
            }
        }

    fun getString(c: Context, key: String, default: String? = null): String? {
        val app = c.applicationContext
        val pc = runCatching { cps(app) }.getOrNull()
        val pd = runCatching { dps(app) }.getOrNull()
        val cv = runCatching { pc?.getString(key, null) }.getOrNull()
        val dv = runCatching { pd?.getString(key, null) }.getOrNull()
        return when (val r = Toggles45.resolveString(
            cv, readRev(pc, key), readDel(pc, key),
            dv, readRev(pd, key), readDel(pd, key)
        )) {
            is Toggles45.Resolved.Value -> r.v
            Toggles45.Resolved.Deleted, Toggles45.Resolved.Conflict,
            Toggles45.Resolved.Unknown -> default
        }
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
