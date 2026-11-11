package com.example.voiceapp3.tools

import android.content.Context
import android.content.SharedPreferences
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager

/**
 * Android-адаптеры для ядра [JitOp]. Решений здесь нет: только чтение состояния, запись префов с
 * проверкой результата, запуск скрипта и запрос сети.
 *
 * Границы (мандат §1.2): сетевые команды — только в клике JIT и в продолжении этой же операции;
 * `ip rule` только читается; `ctl.restart zygote` — единственная команда рестарта; никаких
 * persist-свойств JIT, `kill`/`pkill`, `ip rule del`, `flush`, `route`, `settings`,
 * `umount` по умолчанию (`JitRef.MOUNT_STEP_ENABLED=false`).
 */
object JitNet {

    private const val TAG = "CENTRAL_JitNet"

    internal const val KEY_PEDIDO = JitRef.KEY_PEDIDO
    internal const val KEY_AUTO = JitRef.KEY_AUTO
    internal const val KEY_RESTORE = JitRef.KEY_RESTORE
    internal const val KEY_OP = JitRef.KEY_OP
    internal const val PROP_USEJIT = JitRef.PROP_USEJIT
    internal const val DRAIN_MAX_S = JitRef.DRAIN_MAX_S
    internal const val RESTORE_DELAY_MS = JitRef.RESTORE_DELAY_MS
    internal const val CHAIN_TIMEOUT_MS = JitRef.CHAIN_TIMEOUT_MS
    internal const val MOUNT_STEP_ENABLED = JitRef.MOUNT_STEP_ENABLED

    private val log: (String) -> Unit = { AppLog.i(TAG, it) }

    // ─────────────────────────── shell ───────────────────────────

    private object AndroidShell : JitShell {
        override fun exec(command: String, timeoutMs: Int): Pair<Int, String> = runCatching {
            AdbClient.execute(command, timeoutMs)
        }.getOrElse { t -> Pair(1, "ERR_EXCEPTION:${t.javaClass.simpleName}") }

        override fun cacheVisible(): Boolean? = runCatching {
            java.io.File("/proc/self/maps").useLines { l -> l.any { it.contains("dalvik-jit-code-cache") } }
        }.getOrNull()
    }

    internal object AndroidWaiter : JitWaiter {
        override fun sleep(ms: Long) { runCatching { Thread.sleep(ms) } }
        override fun nowMs(): Long = System.currentTimeMillis()
    }

    /**
     * Хранилище операции поверх CPS+DPS.
     *
     * `Prefs2` писать результат `commit()` наружу не умеет и глотает ошибки, поэтому здесь свои
     * два `commit()` с проверкой и обратным чтением. Общая семантика `Prefs2` для остальных
     * функций НЕ меняется (REVIEW §0.1(5)).
     */
    /**
     * Разбор одной записи: что реально видели и что из этого следует.
     *
     * Вынесено в чистую функцию, чтобы поведение при `commit=false`, исключении, расходящихся
     * значениях и «ключ есть только в одном хранилище» проверялось JVM-тестом, а не только чтением
     * кода (REVIEW 3-го захода, п.3).
     */
    internal data class WriteOutcome(
        val confirmed: Boolean,
        val cpsCommitted: Boolean,
        val dpsCommitted: Boolean,
        val readFull: String?,
        val readCps: String?,
        val readDps: String?,
        val reason: String
    )

