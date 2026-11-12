package com.example.voiceapp3.tools

/**
 * Одноразовая **read-only** диагностика сети: что видит текущая система, когда «интернет не
 * грузится». Никакого лечения и никаких изменений подключения.
 *
 * Зачем: у владельца на 1.0.60 ГУ подключается к раздаче телефона, но сайты не открываются.
 * Причина не установлена. Этот сбор даёт факты (адрес, маршруты, правила, DNS, состояние
 * подключения Android, ограниченные ICMP-проверки), чтобы разбираться по данным, а не по догадкам.
 *
 * Жёсткие границы (проверяются тестом по ФАКТИЧЕСКИ отправленным строкам):
 *  • только чтение: нет `umount`/`mount`, `setprop`, `svc`, `kill`, `reboot`, `iptables`,
 *    `ip rule add|del`, `ndc`, `settings`, изменения Wi-Fi и запуска/остановки сервисов;
 *  • единственные внешние обращения — до трёх ICMP-пакетов на `1.1.1.1` и на `google.com`
 *    (проверка связности и разрешения имени); содержимое не скачивается, данные не передаются;
 *  • не читаются и не пишутся SSID/BSSID, MAC, идентификаторы телефона; в лог попадают только
 *    IP-адреса, маршруты и адреса DNS — они могут быть чувствительными, об этом сказано в начале;
 *  • запуск в фоне, один раз за процесс, без UI-потока, без зависимости от клика JIT.
 *
 * Чего этот сбор НЕ доказывает: неудачный `ping` не означает «интернета нет» (ICMP могут
 * блокироваться, контекст root-shell и приложения различаются), а пустые свойства DNS не
 * означают отсутствия DNS. Поэтому в конце пишется сводка фактов, а не диагноз.
 */
object InternetDiag {

    internal const val SECTION_TIMEOUT_MS = 8_000
    internal const val PING_TIMEOUT_MS = 12_000
    internal const val CHUNK_CHARS = 1_500

    internal const val STATUS_OK = "ok"
    internal const val STATUS_EMPTY = "empty"
    internal const val STATUS_PERMISSION_DENIED = "permission_denied"
    internal const val STATUS_CHANNEL_NOT_READY = "channel_not_ready"
    internal const val STATUS_UNAVAILABLE = "unavailable"
    internal const val STATUS_TIMEOUT = "timeout"
    internal const val STATUS_TRUNCATED = "truncated"
    internal const val STATUS_UNKNOWN = "unknown"

    /** Метки в датированном логе приложения. */
    internal const val BEGIN = "INTERNET_DIAG_BEGIN"
    internal const val SECTION = "INTERNET_DIAG_SECTION"
    internal const val DATA = "INTERNET_DIAG_DATA"
    internal const val END = "INTERNET_DIAG_END"

    /** Итог по одному разделу. */
    data class Section(
        val name: String,
        /** Статус ВЫПОЛНЕНИЯ команды/чтения, не «диагноз». */
        val status: String,
        val rc: Int,
        val lines: Int,
        val parts: Int,
        /** Отдельный смысловой итог там, где он есть (`ping`: `echo_received`/`no_reply`). */
        val verdict: String? = null
    )

    internal data class Cmd(val name: String, val body: String, val timeoutMs: Int, val ping: Boolean = false)

    /**
     * Все shell-команды диагностики — константы этого файла.
     *
     * `ip rule show` и `ip route show table all` — ЧТЕНИЕ правил и маршрутов; ни `add`, ни `del`
     * здесь нет и появиться не может (проверяется тестом).
     */
    internal fun commands(): List<Cmd> = listOf(
        Cmd("wlan0_addr", "ip addr show wlan0", SECTION_TIMEOUT_MS),
        Cmd("ip_rules", "ip rule show", SECTION_TIMEOUT_MS),
        Cmd("routes", "ip route show table all", SECTION_TIMEOUT_MS),
        Cmd(
            "dns_props",
            "for p in net.dns1 net.dns2 net.dns3 net.dns4 dhcp.wlan0.dns1 dhcp.wlan0.dns2 " +
                "net.dns.search; do v=\$(getprop \$p); echo \"\$p=\${v}\"; done",
            SECTION_TIMEOUT_MS
        ),
        Cmd("ping_ip", "ping -c 3 -W 2 1.1.1.1", PING_TIMEOUT_MS, ping = true),
        Cmd("ping_name", "ping -c 3 -W 2 google.com", PING_TIMEOUT_MS, ping = true)
    )

