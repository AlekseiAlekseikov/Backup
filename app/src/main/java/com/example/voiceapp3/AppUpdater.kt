package com.example.voiceapp3

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Обновление по воздуху с GitHub.
 *
 * Скачивание APK на ГУ часто рвётся:
 *   Read error: ssl=… I/O error during system call, Software caused connection abort
 * Это не «ошибка записи на диск», а обрыв HTTPS посреди большого файла.
 *
 * Здесь: докачка (Range), несколько попыток, без ssl-адресов на экране.
 */
object AppUpdater {

    private const val TAG = "AppUpdater"

    private const val VERSION_JSON =
        "https://raw.githubusercontent.com/AlekseiAlekseikov/GeelyTools/main/version.json"
    private const val RELEASES_API =
        "https://api.github.com/repos/AlekseiAlekseikov/GeelyTools/releases/latest"

    private const val PROVIDER_AUTH_SUFFIX = ".fileprovider"
    private const val MAX_ATTEMPTS = 30
    private const val MIN_APK_BYTES = 10_000L

    data class Remote(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val changelog: String
    )

    sealed class CheckResult {
        data class Available(val remote: Remote) : CheckResult()
        object UpToDate : CheckResult()
        data class Error(val message: String) : CheckResult()
    }

    interface Listener {
        fun onCheck(result: CheckResult)
        fun onProgress(percent: Int, message: String)
        fun onInstallStarted()
        fun onFailure(message: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "AppUpdater").apply { isDaemon = true }
    }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.MINUTES)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .pingInterval(20, TimeUnit.SECONDS)
            .cache(null)
            .build()
    }

    fun currentVersionCode(context: Context): Int =
        context.packageManager.getPackageInfo(context.packageName, 0).versionCode

    fun currentVersionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"

    fun check(context: Context, listener: Listener) {
        io.execute {
            val localCode = runCatching { currentVersionCode(context) }.getOrDefault(0)
            val remote = fetch(VERSION_JSON)?.let { parseVersionJson(it) }
                ?: fetch(RELEASES_API, accept = "application/vnd.github+json")?.let { parseReleaseJson(it) }
            main.post {
                when {
                    remote == null -> listener.onCheck(CheckResult.Error("Не удалось проверить обновление"))
                    remote.versionCode > localCode -> listener.onCheck(CheckResult.Available(remote))
                    else -> listener.onCheck(CheckResult.UpToDate)
                }
            }
        }
    }

    fun downloadAndInstall(context: Context, remote: Remote, listener: Listener) {
        if (!isAllowedUrl(remote.apkUrl)) {
            main.post { listener.onFailure("Адрес APK не с GitHub — отказ") }
            return
        }
        io.execute {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { f ->
                if (f.name.startsWith("update-") && !f.name.contains("-${remote.versionCode}.")) {
                    f.delete()
                }
            }
            val apk = File(dir, "update-${remote.versionCode}.apk")
            try {
                main.post { listener.onProgress(0, "Скачиваю ${remote.versionName}…") }
                if (!downloadResumable(apk, remote.apkUrl, listener)) {
                    main.post { listener.onFailure("Не удалось скачать. Нажмите обновление ещё раз.") }
                    return@execute
                }
                val reason = verifyApk(context, apk)
                if (reason != null) {
                    apk.delete()
                    main.post { listener.onFailure(reason) }
                    return@execute
                }
                main.post {
                    listener.onProgress(100, "Устанавливаю…")
                    listener.onInstallStarted()
                    install(context, apk, listener)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "downloadAndInstall", t)
                main.post { listener.onFailure(friendly(t)) }
            }
        }
    }

    /**
     * Качает с докачкой. Обрыв SSL не стирает уже скачанное —
     * следующая попытка шлёт Range: bytes=уже_есть-
     */
    private fun downloadResumable(apk: File, url: String, listener: Listener): Boolean {
        var attempt = 0
        while (attempt < MAX_ATTEMPTS) {
            attempt++
            val have = if (apk.exists()) apk.length() else 0L
            if (have > 0) {
                Log.i(TAG, "докачка с $have байт, попытка $attempt")
                main.post {
                    listener.onProgress(
                        (-1).coerceAtLeast(0),
                        "Продолжаю скачивание (попытка $attempt)…"
                    )
                }
            }
            try {
                val done = downloadOnce(apk, url, have, listener)
                if (done) return true
            } catch (t: Throwable) {
                Log.w(TAG, "попытка $attempt оборвалась: ${t.message}")
                if (attempt >= MAX_ATTEMPTS) {
                    main.post { listener.onFailure(friendly(t)) }
                    return false
                }
                main.post {
                    listener.onProgress(0, "Связь оборвалась, повторяю ($attempt/$MAX_ATTEMPTS)…")
                }
                try {
                    Thread.sleep((1000L * attempt).coerceAtMost(8000L))
                } catch (_: InterruptedException) {
                    return false
                }
            }
        }
        return false
    }

    private fun downloadOnce(
        apk: File,
        url: String,
        already: Long,
        listener: Listener
    ): Boolean {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", "GeelyVoiceAssistant-Android9")
            .header("Accept", "application/octet-stream")
            .header("Accept-Encoding", "identity")
        if (already > 0) {
            builder.header("Range", "bytes=$already-")
        }
        http.newCall(builder.build()).execute().use { resp ->
            val code = resp.code
            if (code == 416) {
                return apk.exists() && apk.length() >= MIN_APK_BYTES
            }
            if (!resp.isSuccessful) {
                throw IOException("HTTP $code")
            }
            val body = resp.body ?: throw IOException("пустой ответ")
            val resume = code == 206 && already > 0
            if (!resume && already > 0) {
                apk.delete()
            }
            val remaining = body.contentLength()
            val total = when {
                resume && remaining > 0 -> already + remaining
                !resume && remaining > 0 -> remaining
                else -> parseTotalFromContentRange(resp.header("Content-Range")) ?: -1L
            }
            val raf = RandomAccessFile(apk, "rw")
            if (resume) raf.seek(already) else raf.setLength(0)
            try {
                body.byteStream().use { input ->
                    val buf = ByteArray(32 * 1024)
                    var read = if (resume) already else 0L
                    var n: Int
                    var lastPct = -1
                    while (input.read(buf).also { n = it } >= 0) {
                        raf.write(buf, 0, n)
                        read += n
                        if (total > 0) {
                            val pct = ((read * 100) / total).toInt().coerceIn(0, 99)
                            if (pct != lastPct) {
                                lastPct = pct
                                main.post { listener.onProgress(pct, "Скачивание $pct%") }
                            }
                        }
                    }
                }
            } finally {
                runCatching { raf.close() }
            }
        }
        if (!apk.exists() || apk.length() < MIN_APK_BYTES) {
            throw IOException("файл не докачался")
        }
        return true
    }

    private fun parseTotalFromContentRange(header: String?): Long? {
        if (header.isNullOrBlank()) return null
        val slash = header.lastIndexOf('/')
        if (slash < 0 || slash == header.lastIndex) return null
        return header.substring(slash + 1).toLongOrNull()
    }

    private fun friendly(t: Throwable): String {
        val m = (t.message ?: "").lowercase()
        return when {
            m.contains("ssl") || m.contains("connection abort") ||
                m.contains("connection reset") || m.contains("broken pipe") ||
                m.contains("timeout") || m.contains("timed out") ->
                "Связь оборвалась. Нажмите обновление ещё раз — скачивание продолжится."
            m.startsWith("http") -> "Сервер не отдал файл ($m)"
            else -> "Не удалось скачать. Нажмите обновление ещё раз."
        }
    }

    private fun fetch(url: String, accept: String = "application/json"): String? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "GeelyVoiceAssistant-Android9")
                .header("Accept", accept)
                .header("Cache-Control", "no-cache")
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.string()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "fetch $url: ${t.message}")
            null
        }
    }

    private fun parseVersionJson(raw: String): Remote? = runCatching {
        val o = JSONObject(raw)
        Remote(
            versionCode = o.getInt("versionCode"),
            versionName = o.getString("versionName"),
            apkUrl = o.getString("apkUrl"),
            changelog = o.optString("changelog", "")
        )
    }.onFailure { Log.w(TAG, "version.json: $it") }.getOrNull()

    private fun parseReleaseJson(raw: String): Remote? = runCatching {
        val o = JSONObject(raw)
        val tag = o.optString("tag_name").removePrefix("v")
        val assets = o.optJSONArray("assets") ?: return@runCatching null
        var apkUrl: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name")
            if (name.endsWith(".apk", ignoreCase = true)) {
                apkUrl = a.getString("browser_download_url")
                if (name.contains("platform") || name.contains("release")) break
            }
        }
        apkUrl ?: return@runCatching null
        val code = Regex("""versionCode\s*[:=]\s*(\d+)""")
            .find(o.optString("body"))?.groupValues?.get(1)?.toIntOrNull()
            ?: versionNameToCode(tag)
        Remote(
            versionCode = code,
            versionName = tag.ifBlank { o.optString("name", "new") },
            apkUrl = apkUrl,
            changelog = o.optString("body", "")
        )
    }.onFailure { Log.w(TAG, "releases: $it") }.getOrNull()

    private fun versionNameToCode(name: String): Int {
        val p = name.split('.', '-', '_').mapNotNull { it.toIntOrNull() }
        val a = p.getOrNull(0) ?: 0
        val b = p.getOrNull(1) ?: 0
        val c = p.getOrNull(2) ?: 0
        return a * 10000 + b * 100 + c
    }

    private fun isAllowedUrl(url: String): Boolean {
        val u = url.lowercase()
        return u.startsWith("https://github.com/") ||
            u.startsWith("https://raw.githubusercontent.com/") ||
            u.startsWith("https://objects.githubusercontent.com/") ||
            u.startsWith("https://release-assets.githubusercontent.com/")
    }

    @Suppress("DEPRECATION")
    private fun verifyApk(context: Context, apk: File): String? {
        if (!apk.exists() || apk.length() < MIN_APK_BYTES) return "Файл слишком маленький, скачайте снова"
        val pm = context.packageManager
        val info = pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES)
            ?: return "Это не APK"
        info.applicationInfo?.apply {
            sourceDir = apk.absolutePath
            publicSourceDir = apk.absolutePath
        }
        if (info.packageName != context.packageName) {
            return "Чужой пакет: ${info.packageName}"
        }
        val current = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        val a = info.signatures?.firstOrNull()?.toByteArray()
        val b = current.signatures?.firstOrNull()?.toByteArray()
        if (a != null && b != null && !a.contentEquals(b)) {
            return "Подпись APK другая. Удалите старое приложение и поставьте новый файл с GitHub."
        }
        return null
    }

    private fun install(context: Context, apk: File, listener: Listener) {
        if (installViaSession(context, apk)) return
        if (installViaIntent(context, apk)) return
        listener.onFailure("Система отказала в установке")
    }

    private fun installViaSession(context: Context, apk: File): Boolean = runCatching {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("app", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val pi = PendingIntent.getBroadcast(
                context,
                sessionId,
                Intent(context, UpdateInstallReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT
            )
            session.commit(pi.intentSender)
        }
        true
    }.onFailure { Log.w(TAG, "PackageInstaller: ${it.message}") }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private fun installViaIntent(context: Context, apk: File): Boolean = runCatching {
        val uri = FileProvider.getUriForFile(
            context,
            context.packageName + PROVIDER_AUTH_SUFFIX,
            apk
        )
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = uri
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
            putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, context.packageName)
        }
        context.startActivity(intent)
        true
    }.onFailure { Log.w(TAG, "ACTION_INSTALL_PACKAGE: ${it.message}") }.getOrDefault(false)
}

class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Toast.makeText(context, "Обновление установлено", Toast.LENGTH_LONG).show()
            }
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "ошибка $status"
                Log.e("AppUpdater", "install failed: $msg")
                Toast.makeText(context, "Установка: $msg", Toast.LENGTH_LONG).show()
            }
        }
    }
}