    /**
     * Правило подтверждения записи снимка.
     *
     * ЧТО ГАРАНТИРУЕТСЯ: значение прочитано обратно ИЗ ОБОИХ хранилищ и совпадает с записанным.
     * Тогда любой будущий читатель (любой приоритет) получит новое значение, даже если один
     * `commit()` вернул `false`.
     *
     * ЧТО НЕ ГАРАНТИРУЕТСЯ (и не выдаётся за гарантию): что `commit()==false` действительно не
     * записал на диск — это поведение Android, а не наше; поэтому `false` при совпавшем чтении
     * считается предупреждением (`reason=readback_ok_commit_false`), а не ошибкой.
     *
     * ОТЛИЧИЕ ОТ РЕФЕРЕНСА (обязательное): референс пишет три ключа одним `commit()` и его
     * результат не проверяет. Здесь запись подтверждается чтением обоих хранилищ, иначе Wi-Fi не
     * выключается — без этого «снимок есть» оставалось бы предположением.
     */
    internal fun judgeWrite(
        value: String?,
        cpsCommitted: Boolean,
        dpsCommitted: Boolean,
        cpsAvailable: Boolean,
        dpsAvailable: Boolean,
        readFull: String?,
        readCps: String?,
        readDps: String?
    ): WriteOutcome {
        if (!cpsAvailable && !dpsAvailable) {
            return WriteOutcome(false, cpsCommitted, dpsCommitted, readFull, readCps, readDps, "no_store")
        }
        // Недоступное хранилище не участвует в проверке (его просто нет), но хотя бы одно должно
        // быть доступно — иначе смотреть некуда (см. ветку `no_store` выше).
        val cpsOk = !cpsAvailable || readCps == value
        val dpsOk = !dpsAvailable || readDps == value
        val reason = when {
            !cpsOk || !dpsOk -> "readback_mismatch"
            readFull != value -> "priority_read_mismatch"
            !cpsCommitted || !dpsCommitted -> "readback_ok_commit_false"
            else -> "ok"
        }
        return WriteOutcome(
            confirmed = reason == "ok" || reason == "readback_ok_commit_false",
            cpsCommitted = cpsCommitted,
            dpsCommitted = dpsCommitted,
            readFull = readFull,
            readCps = readCps,
            readDps = readDps,
            reason = reason
        )
    }

    internal class AndroidStore(private val app: Context) : JitStore {

        private fun cps(): SharedPreferences? = runCatching { Prefs2.cps(app) }.getOrNull()
        private fun dps(): SharedPreferences? = runCatching { Prefs2.dps(app) }.getOrNull()

        /** Приоритет чтения — как у `Prefs2.getString` (CPS, затем DPS): его и проверяем. */
        override fun getString(key: String): String? {
            val c = runCatching { cps()?.getString(key, null) }.getOrNull()
            val d = runCatching { dps()?.getString(key, null) }.getOrNull()
            return c ?: d
        }

        /**
         * Запись одного ключа в оба хранилища + подтверждение чтением ОБОИХ ([judgeWrite]).
         *
         * Общая семантика `Prefs2` для остальных функций не меняется: здесь свои два `commit()`,
         * потому что `Prefs2` результат не возвращает.
         */
        override fun putStringConfirmed(key: String, value: String?): Boolean {
            val c = runCatching { cps() }.getOrNull()
            val d = runCatching { dps() }.getOrNull()
            var cpsOk = false
            var dpsOk = false
            if (c != null) {
                cpsOk = runCatching {
                    val ed = c.edit()
                    if (value == null) ed.remove(key) else ed.putString(key, value)
                    ed.commit()
                }.getOrDefault(false)
            }
            if (d != null) {
                dpsOk = runCatching {
                    val ed = d.edit()
                    if (value == null) ed.remove(key) else ed.putString(key, value)
                    ed.commit()
                }.getOrDefault(false)
            }
            val readCps = runCatching { c?.getString(key, null) }.getOrNull()
            val readDps = runCatching { d?.getString(key, null) }.getOrNull()
            val out = judgeWrite(value, cpsOk, dpsOk, c != null, d != null,
                getString(key), readCps, readDps)
            AppLog.i(TAG, "JIT_STORE put key=$key confirmed=${out.confirmed} reason=${out.reason} " +
                "cps_commit=$cpsOk dps_commit=$dpsOk cps_back=${lenOf(readCps)} dps_back=${lenOf(readDps)} " +
                "priority_back=${lenOf(out.readFull)}")
            return out.confirmed
        }

        override fun getBool(key: String): Boolean? {
            val c = runCatching { cps()?.getBoolean(key, false) }.getOrNull()
            val d = runCatching { dps()?.getBoolean(key, false) }.getOrNull()
            val hasC = runCatching { cps()?.contains(key) }.getOrNull() == true
            val hasD = runCatching { dps()?.contains(key) }.getOrNull() == true
            if (!hasC && !hasD) return null
            return (c == true) || (d == true)
        }