    /** Обёртка команды: маркер кода возврата конкретной команды + маркер конца вывода. */
    internal fun wrap(name: String, body: String): String {
        val tag = name.uppercase()
        return "{ $body ; } 2>&1; echo \"__JMD_${tag}_RC=\$?\"; echo \"__JMD_EOF=1\""
    }

    // ─────────────────────────── разбор ───────────────────────────

    internal fun rcOf(out: String, name: String): Int? =
        Regex("__JMD_${Regex.escape(name.uppercase())}_RC=(\\d+)")
            .find(out)?.groupValues?.get(1)?.toIntOrNull()

    internal fun complete(out: String): Boolean = out.contains("__JMD_EOF=1")

    internal fun body(out: String): String =
        out.lines().filterNot { it.startsWith("__JMD_") }.joinToString("\n").trim()

    // ─────────────────────────── личные данные (§3.2) ───────────────────────────

    /**
     * MAC-адрес в любом из типичных форматов: `aa:bb:cc:dd:ee:ff`, `AA-BB-CC-DD-EE-FF`,
     * `aabb.ccdd.eeff` (Cisco-стиль), а также звёздочки `*` в позициях октетов (Android 10+
     * скрывает MAC в `ip addr`: `link/ether 02:00:00:00:00:00`/`**:**:**:**:**:**`).
     */
    private val MAC_RE = Regex(
        // Границы: слева не hex/`:`/`.`/`-` (иначе вырежем хвост IPv6 вида
        // `fe80::1a:2b:3c:4d:5e:6f`), справа не hex/`:`/`.` (иначе разрежем длинную группу).
        "(?i)(?<![0-9a-f:.\\-])(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}(?![0-9a-f:.])" + "|" +
            // Cisco-формат aabb.ccdd.eeff — только когда вокруг нет hex/двоеточий.
            "(?i)(?<![0-9a-f:.])(?:[0-9a-f]{4}\\.){2}[0-9a-f]{4}(?![0-9a-f:.])" + "|" +
            // Скрытый MAC Android: **:**:**:**:**:**  (только звёздочки, не пересекается с IPv6)
            "(?:\\*{2}:){5}\\*{2}"
    )

    /** SSID/BSSID/имя сети в выводе `ip`/`wpa_cli`/`dumpsys`: значение удаляем, поле оставляем. */
    private val SSID_RE = Regex(
        "(?i)\\b(ssid|bssid|wifi_ssid|wlan_ssid|current_ssid|ap_ssid)\\b(\\s*[:=]\\s*)" +
            "(\"[^\"\\n]*\"|'[^'\\n]*'|[^\\s,;\\n]+)"
    )

    /** Признак строки, которую нельзя писать в лог как есть. */
    internal fun needsRedaction(text: String): Boolean =
        MAC_RE.containsMatchIn(text) || SSID_RE.containsMatchIn(text)

    /**
     * Удаление аппаратных идентификаторов перед записью в лог: MAC (все форматы), SSID/BSSID.
     *
     * Что НЕ трогается: IPv4/IPv6-адреса, маршруты, номера таблиц, DNS, имена интерфейсов,
     * счётчики — они нужны для анализа. Замена явная (`<удалён:MAC>`), чтобы читающий лог видел
     * факт редактирования, а не «пустое место».
     *
     * Ограничение: это фильтр известных форматов. Он не доказывает, что полный общий лог
     * приложения не содержит иных сведений — об этом сказано в предупреждении владельцу.
     */
    fun redact(text: String): String = redactWithFlag(text).first

    /**
     * §3.2 iter40: редактирование + ФАКТИЧЕСКАЯ замена.
     *
     * Второй элемент — `true`, если хотя бы одна замена реально произошла. Флаг нельзя вычислять
     * по уже отредактированному тексту (раньше из-за этого мог печататься `redacted=no` после
     * успешного удаления MAC).
     */
    fun redactWithFlag(text: String): Pair<String, Boolean> {
        var changed = false
        var t = MAC_RE.replace(text) { changed = true; "<удалён:MAC>" }
        t = SSID_RE.replace(t) { m ->
            changed = true
            m.groupValues[1] + m.groupValues[2] + "<удалён:SSID/BSSID>"
        }
        return t to changed
    }

    /** Строки, несущие признаки отказа в правах или отсутствия утилиты. */
    private fun denied(o: String): Boolean =
        o.contains("Permission denied") || o.contains("Operation not permitted") ||
            o.contains("not permitted")

    private fun missingTool(o: String): Boolean =
        o.contains("not found") || o.contains("inaccessible") || o.contains("No such file") ||
            o.contains("not executable")

