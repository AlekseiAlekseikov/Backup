package com.example.voiceapp3.tools

import android.content.Context
import android.content.SharedPreferences
import android.net.wifi.WifiManager

/**
 * iter65 — Android-адаптеры для [JitMech] (механизм референса CentralEXAuto 3.3.9).
 *
 * Здесь нет логики решения: только «куда писать префы», «как спросить Wi-Fi», «через что
 * выполнить команду» и «чем отложить задачу». Вся логика и её тесты — в [JitMech].
 */
object JitMechAndroid {

    private const val TAG = "Desempenho"

    /** Лог не влияет на решение: в JVM-тесте `android.util.Log` — заглушка. */
    internal val log: (String) -> Unit = { line -> runCatching { AppLog.i(TAG, line) } }

    /**
     * Префы поверх ДВУХ хранилищ проекта (`Prefs2.cps` + `Prefs2.dpsOrNull`).
     *
     * Референс пишет три ключа одним `commit()` в одно хранилище. У нас хранилищ два (direct
     * boot), поэтому: запись — в каждое доступное (`commit`), успех = все доступные приняли и
     * хотя бы одно доступно; чтение — CPS, затем DPS (как `getString` у остального проекта);
     * удаление — из обоих. Ни ревизий, ни маркеров, ни счётчиков.
     */
    internal class DualPrefs(
        private val cpsProvider: () -> SharedPreferences?,
        private val dpsProvider: () -> SharedPreferences?
    ) : JitMech.Prefs {

        constructor(app: Context) : this(
            { try { Prefs2.cps(app) } catch (t: Throwable) { null } },
            { try { Prefs2.dpsOrNull(app) } catch (t: Throwable) { null } }
        )

        private fun stores(): List<SharedPreferences> =
            listOfNotNull(runCatching { cpsProvider() }.getOrNull(), runCatching { dpsProvider() }.getOrNull())

        private fun writeAll(edit: (SharedPreferences.Editor) -> SharedPreferences.Editor): Boolean {
            val all = stores()
            if (all.isEmpty()) return false
            var ok = true
            for (sp in all) ok = runCatching { edit(sp.edit()).commit() }.getOrDefault(false) && ok
            return ok
        }

        private fun <T> readFirst(key: String, read: (SharedPreferences) -> T?): T? {
            for (sp in stores()) {
                val v = runCatching { if (sp.contains(key)) read(sp) else null }.getOrNull()
                if (v != null) return v
            }
            return null
        }

        override fun putBool(key: String, value: Boolean): Boolean = writeAll { it.putBoolean(key, value) }
        override fun putString(key: String, value: String): Boolean = writeAll { it.putString(key, value) }
        override fun remove(key: String): Boolean = writeAll { it.remove(key) }

        override fun getBool(key: String): Boolean? = readFirst(key) { sp ->
            // Чужой тип под тем же ключом (мусор старых версий) — не значение, а `null`.
            runCatching { sp.getBoolean(key, false) }.getOrNull()
        }

        override fun getString(key: String): String? = readFirst(key) { sp ->
            runCatching { sp.getString(key, null) }.getOrNull()
        }

        override fun contains(key: String): Boolean =
            stores().any { runCatching { it.contains(key) }.getOrDefault(false) }
    }

    /** `WifiManager.isWifiEnabled` / `setWifiEnabled`. Ошибка ⇒ `null`. */
    internal class AndroidWifi(private val app: Context) : JitMech.Wifi {
        private fun wm(): WifiManager? =
            runCatching { app.getSystemService(Context.WIFI_SERVICE) as WifiManager }.getOrNull()

        override fun isEnabled(): Boolean? = runCatching { wm()?.isWifiEnabled }.getOrNull()

        @Suppress("DEPRECATION")
        override fun setEnabled(on: Boolean): Boolean? = runCatching { wm()?.setWifiEnabled(on) }.getOrNull()
    }

    /** Команда — одним `AdbClient.execute` (как `H1(timeout, cmd)` референса), без доставки файлом. */
    internal object AndroidShell : JitMech.Shell {
        override fun exec(command: String, timeoutMs: Int): Pair<Int, String> = runCatching {
            AdbClient.execute(command, timeoutMs)
        }.getOrElse { t -> Pair(1, "ERR_EXCEPTION:${t.javaClass.simpleName}") }
    }

    /** Отложенная задача в фоновом пуле (у референса — `postDelayed` handler-а + `Thread`). */
    internal object AndroidScheduler : JitMech.Scheduler {
        override fun postDelayed(delayMs: Long, task: () -> Unit) {
            BgWorker.postDelayed("JitMech.restore", delayMs, task)
        }
    }

    /** `/proc/self/maps` этого процесса (`w0/bb`). Ошибка чтения ⇒ `null`. */
    fun selfMaps(): String? = runCatching { java.io.File("/proc/self/maps").readText() }.getOrNull()

    // ─────────────────────────── входы ───────────────────────────

    /** Клик тумблера (`w0.j9.onClick` + `w0/la.run`). Только из фонового потока. */
    fun click(app: Context, want: Boolean): JitMech.Click =
        JitMech.click(DualPrefs(app), AndroidWifi(app), AndroidShell, want, log)

    /** `CentralWatchdogService.onCreate` → 15 с → `w0/e2`. Возвращается сразу. */
    fun watchdogStart(app: Context, alive: () -> Boolean): JitMech.Restaurar? =
        JitMech.watchdogStart(DualPrefs(app), AndroidWifi(app), AndroidScheduler, alive, log)

    /** Сверка `w0/bb` + `w0/r7`. Только из фонового потока (getprop до 8 с). */
    fun verify(app: Context): JitMech.Verify =
        JitMech.verify(DualPrefs(app), AndroidShell, ::selfMaps, log)

    /** Есть ли `jit_pedido` — локально, без shell. */
    fun hasPedido(app: Context): Boolean = DualPrefs(app).contains(JitMech.KEY_PEDIDO)
}
