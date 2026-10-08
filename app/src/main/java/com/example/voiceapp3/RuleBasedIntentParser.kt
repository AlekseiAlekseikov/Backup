package com.example.voiceapp3

import android.util.Log

/**
 * Классификатор команд EX2.
 *
 * Однозонный климат. Люк, шторка, лючок, противотуманки, двухзона — убраны.
 * «подогрев заднего стекла» — обогрев спиралью, не окна.
 * Несколько команд в одной фразе — splitCommands: «и» / «потом» и второй интент.
 */
object RuleBasedIntentParser {

    private const val TAG = "RuleParser"

    private val NUMBERS: Map<String, Int> = mapOf(
        "ноль" to 0, "один" to 1, "одну" to 1, "одна" to 1, "одно" to 1,
        "два" to 2, "две" to 2, "три" to 3, "четыре" to 4, "пять" to 5,
        "шесть" to 6, "семь" to 7, "восемь" to 8, "девять" to 9, "десять" to 10,
        "одиннадцать" to 11, "двенадцать" to 12, "тринадцать" to 13,
        "четырнадцать" to 14, "пятнадцать" to 15, "шестнадцать" to 16,
        "семнадцать" to 17, "восемнадцать" to 18, "девятнадцать" to 19,
        "двадцать" to 20, "тридцать" to 30, "сорок" to 40, "пятьдесят" to 50,
        "шестьдесят" to 60, "семьдесят" to 70, "восемьдесят" to 80,
        "девяносто" to 90, "сто" to 100,
    )

    private val ORDINALS = listOf(
        "первый", "первая", "первое", "первого", "первую",
        "второй", "вторая", "второе", "второго", "вторую",
        "третий", "третья", "третье", "третьего", "третью",
    )

    /**
     * Порядок важен. Частные правила раньше общих.
     * Обогрев стекла — отдельной проверкой до «стекл» окон.
     */
    private val INTENT_RULES: List<Pair<List<String>, String>> = listOf(
        listOf(
            "подогрев сиден", "обогрев сиден", "подогрев кресл", "погрей сиден",
            "нагрей сиден", "теплое сиден", "подогрев попы", "подогрев задниц",
            "подогрей сиден",
        ) to "seat_heat",
        listOf("подогрев рул", "обогрев рул", "погрей рул", "нагрей рул", "подогрей рул") to "steering_heat",

        listOf(
            "максимальн холод", "макс холод", "максимальный холод",
            "полный холод", "макс конд",
        ) to "ac_control",

        listOf(
            "температур", "градус", "тепле", "холодне", "жарче",
            "прохладне", "потепле", "похолодне", "жарко", "холодно",
            "душно", "мерзну",
        ) to "change_ac_temp",

        listOf(
            "обдув", "вентилятор", "вентиляц", "поддув", "интенсивност",
            "дует", "дуй",
        ) to "change_fan_speed",

        listOf(
            "рециркул", "салонн воздух", "с улиц", "свеж воздух", "наружн воздух",
            "кондиционер", "климат", "кондей",
        ) to "ac_control",

        listOf(
            "окн", "окошк", "стекл", "форточк",
            "батискаф", "окунь", "окун", "акно", "акна",
            "замок окон", "блокировк окон", "детский замок",
        ) to "window_control",

        listOf(
            "багажник", "багажн", "пятую дверь", "пятая дверь",
            "капот", "капота", "капоту",
        ) to "trunk_control",

        listOf(
            "аварийк", "аварийн", "аварию", "авари", "мигалк",
            "аварийную", "аварийка", "аварийку",
        ) to "exterior_light_control",

        listOf(
            "диагност", "сканир", "сканер", "дамп",
            "выгруз свойств", "свойств машин", "свойства машин",
            "инвентаризац",
        ) to "dump_vehicle",

        listOf(
            "режим", "спорт", "эко", "эхо", "еко", "ико", "комфорт",
            "экономич", "эконом", "спортивн",
            "sport", "eco", "comfort",
        ) to "drive_mode",

        listOf(
            "приложен", "навигац", "карты", "браузер", "настройк",
            "музык", "радио", "плеер",
        ) to "open_app",
        listOf("скажи", "произнеси", "прочитай", "озвуч") to "play_text",
        listOf("сигнал", "гудок", "бибикн", "посигнал", "звук") to "play_sound",
    )

