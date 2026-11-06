package com.example.voiceapp3.tools

/**
 * iter33j P0.2 — разбор единственной строки вердикта маунта:
 *
 *     HVAC_MOUNT_RESULT mount=0|1 inode_match=true|false md5_match=true|false verdict=OK|FAILED
 *     HVAC_MOUNT_DETAIL src=… dst=… src_inode=… tgt_inode=… src_md5=… tgt_md5=… src_size=… tgt_size=…
 *
 * Правила вердикта (правило владельца 06.11 — «только монтирование», поэтому вердикт должен
 * быть честным): `OK` только если маунт есть И совпал inode И совпал md5. Любое расхождение —
 * `FAILED`. Старый баг «OK при inode:false» закрыт тестом [MountResultTest].
 */
internal data class MountResult(
    val mount: Boolean,
    val inodeMatch: Boolean,
    val md5Match: Boolean,
    val verdict: String,
) {
    val ok: Boolean get() = verdict == "OK"

    /** Строка в лог приложения — та же, что напечатал скрипт (для сверки глазами). */
    fun line(): String =
        "HVAC_MOUNT_RESULT mount=${if (mount) 1 else 0} inode_match=$inodeMatch md5_match=$md5Match verdict=$verdict"

    companion object {
        private val RE = Regex(
            "HVAC_MOUNT_RESULT mount=([01]) inode_match=(true|false) md5_match=(true|false) verdict=(OK|FAILED)"
        )

        /** `null` — строки в выводе нет (скрипт не дошёл до маунта). */
        fun parse(scriptOut: String): MountResult? {
            val m = RE.find(scriptOut) ?: return null
            val mount = m.groupValues[1] == "1"
            val inode = m.groupValues[2] == "true"
            val md5 = m.groupValues[3] == "true"
            val raw = m.groupValues[4]
            // Двойная проверка: «OK» из скрипта, противоречащий флагам, не принимаем.
            val verdict = if (raw == "OK" && mount && inode && md5) "OK" else "FAILED"
            return MountResult(mount, inode, md5, verdict)
        }

        /** `tgt_size=` из `HVAC_MOUNT_DETAIL` — для опознания плагина по размеру. */
        fun detailSize(scriptOut: String, key: String): Long? =
            Regex("HVAC_MOUNT_DETAIL[^\\n]*").find(scriptOut)
                ?.value
                ?.let { Regex("$key=([0-9]+)").find(it)?.groupValues?.get(1)?.toLongOrNull() }
    }
}
