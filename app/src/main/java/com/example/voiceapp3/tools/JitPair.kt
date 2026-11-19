package com.example.voiceapp3.tools

import android.content.SharedPreferences

/**
 * iter67 §K — две копии настроек: согласованное чтение и согласованное удаление.
 *
 * Дефект (воспроизведён на живой ГУ): после правки только `cps` приложение выдало
 * `JIT_START stored=«ambiguous … detail=mark_unparsable cps:…;ts= dps:…;ts=1794577045082;state=sending»`
 * и блокировка стала БЕССРОЧНОЙ. Причина: любое расхождение копий считалось
 * `AMBIGUOUS`, а `AMBIGUOUS` — терминальным состоянием, из которого нет выхода.
 *
 * Что здесь:
 *  • [classifyPair] — КЛАСС расхождения: разные операции (реально неоднозначно, клик блокируется),
 *    расхождение времени/состояния при ОДНОЙ операции (берём «хуже», но не блокируем навсегда),
 *    нечитаемая одна копия (берётся валидная);
 *  • [clickAllowedFor] — правило «это состояние не повод держать владельца вечно»;
 *  • [OwnedRemover] — удаление всегда в ОБА хранилища, одним `commit()` на хранилище, с проверкой
 *    «прочитано после удаления».
 *
 * Классы операций над `SharedPreferences` (интерфейс Android) держат только интерфейс, поэтому
 * проверяются JVM-тестом на фейковых копиях.
 */

/** Класс расхождения двух копий. */
enum class PairDiag { SAME, ONE_EMPTY, BOTH_EMPTY, TS_DIFF, STATE_DIFF, OP_DIFF, INVALID_ONE }

/** Разобранная копия: то, что нужно для классификации. */
data class PairOp(
    val name: String,
    val snapshot: String?,
    val mark: String?
)

object JitPair {

    /**
     * §K.2 — классификация пары. Порядок проверок: пустые копии → нечитаемая → разные операции →
     * разные состояния → разное время.
     *
     * Правила из задания:
     *  • `OP_DIFF` (разные op id) — действительно неоднозначно, клик блокируется;
     *  • `TS_DIFF`/`STATE_DIFF` при ОДНОМ op — неоднозначно только в сторону «хуже» и НЕ повод
     *    блокировать навсегда: класс пишется в журнал, дальше работают правки C и H;
     *  • `INVALID_ONE` (в одной копии отметка невалидна) — берётся валидная копия.
     */
    fun classifyPair(a: PairOp, b: PairOp): PairDiag {
        if (a.snapshot == null && a.mark == null && b.snapshot == null && b.mark == null) {
            return PairDiag.BOTH_EMPTY
        }
        // Одна копия пуста ЦЕЛИКОМ — это `one_empty`, а не конфликт: у второй есть что читать.
        if (a.snapshot == null && a.mark == null) return PairDiag.ONE_EMPTY
        if (b.snapshot == null && b.mark == null) return PairDiag.ONE_EMPTY
        val ma = JitRef.OpMark.parse(a.mark)
        val mb = JitRef.OpMark.parse(b.mark)
        // Одна копия не разбирается, другая — разбирается: берём валидную, это не конфликт.
        if (ma == null && mb != null) return PairDiag.INVALID_ONE
        if (mb == null && ma != null) return PairDiag.INVALID_ONE
        if (ma == null && mb == null) {
            // Обе не разбираются, но данные есть: восстановить нечего — это неоднозначность.
            return if (a.mark == null && b.mark == null) PairDiag.SAME else PairDiag.OP_DIFF
        }
        if (ma!!.op != mb!!.op) return PairDiag.OP_DIFF
        if (ma.state != mb.state) return PairDiag.STATE_DIFF
        if (ma.tsMs != mb.tsMs) return PairDiag.TS_DIFF
        return PairDiag.SAME
    }

    /**
     * §K.2 — «не повод блокировать навсегда». Блокирует только реальная неоднозначность:
     * разные операции в копиях. Всё остальное — выход есть (правки C и H называют его строкой).
     */
    fun clickAllowedFor(diag: PairDiag): Boolean = diag != PairDiag.OP_DIFF

    /**
     * Из двух копий выбирает ту, что «хуже» по состоянию: `SENDING`/`UNDEFINED` важнее `REPLIED`,
     * потому что неизвестный исход нельзя выдать за завершённый. При равных — `cps` (порядок
     * чтения прежний).
     */
    fun worseOf(a: PairOp, b: PairOp): PairOp {
        val ma = JitRef.OpMark.parse(a.mark) ?: return b
        val mb = JitRef.OpMark.parse(b.mark) ?: return a
        fun rank(s: String) = when (s) {
            JitRef.OpMark.UNDEFINED -> 2
            JitRef.OpMark.SENDING -> 2
            else -> 1
        }
        return if (rank(ma.state) >= rank(mb.state)) a else b
    }

    /** Строка журнала для класса расхождения. */
    fun line(diag: PairDiag, used: String): String =
        "JIT_PAIR diag=${diag.name.lowercase()} used=$used clicks=" +
            (if (clickAllowedFor(diag)) "allowed" else "blocked")
}

/** Факт согласованного удаления. */
data class PairRemoval(
    /** Сколько хранилищ подтвердили запись (`commit()==true`). */
    val ok: Int,
    /** Сколько хранилищ участвовало. */
    val stores: Int,
    /** Ключи, которые после удаления всё ещё видны хотя бы в одном хранилище. */
    val still: List<String>
) {
    val clean: Boolean get() = still.isEmpty()

    fun wire(): String =
        "ok=$ok/$stores still=${if (still.isEmpty()) "none" else still.joinToString(",")}"
}

/**
 * §K.1 — удаление согласованно: один `commit()` на КАЖДОЕ хранилище (не `commit` на каждый ключ),
 * затем ПРОВЕРКА «прочитано после удаления». `commit()==false` — «не подтверждено», а не «удалено».
 */
object OwnedRemover {

    /** Боевой путь: копии берутся из [Prefs2] (CPS + DPS без фоллбэка). */
    fun removeOwnedEverywhere(
        prefs: List<SharedPreferences?>
    ): PairRemoval = removeOwnedEverywhereForTest(prefs.filterNotNull(), emptyList())

    /** Тестовый/боевой фасад: явные копии и явные ключи (по умолчанию — ключи операции). */
    fun removeOwnedEverywhereForTest(
        prefs: List<SharedPreferences?>,
        keys: List<String>
    ): PairRemoval {
        val list = keys.ifEmpty { JitRef.OWNED_KEYS }
        val stores = prefs.filterNotNull()
        var ok = 0
        for (p in stores) {
            val committed = runCatching {
                val e = p.edit()
                for (k in list) e.remove(k)
                e.commit()
            }.getOrDefault(false)
            if (committed) ok++
        }
        val still = list.filter { k -> stores.any { runCatching { it.contains(k) }.getOrDefault(false) } }
        return PairRemoval(ok, stores.size, still)
    }
}
