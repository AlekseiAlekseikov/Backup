package com.example.voiceapp3.tools

/**
 * iter48 §Б/§Ж — разбор вывода скрипта TTL в ЧИСТОМ виде (без Android), чтобы его можно было
 * проверять JVM-тестами, а не «на глаз по логу».
 *
 * Вынесено из `NetworkTtl.parseVerifyResult` по двум причинам:
 *  1. §Ж требует проверяемого кода: `NetworkTtl` тянет `Context`/`Prefs2`/`AdbClient` и в
 *     JVM-прогон не входит, поэтому его разбор в тестах не участвовал;
 *  2. при переносе найден и исправлен РЕАЛЬНЫЙ дефект: в исходном коде регулярные выражения
 *     были записаны с ДВОЙНЫМ экранированием (`"\\\\s"` в Kotlin = `\\s` в регулярке), т.е.
 *     искали литеральный обратный слэш, а не пробел. Значение, стоящее в сводной строке после
 *     пробела (`TTL_V6=0`), не разбиралось → `v6 = null`, и OFF при полностью снятых правилах
 *     мог быть объявлен «не выполнен». Здесь экранирование правильное, и это покрыто тестом.
 *
 * Что НЕ доказывается этим разбором: фактический TTL в пакетах на проводе. `wire=not_measured`
 * остаётся правдой: правило iptables — это не измерение TTL.
 */
internal object TtlVerify {

    /** Итог шага (соответствует `ToolsStep.Status`, но без зависимости от gson/Android). */
    enum class Verdict { OK, PARTIAL, FAILED, BLOCKED }

    /**
     * @param verdict итог;
     * @param detail  честный текст для UI/журнала;
     * @param reason  причина несобранного успеха (`null` — успех);
     * @param facts   разобранные значения (для отчёта и тестов).
     */
    data class Parsed(
        val verdict: Verdict,
        val detail: String,
        val reason: String?,
        val facts: Map<String, String>
    )

    private val NUM = "(?:^|\\s|_)".toRegex()
    private val FLAG = "(?:^|\\s)".toRegex()

    private fun num(out: String, key: String): String? =
        Regex(NUM.pattern + Regex.escape("$key=") + "(\\d+)").find(out)?.groupValues?.get(1)

    private fun flag(out: String, key: String): String? =
        Regex(FLAG.pattern + Regex.escape("$key=") + "([A-Za-z0-9_-]+)").find(out)
            ?.groupValues?.get(1)

    private fun line(out: String, prefix: String): String =
        out.lineSequence().firstOrNull { it.startsWith(prefix) } ?: ""

