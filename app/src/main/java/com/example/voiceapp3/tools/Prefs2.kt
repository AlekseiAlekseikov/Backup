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
        val reason: String,
        /**
         * §6.1 iter50: сколько копий ПРОЧИТАЛИСЬ с нашей ревизией и нашим значением.
         *
         * `readback > 0` при `stores == 0` — это и есть дефект, который искали: `commit()`
         * вернул false (или бросил исключение), но in-memory состояние уже изменилось, поэтому
         * чтение «подтверждает» запись, которой на диск не легло. Такая запись НЕ durable.
         */
        val readback: Int = 0
    ) {
        val partial: Boolean get() = confirmed && stores < 2
        fun wire(): String =
            "confirmed=$confirmed stores=$stores readback=$readback rev=$rev conflict=$conflict" +
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
    /**
     * iter49 §6: каталог lock-файлов НЕ должен зависеть от того, какой контекст пришёл.
     *
     * `app.filesDir` у CPS- и DPS-контекста — РАЗНЫЕ каталоги, поэтому boot-ветка (DPS) и UI (CPS)
     * брали бы РАЗНЫЕ lock-файлы и не сериализовались бы между собой, хотя «lock есть».
     * Берём device-protected каталог (он доступен и до разблокировки), а если его нет — обычный.
     */
    private fun lockDir(app: Context): java.io.File {
        val dpsFiles = runCatching { app.createDeviceProtectedStorageContext().filesDir }.getOrNull()
        return java.io.File(dpsFiles ?: app.filesDir, "locks")
    }

    private fun lockFile(app: Context, key: String): java.io.File =
        java.io.File(lockDir(app), "prefs_" + key.replace('/', '_') + ".lock")

    /**
     * §6.1 iter50 — одна копия настроек. `prefs == null` — копия НЕДОСТУПНА (direct boot,
     * отсутствие device-protected storage): это не «пустые настройки» и не второй store.
     */
    internal data class Copy(val name: String, val prefs: SharedPreferences?)

    /**
     * §6.1 iter50 — факт записи ОДНОЙ копии.
     *
     * Разделение «коммит» и «чтение» здесь принципиальное: `Editor.commit()` может вернуть
     * `false` уже после того, как in-memory состояние изменено, поэтому «прочиталось» ≠
     * «сохранено». Подтверждение требует `committed && revOk && valueOk` в ОДНОЙ копии.
     */
    internal data class CopyFact(
        val name: String,
        val available: Boolean,
        val committed: Boolean,
        val rev: Long,
        val revOk: Boolean,
        val valueOk: Boolean
    ) {
        val ok: Boolean get() = available && committed && revOk && valueOk
    }

    /**
     * Реальные копии для боевого контекста.
     *
     * `dpsOrNull` (а НЕ `dps` с фоллбэком на CPS): при недоступном device-protected storage
     * раньше возвращалась та же CPS, и одна физическая запись считалась двумя `stores`.
     */
    internal fun copiesOf(app: Context): List<Copy> = listOf(
        Copy("cps", runCatching { cps(app) }.getOrNull()),
        Copy("dps", runCatching { dpsOrNull(app) }.getOrNull())
    )

    /** Следующая ревизия по фактическим копиям (часы не используются как доказательство). */
    internal fun nextRevOf(copies: List<Copy>, key: String): Long =
        maxOf(copies.map { readRev(it.prefs, key) }.maxOrNull() ?: -1L, 0L) + 1

    /** Записать одну и ту же транзакцию в каждую доступную копию; вернуть РЕАЛЬНЫЕ `commit()`. */
    internal fun writeAll(
        copies: List<Copy>,
        key: String,
        rev: Long,
        whoTag: String,
        put: (SharedPreferences.Editor) -> Unit
    ): List<Boolean> = copies.map { cp ->
        val p = cp.prefs ?: return@map false
        try {
            val ed = p.edit()
            put(ed)
            ed.putLong(revKey(key), rev)
            ed.putString(ownerKey(key), whoTag)
            ed.commit()
        } catch (_: Throwable) {
            false
        }
    }

    /** Сводка по фактам копий. `conflict` считается там, где известны значения. */
    private fun summarize(facts: List<CopyFact>, rev: Long, conflict: Boolean): Save {
        val stores = facts.count { it.committed }
        val readback = facts.count { it.available && it.revOk && it.valueOk }
        val confirmed = !conflict && facts.any { it.ok }
        val why = when {
            conflict -> "conflict_same_rev"
            confirmed && stores == 2 -> ""
            confirmed -> "partial_stores:$stores"
            readback > 0 -> "commit_false_readback_only:$readback"
            else -> "not_persisted"
        }
        return Save(confirmed, stores, if (confirmed) rev else -1L, conflict, why, readback)
    }

    /** §6.1 iter50: запись boolean в набор копий. БЕЗ Context — это позволяет исполнить ровно
     *  этот код в JVM-тесте с поддельными `SharedPreferences`. */
    internal fun putBooleanCore(
        copies: List<Copy>,
        key: String,
        value: Boolean,
        rev: Long,
        whoTag: String
    ): Save {
        val commits = writeAll(copies, key, rev, whoTag) { ed ->
            ed.putBoolean(key, value)
            ed.putBoolean(delKey(key), false)
        }
        val seen = copies.map { cp ->
            runCatching { cp.prefs?.let { if (it.contains(key)) it.getBoolean(key, value) else null } }
                .getOrNull()
        }
        val facts = copies.mapIndexed { i, cp ->
            CopyFact(cp.name, cp.prefs != null, commits[i], readRev(cp.prefs, key),
                readRev(cp.prefs, key) == rev, seen[i] == value)
        }
        val conflict = copies.size == 2 && copies[0].prefs != null && copies[1].prefs != null &&
            readRev(copies[0].prefs, key) == readRev(copies[1].prefs, key) &&
            readRev(copies[0].prefs, key) >= 0L && seen[0] != null && seen[1] != null &&
            seen[0] != seen[1]
        return summarize(facts, rev, conflict)
    }

    /** §6.1 iter50: удаление как tombstone в набор копий. */
    internal fun removeCore(copies: List<Copy>, key: String, rev: Long, whoTag: String): Save {
        val commits = writeAll(copies, key, rev, whoTag) { ed ->
            ed.remove(key)
            ed.putBoolean(delKey(key), true)
        }
        val facts = copies.mapIndexed { i, cp ->
            val r = readRev(cp.prefs, key)
            val gone = runCatching { cp.prefs?.let { !it.contains(key) } }.getOrNull() == true
            val tomb = runCatching { cp.prefs?.let { readDel(it, key) } }.getOrNull() == true
            CopyFact(cp.name, cp.prefs != null, commits[i], r, r == rev, gone && tomb)
        }
        return summarize(facts, rev, false)
    }

    /** §6.1 iter50: запись строки в набор копий. */
    internal fun putStringCore(
        copies: List<Copy>,
        key: String,
        value: String,
        rev: Long,
        whoTag: String
    ): Save {
        val commits = writeAll(copies, key, rev, whoTag) { ed ->
            ed.putString(key, value)
            ed.putBoolean(delKey(key), false)
        }
        val seen = copies.map { cp ->
            runCatching { cp.prefs?.getString(key, null) }.getOrNull()
        }
        val facts = copies.mapIndexed { i, cp ->
            val r = readRev(cp.prefs, key)
            CopyFact(cp.name, cp.prefs != null, commits[i], r, r == rev, seen[i] == value)
        }
        val conflict = copies.size == 2 && copies[0].prefs != null && copies[1].prefs != null &&
            readRev(copies[0].prefs, key) == readRev(copies[1].prefs, key) &&
            readRev(copies[0].prefs, key) >= 0L && seen[0] != null && seen[1] != null &&
            seen[0] != seen[1]
        return summarize(facts, rev, conflict)
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
            val copies = copiesOf(app)
            putBooleanCore(copies, key, value, nextRevOf(copies, key), who)
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
            val copies = copiesOf(app)
            removeCore(copies, key, nextRevOf(copies, key), who)
        }
        if (!locked.executed) {
            return Save(false, 0, -1L, false, "lock_${locked.reason}")
        }
        return locked.value!!
    }

    fun putStringEx(c: Context, key: String, value: String?): Save =
        if (value == null) removeEx(c, key) else {
            val app = c.applicationContext
            ProcLock.withLock(lockFile(app, key)) {
                val copies = copiesOf(app)
                putStringCore(copies, key, value, nextRevOf(copies, key), who)
            }.let { if (it.executed) it.value!! else Save(false, 0, -1L, false, "lock_${it.reason}") }
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

    /**
     * iter49 §6: чтение long по РЕВИЗИИ, а не «первое не-дефолтное».
     *
     * Прежняя реализация (`if (c != default) c else d`) ошибалась в двух случаях: когда в CPS
     * лежало ровно `default` (тогда значение молча бралось из DPS) и когда копии расходились
     * (порядок не проверялся вовсе). Возвращает `null`, если достоверно прочитать нельзя.
     */
    fun getLongOrNull(c: Context, key: String): Long? {
        val app = c.applicationContext
        val pc = runCatching { cps(app) }.getOrNull()
        val pd = runCatching { dps(app) }.getOrNull()
        val cv = runCatching { pc?.let { if (it.contains(key)) it.getLong(key, 0L) else null } }
            .getOrNull()
        val dv = runCatching { pd?.let { if (it.contains(key)) it.getLong(key, 0L) else null } }
            .getOrNull()
        val rc = readRev(pc, key)
        val rd = readRev(pd, key)
        return when {
            cv != null && dv != null && rc != rd -> if (rc > rd) cv else dv
            cv != null && dv != null -> if (cv == dv) cv else null
            cv != null -> cv
            dv != null -> dv
            else -> null
        }
    }

    fun getLong(c: Context, key: String, default: Long = 0L): Long {
        val v = getLongOrNull(c, key)
        if (v == null) {
            AppLog.w("CENTRAL_Prefs2", "PREFS_UNKNOWN key=$key reason=long_unreadable_or_conflict " +
                "fallback=$default")
            return default
        }
        return v
    }

    /** Факт транзакционной записи: значение настройки + новое поколение. */
    data class Save2(val save: Save, val genSave: Save, val gen: Long) {
        /** Подтверждено только если записались ОБЕ части одной транзакции. */
        val confirmed: Boolean get() = save.confirmed && genSave.confirmed
        fun wire(): String = "intent{${save.wire()}} gen{${genSave.wire()}} gen_value=$gen"
    }

    /**
     * iter49 §5.3 — НАМЕРЕНИЕ и его ПОКОЛЕНИЕ одной транзакцией.
     *
     * Дефект iter48 (`NetworkTtl.beginIntent`): `getLong(GEN)+1` и следующий `putLong(GEN, …)` —
     * две независимые записи без проверки результата. Между ними процесс мог умереть, а
     * неудавшаяся запись поколения вообще не проверялась: «намерение подтверждено» заявлялось
     * при неподтверждённом поколении, и `applied` мог ссылаться на поколение, которого нет.
     *
     * Здесь: критическая секция под lock-файлами ОБОИХ ключей (сортировка путей — без
     * взаимной блокировки), чтение текущего поколения, обе записи одним `commit()` в каждую
     * копию, затем readback обоих ключей (ревизия + значение). Если хоть одна часть не
     * подтверждена — [Save2.confirmed] = false, и вызывающий не вправе заявлять `applied`.
     */
    fun putBooleanAndBumpLongEx(c: Context, boolKey: String, value: Boolean, longKey: String): Save2 {
        val app = c.applicationContext
        val l1 = lockFile(app, boolKey)
        val l2 = lockFile(app, longKey)
        val (first, second) = if (l1.absolutePath <= l2.absolutePath) l1 to l2 else l2 to l1
        val outer = ProcLock.withLock(first) {
            val inner = ProcLock.withLock(second) { writeBoolAndBump(app, boolKey, value, longKey) }
            inner.value to inner.reason
        }
        val v = outer.value?.first
            ?: return Save2(
                Save(false, 0, -1L, false, "lock_${outer.value?.second ?: outer.reason}"),
                Save(false, 0, -1L, false, "lock_${outer.value?.second ?: outer.reason}"),
                -1L
            )
        return v
    }

    /**
     * §6.1 iter50 — транзакция intent+generation по набору копий (без Context, чтобы её можно
     * было исполнить в JVM-тесте).
     *
     * Обе части пишутся ОДНИМ `commit()` в каждую копию, а подтверждение требует, чтобы В ОДНОЙ
     * И ТОЙ ЖЕ копии были верны и ревизия intent, и значение intent, и ревизия поколения, и само
     * поколение. Прежняя версия складывала `stores` по числу успешных commit и считала
     * `okL` по любой копии — при частичном коммите это давало ложное «подтверждено».
     */
    internal fun putBoolAndBumpCore(
        copies: List<Copy>,
        boolKey: String,
        value: Boolean,
        longKey: String,
        genNew: Long,
        revB: Long,
        revL: Long,
        whoTag: String
    ): Save2 {
        val commits = copies.map { cp ->
            val p = cp.prefs ?: return@map false
            try {
                val ed = p.edit()
                ed.putBoolean(boolKey, value)
                ed.putBoolean(delKey(boolKey), false)
                ed.putLong(revKey(boolKey), revB)
                ed.putString(ownerKey(boolKey), whoTag)
                ed.putLong(longKey, genNew)
                ed.putBoolean(delKey(longKey), false)
                ed.putLong(revKey(longKey), revL)
                ed.putString(ownerKey(longKey), whoTag)
                ed.commit()
            } catch (_: Throwable) {
                false
            }
        }
        fun boolOf(p: SharedPreferences?): Boolean? = runCatching {
            p?.let { if (it.contains(boolKey)) it.getBoolean(boolKey, value) else null }
        }.getOrNull()
        fun longOf(p: SharedPreferences?): Long? = runCatching {
            p?.let { if (it.contains(longKey)) it.getLong(longKey, -1L) else null }
        }.getOrNull()
        val bSeen = copies.map { boolOf(it.prefs) }
        val lSeen = copies.map { longOf(it.prefs) }
        val factsB = copies.mapIndexed { i, cp ->
            val r = readRev(cp.prefs, boolKey)
            CopyFact(cp.name, cp.prefs != null, commits[i], r, r == revB, bSeen[i] == value)
        }
        val factsL = copies.mapIndexed { i, cp ->
            val r = readRev(cp.prefs, longKey)
            CopyFact(cp.name, cp.prefs != null, commits[i], r, r == revL, lSeen[i] == genNew)
        }
        val conflictB = copies.size == 2 && copies[0].prefs != null && copies[1].prefs != null &&
            readRev(copies[0].prefs, boolKey) == readRev(copies[1].prefs, boolKey) &&
            readRev(copies[0].prefs, boolKey) >= 0L && bSeen[0] != null && bSeen[1] != null &&
            bSeen[0] != bSeen[1]
        val conflictL = copies.size == 2 && copies[0].prefs != null && copies[1].prefs != null &&
            readRev(copies[0].prefs, longKey) == readRev(copies[1].prefs, longKey) &&
            readRev(copies[0].prefs, longKey) >= 0L && lSeen[0] != null && lSeen[1] != null &&
            lSeen[0] != lSeen[1]
        val saveB = summarize(factsB, revB, conflictB)
        val saveL = summarize(factsL, revL, conflictL)
        // Поколение выдаётся только если ОБЕ части подтверждены в одной и той же копии.
        val genConfirmed = factsB.indices.any { factsB[it].ok && factsL[it].ok }
        return Save2(saveB, saveL, if (genConfirmed) genNew else -1L)
    }

    /**
     * §6.1(5) iter50 — следующее поколение БЕЗ переполнения.
     *
     * `genNow + 1` на `Long.MAX_VALUE` дал бы отрицательное число, а `getLongOrNull` вернул бы
     * `null`/0 при неизвестном предыдущем значении. Поколение — метка операции, а не счётчик
     * денег: при переполнении честнее начать с 1 (старые поколения уже не совпадут, потому что
     * текущее состояние намерения перезаписано этой же транзакцией), чем писать отрицательное.
     */
    internal fun nextGen(prev: Long?): Long =
        if (prev == null || prev <= 0L || prev >= Long.MAX_VALUE) 1L else prev + 1L

    /** Внутренняя часть [putBooleanAndBumpLongEx] — ВЫЗЫВАТЬ ТОЛЬКО под обоими lock. */
    internal fun writeBoolAndBump(app: Context, boolKey: String, value: Boolean, longKey: String): Save2 {
        val copies = copiesOf(app)
        val genNew = nextGen(getLongOrNull(app, longKey))
        return putBoolAndBumpCore(
            copies, boolKey, value, longKey, genNew,
            nextRevOf(copies, boolKey), nextRevOf(copies, longKey), who
        )
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