    private val SET_STEMS = listOf(
        "откр", "включ", "запус", "зажг", "актив", "разблок", "подключ",
        "сдела", "устан", "поставь", "выстав", "задай", "заведи",
    )
    private val UNSET_STEMS = listOf(
        "закр", "выключ", "отключ", "погас", "останов", "заблок", "убер",
        "отмен", "прекрат", "заглуш",
    )
    private val INCREASE_STEMS = listOf(
        "прибав", "увелич", "повыс", "добав", "усил", "тепле", "жарче",
        "громче", "ярче", "быстре", "сильне",
        "потепле", "пожарче", "погромче", "поярче", "побыстре", "посильне",
        "побольше", "повыше", "погрей", "нагрей",
    )
    private val DECREASE_STEMS = listOf(
        "убав", "уменьш", "пониз", "сбав", "ослаб", "холодне", "прохладне",
        "тише", "темне", "медленне", "слабе",
        "похолодне", "попрохладне", "потише", "потемне", "помедленне",
        "послабе", "поменьше", "пониже", "остуди",
    )

    private val WINDOW_OPEN_STEMS = listOf("откр", "приоткр", "опуст", "приопуст", "приспуст", "спуст")
    private val WINDOW_CLOSE_STEMS = listOf("закр", "призакр", "подним", "приподн", "задвин")

    private val LEFT_STEMS = listOf("лев", "водител")
    private val RIGHT_STEMS = listOf("прав", "пассажир")
    private val BOTH_STEMS = listOf("все", "всех", "обе", "оба", "обо")
    private val FRONT_STEMS = listOf("передн", "перед", "спереди", "первый ряд")
    private val REAR_STEMS = listOf("задн", "зад", "сзади", "второй ряд", "детск")

    private val BIT_STEMS = listOf(
        "чуть", "чут", "немного", "немого", "слегка", "чуток", "немножко",
        "капельк", "приоткро", "призакро",
    )

    private val VERB_STEMS = listOf(
        "включ", "выключ", "отключ", "откр", "закр", "постав", "сдела",
        "установ", "прибав", "убав", "увелич", "уменьш", "погрей", "нагрей",
        "подогре", "обогре", "режим", "поставь", "опуст", "подним",
    )

    private val CONJ_SPLIT = Regex(
        """(?<=\S)\s+(?:и|затем|а затем|потом|а еще|а потом|после этого|после того|далее|дальше|также|так же|одновременно)\s+(?=\S)"""
    )

    private val VOSK_WINDOW_ALIASES = listOf(
        "батискаф", "батискафа", "батискафу",
        "окунь", "окуня", "окуню", "окуне",
        "акно", "акна", "акон",
    )

    /** Vosk часто пишет «эхо» вместо «эко». */
    private val VOSK_ECO_ALIASES = listOf("эхо", "еко", "ико", "экво", "echo", "ека")

    /** Слова, которые Vosk пишет вместо «рекуперация» (в модели её почти нет). */
    private val VOSK_RECUP_ALIASES = listOf(
        "репутация", "репутаци", "регуляция", "регуляци",
        "рекоперация", "регуперация", "рекреация",
        "рекупераци", "рекупперация", "рекуперацыя",
        "регенерация", "регенарация", "регенерации",
        "рекуператор", "рекуператора", "рекуперации",
        "рекуперацией", "рекуперацию", "рикуперация",
        "рекуперацие", "рекуперция", "рекуператья",
        "лекуперация", "лекупеляция", "лекуперасия", "лекупелесия",
        "лекуперашия", "лекуператия", "лекуперачия", "лекуберация",
        "ликуперация", "лыкуперация", "лэкуперация", "лэкупеляция",
        "лякуперация", "лакуперация", "лепурация", "лекторация",
        "екуперация", "екупеляция", "екуперасия", "экуперация",
        "гекуперация", "гекупеляция", "векуперация", "йекуперация",
        "рекупеляция", "рекупелесия", "рекупеация", "лекуперацией",
        "лекуперацию", "лекуперации", "лекуперацыя",
        "рекуперасия", "рекуперашия", "рекуператия", "рекуперачия",
        "рекуперазия", "рекуперасья", "рекуперацца", "рекуперація",
        "рекуперациа", "рекуперацыа", "рекуберасия", "рехуперасия",
        "рэкуперасия", "рыкуперасия", "рекупераща", "рекуперацья",
        "рекуперацию", "рекупераціею", "рекуперацію", "рекуперації",
        "рекупера", "рекупер", "рекуперац", "рекуперацин", "рекуперацим",
        "рекупрация", "рекуерация", "рекперация", "куперация", "реперация",
        "ракуперация", "текуперация", "пекуперация", "реккуперация",
        "рекупэрация", "рекупырация", "рекупирация", "рэкуперация",
        "рыкуперация", "рекуберация", "рехуперация", "рекупарация",
        "прекуперация", "прикуперация", "рекуперациею", "рекуперацыю",
        "рекреации", "регуляции", "репутации", "репутацию",
        "регуляцию", "рекреацию", "регенерацию", "репетиция",
        "препарация", "сепарация", "рефусация", "рефузация",
        "групперация", "рекусация", "рекузация", "рекупэрция",
        "секуперация", "шекуперация", "чекуперация", "декуперация",
    )