        override fun putBoolConfirmed(key: String, value: Boolean): Boolean {
            val c = runCatching { cps() }.getOrNull()
            val d = runCatching { dps() }.getOrNull()
            var cpsOk = false
            var dpsOk = false
            if (c != null) cpsOk = runCatching { c.edit().putBoolean(key, value).commit() }.getOrDefault(false)
            if (d != null) dpsOk = runCatching { d.edit().putBoolean(key, value).commit() }.getOrDefault(false)
            val readCps = runCatching { c?.contains(key) == true && c!!.getBoolean(key, false) }.getOrDefault(false)
            val readDps = runCatching { d?.contains(key) == true && d!!.getBoolean(key, false) }.getOrDefault(false)
            val ok = (c != null || d != null) &&
                (c == null || readCps == value) && (d == null || readDps == value) &&
                getBool(key) == value
            AppLog.i(TAG, "JIT_STORE put key=$key(bool=$value) confirmed=$ok " +
                "cps_commit=$cpsOk dps_commit=$dpsOk cps_back=$readCps dps_back=$readDps")
            return ok
        }

        private fun lenOf(v: String?): String = if (v == null) "null" else "len${v.length}"
    }

    /** Реальное состояние сети. */
    internal class AndroidNetState(private val app: Context) : JitNetState {

        private fun wm(): WifiManager? = runCatching {
            app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        }.getOrNull()

        override fun wifiOn(): Boolean? = runCatching { wm()?.isWifiEnabled }.getOrNull()

        /**
         * AP поднят? `null` — прочитать НЕ УДАЛОСЬ (исключение), `false` — интерфейса `ap0` нет.
         *
         * Раньше исключение и отсутствие интерфейса давали одинаковый результат: `false`
         * («AP выключен»). Для решения «включать AP» это разные факты, поэтому они разделены —
         * неизвестное состояние не выдаётся за выключенное (REVIEW 3-го захода, п.7).
         */
        override fun apUp(): Boolean? = try {
            val ni = java.net.NetworkInterface.getByName("ap0")
            if (ni == null) false else ni.isUp && ni.inetAddresses.hasMoreElements()
        } catch (t: Throwable) {
            AppLog.w(TAG, "JIT_AP_STATE unreadable err=${t.javaClass.simpleName}:${t.message}")
            null
        }

        /**
         * Запрос включения/выключения Wi-Fi. `setWifiEnabled` возвращает boolean «принято/нет» —
         * это НЕ состояние сети: состояние читается отдельно ([wifiOn]).
         */
        @Suppress("DEPRECATION")
        override fun setWifi(on: Boolean): JitRef.Request {
            val w = wm() ?: return JitRef.Request.notInvoked("wifi_manager_absent")
            return try {
                val accepted = w.setWifiEnabled(on)
                if (accepted) JitRef.Request.accepted("setWifiEnabled($on)=true")
                else JitRef.Request.rejected("setWifiEnabled($on)=false")
            } catch (t: Throwable) {
                JitRef.Request.notInvoked("throw:" + t.javaClass.simpleName + ":" + (t.message ?: ""))
            }
        }

        /**
         * Как `U`/`d` референса: рефлексия `startSoftAp(WifiConfiguration)`, аргумент `null`.
         *
         * Возвращаем ТРИ разных факта (REVIEW 3-го захода, п.7):
         *  • `invoked=false` — метода нет / вызвать нельзя (исключение рефлексии);
         *  • `invoked=true, accepted=false` — метод вернул `Boolean.FALSE` (система отказала);
         *  • `invoked=true, accepted=true` — метод вернул `true` или `null`/`void` без исключения.
         * Отсутствие исключения больше НЕ считается «принято», если метод ответил `false`.
         */
        override fun startAp(): JitRef.Request {
            val w = wm() ?: return JitRef.Request.notInvoked("wifi_manager_absent")
            return try {
                val m = w.javaClass.getDeclaredMethod("startSoftAp", WifiConfiguration::class.java)
                m.isAccessible = true
                when (val r = m.invoke(w, null)) {
                    is Boolean -> if (r) JitRef.Request.accepted("startSoftAp=true")
                                  else JitRef.Request.rejected("startSoftAp=false")
                    null -> JitRef.Request.accepted("startSoftAp=void")
                    else -> JitRef.Request.accepted("startSoftAp=" + r.javaClass.simpleName)
                }
            } catch (t: java.lang.reflect.InvocationTargetException) {
                val c = t.targetException ?: t
                JitRef.Request.notInvoked("target:" + c.javaClass.simpleName + ":" + (c.message ?: ""))
            } catch (t: Throwable) {
                JitRef.Request.notInvoked("throw:" + t.javaClass.simpleName + ":" + (t.message ?: ""))
            }
        }
    }

