package com.example.voiceapp3

import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import kotlin.math.max
import kotlin.math.min

/**
 * Активация ассистента фразой «привет джили». Версия 12.
 *
 * v8 вернул грамматику (без неё «джили» OOV и вызов глухой), но положил
 * в неё одиночные «привет» / «дели» / «гели» и срабатывал на partial.
 *
 * v9: только финал, без «дели»/«гели», склейка exact «привет» + strong.
 * v10: отсечка коротких финалов по меткам/часам — в салоне не хватило:
 * грамматика по-прежнему подгоняет радио и разговор под целую фразу.
 *
 * v11 — ложные вызовы:
 *  — нет склейки двух финалов;
 *  — в грамматике нет одиночных «джили»/«джиле»;
 *  — вызов только если в одном финале два слова и был partial «привет»;
 *  — фраза короче 650 мс — обрывок.
 *
 * v12 — чуть слабее вызов (в машине v11 глухой):
 *  — conf 0.50, фраза 480 мс, слово 110 мс;
 *  — partial «привет» не обязателен, если метки/часы похожи на живую фразу;
 *  — partial ловит «привет» нечётко (опечатка в 1 букву);
 *  — склейки двух финалов по-прежнему нет, «дели»/«гели»/«или» нет.
 */
class WakeWordDetector(
    private val model: Model,
    /** true — печатать всё услышанное. Для настройки фразы. */
    private val debug: Boolean = true,
    private val onWake: () -> Unit,
) : MicPump.Consumer {

    companion object {
        private const val TAG = "WakeWord"

        private const val FIRST_WORD = "привет"
        private const val FIRST_WORD_MAX_DIST = 1

        /**
         * Во что модель превращает «джили» в одной реплике с «привет».
         * Короткие общие «дели» / «гели» / «или» не берём.
         */
        private val SECOND_WORD_VARIANTS = listOf(
            "джили", "жили", "джиле", "жиле", "джилли", "джилий",
            "джилы", "джиля",
        )

        private fun maxDistFor(word: String): Int = when {
            word.length <= 4 -> 0
            word.length <= 6 -> 1
            else -> 2
        }

        private val EXACT_PHRASES = listOf(
            "привет джили",
            "привет жили",
            "привет джи ли",
            "привет джиле",
            "привет жиле",
            "привет джилли",
            "привет джилы",
            "привет джиля",
        )

        /**
         * Только целые фразы + «привет» + [unk].
         * Одиночное второе слово в грамматике давало ложные финалы из шума.
         */
        private val GRAMMAR_PHRASES = EXACT_PHRASES + listOf("привет")

        private const val REARM_DELAY_MS = 1200L
        /** После «Готово» колонки ещё звучат. Дольше 350 мс — глухой повторный вызов. */
        private const val ECHO_GUARD_MS = 350L
        private const val MAX_WORDS_FOR_WAKE = 3

        /**
         * Нижняя граница conf слова из VOSK (setWords).
         * -1 в разборе = conf нет, тогда смотрим только текст и длительность.
         */
        private const val MIN_WORD_CONF = 0.42f

        /** Полная фраза «привет джили» живым голосом — обычно 0.45–1.3 с. */
        private const val MIN_PHRASE_MS = 400L

        /** Одно слово по меткам VOSK. Обрывок садится в 50–100 мс. */
        private const val MIN_WORD_MS = 90L
    }

    private var recognizer: Recognizer? = null
    private var lastTriggerAt = 0L
    private var lastLogged = ""
    private val heardStats = HashMap<String, Int>()

    /** Первый non-empty partial текущей реплики. 0 — partial не было. */
    @Volatile private var utteranceAt = 0L

    /** В этой реплике уже был partial со словом «привет». */
    @Volatile private var heardHelloPartial = false

    @Volatile private var ignoreUntil = 0L

    @Synchronized
    fun prepare(): Boolean = try {
        recognizer = createRecognizer()
        Log.i(TAG, "Слушаю: '$FIRST_WORD' + ${SECOND_WORD_VARIANTS.take(4)}...")
        Log.i(TAG, ">>> СКАЖИТЕ: привет джили <<<")
        true
    } catch (t: Throwable) {
        Log.e(TAG, "Не удалось создать распознаватель", t)
        false
    }

    @Synchronized
    fun release() {
        runCatching { recognizer?.close() }
        recognizer = null
        if (debug) dumpStats()
        Log.i(TAG, "Остановлен")
    }

    override fun onAttached() {
        runCatching { recognizer?.reset() }
        utteranceAt = 0L
        heardHelloPartial = false
        lastLogged = ""
        ignoreUntil = System.currentTimeMillis() + ECHO_GUARD_MS
        Log.i(TAG, "Снова слушаю, эхо ${ECHO_GUARD_MS}мс")
    }

    override fun onDetached() {
        Log.i(TAG, "Пауза (микрофон при этом не закрывается)")
    }

    override fun onAudio(data: ByteArray, length: Int) {
        val recog = recognizer ?: return

        val isFinal = try {
            recog.acceptWaveForm(data, length)
        } catch (t: Throwable) {
            Log.e(TAG, "acceptWaveForm", t)
            return
        }

        val json = try {
            if (isFinal) recog.result else recog.partialResult
        } catch (_: Throwable) {
            return
        }

        val parsed = parseVosk(json, isFinal)
        val text = parsed.text
        val now = System.currentTimeMillis()

        if (!isFinal) {
            if (text.isNotBlank()) {
                if (utteranceAt == 0L) utteranceAt = now
                if (hasHello(text)) heardHelloPartial = true
                if (debug && text != lastLogged) {
                    Log.d(TAG, "слышу: '$text' final=false")
                    lastLogged = text
                    heardStats[text] = (heardStats[text] ?: 0) + 1
                }
            }
            return
        }

        val clockMs = if (utteranceAt == 0L) 0L else now - utteranceAt
        val hadHello = heardHelloPartial
        utteranceAt = 0L
        heardHelloPartial = false

        if (text.isBlank()) return

        if (debug) {
            Log.d(
                TAG,
                "слышу: '$text' final=true conf=${parsed.minConf} " +
                    "span=${parsed.spanSec} word=${parsed.minWordSec} " +
                    "clock=${clockMs}мс helloPartial=$hadHello"
            )
            lastLogged = text
            heardStats[text] = (heardStats[text] ?: 0) + 1
        }

        if (!hadHello) {
            val looksLive = parsed.spanSec >= 0.38f || clockMs >= 320L
            if (!looksLive) {
                if (debug) Log.d(TAG, "отбой нет partial «привет»: '$text'")
                return
            }
        }

        if (parsed.minConf >= 0f && parsed.minConf < MIN_WORD_CONF) {
            if (debug) Log.d(TAG, "отбой по conf=${parsed.minConf}: '$text'")
            return
        }

        val wordN = text.split(" ").filter { it.isNotEmpty() }.size
        if (wordN < 2) {
            if (debug) Log.d(TAG, "отбой одно слово, склейки нет: '$text'")
            return
        }
        if (isShortSnippet(parsed, clockMs)) {
            if (debug) {
                Log.d(
                    TAG,
                    "отбой короткий обрывок clock=${clockMs}мс " +
                        "span=${parsed.spanSec} words=$wordN: '$text'"
                )
            }
            return
        }

        if (!matches(text, isFinal = true)) return

        if (now - lastTriggerAt < REARM_DELAY_MS) return
        lastTriggerAt = now

        Log.i(
            TAG,
            "СРАБОТАЛО на: '$text' conf=${parsed.minConf} " +
                "span=${parsed.spanSec} clock=${clockMs}мс"
        )
        runCatching { recog.reset() }
        onWake()
    }

    /**
     * Грамматика нужна: «джили» нет в словаре, свободный режим это слово
     * не выдаст. Если VOSK отвергнет грамматику — свободное распознавание.
     */
    private fun createRecognizer(): Recognizer {
        val grammar = buildString {
            append("[")
            GRAMMAR_PHRASES.forEach { append("\"").append(it).append("\", ") }
            append("\"[unk]\"]")
        }
        val rec = try {
            Recognizer(model, MicPump.SAMPLE_RATE.toFloat(), grammar).also {
                Log.i(TAG, "Режим: ГРАММАТИКА $grammar")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Грамматика не собралась, свободное распознавание", t)
            Recognizer(model, MicPump.SAMPLE_RATE.toFloat()).also {
                Log.i(TAG, "Режим: СВОБОДНОЕ РАСПОЗНАВАНИЕ")
            }
        }
        runCatching {
            rec.setWords(true)
            Log.i(TAG, "setWords=true (порог conf=$MIN_WORD_CONF, фраза≥${MIN_PHRASE_MS}мс)")
        }.onFailure {
            Log.w(TAG, "setWords недоступен, порог conf не работает", it)
        }
        return rec
    }

    private data class VoskText(
        val text: String,
        val minConf: Float,
        /** last.end − first.start по словам, −1 если меток нет. */
        val spanSec: Float,
        /** самое короткое слово по меткам, −1 если меток нет. */
        val minWordSec: Float,
    )

    private fun parseVosk(raw: String, isFinal: Boolean): VoskText {
        return try {
            val obj = JSONObject(raw)
            val text = normalize(
                if (isFinal) obj.optString("text", "")
                else obj.optString("partial", "")
            )
            var minConf = -1f
            var start = Float.POSITIVE_INFINITY
            var end = 0f
            var minWord = Float.POSITIVE_INFINITY
            val arr = obj.optJSONArray("result")
            if (arr != null && arr.length() > 0) {
                minConf = 1f
                for (i in 0 until arr.length()) {
                    val w = arr.optJSONObject(i) ?: continue
                    minConf = min(minConf, w.optDouble("conf", 1.0).toFloat())
                    val s = w.optDouble("start", -1.0).toFloat()
                    val e = w.optDouble("end", -1.0).toFloat()
                    if (s >= 0f) start = min(start, s)
                    if (e >= 0f) end = max(end, e)
                    if (s >= 0f && e > s) minWord = min(minWord, e - s)
                }
            }
            val span = if (start.isFinite() && end > start) end - start else -1f
            val word = if (minWord.isFinite() && minWord > 0f) minWord else -1f
            VoskText(text, minConf, span, word)
        } catch (_: Throwable) {
            VoskText("", -1f, -1f, -1f)
        }
    }

    /**
     * Обрывок: финал короче живой фразы.
     * Метки VOSK главные. Нет меток — часы с первого partial.
     * Финал без partial (clock=0, span нет) всегда обрывок.
     */
    private fun isShortSnippet(parsed: VoskText, clockMs: Long): Boolean {
        if (parsed.spanSec >= 0f) {
            if (parsed.spanSec * 1000f < MIN_PHRASE_MS) return true
            if (parsed.minWordSec >= 0f && parsed.minWordSec * 1000f < MIN_WORD_MS) return true
            return false
        }
        return clockMs < MIN_PHRASE_MS
    }

    private fun normalize(s: String) = s.lowercase()
        .replace('ё', 'е')
        .replace("[unk]", " ")
        .replace(Regex("[^а-яa-z ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun hasHello(text: String): Boolean {
        if (text == FIRST_WORD || text.startsWith("$FIRST_WORD ")) return true
        return text.split(" ").any { isFirstWord(it) }
    }

    @JvmOverloads
    fun matches(raw: String, isFinal: Boolean = true): Boolean {
        if (!isFinal) return false

        val heard = normalize(raw)
        if (heard.isEmpty()) return false

        if (heard in EXACT_PHRASES) return true

        val words = heard.split(" ").filter { it.isNotEmpty() }
        if (words.size < 2 || words.size > MAX_WORDS_FOR_WAKE) return false

        for (i in words.indices) {
            if (!isFirstWord(words[i])) continue
            if (i + 1 < words.size && isSecondWord(words[i + 1])) return true
            if (i + 2 < words.size && isSecondWord(words[i + 1] + words[i + 2])) return true
        }
        return false
    }

    private fun isFirstWord(w: String): Boolean {
        if (w.length !in 5..8) return false
        return levenshtein(w, FIRST_WORD) <= FIRST_WORD_MAX_DIST
    }

    private fun isSecondWord(w: String): Boolean {
        if (w.length < 4) return false
        return SECOND_WORD_VARIANTS.any { levenshtein(w, it) <= maxDistFor(it) }
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)

        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    fun dumpStats() {
        if (heardStats.isEmpty()) return
        Log.i(TAG, "=== Что услышала модель ===")
        heardStats.entries.sortedByDescending { it.value }.take(20).forEach { (text, n) ->
            Log.i(TAG, "  ${n}x  '$text'")
        }
    }
}
