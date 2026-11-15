package com.example.voiceapp3.tools

import android.content.Context
import java.security.SecureRandom

/**
 * iter54 §6.1 — УСТОЙЧИВАЯ ИДЕНТИЧНОСТЬ СВОЕЙ ЦЕПИ TTL (вместо «имя + совпадение тела»).
 *
 * Что было не так (iter53 и раньше): цепь называлась `VAPP_TTL`, а владение выводилось из
 * совпадения ТЕЛА правила. Такую же цепь с тем же именем и тем же правилом могло создать другое
 * приложение (или прежняя версия) — отличить её по этим фактам невозможно, а iter53 ещё и
 * «мигрировал» такую цепь, то есть менял чужое.
 *
 * Что теперь:
 *  1. Имя цепи УНИКАЛЬНО для установки: `VAPP_TTL_<token8>` (≤28 символов — предел iptables).
 *  2. `token` — 8 hex, создаётся один раз и хранится в реестре (обе копии префов) с
 *     подтверждением записи и readback.
 *  3. Метка в правиле (`voiceapp3:ttl:<token>`) делает владение проверяемым: чужое приложение
 *     не знает токен и не может «случайно» создать помеченное наше правило.
 *  4. Историческое имя `VAPP_TTL` только перечисляется в отчёте (`TTL_LEGACY_CHAIN`) и
 *     НИКОГДА не изменяется.
 *
 * Чего это не даёт: при утере реестра (переустановка, очистка данных) прежняя наша цепь остаётся
 * «чужой неизвестной» — присвоить её нечем, и это честно сообщается. При неизвестном реестре
 * (конфликт копий/ошибка чтения) таблица не изменяется вовсе.
 */
internal object TtlOwnership {

    /** Ключ реестра: токен установки. */
    internal const val PREF_TOKEN = "ttl_own_token"

    /** Префикс имени цепи; вместе с 8 hex даёт 17 символов (предел iptables — 28). */
    internal const val CHAIN_PREFIX = "VAPP_TTL_"

    /** Историческое имя (не изменяется никогда). */
    internal const val LEGACY_CHAIN = TtlScript.LEGACY_CHAIN

    /** Предел длины имени цепи iptables. */
    internal const val MAX_CHAIN_LEN = 28

    enum class RegistryState {
        /** Токен прочитан: имя цепи и метка известны. */
        PRESENT,

        /** Токена нет (первый запуск / данные очищены): можно создать свой. */
        ABSENT,

        /** Реестр прочитать/записать нельзя: ничего не меняем. */
        UNKNOWN
    }

    data class Registry(
        val state: RegistryState,
        val token: String?,
        val why: String = ""
    ) {
        /** Имя своей цепи; при неизвестном реестре — `null` (изменять нечего и нельзя). */
        val chain: String? get() = token?.let { CHAIN_PREFIX + it.take(8) }

        fun wire(): String = "state=$state token=${if (token == null) "none" else "present"} " +
            "chain=${chain ?: "none"}${if (why.isEmpty()) "" else " why=$why"}"
    }