    /**
     * Статус раздела. Код возврата КОНКРЕТНОЙ команды отделён от статуса транспорта и от того,
     * что мы увидели в тексте.
     */
    internal fun statusOf(transportRc: Int, out: String, rc: Int?, hasBody: Boolean): String {
        if (transportRc == 137 || out.contains("ERR_ADB_TIMEOUT") || out.contains("ERR_TIMEOUT")) {
            return STATUS_TIMEOUT
        }
        // §3.1/§3.4: пассивный путь честно сказал «недоступно» — это НЕ пусто и не «нет root».
        if (out.contains("ERR_ADB_PASSIVE_UNAVAILABLE")) {
            return if (out.contains("channel_not_ready") || out.contains("not_started") ||
                out.contains("state=BOOTING") || out.contains("state=IDLE")
            ) STATUS_CHANNEL_NOT_READY else STATUS_UNAVAILABLE
        }
        if (out.contains("ERR_ROOT_CHANNEL_UNAVAILABLE")) return STATUS_UNAVAILABLE
        if (!complete(out)) return STATUS_UNKNOWN          // хвост потерян: полноты не подтвердить
        if (rc == null) return STATUS_UNKNOWN              // нет маркера кода возврата
        if (denied(out)) return STATUS_PERMISSION_DENIED
        if (missingTool(out)) return STATUS_UNAVAILABLE
        // §3.4: транспорт вернул свой код — даже при целых маркерах полноту не подтверждаем.
        if (transportRc != 0) return STATUS_UNKNOWN
        if (rc != 0) return STATUS_UNKNOWN
        if (!hasBody) return STATUS_EMPTY
        return STATUS_OK
    }

    /**
     * Части для лога. Длинная строка НЕ обрезается молча: она делится на куски и помечается
     * как продолжение — иначе в логе получился бы «обрезанный, но выглядящий целым» вывод.
     */
    fun chunks(text: String, limit: Int = CHUNK_CHARS): List<String> {
        if (text.isBlank()) return emptyList()
        val out = ArrayList<String>()
        val sb = StringBuilder()
        fun flush() {
            if (sb.isNotEmpty()) { out += sb.toString(); sb.setLength(0) }
        }
        for (line in text.split('\n')) {
            if (line.length > limit) {
                var i = 0
                while (i < line.length) {
                    val piece = line.substring(i, minOf(line.length, i + limit))
                    sb.append("⟪продолжение строки, символы $i..${i + piece.length - 1}⟫ ").append(piece)
                    flush()
                    i += piece.length
                }
                continue
            }
            if (sb.isNotEmpty() && sb.length + line.length + 1 > limit) flush()
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(line)
        }
        flush()
        return out
    }

    /** Итог ping по тексту: сам факт ответа ICMP, а не «интернет есть/нет». */
    internal fun pingVerdict(out: String): String = when {
        Regex("(\\d+) (packets )?received").find(out)?.groupValues?.get(1)?.toIntOrNull()?.let { it > 0 } == true ->
            "echo_received"
        out.contains("100% packet loss") || out.contains("100% loss") -> "no_reply"
        else -> "not_checked"
    }

    /** Нейтральная сводка: только факты из разделов, без выводов о причине. */
    internal fun summary(sections: List<Section>, extra: String): String {
        fun st(n: String) = sections.firstOrNull { it.name == n }?.status ?: "not_run"
        fun vd(n: String) = sections.firstOrNull { it.name == n }?.verdict ?: "n/a"
        return "sections=${sections.size} wlan0=${st("wlan0_addr")} ip_rules=${st("ip_rules")} " +
            "routes=${st("routes")} dns_props=${st("dns_props")} " +
            "ping_ip=${st("ping_ip")}/${vd("ping_ip")} ping_name=${st("ping_name")}/${vd("ping_name")} " +
            "android=${st("android_connectivity")} $extra " +
            "note=\"это факты, а не диагноз: ping без ответа и пустые свойства DNS не доказывают " +
            "отсутствия интернета; смена подключения не выполнялась\""
    }

    // ─────────────────────────── Android API (без shell) ───────────────────────────

    /**
     * Данные Android, которые нельзя получить shell-командой. Только чтение; SSID/BSSID/MAC
     * не читаются вовсе. `null` — значение недоступно (не «нет»).
     */
    interface NetInfo {
        fun wifiEnabled(): Boolean?
        /** Транспорты активной сети (`wifi`, `cellular`, …); `null` — активной сети не видно. */
        fun activeTransports(): Set<String>?
        /** `VALIDATED` у активной сети; `null` — узнать нельзя. */
        fun validated(): Boolean?
        /** Признак captive portal; `null` — узнать нельзя. */
        fun captivePortal(): Boolean?
        /** DNS активной сети (только IP), `null` — недоступно. */
        fun dnsServers(): List<String>?
    }