    private val RECUP_FUZZY_TARGETS = listOf(
        "рекуперация", "рекуперации", "рекуперацией", "рекуперацию", "рекуператор",
    )

    fun predict(raw: String): PredictionResult {
        val normalized = normalizeInput(raw)
        val tokens = normalized.split(' ').filter { it.isNotBlank() }

        val intent = detectIntent(normalized, tokens) ?: "unknown"
        val action = detectAction(tokens, intent, normalized)
        val value = detectValue(tokens)
        val unit = detectUnit(normalized, intent, value)
        val direction = detectDirection(tokens)
        val position = detectPosition(tokens)

        val entities = mutableMapOf<String, Any>()
        action?.let { entities["action"] = it }
        value?.let { entities["value"] = it }
        unit?.let { entities["unit"] = it }
        direction?.let { entities["direction"] = it }
        position?.let { entities["position"] = it }
        if (isBit(normalized, tokens)) entities["amount"] = "bit"
        if (intent == "drive_mode") {
            detectDriveMode(normalized)?.let { entities["mode"] = it }
        }
        if (intent == "recup_mode") {
            detectRecupMode(normalized)?.let { entities["mode"] = it }
        }
        if (intent == "seat_heat" || intent == "steering_heat") {
            val level = detectSeatLevel(normalized, tokens, value)
            if (level != null) entities["value"] = level
            else entities.remove("value")
        }

        val confidence = when {
            intent == "drive_mode" && entities["mode"] != null -> 0.95f
            intent == "recup_mode" && entities["mode"] != null -> 0.95f
            intent != "unknown" && action != null -> 0.95f
            intent != "unknown" -> 0.60f
            else -> 0.15f
        }

        Log.i(TAG, "'$raw' -> intent=$intent conf=$confidence entities=$entities")

        return PredictionResult(
            text = raw,
            normalizedText = normalized,
            intent = intent,
            confidence = confidence,
            entities = entities,
        )
    }

    /**
     * Несколько команд в одной фразе.
     * «включи кондиционер и подогрев сиденья»
     * «поставь 22 градуса и обдув на 4»
     * «закрой окна включи аварийку» — без союза, по второму интенту.
     *
     * «открой левое и правое окно» не режется: кусок без интента склеивается.
     */
    fun splitCommands(raw: String): List<String> {
        val text = normalizeInput(raw)
        if (text.isBlank()) return emptyList()

        val byConj = text.split(CONJ_SPLIT).map { it.trim() }.filter { it.isNotEmpty() }
        val merged = mergeUnknownParts(byConj)
        if (merged.size > 1) {
            Log.i(TAG, "split conj: $merged")
            return merged.take(4)
        }

        val byIntent = splitByIntentHits(text)
        if (byIntent.size > 1) {
            Log.i(TAG, "split intent: $byIntent")
            return byIntent.take(4)
        }
        return listOf(raw.trim()).filter { it.isNotEmpty() }
    }

    private fun normalizeInput(raw: String): String {
        var normalized = raw.lowercase().trim()
            .replace('ё', 'е')
            .replace(Regex("\\s+"), " ")
        for (alias in VOSK_WINDOW_ALIASES) {
            if (normalized.contains(alias)) {
                normalized = normalized.replace(alias, "окно")
            }
        }
        return normalized.split(' ').filter { it.isNotBlank() }
            .joinToString(" ") { rewriteToken(it) }
    }

    private fun partHasIntent(part: String): Boolean {
        val tokens = part.split(' ').filter { it.isNotBlank() }
        return detectIntent(part, tokens) != null
    }

