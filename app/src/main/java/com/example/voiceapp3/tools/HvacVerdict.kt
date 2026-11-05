package com.example.voiceapp3.tools

/**
 * iter33c A1/A2/A3 — разбор вывода скрипта установки плагина климата.
 *
 * Зачем отдельный объект: оба дефекта второй пересборки были регрессами **разбора строк**
 * (ждали `"SRC_INODE=<n> TGT_INODE=<n>"` одной строкой и брали первое `via=` из служебного
 * поля `HVAC_UNSHADOW`). Ловятся такие регрессы только тестом, а тест не должен тащить
 * Android-зависимости ⇒ здесь чистый Kotlin, без `Context`/`AdbClient`/`AppLog`.
 *
 * Источник истины — то, что скрипт печатает сам:
 *  • `###STAGE name=bind status=OK reason=inode_match:<inode>` — bind удался, inode совпал;
 *  • `###STAGE name=bind status=SKIP reason=already_mounted_inode_match` — уже смонтировано;
 *  • `###STAGE name=bind status=FAILED reason=mounted:<0|1> inode:<src>/<tgt>` — провал;
 *  • `SRC_INODE=<n>` / `TGT_INODE=<n>` — отдельные строки (теперь печатаются обе);
 *  • итоговая строка `VIA=bind|install|nenhum` — строго с начала строки;
 *  • служебная `HVAC_UNSHADOW: … unshadow_via=<pm_uninstall|uninstall_system_updates>` — НЕ итог.
 */
object HvacVerdict {

    /** Разобранный вердикт. Значения по умолчанию — «ничего не нашли», без исключений. */
    data class Data(
        /** Итоговый способ установки: `bind` | `install` | `nenhum` | `?`. */
        val via: String = "?",
        /** Служебное поле снятия затмения: `pm_uninstall` | `uninstall_system_updates` | `none` | `-`. */
        val unshadowVia: String = "-",
        val srcInode: String = "-",
        val tgtInode: String = "-",
        /** Inode источника и цели совпали (или скрипт прямо сказал `status=OK reason=inode_match:`). */
        val inodeMatch: Boolean = false,
        /** Статус шага bind из `###STAGE`: `OK` | `SKIP` | `FAILED` | `none`. */
        val bindStage: String = "none",
        /** Скрипт напечатал `HVAC_DIGITAL_FALHOU` (явный провал). */
        val failFlag: Boolean = false,
        /** Скрипт напечатал `HVAC_DIGITAL_OK`. */
        val okFlag: Boolean = false
    )

    // ── регулярки ────────────────────────────────────────────────────────────
    /** A2: `^VIA=…$` — якоря обязательны, иначе первым совпадением станет `via=` из HVAC_UNSHADOW. */
    private val RE_VIA = Regex("(?m)^VIA=([A-Za-z_]+)\\s*$", RegexOption.IGNORE_CASE)
    private val RE_UNSHADOW_VIA = Regex("unshadow_via=([A-Za-z_]+)", RegexOption.IGNORE_CASE)
    private val RE_SRC = Regex("(?m)^SRC_INODE=([0-9]+)\\s*$")
    private val RE_TGT = Regex("(?m)^TGT_INODE=([0-9]+)\\s*$")
    private val RE_BIND_STAGE = Regex("###STAGE name=bind status=([A-Za-z]+)(?: reason=([^\\n]*))?")
    private val RE_INODE_MATCH_REASON = Regex("inode_match:([0-9]+)")
    private val RE_INODE_PAIR_REASON = Regex("inode:([0-9]*)/([0-9]*)")

    fun parse(out: String): Data {
        if (out.isBlank()) return Data()

        val via = RE_VIA.find(out)?.groupValues?.get(1)?.lowercase() ?: "?"
        val unshadowVia = RE_UNSHADOW_VIA.find(out)?.groupValues?.get(1) ?: "-"

        val src = RE_SRC.find(out)?.groupValues?.get(1) ?: "-"
        var tgt = RE_TGT.find(out)?.groupValues?.get(1) ?: "-"

        val bindStatus = RE_BIND_STAGE.find(out)?.let { m ->
            m.groupValues[1].uppercase() to m.groupValues[2].trim()
        }
        val bindStage = bindStatus?.first ?: "none"
        val bindReason = bindStatus?.second.orEmpty()

        // TGT_INODE может отсутствовать отдельной строкой (старый вывод) — берём из reason.
        if (tgt == "-") {
            tgt = RE_INODE_MATCH_REASON.find(bindReason)?.groupValues?.get(1)
                ?: RE_INODE_PAIR_REASON.find(bindReason)?.groupValues?.get(2)?.takeIf { it.isNotEmpty() }
                ?: "-"
        }
        // SRC_INODE — тоже fallback из reason `inode:<src>/<tgt>`.
        val srcFinal = if (src == "-") {
            RE_INODE_PAIR_REASON.find(bindReason)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() } ?: "-"
        } else src

        val sameInode = srcFinal != "-" && tgt != "-" && srcFinal == tgt && srcFinal != "0"
        // A1: bind-стадия сама себе доказательство — `OK` с inode_match или `SKIP` «уже смонтировано
        // с совпадением inode». Без этого `inodeMatch` оставался false и успех уезжал в PARTIAL.
        val inodeMatch = sameInode ||
            (bindStage == "OK" && bindReason.contains("inode_match")) ||
            (bindStage == "SKIP" && bindReason.contains("inode_match"))

        return Data(
            via = via,
            unshadowVia = unshadowVia,
            srcInode = srcFinal,
            tgtInode = tgt,
            inodeMatch = inodeMatch,
            bindStage = bindStage,
            failFlag = out.contains("HVAC_DIGITAL_FALHOU"),
            okFlag = out.contains("HVAC_DIGITAL_OK")
        )
    }

    /**
     * A1 (третье следствие): статус шага. Чистая функция — её же проверяет тест.
     *
     * Правила, важные для приёмки:
     *  • явный провал (`HVAC_DIGITAL_FALHOU`, `VIA=nenhum`, `###STAGE name=bind status=FAILED`) ⇒ FAILED,
     *    даже если `mountOk` почему-то true;
     *  • `mountOk == true` **и** bind-стадия `OK|SKIP` ⇒ OK **независимо** от того, удалось ли
     *    посчитать inode (`inodeMatch == false` не имеет права единолично давать PARTIAL);
     *  • PARTIAL — только когда mount есть, а вердикта по bind нет вовсе.
     *
     * @return `OK` | `PARTIAL` | `FAILED` | `BLOCKED`
     */
    fun statusOf(
        d: Data,
        mountOk: Boolean,
        rc: Int,
        permDenied: Boolean = false,
        rootUnavailable: Boolean = false,
        naoVingou: Boolean = false
    ): String = when {
        rootUnavailable -> "BLOCKED"
        permDenied -> "FAILED"
        d.via == "nenhum" || naoVingou || d.failFlag || d.bindStage == "FAILED" -> "FAILED"
        // Затмение не снято и скрипт вышел по ошибке — это провал, а не «OK по /data/app».
        d.via == "install" && rc != 0 -> "FAILED"
        d.via == "install" -> "OK"
        d.via == "bind" && (mountOk || d.inodeMatch || d.bindStage == "OK" || d.bindStage == "SKIP") -> "OK"
        mountOk && (d.bindStage == "OK" || d.bindStage == "SKIP") -> "OK"
        mountOk -> "PARTIAL"
        else -> "FAILED"
    }
}
