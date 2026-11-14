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

    /**
     * iter52 §6.1: число СО ЗНАКОМ. `refs_out4=-1` означает «прочитать нельзя», и это
     * НЕ то же, что «ссылок нет» (0). Прежний `num` (только `\d+`) отбрасывал `-1` в `null`,
     * после чего неизвестность выглядела как нормальный успех.
     */
    private fun signed(out: String, key: String): String? =
        Regex(NUM.pattern + Regex.escape("$key=") + "(-?\\d+)").find(out)?.groupValues?.get(1)

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
        // iter52 §6.1: ссылки на нашу цепочку из других мест (включая `-g`) — часть владения.
        val refsOut4 = signed(out, "refs_out4")
        val refsOut6 = signed(out, "refs_out6")
        val refsAny4 = signed(out, "refs_any4")
        val refsAny6 = signed(out, "refs_any6")
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
            "OWN_LEFT_STOP" in ttlCheckLine -> "OWN_LEFT_STOP"
            "APPLY_FAILED" in ttlCheckLine -> "APPLY_FAILED"
            "NOT_APPLIED" in ttlCheckLine -> "NOT_APPLIED"
            "REF_ELSEWHERE" in ttlCheckLine -> "REF_ELSEWHERE"
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
        // iter50 §5.1(3): своё правило снять не удалось — повторное включение остановлено.
        // В таблице могло остаться КОРРЕКТНОЕ правило от прошлого раза; это не «включилось сейчас».
        val ownLeftStop = ttlVerdict == "OWN_LEFT_STOP"
        // iter51 §4.3(1): состояние IPv6 проверяется ОТДЕЛЬНО от IPv4. Раньше при ON
        // достаточно было `VERIFY_V4=OK` + `uplink_only=yes`: `VERIFY_V6=FAIL` (правило v6 не
        // встало/не прочиталось) давал ПОЛНЫЙ OK, хотя половина семейства не применена.
        // Различаем:
        //  • `SKIPPED`/`none` — IPv6 в этой системе НЕТ (не поддержан/выключен): это не ошибка,
        //    но и не «всё проверено» — статус остаётся `SCOPED_V4_ONLY`;
        //  • `FAIL`/`NOT_APPLIED`/`COLLISION`/`UNKNOWN` — IPv6 ЕСТЬ, но применение/чтение не
        //    удалось: полного подтверждения быть не может.
        val v6PresentButFailed = v6Verify != null && v6Verify != "OK" &&
            v6Verify != "SKIPPED" && v6Verify != "none"
        // iter52 §6.1: «IPv6 нет в системе» доказывается ФЛАГОМ `v6_supported=0`, а не
        // ОТСУТСТВИЕМ строки. Раньше `VERIFY_V6` не разобран (null) трактовался как
        // «IPv6 не поддержан», и обрезанный/потерянный вывод давал заявление «IPv6 в системе
        // нет» и спокойный OK по одному семейству — то есть догадку вместо факта.
        val v6Supported = flag(out, "v6_supported")
        val v6Unsupported = v6Supported == "0" || v6Verify == "none" ||
            (v6Verify == "SKIPPED" && v6Supported != "1")
        // Нет ни строки VERIFY_V6, ни явного `v6_supported=0` — состояние IPv6 НЕИЗВЕСТНО.
        val v6NotParsed = !v6Unsupported && v6Verify == null
        // §4.3(1): ЧАСТИЧНЫЙ случай — ровно «v4 применён и проверен, а v6 присутствует, но не
        // применён/не прочитан». Любая другая проблема (коллизия, откат, нечитаемая таблица,
        // нет uplink, не встал v4) остаётся FAILED с прежней причиной.
        val v6OnlyIssue = expected && v6PresentButFailed && v4Verify == "OK" && v4Count >= 1 &&
            !collision && !rolledBack && !readFailed && uplinkOnly
        val v6UnknownPartial = expected && v6NotParsed && v4Verify == "OK" && v4Count >= 1 &&
            !collision && !rolledBack && !readFailed && uplinkOnly
        val v6Reason = "IPv6 присутствует, но применение или чтение не удалось " +
            "(VERIFY_V6=$v6Verify): v4=$v4Verify — полного подтверждения обоих семейств нет, " +
            "состояние ЧАСТИЧНОЕ. Правило v4 при этом стоит; смотрите TTL_V4/TTL_CHECK."
        // Ссылка из другой цепи/через `-g` = правило действует шире, чем мы ставили.
        // `-1` — таблицу прочитать не удалось: это не «ссылок нет», а неизвестность.
        val refProblem = listOf(refsOut4, refsOut6, refsAny4, refsAny6).any { it == "-1" } ||
            (refsOut4?.toIntOrNull() ?: 0) > 0 || (refsOut6?.toIntOrNull() ?: 0) > 0
        val refUnknown = listOf(refsOut4, refsOut6).any { it == "-1" }
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
            v6OnlyIssue -> v6Reason
            refUnknown ->
                "ссылки на ${TtlScript.OUR_CHAIN} прочитать не удалось (refs_out4=${refsOut4 ?: "?"} " +
                    "refs_out6=${refsOut6 ?: "?"}): владение цепочкой НЕ доказано — состояние неизвестно"
            refProblem ->
                "на ${TtlScript.OUR_CHAIN} ссылаются НЕ только наш переход -o <uplink>: " +
                    "refs_out4=$refsOut4 refs_out6=$refsOut6 (в том числе через -g). Правило " +
                    "действует шире, чем мы ставили; чужое не удаляем, но и полного успеха нет."
            v6UnknownPartial || (v6NotParsed && expected) ->
                "результат по IPv6 НЕ РАЗОБРАН: в выводе нет ни VERIFY_V6, ни `v6_supported=0` — " +
                    "состояние второго семейства НЕИЗВЕСТНО (обрезанный/потерянный вывод). Это не " +
                    "«IPv6 в системе нет»: полного подтверждения нет, смотрите TTL_RESULT целиком."
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
            ownLeftStop ->
                "своё правило снять не удалось (TTL_DEL rc≠0): повторное включение остановлено, " +
                    "чтобы рядом не появилось второе правило. TTL не подтверждён — посмотрите TTL_DEL/TTL_OWN."
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
            // iter51 §4.3(1): ON с присутствующим, но неработающим IPv6 — ЧАСТИЧНО (v4 применён,
            // v6 нет). Не FAILED: половина действительно применена и это надо показать как есть.
            v6OnlyIssue -> Verdict.PARTIAL
            v6UnknownPartial -> Verdict.PARTIAL
            // Правило стоит и подтверждено по цели, но ссылки шире нашей: это ЧАСТИЧНО.
            refProblem && !refUnknown && v4Verify == "OK" -> Verdict.PARTIAL
            else -> Verdict.FAILED
        }

        val detail = when {
            reason != null -> reason
            expected && v6Unsupported && v4Verify == "OK" ->
                "применено ТОЛЬКО IPv4 (IPv6 в системе нет/не поддержан: VERIFY_V6=$v6Verify): " +
                    "это не ошибка, но и не полная проверка обоих семейств. TTL_V4=$v4 " +
                    "scoped=uplink_only wire=not_measured"
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
                "refs_out4" to (refsOut4 ?: "?"),
                "refs_out6" to (refsOut6 ?: "?"),
                "read_v4" to (read4 ?: "?"),
                "read_v6" to (read6 ?: "?"),
                "ttl_off" to offLine.ifEmpty { "-" },
                "ttl_own" to ownLine.ifEmpty { "-" }
            )
        )
    }
}