    private fun mergeUnknownParts(parts: List<String>): List<String> {
        if (parts.size <= 1) return parts
        val out = mutableListOf<String>()
        for (part in parts) {
            val known = partHasIntent(part)
            if (out.isEmpty()) {
                out.add(part)
            } else if (!known) {
                out[out.lastIndex] = out.last() + " и " + part
            } else if (!partHasIntent(out.last())) {
                out[out.lastIndex] = out.last() + " и " + part
            } else {
                out.add(part)
            }
        }
        return out
    }

    private fun splitByIntentHits(text: String): List<String> {
        val hits = intentHits(text)
        if (hits.size < 2) return listOf(text)
        val cuts = mutableListOf(0)
        for (i in 1 until hits.size) {
            val (pos, intent) = hits[i]
            val prevIntent = hits[i - 1].second
            if (intent == prevIntent) continue
            var cut = rewindToVerb(text, pos)
            if (cut <= hits[i - 1].first) cut = pos
            if (cut > cuts.last() + 4) cuts.add(cut)
        }
        if (cuts.size < 2) return listOf(text)
        val out = mutableListOf<String>()
        for (i in cuts.indices) {
            val end = if (i + 1 < cuts.size) cuts[i + 1] else text.length
            val piece = text.substring(cuts[i], end).trim()
            if (piece.isNotEmpty() && partHasIntent(piece)) out.add(piece)
        }
        return if (out.size > 1) out else listOf(text)
    }

    private fun rewindToVerb(text: String, pos: Int): Int {
        val before = text.substring(0, pos.coerceIn(0, text.length))
        val words = before.split(' ')
        var consumed = 0
        for (i in words.indices.reversed()) {
            val w = words[i]
            if (w.isBlank()) continue
            if (VERB_STEMS.any { w.startsWith(it) }) {
                val idx = before.length - consumed - w.length
                return idx.coerceAtLeast(0)
            }
            consumed += w.length + 1
            if (consumed > 24) break
        }
        return pos
    }

    private fun intentHits(text: String): List<Pair<Int, String>> {
        val hits = mutableListOf<Pair<Int, String>>()
        fun add(key: String, intent: String) {
            var i = text.indexOf(key)
            while (i >= 0) {
                hits.add(i to intent)
                i = text.indexOf(key, i + key.length)
            }
        }
        if (isGlassHeat(text)) {
            listOf("лобов", "подогре", "обогре", "спираль", "оттай").forEach {
                if (text.contains(it)) add(it, "hvac_defrost")
            }
        }
        if (isQuickClimate(text)) {
            listOf("быстр").forEach { add(it, "ac_control") }
        }
        if (isHazard(text)) add("авари", "exterior_light_control")
        if (isRecup(text)) add("рекуперац", "recup_mode")
        for ((keys, intent) in INTENT_RULES) {
            for (key in keys) {
                if (text.contains(key)) add(key, intent)
            }
        }
        return hits.sortedBy { it.first }
            .distinctBy { it.first }
    }

    private fun detectIntent(text: String, tokens: List<String>): String? {
        if (isGlassHeat(text)) return "hvac_defrost"
        if (isQuickClimate(text)) return "ac_control"
        if (isHazard(text)) return "exterior_light_control"
        if (isRecup(text)) return "recup_mode"

        INTENT_RULES.firstOrNull { (keys, _) -> keys.any { text.contains(it) } }
            ?.second
            ?.let { return it }

        val openOrClose = tokens.anyStartsWith(listOf("откр", "приоткр", "закр", "призакр"))
        val windowWord = text.contains("окн") || text.contains("стекл") || text.contains("форточ")
        if (openOrClose && windowWord) {
            Log.i(TAG, "salvage: '$text' -> window_control")
            return "window_control"
        }
        return null
    }

    /**
     * Спираль, «обдув лобового стекла», оттай.
     * Не направление «обдув на стекло».
     */
    private fun isGlassHeat(text: String): Boolean {
        if (isFanDirectionToGlass(text)) return false
        if (text.contains("оттай")) return true
        if (text.contains("лобов")) return true
        if (text.contains("стекл") && text.contains("макс")) return true
        val heat = text.contains("подогре") || text.contains("обогре") ||
                text.contains("нагрей") || text.contains("обогрей") ||
                text.contains("спираль")
        val glass = text.contains("стекл") || text.contains("зеркал")
        return heat && glass
    }

