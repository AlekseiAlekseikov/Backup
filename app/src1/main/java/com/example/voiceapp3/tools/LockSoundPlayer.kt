package com.example.voiceapp3.tools

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Воспроизведение звука замка приложением (нужно только для разблокировки:
 * звук закрытия играет сама прошивка ГУ из ключа `lock_car_audio_path`).
 *
 * ## Маршрут вывода — что реально возможно без system-прав
 *
 * Штатный сигнал охраны уходит в наружный динамик через vendor-микс; точный
 * mix-port виден в `audio_policy_configuration.xml` / `mixer_paths.xml` дампа
 * прошивки. Дамп получить не удалось (gofile: 401 error-notPremium), поэтому
 * конкретный bus здесь не называется и не выдумывается.
 *
 * Что доступно приложению:
 *  1. `AudioAttributes` с `USAGE_ASSISTANCE_SONIFICATION` +
 *     `CONTENT_TYPE_SONIFICATION` — в car_audio_config этому соответствует
 *     контекст SONIFICATION, который маршрутизируется политикой ГУ;
 *  2. `MediaPlayer.setPreferredDevice(AudioDeviceInfo)` — явный выбор
 *     устройства вывода. Требует `MODIFY_AUDIO_SETTINGS` (в манифесте есть) и
 *     возвращает `false`, если маршрут приложению не отдан.
 *
 * Результат обеих попыток (включая полный список sink-устройств) пишется в
 * logcat с тегом `GeelyTools` и возвращается строкой-отчётом в шаг результата.
 * Если `setPreferredDevice` вернул `false` — наружный динамик этому процессу не
 * отдан, и код это не скрывает.
 *
 * Отклонение от предыдущей реализации: её комментарий обещал «AVAS usage 29 /
 * exterior speaker», тогда как в коде стоял `USAGE_ASSISTANCE_SONIFICATION`
 * (= 13) и никакого выбора устройства не было вовсе.
 */
object LockSoundPlayer {
    const val TAG = "GeelyTools"

    /**
     * `AudioDeviceInfo.TYPE_BUS` (21). Объявлен своей константой: в части сборок
     * SDK он помечен как системный, а значение стабильно.
     */
    const val TYPE_BUS = 21

    /**
     * Наружная шина AVAS по дампу прошивки: `vendor_etc/audio_policy_configuration.xml`,
     * `devicePort tagName="bus6_avas_out" type="AUDIO_DEVICE_OUT_BUS" address="bus6_avas_out"`.
     */
    const val AVAS_BUS_ADDRESS = "bus6_avas_out"

    /**
     * Салонные шины из того же файла дампа: media, системные звуки, телефон,
     * TTS и навигация. На них звук разблокировки выводиться не должен.
     */
    val CABIN_BUS_ADDRESSES = listOf(
        "bus0_media_out", "bus1_system_sound_out", "bus2_phone_out", "bus3_tts_out", "bus4_navi_out"
    )

    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    private var player: MediaPlayer? = null

    /** Все sink-устройства вывода, которые видит процесс приложения. */
    fun listOutputDevices(ctx: Context): List<AudioDeviceInfo> {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return emptyList()
        return try {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        } catch (t: Throwable) {
            AppLog.w(ctx, TAG, "getDevices(OUTPUTS) → ${describeThrowable(t)}", t)
            emptyList()
        }
    }

    /**
     * Выбор устройства для наружного сигнала: `TYPE_BUILTIN_SPEAKER` → любой
     * sink, кроме bluetooth/проводных (на них сигнал охраны уйти не должен).
     */
    /**
     * Адрес устройства вывода. `AudioDeviceInfo.getAddress()` в публичном SDK
     * отсутствует (hidden API), поэтому чтение через рефлексию: на ГУ адрес
     * доступен системным сервисам, а при отказе мы просто теряем точность
     * выбора — в отчёте это видно как `addr=?`.
     */
    private fun deviceAddress(d: AudioDeviceInfo): String = try {
        AudioDeviceInfo::class.java.getMethod("getAddress").invoke(d) as? String ?: ""
    } catch (t: Throwable) { "" }