    private fun randomToken(): String {
        val bytes = ByteArray(4)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    internal fun isValidToken(t: String?): Boolean =
        t != null && t.length >= 8 && t.all { it.isDigit() || it in 'a'..'f' }

    /** Имя цепи по токену (с гарантией длины ≤28). */
    fun chainFor(token: String): String =
        (CHAIN_PREFIX + token.filter { it.isDigit() || it in 'a'..'f' }.take(8)).take(MAX_CHAIN_LEN)

    /** Метка владения для правила. */
    fun markerOf(token: String?): String = "voiceapp3:ttl:" + (token ?: "default")

    private fun fromResolved(r: Toggles45.Resolved<String>?): Registry = when (r) {
        is Toggles45.Resolved.Value ->
            if (isValidToken(r.v)) Registry(RegistryState.PRESENT, r.v)
            else Registry(RegistryState.ABSENT, null, "invalid_token")
        Toggles45.Resolved.Deleted -> Registry(RegistryState.ABSENT, null, "none")
        Toggles45.Resolved.Conflict -> Registry(RegistryState.UNKNOWN, null, "conflict_same_rev")
        Toggles45.Resolved.Unknown, null -> Registry(RegistryState.UNKNOWN, null, "unreadable")
    }

    // ───────────────────────── ядро (без Android): проверяется на подставных копиях ──────────

    /** Прочитать реестр из набора копий. Ничего не создаёт. */
    fun load(copies: List<Prefs2.Copy>): Registry {
        if (copies.isEmpty() || copies.all { it.prefs == null }) {
            return Registry(RegistryState.UNKNOWN, null, "no_stores")
        }
        val r = runCatching { Prefs2.readStringCore(copies, PREF_TOKEN) }.getOrNull()
            ?: return Registry(RegistryState.UNKNOWN, null, "read_error")
        // Пусто в обеих читаемых копиях — это ДОКАЗАННОЕ отсутствие токена (первый запуск
        // после установки/очистки данных), а не ошибка чтения: только в этом случае можно
        // завести свою идентичность. Ошибка чтения уже обработана выше (null) и в
        // `Resolved.Unknown` ниже.
        val anyKey = copies.any { cp ->
            runCatching { cp.prefs?.contains(PREF_TOKEN) == true }.getOrDefault(false)
        }
        val anyDeleted = copies.any { cp ->
            runCatching { cp.prefs?.getBoolean(PREF_TOKEN + "#del", false) == true }
                .getOrDefault(false)
        }
        if (!anyKey && !anyDeleted && r == Toggles45.Resolved.Unknown) {
            return Registry(RegistryState.ABSENT, null, "none")
        }
        return fromResolved(r)
    }

    /**
     * Прочитать реестр, создав токен только при его ДОКАЗАННОМ отсутствии.
     *
     * Запись подтверждается readback: возвращается `PRESENT` лишь если прочитанное значение
     * совпало с записанным. Иначе `UNKNOWN` — и production ничего не меняет в таблице.
     */
    fun ensure(copies: List<Prefs2.Copy>): Registry {
        val cur = load(copies)
        if (cur.state != RegistryState.ABSENT) return cur
        val token = randomToken()
        val rev = runCatching { Prefs2.nextRevOf(copies, PREF_TOKEN) }.getOrNull()
            ?: return Registry(RegistryState.UNKNOWN, null, "rev_unreadable")
        val save = runCatching {
            Prefs2.putStringCore(copies, PREF_TOKEN, token, rev, "ttl_own")
        }.getOrNull() ?: return Registry(RegistryState.UNKNOWN, null, "write_error")
        if (!save.confirmed) return Registry(RegistryState.UNKNOWN, null, "write_unconfirmed")
        val back = load(copies)
        return if (back.state == RegistryState.PRESENT && back.token == token) back
        else Registry(RegistryState.UNKNOWN, null, "readback_mismatch")
    }

    // ───────────────────────── production-обёртки (Android-контекст) ────────────────────────

    /**
     * Прочитать реестр из боевого хранилища.
     *
     * Различие «нет токена» и «прочитать нельзя» принципиально: в первом случае можно безопасно
     * создать новую СВОЮ цепь (прежняя, если была, останется нетронутой как неизвестная), во
     * втором — нельзя даже этого, иначе каждое чтение с ошибкой порождало бы новую цепь.
     */
    fun load(ctx: Context?): Registry {
        if (ctx == null) return Registry(RegistryState.UNKNOWN, null, "no_context")
        val copies = runCatching { Prefs2.copiesOf(ctx.applicationContext) }.getOrNull()
            ?: return Registry(RegistryState.UNKNOWN, null, "no_stores")
        return load(copies)
    }

    /**
     * Получить реестр, создав токен при доказанном отсутствии.
     *
     * Запись идёт через `Prefs2.putStringEx` (межпроцессный lock + одна транзакция на копию).
     * Заявка считается успешной только при `confirmed` и совпавшем readback.
     */
    fun ensure(ctx: Context?): Registry {
        val cur = load(ctx)
        if (cur.state != RegistryState.ABSENT || ctx == null) return cur
        val token = randomToken()
        val save = runCatching { Prefs2.putStringEx(ctx, PREF_TOKEN, token) }.getOrNull()
            ?: return Registry(RegistryState.UNKNOWN, null, "write_error")
        if (!save.confirmed) return Registry(RegistryState.UNKNOWN, null, "write_unconfirmed")
        val back = load(ctx)
        return if (back.state == RegistryState.PRESENT && back.token == token) back
        else Registry(RegistryState.UNKNOWN, null, "readback_mismatch")
    }
}