    /** «Быстрый обогрев» / «Быстрое охлаждение» — пресеты климата, не сиденье и не спираль. */
    private fun isQuickClimate(text: String): Boolean {
        if (!text.contains("быстр")) return false
        if (text.contains("охлажд")) return true
        if (text.contains("сиден") || text.contains("стекл") || text.contains("лобов") ||
            text.contains("задн") || text.contains("рул") || text.contains("зеркал")
        ) return false
        return text.contains("обогрев") || text.contains("нагрев")
    }

    /**
     * Как «на лицо» / «в ноги».
     * «Обдув на стекло» и «обдув на лобовое стекло» — только направление.
     * «Обдув лобового стекла» без «на» сюда не входит.
     */
    private fun isFanDirectionToGlass(text: String): Boolean {
        if (text.contains("подогре") || text.contains("обогре") ||
            text.contains("спираль") || text.contains("оттай") ||
            text.contains("макс")
        ) return false
        if (!text.contains("обдув")) return false
        if (text.contains(" на ") &&
            (text.contains("стекл") || text.contains("лобов") || text.contains("вверх"))
        ) return true
        if (text.contains("лобов")) return false
        return text.contains("стекл") || text.contains("вверх")
    }

    private fun isHazard(text: String): Boolean =
        text.contains("авари") || text.contains("мигалк")

    private fun rewriteToken(token: String): String = when {
        token in VOSK_ECO_ALIASES -> "эко"
        isRecupMishear(token) -> "рекуперация"
        else -> token
    }

    private fun isRecupMishear(token: String): Boolean {
        if (token in VOSK_RECUP_ALIASES) return true
        if (token.contains("рекуперац") || token.contains("рекуператор") ||
            token.contains("лекупера") || token.contains("лекупеля") ||
            token.contains("екупера") || token.contains("гекупера") ||
            token.contains("рекуперас") || token.contains("рекуберац") ||
            token.contains("рекупеля") || token.contains("рекупераш") ||
            token.contains("рекуперач") || token.contains("рекупераз")
        ) return true
        if (token.length < 9) return false
        return RECUP_FUZZY_TARGETS.any { levenshtein(token, it) <= 3 }
    }