    /**
     * Разбор результата ОДНОГО применения.
     *
     * ON успешен только при `TTL_V4>=1` (своё правило), `VERIFY_V4=OK`, `uplink_only=yes`,
     * `variant=uplink`; OFF — при `TTL_V4=0 && TTL_V6=0` и `TTL_OFF … result=OK`.
     * `result=PARTIAL reason=foreign_rules_remain` — свои правила сняты, но на машине остались
     * ЧУЖИЕ `ttl-set`/`hl-set`: это `PARTIAL` (чужое не удаляем, «к стоку вернулось» не заявляем).
     */
    fun parse(expected: Boolean, out: String): Parsed {
        val v4 = num(out, "TTL_V4")
        val v6 = num(out, "TTL_V6")
        val ownJ4 = num(out, "OWN_J4") ?: num(out, "J4")
        val ownJ6 = num(out, "OWN_J6") ?: num(out, "J6")
        // iter49 §5.2: «прочитано/не прочитано» печатается отдельно от «сколько правил».
        val read4 = flag(out, "READ_V4")
        val read6 = flag(out, "READ_V6")
        val chain4 = flag(out, "CHAIN4")
        val chain6 = flag(out, "CHAIN6")
        val foreign4 = num(out, "FOREIGN_V4")
        val foreign6 = num(out, "FOREIGN_V6")
        val v4Verify = flag(out, "VERIFY_V4")
        val v6Verify = flag(out, "VERIFY_V6")
        val ttlCheckLine = line(out, "TTL_CHECK:")
        val ttlVerdict = when {
            "SCOPED_OK" in ttlCheckLine -> "SCOPED_OK"
            "OFF_OK" in ttlCheckLine -> "OFF_OK"
            "OFF_OWN_FOREIGN_LEFT" in ttlCheckLine -> "OFF_OWN_FOREIGN_LEFT"
            "SCOPED_V4_ONLY" in ttlCheckLine -> "SCOPED_V4_ONLY"
            "CHECK_OWNER" in ttlCheckLine -> "CHECK_OWNER"
            "CHAIN_COLLISION" in ttlCheckLine -> "CHAIN_COLLISION"
            "READ_UNKNOWN" in ttlCheckLine -> "READ_UNKNOWN"
            "APPLY_FAILED" in ttlCheckLine -> "APPLY_FAILED"
            "NOT_APPLIED" in ttlCheckLine -> "NOT_APPLIED"
            "\u2192 VERIFIED" in ttlCheckLine -> "VERIFIED"
            "\u2192 MISMATCH" in ttlCheckLine -> "MISMATCH"
            "\u2192 INDIRECT" in ttlCheckLine -> "INDIRECT"
            "\u2192 UNVERIFIED" in ttlCheckLine -> "UNVERIFIED"
            else -> ""
        }
        val tryLine = line(out, "TTL_TRY")
        val scopeLine = line(out, "TTL_SCOPE")
        val variant = Regex("variant=([^ ]+)").find(tryLine)?.groupValues?.get(1) ?: ""
        val noUplink = variant == "none" && "reason=no_uplink" in tryLine
        val uplinkOnly = "uplink_only=yes" in scopeLine
        val hasLock = out.lowercase().contains("xtables lock")
        val hasPerm = out.lowercase().contains("permission denied") ||
            out.lowercase().contains("avc: denied")
        val hasNoRoot = out.contains("ERR_ROOT_CHANNEL_UNAVAILABLE")
        val noTarget = out.lowercase().contains("no chain/target/match")
        val v4Count = v4?.toIntOrNull() ?: 0
        val offLine = line(out, "TTL_OFF ")
        val offOk = "result=OK" in offLine
        val offPartialForeign = "result=PARTIAL" in offLine && "foreign_rules_remain" in offLine
        val ownLine = line(out, "TTL_OWN ")
        val ownPartial = "removed=partial" in ownLine
        // iter49 §5.2: пустой ответ `2>/dev/null` больше не выдаётся за «правил нет».
        val readFailed = read4 == "failed" || read6 == "failed"
        val offUnknownReadFail = offLine.contains("result=UNKNOWN") || "reason=read_failed" in out
        val collision = chain4 == "collision" || chain6 == "collision" ||
            v4Verify == "COLLISION" || v6Verify == "COLLISION" || "CHAIN_COLLISION" in ttlCheckLine
        val rolledBack = "APPLY_FAILED" in ttlCheckLine || "TTL_ROLLBACK" in out
        val notApplied = "NOT_APPLIED" in ttlCheckLine ||
            v4Verify == "NOT_APPLIED" || v6Verify == "NOT_APPLIED"
        val quotedRefused = "reason=quoted_spec_needs_owner" in out
        val foreignN = foreign4?.toIntOrNull() ?: 0
        val foreignN6 = foreign6?.toIntOrNull() ?: 0

        val reason: String? = when {
            readFailed || offUnknownReadFail ->
                "правила прочитать не удалось (READ_V4=${read4 ?: "?"} READ_V6=${read6 ?: "?"}) — " +
                    "состояние НЕИЗВЕСТНО, а не «правил нет». Повторите и посмотрите TTL_FOREIGN."
            collision ->
                "цепочка ${TtlScript.OUR_CHAIN} занята не нами (CHAIN4=${chain4 ?: "?"} " +
                    "CHAIN6=${chain6 ?: "?"}): содержимое не совпадает с нашим правилом. Чужое не " +
                    "трогаем (ни -F, ни -X): TTL не применён."
            rolledBack ->
                "включение откатано целиком: половинчатое состояние (только v4 или только v6) не " +
                    "оставлено. Смотрите TTL_ADD/TTL_ROLLBACK — iptables не принял правило."
            notApplied ->
                "не применялось: состояние одной из семей не наше (или не прочитано) — " +
                    "включать половину нельзя."
            hasNoRoot -> "канал недоступен — 5555 не отвечает (uid=1000). Проверьте «Для разработчика» → «Проверить root»."
            hasPerm -> "Permission denied (нужен root-шелл uid=0)"
            hasLock -> "xtables lock (iptables занят) — правило не применилось, повторите"
            noTarget -> "iptables target 'TTL' не поддерживается ядром (модуль отсутствует)"
            !expected && offPartialForeign -> null
            !expected && quotedRefused && ownPartial ->
                "свои переходы сняты не все: переход со сложной спецификацией (кавычки/match) не " +
                    "разобран и оставлен как есть — удалять по догадке нельзя. TTL мог остаться " +
                    "включённым, состояние частичное."
            !expected && ownPartial ->
                "своё снято не полностью ($ownLine) — состояние неизвестно, повторите"
            !expected && !(v4 == "0" && v6 == "0" && offOk) ->
                "TTL_OFF не выполнился: осталось своё правило v4=${v4 ?: "?"} v6=${v6 ?: "?"} " +
                    "(см. TTL_DEL/TTL_OWN/TTL_FOREIGN)"
            !expected -> null
            noUplink ->
                "нет uplink (правило без uplink не ставим — ломает CarPlay), TTL_TRY variant=none reason=no_uplink"
            v4Count < 1 || v4Verify != "OK" ->
                "rule_not_installed: своего правила TTL в mangle/${
                    TtlScript.OUR_CHAIN
                } нет (TTL_V4=$v4 VERIFY_V4=$v4Verify)"
            !uplinkOnly ->
                "правило без скоупа -o <uplink> — запрещено (CarPlay): $scopeLine"
            else -> null
        }

