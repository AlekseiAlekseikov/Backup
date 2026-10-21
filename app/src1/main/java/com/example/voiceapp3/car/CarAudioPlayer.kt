package com.example.voiceapp3.car

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Озвучка. Почему раньше «модель загрузили, а помощник не заговорил»:
 *
 * 1. Модель ищется только в assets/tts-ru/. Если файлы лежат в tts-model/
 *    или на уровень глубже (как в tar.bz2) — OfflineTts не стартует.
 * 2. playText шёл в USAGE_MEDIA (1) на Coolray/EX2. Бипы «Слушаю» играют
 *    через USAGE_ASSISTANCE_SONIFICATION — поэтому бип слышно, а голос нет.
 * 3. Сервис сразу после команды делает abandonAudioFocus() — речь обрезается,
 *    даже если генерация прошла.
 * 4. MODE_STATIC на длинной фразе на API 28 падает.
 * 5. Штатный Android TTS как запасной путь не использовался.
 *
 * adb не нужен: модель пакуется в APK (assets/tts-ru/) и копируется
 * во внутреннее хранилище при первом запуске.
 */
class CarAudioPlayer(private val context: Context) {

    companion object {
        private const val TAG = "CarAudioPlayer"
        private const val CAR_EXTERNAL_USAGE_STARSHIP = 29
        private const val CAR_EXTERNAL_USAGE_E5 = 73
        private const val MIN_ONNX_BYTES = 5_000_000L
        private const val MODEL_FILE = "ru_RU-ruslan-medium.onnx"
        private const val TOKENS_FILE = "tokens.txt"
        private const val ESPEAK_DIR = "espeak-ng-data"
        private val CACHED_PHRASES = arrayOf("Привет", "Не понял", "Готово", "Не получилось")
    }

    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "CarTts").apply { isDaemon = true }
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var ttsFocusRequest: AudioFocusRequest? = null
    private val modelStorageDir: File = File(context.filesDir, "tts_models")
    private var tts: OfflineTts? = null
    private var androidTts: TextToSpeech? = null
    private val androidTtsReady = AtomicBoolean(false)
    private var mediaPlayer: MediaPlayer? = null
    private var audioTrack: AudioTrack? = null
    private val phraseCache = HashMap<String, File>()

    /**
     * true с момента вызова playText() до конца проигрывания.
     * Сервис смотрит на это, чтобы не забирать аудиофокус и не бипать поверх.
     */
    @Volatile var isSpeaking: Boolean = false
        private set

    /** Главный поток: озвучка началась — сервис глушит микрофон, иначе TTS будит сам себя. */
    var onSpeechStarted: (() -> Unit)? = null

    /** Вызывается на главном потоке, когда речь закончилась. */
    var onSpeechFinished: (() -> Unit)? = null

    val isReady: Boolean
        get() = tts != null || androidTtsReady.get()

    fun initialize() {
        try {
            copyModelAssets()
        } catch (t: Throwable) {
            Log.e(TAG, "copy TTS assets failed", t)
        }
        initAndroidTts()
        val onnx = File(modelStorageDir, MODEL_FILE)
        Log.i(
            TAG,
            "TTS init: onnx=${onnx.exists()} size=${onnx.length()} " +
                "androidTts=${androidTtsReady.get()}"
        )
        // Грузим Ruslan сразу, параллельно с VOSK — иначе первый «Привет»
        // либо ждёт 1.5 с, либо говорит чужим Google-голосом.
        executor.execute {
            val sherpa = ensureSherpa() ?: return@execute
            for (phrase in CACHED_PHRASES) cachePhrase(sherpa, phrase)
        }
    }

    private fun cachePhrase(sherpa: OfflineTts, text: String) {
        try {
            val audio = sherpa.generate(text) ?: return
            if (audio.samples.isEmpty()) return
            val pcm = convertFloatToPCM16(audio.samples)
            val file = File(context.cacheDir, "tts_cache_${text.hashCode()}.wav")
            writeWav(pcm, audio.sampleRate, file)
            phraseCache[text] = file
            Log.i(TAG, "Кэш TTS '$text' ${file.length()}b")
        } catch (t: Throwable) {
            Log.w(TAG, "не закэшировал '$text'", t)
        }
    }

    /** OfflineTts трогаем только с потока CarTts — JNI sherpa этого требует. */
    private fun ensureSherpa(): OfflineTts? {
        tts?.let { return it }
        val onnx = File(modelStorageDir, MODEL_FILE)
        val tokens = File(modelStorageDir, TOKENS_FILE)
        val espeak = File(modelStorageDir, ESPEAK_DIR)
        if (!onnx.isFile || onnx.length() < MIN_ONNX_BYTES || !tokens.isFile || !espeak.isDirectory) {
            Log.e(
                TAG,
                "Sherpa-модель не найдена или битая. " +
                    "onnx=${onnx.exists()} size=${onnx.length()} " +
                    "tokens=${tokens.exists()} espeak=${espeak.exists()}. " +
                    "Положите в app/src/main/assets/tts-ru/: " +
                    "$MODEL_FILE, $TOKENS_FILE, $ESPEAK_DIR/ " +
                    "и пересоберите APK. adb не нужен."
            )
            return null
        }
        return try {
            OfflineTts(buildSherpaConfig(onnx, tokens, espeak)).also {
                tts = it
                Log.i(TAG, "Sherpa TTS готов (${onnx.length()} байт)")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to init sherpa TTS", t)
            null
        }
    }

    // ------------------------------------------------------------------
    //  Куда играет звук
    // ------------------------------------------------------------------

    /**
     * EX2: голос идёт тем же usage, что и бип «Слушаю» (13).
     * USAGE_MEDIA (1) на этом ГУ часто уходит в никуда.
     * 29 и 73 оставляем только как запасные попытки в usagesToTry.
     */
    fun getExternalUsage(): Int = AudioAttributes.USAGE_ASSISTANCE_SONIFICATION

    private fun usagesToTry(): IntArray {
        val seen = LinkedHashSet<Int>()
        seen.add(getExternalUsage())
        seen.add(AudioAttributes.USAGE_VOICE_COMMUNICATION)     // 2
        seen.add(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION) // 13, как бипы
        seen.add(CAR_EXTERNAL_USAGE_STARSHIP)                   // 29
        seen.add(CAR_EXTERNAL_USAGE_E5)                         // 73
        seen.add(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE) // 12
        seen.add(AudioAttributes.USAGE_MEDIA)                   // 1
        val out = IntArray(seen.size)
        var i = 0
        for (u in seen) out[i++] = u
        return out
    }

    // ------------------------------------------------------------------
    //  Публичная озвучка
    // ------------------------------------------------------------------

    /**
     * Всегда возвращается сразу. isSpeaking=true уже на вызывающем потоке,
     * чтобы сервис не успел забрать фокус.
     */
    fun playText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        isSpeaking = true
        // Сразу, не через post: иначе кадры TTS успевают в распознаватель.
        runCatching { onSpeechStarted?.invoke() }
        executor.execute {
            try {
                speakBlocking(trimmed)
            } catch (t: Throwable) {
                Log.e(TAG, "TTS generation/playback failed", t)
            } finally {
                isSpeaking = false
                val cb = onSpeechFinished
                if (cb != null) main.post { cb() }
            }
        }
    }

    fun playWithCustomUsage(assetName: String) {
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(attrs(getExternalUsage()))
                context.assets.openFd("media/$assetName").use { fd ->
                    setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                }
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in playing sound: $e")
        }
    }

    fun release() {
        executor.shutdownNow()
        runCatching { tts?.release() }
        tts = null
        runCatching { androidTts?.shutdown() }
        androidTts = null
        androidTtsReady.set(false)
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
        stopAudioTrack()
    }

    // ------------------------------------------------------------------
    //  Генерация + проигрывание
    // ------------------------------------------------------------------

    private fun speakBlocking(text: String) {
        grabTtsFocus()
        try {
            phraseCache[text]?.let { cached ->
                if (cached.isFile && cached.length() > 44 && playWavFile(cached)) return
            }
            val sherpa = ensureSherpa()
            if (sherpa != null) {
                val audio = sherpa.generate(text)
                if (audio != null && audio.samples.isNotEmpty()) {
                    val pcm = convertFloatToPCM16(audio.samples)
                    val wav = writeWav(
                        pcm, audio.sampleRate,
                        File(context.cacheDir, "tts_cache_${text.hashCode()}.wav")
                    )
                    phraseCache[text] = wav
                    if (playWavFile(wav)) return
                    if (playPcmTrack(pcm, audio.sampleRate)) return
                    Log.w(TAG, "Sherpa сгенерировал звук, но проиграть не удалось")
                } else {
                    Log.w(TAG, "Sherpa вернул пустой аудио")
                }
            }
            Log.w(TAG, "Sherpa не сказал '$text', пробую Google TTS")
            if (speakAndroidTts(text)) return
            Log.e(TAG, "Ни sherpa, ни Android TTS не смогли сказать: '$text'")
        } finally {
            dropTtsFocus()
        }
    }

    private fun grabTtsFocus() {
        try {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs(getExternalUsage()))
                .build()
            ttsFocusRequest = req
            audioManager.requestAudioFocus(req)
        } catch (t: Throwable) {
            Log.w(TAG, "TTS focus: ${t.message}")
        }
    }

    private fun dropTtsFocus() {
        try {
            ttsFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } catch (_: Throwable) {}
        ttsFocusRequest = null
    }

    private fun playWavFile(file: File): Boolean {
        for (usage in usagesToTry()) {
            Log.i(TAG, "MediaPlayer TTS usage=$usage file=${file.length()}b")
            val done = CountDownLatch(1)
            var started = false
            val mp = MediaPlayer()
            try {
                mp.setAudioAttributes(attrs(usage))
                mp.setDataSource(file.absolutePath)
                mp.setOnCompletionListener { done.countDown() }
                mp.setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error what=$what extra=$extra")
                    done.countDown()
                    true
                }
                mp.prepare()
                mp.start()
                started = true
                mediaPlayer = mp
                if (!done.await(30, TimeUnit.SECONDS)) {
                    Log.w(TAG, "TTS playback timeout")
                }
                return true
            } catch (t: Throwable) {
                Log.w(TAG, "usage=$usage не принят: ${t.message}")
                runCatching { mp.release() }
                if (!started) continue
                return false
            } finally {
                if (mediaPlayer === mp) mediaPlayer = null
                runCatching { mp.reset() }
                runCatching { mp.release() }
            }
        }
        return false
    }

    private fun playPcmTrack(pcm: ByteArray, sampleRate: Int): Boolean {
        stopAudioTrack()
        val min = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (min <= 0) return false
        for (usage in usagesToTry()) {
            try {
                val track = AudioTrack.Builder()
                    .setAudioAttributes(attrs(usage))
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(maxOf(min, pcm.size.coerceAtMost(min * 8)))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                audioTrack = track
                track.play()
                var offset = 0
                while (offset < pcm.size) {
                    val n = track.write(pcm, offset, minOf(min, pcm.size - offset))
                    if (n <= 0) break
                    offset += n
                }
                val ms = (pcm.size / 2) * 1000L / sampleRate + 200
                Thread.sleep(ms.coerceAtMost(30_000))
                stopAudioTrack()
                return true
            } catch (t: Throwable) {
                Log.w(TAG, "AudioTrack usage=$usage: ${t.message}")
                stopAudioTrack()
            }
        }
        return false
    }

    private fun speakAndroidTts(text: String): Boolean {
        val engine = androidTts ?: return false
        if (!androidTtsReady.get()) return false
        val done = CountDownLatch(1)
        val played = AtomicBoolean(false)
        main.post {
            try {
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        Log.i(TAG, "Android TTS onStart")
                    }
                    override fun onDone(utteranceId: String?) {
                        played.set(true)
                        done.countDown()
                    }
                    @Deprecated("deprecated")
                    override fun onError(utteranceId: String?) {
                        done.countDown()
                    }
                    override fun onError(utteranceId: String?, errorCode: Int) {
                        Log.e(TAG, "Android TTS error $errorCode")
                        done.countDown()
                    }
                })
                val params = Bundle()
                params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "va")
                val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, "va")
                Log.i(TAG, "Android TTS speak()=$result text='$text'")
                if (result != TextToSpeech.SUCCESS) done.countDown()
            } catch (t: Throwable) {
                Log.e(TAG, "Android TTS speak failed", t)
                done.countDown()
            }
        }
        done.await(12, TimeUnit.SECONDS)
        return played.get()
    }

    private fun initAndroidTts() {
        val starter = Runnable {
            try {
                androidTts = TextToSpeech(context) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        val engine = androidTts
                        val ru = engine?.setLanguage(Locale("ru", "RU")) ?: TextToSpeech.ERROR
                        if (ru == TextToSpeech.LANG_MISSING_DATA || ru == TextToSpeech.LANG_NOT_SUPPORTED) {
                            Log.w(TAG, "Русский Android TTS недоступен ($ru), пробую язык системы")
                            engine?.setLanguage(Locale.getDefault())
                        }
                        engine?.setAudioAttributes(attrs(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION))
                        androidTtsReady.set(true)
                        Log.i(TAG, "Android TTS готов (запасной путь)")
                    } else {
                        Log.w(TAG, "Android TTS init status=$status")
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Android TTS недоступен", t)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) starter.run() else main.post(starter)
    }

    // ------------------------------------------------------------------
    //  Модель из assets
    // ------------------------------------------------------------------

    private fun copyModelAssets() {
        if (!modelStorageDir.exists()) modelStorageDir.mkdirs()

        modelStorageDir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.endsWith(".onnx") && f.name != MODEL_FILE) {
                Log.i(TAG, "Удаляю старую TTS-модель ${f.name} (${f.length()} байт)")
                f.delete()
            }
        }

        val existing = File(modelStorageDir, MODEL_FILE)
        if (existing.isFile && existing.length() >= MIN_ONNX_BYTES &&
            File(modelStorageDir, TOKENS_FILE).isFile &&
            File(modelStorageDir, ESPEAK_DIR).isDirectory
        ) {
            Log.i(TAG, "Модель уже на месте (${existing.length()} байт)")
            return
        }

        val onnxAsset = findAssetFile(MODEL_FILE)
        if (onnxAsset == null) {
            Log.e(
                TAG,
                "В APK нет $MODEL_FILE. Искал в assets/tts-ru, tts-model, tts_models, tts. " +
                    "Скачайте vits-piper-ru_RU-ruslan-medium.tar.bz2, распакуйте СОДЕРЖИМОЕ " +
                    "в app/src/main/assets/tts-ru/ и пересоберите APK."
            )
            listAssetTree("")
            return
        }
        val assetDir = onnxAsset.substringBeforeLast('/', "")
        Log.i(TAG, "Нашёл модель в assets/$onnxAsset")

        copyAssetFile(onnxAsset, File(modelStorageDir, MODEL_FILE))
        val tokensAsset = if (assetDir.isEmpty()) TOKENS_FILE else "$assetDir/$TOKENS_FILE"
        copyAssetFile(tokensAsset, File(modelStorageDir, TOKENS_FILE))
        val espeakAsset = if (assetDir.isEmpty()) ESPEAK_DIR else "$assetDir/$ESPEAK_DIR"
        copyAssetDir(espeakAsset, File(modelStorageDir, ESPEAK_DIR))

        val copied = File(modelStorageDir, MODEL_FILE)
        Log.i(TAG, "Скопировано onnx=${copied.length()} байт")
        if (copied.length() < MIN_ONNX_BYTES) {
            Log.e(TAG, "Файл слишком маленький — это не модель (часто HTML 404 или .onnx.json)")
        }
    }

    private fun findAssetFile(fileName: String, dir: String = ""): String? {
        val children = try {
            context.assets.list(dir)
        } catch (_: Exception) {
            null
        } ?: return null
        for (name in children) {
            val path = if (dir.isEmpty()) name else "$dir/$name"
            if (name == fileName) {
                try {
                    context.assets.open(path).close()
                    return path
                } catch (_: Exception) {
                    // это каталог с тем же именем
                }
            }
            findAssetFile(fileName, path)?.let { return it }
        }
        return null
    }

    private fun copyAssetFile(assetPath: String, dest: File) {
        try {
            dest.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                FileOutputStream(dest).use { input.copyTo(it) }
            }
            Log.i(TAG, "asset $assetPath -> ${dest.length()}b")
        } catch (t: Throwable) {
            Log.e(TAG, "Не скопировался $assetPath", t)
        }
    }

    private fun copyAssetDir(assetPath: String, dest: File) {
        val children = try {
            context.assets.list(assetPath)
        } catch (_: Exception) {
            null
        }
        if (children == null || children.isEmpty()) {
            copyAssetFile(assetPath, dest)
            return
        }
        dest.mkdirs()
        for (child in children) {
            copyAssetDir("$assetPath/$child", File(dest, child))
        }
    }

    private fun listAssetTree(dir: String, depth: Int = 0) {
        if (depth > 3) return
        val children = try {
            context.assets.list(dir)
        } catch (_: Exception) {
            return
        } ?: return
        if (children.isEmpty()) return
        Log.i(TAG, "assets/${if (dir.isEmpty()) "." else dir}: ${children.toList()}")
        for (name in children.take(20)) {
            val path = if (dir.isEmpty()) name else "$dir/$name"
            listAssetTree(path, depth + 1)
        }
    }

    private fun buildSherpaConfig(onnx: File, tokens: File, espeak: File): OfflineTtsConfig {
        val vits = OfflineTtsVitsModelConfig.Builder()
            .setModel(onnx.absolutePath)
            .setTokens(tokens.absolutePath)
            .setDataDir(espeak.absolutePath)
            .build()
        return OfflineTtsConfig.Builder()
            .setModel(
                OfflineTtsModelConfig.Builder()
                    .setVits(vits)
                    .setNumThreads(2)
                    .setDebug(true)
                    .setProvider("cpu")
                    .build()
            )
            .build()
    }

    // ------------------------------------------------------------------
    //  WAV / PCM
    // ------------------------------------------------------------------

    private fun convertFloatToPCM16(floatSamples: FloatArray): ByteArray {
        val pcmData = ByteArray(floatSamples.size * 2)
        for (i in floatSamples.indices) {
            val sample = (floatSamples[i].coerceIn(-1.0f, 1.0f) * 32767).toInt()
            pcmData[i * 2] = (sample and 0xFF).toByte()
            pcmData[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        return pcmData
    }

    private fun writeWav(pcm: ByteArray, sampleRate: Int, dest: File? = null): File {
        val file = dest ?: File(context.cacheDir, "tts_play.wav")
        val header = ByteArrayOutputStream(44)
        fun wstr(s: String) = header.write(s.toByteArray(Charsets.US_ASCII))
        fun w32(v: Int) {
            header.write(v and 0xff)
            header.write(v shr 8 and 0xff)
            header.write(v shr 16 and 0xff)
            header.write(v shr 24 and 0xff)
        }
        fun w16(v: Int) {
            header.write(v and 0xff)
            header.write(v shr 8 and 0xff)
        }
        wstr("RIFF"); w32(36 + pcm.size); wstr("WAVE")
        wstr("fmt "); w32(16); w16(1); w16(1)
        w32(sampleRate); w32(sampleRate * 2); w16(2); w16(16)
        wstr("data"); w32(pcm.size)
        FileOutputStream(file).use {
            it.write(header.toByteArray())
            it.write(pcm)
        }
        return file
    }

    private fun attrs(usage: Int): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

    private fun stopAudioTrack() {
        audioTrack?.apply {
            runCatching { if (playState != AudioTrack.PLAYSTATE_STOPPED) stop() }
            runCatching { release() }
        }
        audioTrack = null
    }
}