    // ─────────────────────────── чтение состояния (read-only) ───────────────────────────

    fun read(app: Context, key: String): String? = AndroidStore(app).getString(key)
    fun readBool(app: Context, key: String): Boolean? = AndroidStore(app).getBool(key)

    /** Факт `getprop dalvik.vm.usejit` с rc и причиной (для UI и логов). */
    fun usejitFact(app: Context): JitRef.PropFact = JitOp.readProp(AndroidShell)

    fun codeCacheVisible(): Boolean? = AndroidShell.cacheVisible()

    fun linkUp(app: Context, iface: String = "wlan0"): String {
        val (code, out) = runCatching {
            AdbClient.execute("ip -o addr show $iface 2>/dev/null", 6_000, expectRoot = false)
        }.getOrDefault(Pair(1, ""))
        if (out.contains("ERR_") || (code != 0 && out.isBlank())) return "unknown"
        return if (out.lineSequence().any { it.contains("inet ") }) "up" else "down"
    }

    fun oifWlan0Rules(app: Context): Int {
        val (_, out) = runCatching {
            AdbClient.execute("ip rule show 2>/dev/null | grep -c 'oif wlan0'", 6_000, expectRoot = false)
        }.getOrDefault(Pair(1, ""))
        return out.trim().toIntOrNull() ?: -1
    }

    // ─────────────────────────── операция ───────────────────────────

    fun click(app: Context, want: Boolean, bootTag: String, nowMs: Long): JitOp.Click {
        BgWorker.assertNotMain("JitNet.click")
        val res = JitOp.click(
            store = AndroidStore(app),
            netState = AndroidNetState(app),
            shell = AndroidShell,
            want = want,
            bootTag = bootTag,
            nowMs = nowMs,
            mountStep = JitRef.MOUNT_STEP_ENABLED,
            log = log
        )
        log("JIT_CLICK result=${res.result.name.lowercase()} want=$want boot=$bootTag " +
            "op=${res.op?.log() ?: "none"} rc=${res.run?.exitCode ?: -1} " +
            "outcome=${res.run?.let { JitRef.chainOutcomeLabel(it.outcome) } ?: "none"} " +
            "mount_step=${if (res.mountStep) "on" else "off"} (рестарт фреймворка не заявляем)")
        return res
    }

    fun onProcessStart(app: Context, bootTag: String): JitOp.StartOutcome {
        BgWorker.assertNotMain("JitNet.onProcessStart")
        val out = JitOp.onProcessStart(
            store = AndroidStore(app),
            netState = AndroidNetState(app),
            shell = AndroidShell,
            bootTag = bootTag,
            waiter = AndroidWaiter,
            log = log
        )
        log("JIT_START_RESULT decision=${out.decision.name.lowercase()} " +
            "restore=${out.restore?.summary ?: "none"} verify=${out.verify?.verdict?.let { JitRef.verifyLabel(it) } ?: "none"} " +
            "link=${linkUp(app)} oif_wlan0_rules=${oifWlan0Rules(app)} (правила только читаем)")
        return out
    }

    fun verifyApplied(app: Context, wantFallback: Boolean?): JitOp.VerifyState {
        BgWorker.assertNotMain("JitNet.verifyApplied")
        return JitOp.verifyApplied(AndroidStore(app), AndroidShell, wantFallback, true, log)
    }
}
