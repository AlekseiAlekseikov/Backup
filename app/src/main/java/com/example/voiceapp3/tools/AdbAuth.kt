package com.example.voiceapp3.tools

import android.content.Context
import android.util.Base64
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.PKCS8EncodedKeySpec

/**
 * iter28 P0-B: ключ приложения для ADB-авторизации.
 *
 * РЕФЕРЕНС: `НЕТ В ЭТАЛОНЕ` — в CEX своего ключа нет (он пользуется готовым транспортом).
 * Механизм — стандартный `adb` (platform-tools, SDK 28–30) и проверяется по протоколу:
 *  - `AUTH` c `arg0=1` (TOKEN) приносит 20 случайных байт; хост подписывает их `SHA1withRSA`
 *    и отвечает `AUTH` c `arg0=2` (SIGNATURE);
 *  - если ключа нет в `/data/misc/adb/adb_keys`, хост отправляет `AUTH` c `arg0=3`
 *    (RSAPUBLICKEY) и строкой `base64(n) base64(e) имя` — ровно в таком виде ключи и лежат в `adb_keys`.
 *
 * Ключ стабильный: `filesDir/adb_key.pk8` (PKCS#8 DER), переиспользуется между запусками.
 * Публичная строка пишется рядом в `filesDir/adb_key.pub` (формат строки `adb_keys`).
 */
object AdbAuth {
    private const val TAG = "CENTRAL_AdbAuth"
    private const val KEY_NAME = "voiceapp3@ihu629g"

    @Volatile private var priv: PrivateKey? = null
    @Volatile private var pubLine: String? = null
    @Volatile private var initialized = false

    /** Загрузить или сгенерировать ключ. Идемпотентно; вызывать с фонового потока. */
    @Synchronized
    fun init(ctx: Context?) {
        if (initialized) return
        initialized = true
        try {
            val dir = ctx?.filesDir
            val pk8 = dir?.let { File(it, "adb_key.pk8") }
            var key: PrivateKey? = null
            if (pk8 != null && pk8.exists() && pk8.length() > 0) {
                key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pk8.readBytes()))
                AppLog.i(TAG, "ключ загружен из ${pk8.name} (${pk8.length()} B)")
            } else {
                val gen = KeyPairGenerator.getInstance("RSA")
                gen.initialize(2048)
                val kp = gen.generateKeyPair()
                key = kp.private
                if (pk8 != null) {
                    runCatching { pk8.writeBytes(kp.private.encoded) }
                    AppLog.i(TAG, "ключ создан и сохранён: ${pk8.name} (${kp.private.encoded.size} B)")
                } else {
                    AppLog.w(TAG, "нет Context — ключ только в памяти (после рестарта будет новый)")
                }
            }
            priv = key
            pubLine = (key as? RSAPrivateCrtKey)?.let {
                "${unsignedB64(it.modulus)} ${unsignedB64(it.publicExponent)} $KEY_NAME"
            }
            pubLine?.let { line ->
                runCatching { dir?.let { d -> File(d, "adb_key.pub").writeText("$line\n") } }
            }
        } catch (t: Throwable) {
            AppLog.e(TAG, "init failed: ${t.javaClass.simpleName}: ${t.message}")
            priv = null
            pubLine = null
        }
        // P0-B п.3: одна строка без переносов + длина (владелец заберёт её с ПК при необходимости).
        AppLog.i(TAG, "ADB_PUBKEY: ${pubLine ?: "(нет ключа)"}")
        AppLog.i(TAG, "ADB_PUBKEY_LEN=${pubLine?.length ?: 0}")
    }

    fun hasKey(): Boolean = priv != null

    /** Подпись 20-байтного токена: SHA1withRSA — ровно то, что проверяет adbd. */
    fun signToken(token: ByteArray): ByteArray? {
        val k = priv ?: return null
        return try {
            val s = Signature.getInstance("SHA1withRSA")
            s.initSign(k)
            s.update(token)
            s.sign()
        } catch (t: Throwable) {
            AppLog.e(TAG, "signToken failed: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    /** Строка ключа "b64(n) b64(e) имя" — она же строка для `adb_keys`. */
    fun publicKeyLine(): String? = pubLine

    /** payload пакета AUTH(RSAPUBLICKEY): строка ключа + нулевой байт. */
    fun publicKeyPayload(): ByteArray? = pubLine?.let { "$it\u0000".toByteArray(Charsets.UTF_8) }

    /** "b64(n) b64(e) имя" — big-endian без ведущих нулей, как в platform-tools. */
    private fun unsignedB64(v: BigInteger): String {
        val b = v.toByteArray()
        val start = if (b.size > 1 && b[0] == 0.toByte()) 1 else 0
        return Base64.encodeToString(b.copyOfRange(start, b.size), Base64.NO_WRAP)
    }
}