    private fun levenshtein(a: String, b: String): Int {
        val n = a.length
        val m = b.length
        if (n == 0) return m
        if (m == 0) return n
        val dp = IntArray(m + 1) { it }
        for (i in 1..n) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..m) {
                val cur = dp[j]
                dp[j] = minOf(
                    dp[j] + 1,
                    dp[j - 1] + 1,
                    prev + if (a[i - 1] == b[j - 1]) 0 else 1,
                )
                prev = cur
            }
        }
        return dp[m]
    }

    private fun isRecup(text: String): Boolean =
        text.contains("рекуперац") || text.contains("рекуп")

    private fun isBit(text: String, tokens: List<String>): Boolean =
        BIT_STEMS.any { text.contains(it) } || tokens.anyStartsWith(BIT_STEMS)

    private fun detectAction(tokens: List<String>, intent: String, text: String): String? {
        if (intent == "window_control" || intent == "trunk_control") {
            if (tokens.anyStartsWith(WINDOW_OPEN_STEMS)) return "set"
            if (tokens.anyStartsWith(WINDOW_CLOSE_STEMS)) return "unset"
        }

        if (isQuickClimate(text)) {
            if (tokens.anyStartsWith(UNSET_STEMS) ||
                text.contains("выключ") || text.contains("отключ")
            ) return "unset"
            if (tokens.anyStartsWith(SET_STEMS) || text.contains("включ")) return "set"
            return null
        }

        if (intent == "seat_heat" || intent == "steering_heat") {
            if (tokens.anyStartsWith(UNSET_STEMS) ||
                text.contains("выключ") || text.contains("отключ")
            ) return "unset"
            if (text.contains("сильнее") || text.contains("прибав") ||
                text.contains("увелич") || text.contains("повыс")
            ) return "increase"
            if (text.contains("слабее") || text.contains("убав") ||
                text.contains("уменьш") || text.contains("пониз")
            ) return "decrease"
            if (tokens.anyStartsWith(SET_STEMS) || text.contains("включ") ||
                text.contains("подогре") || text.contains("обогре") ||
                text.contains("погрей") || text.contains("нагрей")
            ) return "set"
        }

        if (tokens.anyStartsWith(INCREASE_STEMS)) return "increase"
        if (tokens.anyStartsWith(DECREASE_STEMS)) return "decrease"

        if (tokens.anyStartsWith(listOf("подним", "приподн"))) return "increase"
        if (tokens.anyStartsWith(listOf("опуст", "приспуст"))) return "decrease"

        if (tokens.anyStartsWith(SET_STEMS)) return "set"
        if (tokens.anyStartsWith(UNSET_STEMS)) return "unset"

        if (intent == "change_ac_temp") {
            if (text.contains("жарко") || text.contains("душно")) return "decrease"
            if (text.contains("холодно") || text.contains("мерзну")) return "increase"
        }

        return null
    }

    /** Ступень подогрева 1…3. Число больше 3 — не ступень (часто «22» от климата). */
    private fun detectSeatLevel(text: String, tokens: List<String>, value: Int?): Int? {
        when {
            text.contains("сильнее") || text.contains("слабее") -> { /* шаг, не ступень */ }
            text.contains("макс") || text.contains("сильный") || text.contains("сильна") ||
                    text.contains("полн") || text.contains("на всю") -> return 3
            text.contains("средн") || text.contains("норм") -> return 2
            text.contains("слаб") -> return 1
            text.contains("минималь") -> return 1
            text.contains("мин") && !text.contains("минус") && !text.contains("максим") -> return 1
        }
        if (tokens.any { it.startsWith("трет") }) return 3
        if (tokens.any { it.startsWith("втор") }) return 2
        if (text.contains("уровен") && tokens.any { it.startsWith("перв") || it == "один" }) return 1
        if (value != null && value in 1..3) return value
        return null
    }

    private fun detectValue(tokens: List<String>): Int? {
        tokens.firstNotNullOfOrNull { Regex("\\d+").find(it)?.value?.toIntOrNull() }
            ?.let { return it }

        val meaningful = tokens.filterNot { it in ORDINALS }
        val nums = meaningful.mapNotNull { NUMBERS[it] }
        return when {
            nums.isEmpty() -> null
            nums.size == 1 -> nums[0]
            nums[0] >= 20 && nums[1] < 10 -> nums[0] + nums[1]
            else -> nums[0]
        }
    }

    private fun detectUnit(text: String, intent: String, value: Int?): String? = when {
        text.contains("процент") -> "percent"
        text.contains("градус") -> "degree"
        value != null && intent == "change_ac_temp" -> "degree"
        value != null && intent == "change_fan_speed" -> "number"
        value != null && (intent == "seat_heat" || intent == "steering_heat") -> "level"
        else -> null
    }

    private fun detectRecupMode(text: String): String? = when {
        text.contains("сильн") || text.contains("максимальн") ||
                text.contains("strong") -> "strong"
        text.contains("слаб") || text.contains("минимальн") ||
                text.contains("weak") -> "weak"
        text.contains("средн") || text.contains("нормальн") ||
                text.contains("medium") -> "medium"
        else -> null
    }

    private fun detectDriveMode(text: String): String? = when {
        text.contains("спорт") || text.contains("sport") -> "sport"
        text.contains("эко") || text.contains("эхо") ||
                text.contains("еко") || text.contains("ико") ||
                text.contains("эконом") || text.contains("экономич") ||
                text.contains("eco") -> "eco"
        text.contains("комфорт") || text.contains("comfort") ||
                text.contains("обычн") || text.contains("стандарт") ||
                text.contains("нормальн") -> "comfort"
        else -> null
    }

    private fun detectDirection(tokens: List<String>): String? = when {
        tokens.anyStartsWith(BOTH_STEMS) -> "both"
        tokens.anyStartsWith(LEFT_STEMS) && tokens.anyStartsWith(RIGHT_STEMS) -> "both"
        tokens.anyStartsWith(LEFT_STEMS) -> "left"
        tokens.anyStartsWith(RIGHT_STEMS) -> "right"
        else -> null
    }

    private fun detectPosition(tokens: List<String>): String? = when {
        tokens.anyStartsWith(BOTH_STEMS) -> "both"
        tokens.anyStartsWith(FRONT_STEMS) && tokens.anyStartsWith(REAR_STEMS) -> "both"
        tokens.anyStartsWith(FRONT_STEMS) -> "front"
        tokens.anyStartsWith(REAR_STEMS) -> "rear"
        else -> null
    }

    private fun List<String>.anyStartsWith(stems: List<String>): Boolean =
        any { token -> stems.any { token.startsWith(it) } }
}