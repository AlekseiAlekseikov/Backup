package com.example.voiceapp3

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Распознавание команды после слова пробуждения.
 *
 * setMaxAlternatives(5) на vosk-android 0.3.47 в нативе падает SIGSEGV
 * на первом куске речи — процесс умирает, в логе только PROCESS ENDED,
 * без Java-стека. Поэтому альтернатив нет: свободный словарь, JSON
 * разбирается в try/catch, нативе не даём уронить поток насоса.
 */
class CommandRecognizer(
    private val model: Model,
    private val onPartial: (String) -> Unit,
    private val onResult: (String) -> Unit,
) : MicPump.Consumer {

    companion object {
        private const val TAG = "CommandRecognizer"
        /** Пауза после последней речи. 1800 держало микрофон слишком долго. */
        private const val SILENCE_END_MS = 1100L
        private const val MAX_SESSION_MS = 10000L
        /** Коротко: длиннее — глотаем команду, которую говорят сразу после «Привет». */
        private const val ECHO_GUARD_MS = 350L
    }

    private val main = Handler(Looper.getMainLooper())

    private var recognizer: Recognizer? = null
    @Volatile private var active = false
    private var startedAt = 0L
    private var lastSpeechAt = 0L
    private var ignoreUntil = 0L
    private var lastPartial = ""
    private var finished = false

    val isActive: Boolean get() = active

    @Synchronized
    fun prepare(): Boolean = try {
        recognizer = Recognizer(model, MicPump.SAMPLE_RATE.toFloat())
        Log.i(TAG, "Распознаватель команд: свободный словарь")
        true
    } catch (t: Throwable) {
        Log.e(TAG, "Не удалось создать распознаватель команд", t)
        false
    }

    @Synchronized
    fun release() {
        active = false
        runCatching { recognizer?.close() }
        recognizer = null
    }

    override fun onAttached() {
        runCatching { recognizer?.reset() }
        val now = System.currentTimeMillis()
        startedAt = now
        lastSpeechAt = now
        ignoreUntil = now + ECHO_GUARD_MS
        lastPartial = ""
        finished = false
        active = true
        Log.i(TAG, "Сессия команды начата, эхо ${ECHO_GUARD_MS}мс")
    }

    override fun onDetached() {
        active = false
        Log.i(TAG, "Сессия команды закрыта")
    }

    override fun onAudio(data: ByteArray, length: Int) {
        if (!active || finished) return
        if (System.currentTimeMillis() < ignoreUntil) return
        val recog = recognizer ?: return
        try {
            step(recog, data, length)
        } catch (t: Throwable) {
            Log.e(TAG, "onAudio", t)
            finish(lastPartial)
        }
    }

    private fun step(recog: Recognizer, data: ByteArray, length: Int) {
        val now = System.currentTimeMillis()
        val accepted = try {
            recog.acceptWaveForm(data, length)
        } catch (t: Throwable) {
            Log.e(TAG, "acceptWaveForm", t)
            finish(lastPartial)
            return
        }

        if (accepted) {
            val text = readText(recog.result)
            if (text.isNotEmpty()) {
                finish(text)
                return
            }
        } else {
            val partial = readPartial(recog.partialResult)
            if (partial.isNotEmpty() && partial != lastPartial) {
                lastPartial = partial
                lastSpeechAt = now
                main.post { onPartial(partial) }
            }
        }

        if (lastPartial.isNotEmpty() && now - lastSpeechAt > SILENCE_END_MS) {
            val text = readText(try { recog.finalResult } catch (_: Throwable) { "" })
            finish(if (text.isNotEmpty()) text else lastPartial)
            return
        }

        if (now - startedAt > MAX_SESSION_MS) {
            Log.w(TAG, "Потолок ${MAX_SESSION_MS}мс — закрываю сессию")
            val text = readText(try { recog.finalResult } catch (_: Throwable) { "" })
            finish(if (text.isNotEmpty()) text else lastPartial)
        }
    }

    private fun readText(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return try {
            JSONObject(raw).optString("text", "").trim()
                .replace("[unk]", "").trim()
        } catch (_: Throwable) {
            ""
        }
    }

    private fun readPartial(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return try {
            JSONObject(raw).optString("partial", "").trim()
                .replace("[unk]", "").trim()
        } catch (_: Throwable) {
            ""
        }
    }

    private fun finish(text: String) {
        if (finished) return
        finished = true
        active = false
        val cleaned = text.replace("[unk]", "").trim()
        Log.i(TAG, "Result got: $cleaned")
        main.post { onResult(cleaned) }
    }
}