    // ─────────────────────────── сбор ───────────────────────────

    /**
     * Собрать и записать всё в датированный лог.
     *
     * @param channelReady готовность существующего канала на момент сбора; `false` ⇒ разделы,
     *   которым нужен shell, получают честный `channel_not_ready`, а не «пусто».
     * @param netInfo Android-часть (может быть `null` в тестах — тогда `unavailable`).
     */
    internal fun collect(
        shell: JitRef.JitShell,
        channelReady: Boolean,
        netInfo: NetInfo?,
        sink: LogBudget.Sink,
        source: String,
        appVersion: String = "unknown",
        /**
         * §3.1: «были ли строки этого блока усечены бюджетом лога». Нужен, чтобы сводка `END`
         * отличала прочитанное от реально записанного. `null` — проверить нельзя (честно пишем
         * `written=unknown`, а не `full`).
         */
        truncatedProbe: (() -> Boolean)? = null
    ): List<Section> {
        val t0 = System.currentTimeMillis()
        // §5.1 iter41: каждая строка блока идёт через приёмник, который возвращает ФАКТ записи
        // (WRITTEN/DROPPED/FAILED). «Сборщик сформировал строку» ≠ «строка в датированном файле».
        val tally = LogBudget.WriteTally(sink)
        val log: (String) -> Unit = { line -> tally.emit(line) }
        log(
            "$BEGIN ts=$t0 source=$source mode=read_only transport=jit_shell " +
                "channel_ready=$channelReady app_version=$appVersion " +
                "note=\"в логе будут IP-адреса, маршруты и адреса DNS ГУ — это чувствительные " +
                "данные владельца; SSID/BSSID/MAC удаляются редактором известных форматов " +
                "(redacted=yes/no в разделе), но ПОЛНЫЙ общий лог приложения может содержать " +
                "другие сведения; сеть НЕ изменяется, лог никуда не отправляется\""
        )
        val sections = ArrayList<Section>(7)

        // 1–6. Shell-части.
        for (c in commands()) {
            if (!channelReady) {
                log(
                    "$SECTION name=${c.name} status=$STATUS_CHANNEL_NOT_READY rc=-1 command=skipped " +
                        "note=\"канал приложения ещё не готов: это НЕ отсутствие root и НЕ " +
                        "результат проверки\""
                )
                sections += Section(c.name, STATUS_CHANNEL_NOT_READY, -1, 0, 0)
                continue
            }
            val cmd = wrap(c.name, c.body)
            val (trcRaw, outRaw) = runCatching { shell.exec(cmd, c.timeoutMs) }
                .getOrElse { t -> Pair(1, "ERR_EXCEPTION:${t.javaClass.simpleName}") }
            // §3.2: любой путь (включая ошибки транспорта) идёт в лог только через redact,
            // а флаг считается по ИСХОДНОМУ выводу (иначе после успешной замены было бы "no").
            val redactedPair = redactWithFlag(outRaw)
            val out = redactedPair.first
            val wasRedacted = redactedPair.second
            val trc = trcRaw
            val rc = rcOf(out, c.name)
            val content = body(out)
            val status = statusOf(trc, out, rc, content.isNotBlank())
            val verdict = if (c.ping) pingVerdict(content) else null
            val parts = chunks(content)
            log(
                "$SECTION name=${c.name} status=$status transport_rc=$trc rc=${rc ?: -1} " +
                    "lines=${content.lines().count { it.isNotBlank() }} parts=${parts.size} " +
                    "redacted=${if (wasRedacted || needsRedaction(outRaw)) "yes" else "no"} " +
                    "command=\"${redact(c.body)}\"" +
                    (if (verdict != null) " verdict=$verdict" else "") +
                    (if (c.ping) " note=\"это проверка ICMP до ${
                        if (c.name == "ping_ip") "1.1.1.1" else "google.com (разрешение имени)"
                    }: отсутствие ответа НЕ доказывает отсутствия интернета\"" else "")
            )
            parts.forEachIndexed { i, part ->
                val rp = redactWithFlag(part)
                log(
                    "$DATA name=${c.name} part=${i + 1}/${parts.size}" +
                        (if (rp.second) " redacted=yes" else "") + " ${rp.first}"
                )
            }
            sections += Section(c.name, status, trc, content.lines().count { it.isNotBlank() }, parts.size, verdict)
        }