        val verdict = when {
            reason == null && offPartialForeign -> Verdict.PARTIAL
            reason == null -> Verdict.OK
            hasNoRoot -> Verdict.BLOCKED
            else -> Verdict.FAILED
        }

        val detail = when {
            reason != null -> reason
            expected -> "своё правило стоит: TTL_V4=$v4 TTL_V6=$v6 OWN_J4=$ownJ4 OWN_J6=$ownJ6 " +
                "VERIFY=$v4Verify/$v6Verify scoped=uplink_only verdict=$ttlVerdict " +
                "foreign_v4=${foreignN} foreign_v6=${foreignN6} wire=not_measured"
            offPartialForeign ->
                "свои правила сняты (own_v4=0 own_v6=0), но на машине остались ЧУЖИЕ ttl-set/hl-set: " +
                    "foreign_v4=$foreignN foreign_v6=$foreignN6. Их не удаляем: принадлежность " +
                    "приложению не доказана. К стоку это не «вернулось»."
            else -> "TTL_V4=0 TTL_V6=0 VERIFY=$v4Verify/$v6Verify verdict=$ttlVerdict TTL_OFF result=OK"
        }

        return Parsed(
            verdict = verdict,
            detail = detail,
            reason = reason,
            facts = mapOf(
                "ttl_v4" to (v4 ?: "?"),
                "ttl_v6" to (v6 ?: "?"),
                "own_j4" to (ownJ4 ?: "?"),
                "own_j6" to (ownJ6 ?: "?"),
                "foreign_v4" to (foreign4 ?: "?"),
                "foreign_v6" to (foreign6 ?: "?"),
                "verify_v4" to (v4Verify ?: "?"),
                "verify_v6" to (v6Verify ?: "?"),
                "variant" to variant.ifEmpty { "?" },
                "uplink_only" to if (uplinkOnly) "yes" else "no",
                "verdict" to ttlVerdict.ifEmpty { "?" },
                "chain_v4" to (chain4 ?: "?"),
                "chain_v6" to (chain6 ?: "?"),
                "read_v4" to (read4 ?: "?"),
                "read_v6" to (read6 ?: "?"),
                "ttl_off" to offLine.ifEmpty { "-" },
                "ttl_own" to ownLine.ifEmpty { "-" }
            )
        )
    }
}
