package com.example.voiceapp3

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log

/**
 * ЕДИНСТВЕННЫЙ микрофон приложения.
 *
 * AudioRecord открывается при старте сервиса и живёт до его смерти.
 * Поток читает вход непрерывно и отдаёт буферы текущему потребителю.
 *
 * Между wake и командой микрофон НЕ закрывается. Ни stop(), ни release().
 *
 * На ГУ вход VOICE_RECOGNITION иногда умирает сам: read() = ERROR_DEAD_OBJECT,
 * recordingState сбрасывается. Тумблер «распознавание» помогает, потому что
 * он делает stop()+start() и поднимает новый AudioRecord. Здесь то же самое
 * автоматически: по ошибке чтения и по мёртвому состоянию — recover().
 * По тишине в салоне НЕ пересоздаём.
 */
class MicPump(
    private val audioManager: AudioManager,
    /** true — печатать уровень звука раз в две секунды. */
    private val debug: Boolean = true,
) {

    /**
     * Получатель звука. Реализуют WakeWordDetector и CommandRecognizer.
     *
     * @param data буфер, ПЕРЕИСПОЛЬЗУЕМЫЙ насосом — копируйте, если нужно хранить
     * @param length сколько байт реально прочитано
     */
    interface Consumer {
        fun onAudio(data: ByteArray, length: Int)

        /** Вызывается, когда потребителя подключили. Место для reset(). */
        fun onAttached() {}

        /** Вызывается, когда потребителя отключили. */
        fun onDetached() {}
    }

    companion object {
        private const val TAG = "MicPump"

        const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        private const val LEVEL_LOG_INTERVAL_MS = 2000L
        private const val SILENCE_THRESHOLD_PCT = 2
        private const val SILENCE_WARN_MS = 15_000L
        private const val RECOVER_COOLDOWN_MS = 2_500L
        private const val ERROR_STREAK_LIMIT = 6
    }

    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var consumer: Consumer? = null
    @Volatile private var running = false
    private var thread: Thread? = null
    private var bufferSize = 4096
    private var noiseSuppressor: NoiseSuppressor? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var agc: AutomaticGainControl? = null

    @Volatile private var lastRecoverAt = 0L

    /**
     * Громкость последнего кадра, 0…100.
     * Сервис ждёт, пока после TTS упадёт — иначе эхо «Привет» становится командой.
     */
    @Volatile var lastLevelPct: Int = 0
        private set

    /** Полностью ли жив насос. Сервис может проверить перед стартом сессии. */
    val isRunning: Boolean get() = running && audioRecord != null

    /** Вход реально пишет кадры, не только объект жив. */
    val isHealthy: Boolean
        get() {
            val rec = audioRecord ?: return false
            val alive = thread?.isAlive == true
            return running && alive &&
                    rec.state == AudioRecord.STATE_INITIALIZED &&
                    rec.recordingState == AudioRecord.RECORDSTATE_RECORDING
        }

    // ------------------------------------------------------------------

    @Synchronized
    fun start(): Boolean {
        if (running && isHealthy) {
            Log.d(TAG, "уже запущен")
            return true
        }
        if (running && !isHealthy) {
            Log.w(TAG, "поток жив, вход мёртв — recover")
            return recoverLocked("start-unhealthy")
        }
        if (!openMic()) return false

        running = true
        thread = Thread({ loop() }, "MicPumpThread").apply {
            priority = Thread.NORM_PRIORITY - 1
            start()
        }
        Log.i(TAG, "Микрофон открыт на всё время работы сервиса")
        return true
    }

    @Synchronized
    fun stop() {
        running = false
        thread?.let { runCatching { it.join(700) } }
        thread = null
        runCatching { consumer?.onDetached() }
        consumer = null
        closeMic()
        Log.i(TAG, "Остановлен, микрофон отпущен")
    }

    /**
     * Аварийное пересоздание входа. Тумблер распознавания делает то же самое.
     * Поток насоса не трогаем.
     */
    @Synchronized
    fun restart(): Boolean {
        Log.w(TAG, "Аварийное пересоздание AudioRecord")
        return recoverLocked("restart")
    }

    /**
     * Если вход умер — пересоздать. Можно звать с главного потока (watchdog).
     * true = вход жив после вызова.
     */
    @Synchronized
    fun recoverIfDead(): Boolean {
        if (isHealthy) return true
        Log.w(TAG, "recoverIfDead: вход не здоров")
        return recoverLocked("watchdog")
    }

    /**
     * Переключение потребителя. Микрофон при этом не трогаем — в этом весь смысл.
     */
    @Synchronized
    fun setConsumer(next: Consumer?) {
        val prev = consumer
        if (prev === next) return
        consumer = next
        runCatching { prev?.onDetached() }
        runCatching { next?.onAttached() }
        Log.d(TAG, "Потребитель: ${prev?.javaClass?.simpleName ?: "нет"}" +
                " -> ${next?.javaClass?.simpleName ?: "нет"}")
    }

    // ------------------------------------------------------------------

    private fun recoverLocked(reason: String): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastRecoverAt < RECOVER_COOLDOWN_MS && audioRecord != null) {
            Log.w(TAG, "recover skip ($reason), cooldown")
            return audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING
        }
        lastRecoverAt = now
        Log.w(TAG, "recover ($reason)")
        closeMic()
        try {
            Thread.sleep(220)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        val ok = openMic()
        if (!ok) Log.e(TAG, "recover не открыл вход ($reason)")
        else Log.i(TAG, "вход поднят ($reason)")
        return ok
    }

    @SuppressLint("MissingPermission")
    private fun openMic(): Boolean = try {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        bufferSize = if (minBuf > 0) minBuf * 2 else 4096

        val rec = AudioRecord.Builder()
            // VOICE_RECOGNITION включает аппаратное эхоподавление и шумодав.
            // Без него музыка из динамиков попадает обратно в микрофон.
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(ENCODING)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .build()

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord не инициализирован")
            rec.release()
            false
        } else {
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.e(TAG, "startRecording не перевёл в RECORDING")
                rec.release()
                false
            } else {
                attachFx(rec)
                audioRecord = rec
                true
            }
        }
    } catch (t: Throwable) {
        Log.e(TAG, "openMic упал", t)
        false
    }

    /**
     * Шумодав / AEC / AGC на сессии микрофона.
     * На обдуве 8 без этого Vosk почти не слышит фразу.
     * Ссылки держим в полях — иначе GC снимает эффект.
     */
    private fun attachFx(rec: AudioRecord) {
        val id = rec.audioSessionId
        if (id == AudioManager.ERROR) return
        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = runCatching {
                NoiseSuppressor.create(id)?.also { it.enabled = true }
            }.onFailure { Log.w(TAG, "NoiseSuppressor", it) }.getOrNull()
        }
        if (AcousticEchoCanceler.isAvailable()) {
            echoCanceler = runCatching {
                AcousticEchoCanceler.create(id)?.also { it.enabled = true }
            }.onFailure { Log.w(TAG, "AEC", it) }.getOrNull()
        }
        if (AutomaticGainControl.isAvailable()) {
            agc = runCatching {
                AutomaticGainControl.create(id)?.also { it.enabled = true }
            }.onFailure { Log.w(TAG, "AGC", it) }.getOrNull()
        }
        Log.i(TAG, "mic fx ns=${noiseSuppressor != null} aec=${echoCanceler != null} agc=${agc != null}")
    }

    private fun releaseFx() {
        runCatching { noiseSuppressor?.enabled = false }
        runCatching { noiseSuppressor?.release() }
        noiseSuppressor = null
        runCatching { echoCanceler?.enabled = false }
        runCatching { echoCanceler?.release() }
        echoCanceler = null
        runCatching { agc?.enabled = false }
        runCatching { agc?.release() }
        agc = null
    }

    private fun closeMic() {
        val rec = audioRecord ?: return
        audioRecord = null
        releaseFx()
        // stop() без release() оставляет вход занятым — это уже проверено на API 28
        runCatching { if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) rec.stop() }
        runCatching { rec.release() }
    }

    private fun loop() {
        val buffer = ByteArray(bufferSize.coerceAtLeast(4096))
        var peak = 0
        var lastLevelLogAt = 0L
        var silenceSince = System.currentTimeMillis()
        var warned = false
        var errorStreak = 0

        while (running) {
            val rec = audioRecord
            if (rec == null) {
                if (running) recoverFromLoop("null-record")
                Thread.sleep(200)
                continue
            }
            if (rec.state != AudioRecord.STATE_INITIALIZED ||
                rec.recordingState != AudioRecord.RECORDSTATE_RECORDING
            ) {
                recoverFromLoop("state=${rec.state}/${rec.recordingState}")
                Thread.sleep(80)
                continue
            }

            val read = try {
                rec.read(buffer, 0, buffer.size)
            } catch (t: Throwable) {
                Log.e(TAG, "read упал", t)
                recoverFromLoop("read-throw")
                Thread.sleep(80)
                continue
            }
            if (read <= 0) {
                errorStreak++
                if (read == AudioRecord.ERROR_DEAD_OBJECT ||
                    read == AudioRecord.ERROR_INVALID_OPERATION ||
                    errorStreak >= ERROR_STREAK_LIMIT
                ) {
                    recoverFromLoop("read=$read streak=$errorStreak")
                    errorStreak = 0
                }
                Thread.sleep(20)
                continue
            }
            errorStreak = 0

            // --- уровень сигнала: и для лога, и для диагностики мёртвого входа ---
            var i = 0
            var framePeak = 0
            while (i + 1 < read) {
                val s = ((buffer[i + 1].toInt() shl 8) or
                        (buffer[i].toInt() and 0xFF)).toShort().toInt()
                val abs = if (s < 0) -s else s
                if (abs > peak) peak = abs
                if (abs > framePeak) framePeak = abs
                i += 2
            }
            lastLevelPct = framePeak * 100 / 32768

            val now = System.currentTimeMillis()
            if (peak * 100 / 32768 >= SILENCE_THRESHOLD_PCT) {
                silenceSince = now
                warned = false
            } else if (!warned && now - silenceSince > SILENCE_WARN_MS) {
                Log.w(TAG, "Вход молчит ${SILENCE_WARN_MS / 1000} сек. " +
                        "Тишина в салоне — не пересоздаю. Мёртвый вход ловит read/state.")
                warned = true
            }

            if (debug && now - lastLevelLogAt > LEVEL_LOG_INTERVAL_MS) {
                val pct = peak * 100 / 32768
                Log.d(TAG, "уровень звука: %3d%% %s %s".format(
                    pct, "#".repeat((pct / 5).coerceAtMost(20)),
                    if (pct < SILENCE_THRESHOLD_PCT) "<- тишина" else ""))
                lastLevelLogAt = now
                peak = 0
            }

            // Во время телефонного разговора звук не раздаём, но вход держим открытым.
            if (isPhoneBusy()) continue

            val c = consumer ?: continue
            try {
                c.onAudio(buffer, read)
            } catch (t: Throwable) {
                Log.e(TAG, "Потребитель ${c.javaClass.simpleName} бросил исключение", t)
            }
        }
    }

    private fun recoverFromLoop(reason: String) {
        // loop не держит synchronized; restart() берёт замок сам.
        runCatching { restart() }
            .onFailure { Log.e(TAG, "recoverFromLoop $reason", it) }
    }

    private fun isPhoneBusy(): Boolean =
        audioManager.mode == AudioManager.MODE_IN_CALL ||
                audioManager.mode == AudioManager.MODE_IN_COMMUNICATION
}