        // 7. Android API: активная сеть/транспорт/Wi-Fi/validated/DNS активной сети.
        run {
            val wifi = runCatching { netInfo?.wifiEnabled() }.getOrNull()
            val transports = runCatching { netInfo?.activeTransports() }.getOrNull()
            val validated = runCatching { netInfo?.validated() }.getOrNull()
            val portal = runCatching { netInfo?.captivePortal() }.getOrNull()
            val dns = runCatching { netInfo?.dnsServers() }.getOrNull()
            val available = netInfo != null && (wifi != null || transports != null ||
                validated != null || portal != null || dns != null)
            val status = when {
                netInfo == null -> STATUS_UNAVAILABLE
                !available -> STATUS_UNAVAILABLE
                else -> STATUS_OK
            }
            val dnsText = when {
                dns == null -> "unavailable"
                dns.isEmpty() -> "empty"
                else -> dns.joinToString(",")
            }
            val lines = mutableListOf<String>()
            lines += "wifi_enabled=${wifi?.toString() ?: "unknown"}"
            lines += "active_transports=${transports?.sorted()?.joinToString("+") ?: "none_or_unknown"}"
            lines += "validated=${validated?.toString() ?: "unknown"}"
            lines += "captive_portal=${portal?.toString() ?: "unknown"}"
            lines += "active_network_dns=$dnsText"
            val parts = chunks(lines.joinToString("\n"))
            log(
                "$SECTION name=android_connectivity status=$status rc=-1 " +
                    "api=\"ConnectivityManager/WifiManager (только чтение)\" lines=${lines.size} " +
                    "parts=${parts.size} note=\"validated≠проверка конкретного сайта; " +
                    "SSID/BSSID/MAC не читаются\""
            )
            parts.forEachIndexed { i, part ->
                log("$DATA name=android_connectivity part=${i + 1}/${parts.size} $part")
            }
            sections += Section("android_connectivity", status, -1, lines.size, parts.size)
        }

        // §5.1 iter41: `written=full` разрешено ТОЛЬКО когда каждая строка блока подтверждена в
        // ОДНОМ датированном файле и бюджет этого блока не объявлял усечения. Иначе — честные
        // `truncated` / `partial` (в т.ч. части в разных файлах после фоллбэка) / `unknown`.
        val truncated = runCatching { truncatedProbe?.invoke() }.getOrNull()
        val extra = "elapsed_ms=${System.currentTimeMillis() - t0} network_changes=0 " +
            tally.fields(truncated)
        tally.emit("$END ${summary(sections, extra)}", isEnd = true)
        if (tally.endOutcome != LogBudget.WriteOutcome.WRITTEN) {
            // Самоподтверждающегося ложного заявления быть не должно: если даже `END` не записан,
            // факт уходит в независимый канал, а не «исчезает».
            sink.independent(
                "INTERNET_DIAG_END_NOT_CONFIRMED reason=${tally.endOutcome?.name?.lowercase() ?: "unknown"} " +
                    "sink=«${tally.sinkList()}» lines=${tally.written}/${tally.offered} " +
                    "skipped_by_budget=${tally.dropped} write_failed=${tally.failed} " +
                    "(строки этого блока могли остаться только в logcat/фрагментарно; " +
                    "полноту лога не подтверждаем)"
            )
        }
        return sections
    }

    /** Честная запись случая «канал не готов» без единой команды. */
    internal fun collectChannelNotReady(sink: LogBudget.Sink, source: String, detail: String): List<Section> {
        val t0 = System.currentTimeMillis()
        val tally = LogBudget.WriteTally(sink)
        val log: (String) -> Unit = { line -> tally.emit(line) }
        log(
            "$BEGIN ts=$t0 source=$source mode=read_only channel_ready=false reason=$detail " +
                "note=\"канал приложения не готов: сбор не выполнялся, root_unknown, а не " +
                "'нет root' и не 'нет интернета'\""
        )
        val sections = commands().map { Section(it.name, STATUS_CHANNEL_NOT_READY, -1, 0, 0) } +
            Section("android_connectivity", STATUS_CHANNEL_NOT_READY, -1, 0, 0)
        tally.emit(
            "$END " + summary(
                sections,
                "elapsed_ms=${System.currentTimeMillis() - t0} network_changes=0 " +
                    tally.fields(null)
            ),
            isEnd = true
        )
        return sections
    }
}