    /**
     * Выбор НАРУЖНОГО устройства вывода.
     *
     * Порядок задан дампом прошивки ГУ (geely_dump, ECARX), а не догадкой:
     * `vendor_etc/audio_policy_configuration.xml` описывает шины
     * `bus0_media_out`, `bus1_system_sound_out`, `bus2_phone_out`,
     * `bus3_tts_out`, `bus4_navi_out` — все они салонные, — и отдельную
     * `bus6_avas_out` (`AUDIO_DEVICE_OUT_BUS`, address="bus6_avas_out",
     * PCM 16 бит / 48 кГц / стерео, усиление 0…3900 мБ), которая ведёт на
     * внешний динамик AVAS. Именно на неё и нужно попасть.
     *
     * Прежняя реализация предпочитала TYPE_BUILTIN_SPEAKER — по этому же
     * дампу `devicePort tagName="Speaker" type="AUDIO_DEVICE_OUT_SPEAKER"`,
     * т.е. динамик в салоне. Это и есть симптом «звук играет в салоне».
     */
    fun pickOutsideDevice(ctx: Context): AudioDeviceInfo? {
        val sinks = listOutputDevices(ctx).filter { it.isSink }
        val addrs = sinks.associateWith { deviceAddress(it) }
        val buses = sinks.filter { it.type == TYPE_BUS }
        val candidate =
            // 1) шина AVAS — наружный динамик (bus6_avas_out по дампу)
            buses.firstOrNull { addrs[it].orEmpty().contains("avas", true) }
                // 2) любая шина, не входящая в салонный набор bus0…bus4
                ?: buses.firstOrNull { a -> CABIN_BUS_ADDRESSES.none { a2 -> addrs[a].orEmpty().contains(a2, true) } }
                // 3) прочее не-Bluetooth/не-гарнитурное устройство
                ?: sinks.firstOrNull {
                    it.type != AudioDeviceInfo.TYPE_BLUETOOTH_A2DP &&
                        it.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO &&
                        it.type != AudioDeviceInfo.TYPE_WIRED_HEADSET &&
                        it.type != AudioDeviceInfo.TYPE_WIRED_HEADPHONES &&
                        it.type != AudioDeviceInfo.TYPE_USB_HEADSET &&
                        it.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                }
                // 4) встроенный динамик — крайний случай: по дампу он салонный
                ?: sinks.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        AppLog.i(
            ctx, TAG, "pickOutsideDevice: sink=${sinks.size} [" +
                sinks.joinToString("; ") { "id=${it.id} type=${it.type} addr=${addrs[it]?.ifEmpty { "?" } ?: "?"} name=${it.productName}" } +
                "] → выбрано " + (candidate?.let { "id=${it.id} type=${it.type} addr=" + (addrs[it]?.ifEmpty { "?" } ?: "?") } ?: "ничего") +
                "; наружная шина по дампу прошивки — $AVAS_BUS_ADDRESS"
        )
        return candidate
    }

    /**
     * Сыграть файл по маршруту «sonification». Блокирует не дольше [waitMs]:
     * вызывающая сторона (триггер замка) работает в своём потоке.
     *
     * @return отчёт: usage, громкость, задержка, выбранное устройство,
     *         фактический результат `setPreferredDevice` и чем кончился запуск.
     */
    fun play(ctx: Context, file: File, volumePercent: Int, delayMs: Int, waitMs: Long = 15_000): String {
        if (!file.exists() || file.length() <= 0) {
            val why = "файл ${file.absolutePath} не существует или пуст"
            AppLog.w(ctx, TAG, "play: $why")
            return why
        }
        var out = ""
        val latch = CountDownLatch(1)
        executor.execute {
            out = playInternal(ctx, file, volumePercent, delayMs)
            latch.countDown()
        }
        try {
            if (!latch.await(waitMs, TimeUnit.MILLISECONDS)) return "таймаут запуска воспроизведения (${waitMs} мс)"
        } catch (_: InterruptedException) {
            return "поток воспроизведения прерван"
        }
        return out
    }

    private fun playInternal(ctx: Context, file: File, volumePercent: Int, delayMs: Int): String {
        val device = pickOutsideDevice(ctx)
        val vol = volumePercent.coerceIn(5, 100) / 100f
        if (delayMs > 0) {
            try {
                Thread.sleep(delayMs.toLong())
            } catch (_: InterruptedException) {
                return "задержка ${delayMs} мс прервана — воспроизведение отменено"
            }
        }
        var mp: MediaPlayer? = null
        return try {
            player?.release()
            mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.setDataSource(file.absolutePath)
            mp.setVolume(vol, vol)
            val routed = if (device != null) mp.setPreferredDevice(device) else false
            mp.prepare()
            mp.start()
            player = mp
            val r = "usage=ASSISTANCE_SONIFICATION content=SONIFICATION vol=$volumePercent% delay=${delayMs}мс " +
                "device=${device?.let { "id=${it.id} type=${it.type} name=${it.productName}" } ?: "не выбрано"} " +
                "setPreferredDevice=${if (device == null) "не вызван" else routed.toString()} → MediaPlayer запущен " +
                "(фактический маршрут подтверждается только dumpsys audio / dumpsys media.audio_flinger на ГУ)"
            AppLog.i(ctx, TAG, "play: $r")
            r
        } catch (t: Throwable) {
            try {
                mp?.release()
            } catch (_: Throwable) {
            }
            if (player === mp) player = null
            val r = "воспроизведение не удалось: ${describeThrowable(t)}"
            AppLog.e(ctx, TAG, "play: $r", t)
            r
        }
    }

    fun stop() {
        try {
            player?.release()
        } catch (_: Throwable) {
        }
        player = null
    }

    /** Снимок маршрута для строки диагностики в UI. */
    /**
     * Отчёт о маршруте для строки «Маршрут вывода»: виден адрес каждой шины,
     * поэтому на первом же прогоне на ГУ понятно, есть ли в системе `bus6_avas_out`
     * и удалось ли на неё попасть.
     */
    fun routeReport(ctx: Context): String {
        val sinks = listOutputDevices(ctx).filter { it.isSink }
        val picked = pickOutsideDevice(ctx)
        return "sink=${sinks.size}[" +
            sinks.joinToString(",") { "${it.type}:${deviceAddress(it).ifEmpty { it.productName.toString() }}" } +
            "] preferred=" +
            (picked?.let { "${it.type}:${deviceAddress(it).ifEmpty { it.productName.toString() }}" } ?: "нет") +
            "; наружная шина прошивки=$AVAS_BUS_ADDRESS, салонные=$CABIN_BUS_ADDRESSES"
    }

}
