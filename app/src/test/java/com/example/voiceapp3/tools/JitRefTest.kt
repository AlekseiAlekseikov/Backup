package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Разбор и формулы ядра — по DEX референса (iter36: только клиентский Wi-Fi, без AP).
 *
 * Здесь проверяется то, что видно в `w0/r7`, `w0/j9`, `w0/la`, `onCreate`, `w0/e2`, `w0/bb`+`w0/j5`:
 * литерал скрипта, форматы снимка (новый и старый), трактовка `getprop`, формула `ok`,
 * разбор `/proc/self/maps` по правам, положение тумблера.
 */
class JitRefTest {

    private fun code(rel: String): String =
        listOf(File(rel), File("app/$rel")).first { it.isFile }.readText()

    private fun codeNoComments(rel: String): String =
        code(rel).lines()
            .filterNot { val s = it.trim(); s.startsWith("*") || s.startsWith("//") || s.startsWith("/*") }
            .joinToString("\n")

    // ─────────────────────────── литерал `w0/la.run` ───────────────────────────

    @Test
    fun chainScriptIsReferenceLiteralByteForByte() {
        // Литерал из DEX (`w0/la` pswitch_10e), 392 символа, как было в APK.
        val dex = "\n" +
            "        svc wifi disable\n" +
            "        _n=0\n" +
            "        while [ \$_n -lt 20 ]; do\n" +
            "          [ \"\$(ip rule show | grep -c 'oif wlan0')\" = \"0\" ] && break\n" +
            "          sleep 1; _n=\$((_n + 1))\n" +
            "        done\n" +
            "        for _m in \$(grep ' /data/app/' /proc/mounts | awk '{print \$2}'); do\n" +
            "          umount -l \"\$_m\" 2>/dev/null\n" +
            "        done\n" +
            "        setprop dalvik.vm.usejit true\n" +
            "        setprop ctl.restart zygote\n    "
        assertEquals(dex, JitRef.referenceScript(true))
        assertEquals(392, JitRef.referenceScript(true).length)
        assertEquals("литерал тот же и для false (меняется только значение)", dex.replace("usejit true", "usejit false"),
            JitRef.referenceScript(false))
    }

    @Test
    fun executedScriptIsTheSameLiteral() {
        assertEquals(JitRef.referenceScript(true), JitRef.chainScript(true))
        assertEquals(JitRef.referenceScript(false), JitRef.chainScript(false))
        assertTrue("шаг umount -l присутствует и исполняется", JitRef.chainScript(true).contains("umount -l"))
        assertEquals("mount_step=present", JitRef.mountStepNote())
    }

    @Test
    fun scriptOrderAndBoundaries() {
        val lines = JitRef.chainScript(true).trim().lines().map { it.trim() }.filter { it.isNotEmpty() }
        assertEquals("svc wifi disable", lines.first())
        assertEquals("последняя команда — рестарт zygote", "setprop ctl.restart zygote", lines.last())
        assertEquals(1, lines.count { it == "setprop ctl.restart zygote" })
        assertEquals(1, lines.count { it.startsWith("setprop dalvik.vm.usejit") })
        val propIdx = lines.indexOfFirst { it.startsWith("setprop dalvik.vm.usejit") }
        val umountIdx = lines.indexOfFirst { it.startsWith("umount -l") }
        assertTrue("umount до setprop", umountIdx in 0 until propIdx)
        assertTrue("слив правил до umount", lines.indexOfFirst { it.contains("ip rule show") } < umountIdx)
    }

    @Test
    fun noForbiddenPropertiesOrCommandsInScript() {
        for (want in listOf(true, false)) {
            val s = JitRef.chainScript(want)
            for (bad in listOf("persist.dalvik.vm.usejit", "persist.sys.dalvik.vm.usejit",
                "usejitprofiles", "ip rule del", "ip rule flush", "ndc ", "settings ", "setenforce",
                "kill ", "pkill", "route add", "iptables", "startSoftAp", "ap0")) {
                assertFalse("в скрипте нет «$bad»", s.contains(bad))
            }
        }
    }

    // ─────────────────────────── снимок `jit_restaurar` ───────────────────────────

    @Test
    fun newSnapshotIsMinimalWifiOnly() {
        assertEquals(JitRef.WifiSnapshot(true), JitRef.parseSnapshot("wifi=1")!!.snap)
        assertEquals(JitRef.WifiSnapshot(false), JitRef.parseSnapshot("wifi=0")!!.snap)
        assertEquals("wifi_only", JitRef.parseSnapshot("wifi=1")!!.format)
        assertEquals("wifi=1", JitRef.WifiSnapshot(true).wire())
        assertEquals("wifi=0", JitRef.WifiSnapshot(false).wire())
        assertFalse("в новом формате нет поля ap", JitRef.WifiSnapshot(true).wire().contains("ap="))
    }

    @Test
    fun legacySnapshotIsAcceptedAndApIgnored() {
        // Старый формат референса читается, но поле ap не используется: никаких вызовов AP нет.
        assertEquals(JitRef.WifiSnapshot(true), JitRef.parseSnapshot("wifi=1,ap=0")!!.snap)
        assertEquals(JitRef.WifiSnapshot(true), JitRef.parseSnapshot("wifi=1,ap=1")!!.snap)
        assertEquals(JitRef.WifiSnapshot(false), JitRef.parseSnapshot("wifi=0,ap=1")!!.snap)
        assertEquals("legacy_ap_ignored", JitRef.parseSnapshot("wifi=1,ap=1")!!.format)
    }

    @Test
    fun brokenSnapshotIsNotExecuted() {
        // `matches()` без trim: ничего не обрезаем и не «исправляем».
        assertNull(JitRef.parseSnapshot(null))
        assertNull(JitRef.parseSnapshot(""))
        assertNull(JitRef.parseSnapshot(" wifi=1"))
        assertNull(JitRef.parseSnapshot("wifi=1 "))
        assertNull(JitRef.parseSnapshot("wifi=2"))
        assertNull(JitRef.parseSnapshot("wifi=1,ap=0,extra=1"))
        assertNull(JitRef.parseSnapshot("WIFI=1,AP=0"))
        assertNull(JitRef.parseSnapshot("ap=1"))
    }

    @Test
    fun noApAnywhereInJitCode() {
        // Решение владельца: AP/CarplayMonitor/connadaptor в JIT не переносятся.
        val core = codeNoComments("src/main/java/com/example/voiceapp3/tools/JitNetCore.kt")
        val net = codeNoComments("src/main/java/com/example/voiceapp3/tools/JitNet.kt")
        for (bad in listOf("startSoftAp", "apUp", "apFailedAtMs", "carplayBusy", "AUTO_WIFI_ENABLED",
            "AP_STATE_CHANGED", "AP_ATTEMPTS", "AP_LATCH", "openAp", "connadaptor", "CarplayMonitor",
            "WIFI_AP_STATE")) {
            assertFalse("ядро не содержит «$bad»", core.contains(bad))
            assertFalse("адаптер не содержит «$bad»", net.contains(bad))
        }
        assertFalse("адаптер не поднимает AP", net.contains("startAp"))
        assertFalse("адаптер не трогает ap0", net.contains("\"ap0\""))
    }

    // ─────────────────────────── `getprop` ───────────────────────────

    @Test
    fun parsePropTruthTable() {
        assertEquals(JitRef.Prop.TRUE, JitRef.parseProp(0, "true").value)
        assertEquals(JitRef.Prop.TRUE, JitRef.parseProp(0, "true\n").value)
        assertEquals(JitRef.Prop.FALSE, JitRef.parseProp(0, "false").value)
        // ОТЛИЧИЕ: нечитаемое/странное — UNKNOWN, а не «выключено».
        assertEquals(JitRef.Prop.UNKNOWN, JitRef.parseProp(1, "true").value)
        assertEquals(JitRef.Prop.UNKNOWN, JitRef.parseProp(0, "").value)
        assertEquals(JitRef.Prop.UNKNOWN, JitRef.parseProp(0, "ERR_TIMEOUT").value)
        assertEquals(JitRef.Prop.UNKNOWN, JitRef.parseProp(0, "true\nfalse").value)
        assertEquals(JitRef.Prop.UNKNOWN, JitRef.parseProp(0, "yes").value)
        assertEquals("rc_nonzero", JitRef.parseProp(2, "").reason)
        assertEquals("transport_error", JitRef.parseProp(0, "ERR_X").reason)
    }

    // ─────────────────────────── формула `w0/r7` целиком ───────────────────────────

    private fun prop(v: JitRef.Prop) = JitRef.PropFact(v, v.name.lowercase(), 0, "ok")

    @Test
    fun verifyTableForConfirmedValues() {
        // ok = (pedido == getprop) && (!pedido || maps != Boolean.FALSE)
        val t = JitRef.Prop.TRUE
        val f = JitRef.Prop.FALSE
        // pedido = true — все 3 состояния maps
        assertEquals("true/true/cache=true → ok", JitRef.Verify.OK, JitRef.verify(true, prop(t), true))
        assertEquals("true/true/cache=null → ok", JitRef.Verify.OK, JitRef.verify(true, prop(t), null))
        assertEquals("true/true/cache=false → НЕ ok", JitRef.Verify.CACHE_MISSING,
            JitRef.verify(true, prop(t), false))
        assertEquals("true/false (cache=true) → НЕ ok", JitRef.Verify.NOT_APPLIED,
            JitRef.verify(true, prop(f), true))
        assertEquals(JitRef.Verify.NOT_APPLIED, JitRef.verify(true, prop(f), false))
        assertEquals(JitRef.Verify.NOT_APPLIED, JitRef.verify(true, prop(f), null))
        // pedido = false — все 3 состояния maps
        assertEquals("false/false/cache=false → ok (в референсе cache не мешает)", JitRef.Verify.OK,
            JitRef.verify(false, prop(f), false))
        assertEquals(JitRef.Verify.OK, JitRef.verify(false, prop(f), true))
        assertEquals(JitRef.Verify.OK, JitRef.verify(false, prop(f), null))
        assertEquals("false/true → НЕ ok", JitRef.Verify.NOT_APPLIED, JitRef.verify(false, prop(t), true))
        assertEquals(JitRef.Verify.NOT_APPLIED, JitRef.verify(false, prop(t), false))
        assertEquals(JitRef.Verify.NOT_APPLIED, JitRef.verify(false, prop(t), null))
    }

    @Test
    fun verifyUnknownPropertyIsNotAVerdict() {
        val u = JitRef.PropFact(JitRef.Prop.UNKNOWN, "", 137, "rc_nonzero")
        for (pedido in listOf(true, false)) {
            for (cache in listOf(true, false, null)) {
                assertEquals("неизвестное свойство не выдаётся за результат",
                    JitRef.Verify.UNKNOWN, JitRef.verify(pedido, u, cache))
            }
        }
    }

    @Test
    fun verifyLabelsAndHints() {
        assertEquals("ok", JitRef.verifyLabel(JitRef.Verify.OK))
        assertEquals("not_applied", JitRef.verifyLabel(JitRef.Verify.NOT_APPLIED))
        assertEquals("cache_missing", JitRef.verifyLabel(JitRef.Verify.CACHE_MISSING))
        assertEquals("unknown", JitRef.verifyLabel(JitRef.Verify.UNKNOWN))
        // Подпись для существующей строки панели: успех и неизвестность не пугают зря.
        assertNull(JitRef.verifyHint(JitRef.Verify.OK, prop(JitRef.Prop.TRUE), true))
        assertNull(JitRef.verifyHint(JitRef.Verify.UNKNOWN,
            JitRef.PropFact(JitRef.Prop.UNKNOWN, "", 1, "empty"), null))
        assertTrue(JitRef.verifyHint(JitRef.Verify.NOT_APPLIED, prop(JitRef.Prop.FALSE), true)!!
            .startsWith("JIT не применился"))
        assertTrue(JitRef.verifyHint(JitRef.Verify.CACHE_MISSING, prop(JitRef.Prop.TRUE), false)!!
            .contains("JIT-кэша"))
        assertTrue("отсутствие данных о карте названо", JitRef.verifyHint(
            JitRef.Verify.CACHE_MISSING, prop(JitRef.Prop.TRUE), null)!!.contains("карта не прочитана"))
    }

    // ─────────────────────────── `/proc/self/maps` (w0/bb → w0/sa → w0/j5.d) ───────────────────────────

    private val jitLine = "7c4b000000-7c4b040000 r-xp 00000000 fd:04 1234 /data/dalvik-cache/arm64/jit-cache"
    private val jitLineNoX = "7c4b040000-7c4b080000 rw-p 00000000 fd:04 1234 /data/dalvik-cache/arm64/jit-cache"
    private val otherLine = "7c4b100000-7c4b100400 r--p 00000000 fd:04 1234 /system/framework/foo.oat"

    @Test
    fun cacheExecutableUsesPermissionsNotFileName() {
        // r-xp — исполняемая карта
        assertEquals(true, JitRef.cacheExecutable(jitLine))
        // rwxp / rwx — тоже (символьный класс референса допускает w и s)
        assertEquals(true, JitRef.cacheExecutable(jitLine.replace("r-xp", "rwxp")))
        assertEquals(true, JitRef.cacheExecutable(jitLine.replace("r-xp", "r-xs")))
        assertEquals(true, JitRef.cacheExecutable(jitLine.replace("r-xp", "rwxs")))
        // rw-p — файл тот же, но права БЕЗ x: это НЕ успех (прежний код считал это успехом)
        assertEquals(false, JitRef.cacheExecutable(jitLineNoX))
        // смешанные строки: достаточно одной исполняемой
        assertEquals(true, JitRef.cacheExecutable("$jitLine\n$jitLineNoX"))
        assertEquals(true, JitRef.cacheExecutable("$jitLineNoX\n$jitLine\n$otherLine"))
        // слово rx внутри другого слова не считается (границы слова в регулярке)
        assertEquals(false, JitRef.cacheExecutable("7c-7d rwxpX 0000 /data/dalvik-cache/jit-cache"))
    }

    @Test
    fun cacheExecutableNullIsNotFalse() {
        // Пустой список строк в j5.d ⇒ null, а не false.
        assertEquals(null, JitRef.cacheExecutable(""))
        assertEquals(null, JitRef.cacheExecutable("\n\n  \n"))
        // Чтение не удалось (catchall_67) ⇒ null.
        assertEquals(null, JitRef.cacheExecutable(null))
        // Фильтр w0/sa не нашёл строк JIT-кэша ⇒ excerpt пуст ⇒ null (JIT-кэш ещё не отображён).
        assertEquals(null, JitRef.cacheExecutableFromMaps("$otherLine\n7c4b200000-7c4b204000 rw-p 0 fd:04 1 [stack]"))
        assertEquals(null, JitRef.cacheExecutableFromMaps(null))
        assertEquals(null, JitRef.cacheExecutableFromMaps(""))
    }

    @Test
    fun mapsFilterAndJoinIsLikeReference() {
        // dalvik-jit-code-cache — второе имя, которое ищет `w0/sa`.
        val dalvik = "7c4b000000-7c4b040000 r-xp 00000000 fd:04 9 /data/dalvik-cache/arm64/dalvik-jit-code-cache"
        assertEquals(true, JitRef.cacheExecutableFromMaps("$otherLine\n$dalvik"))
        assertEquals(true, JitRef.cacheExecutableFromMaps("$jitLineNoX\n$jitLine"))
        assertEquals(false, JitRef.cacheExecutableFromMaps("$jitLineNoX"))
        assertTrue("фильтр w0/sa", JitRef.isCacheLine(jitLine))
        assertTrue(JitRef.isCacheLine(dalvik))
        assertFalse(JitRef.isCacheLine(otherLine))
    }

    @Test
    fun realProcSelfMapsOfThisProcessIsRead() {
        // Не фейк: читается настоящий /proc/self/maps процесса JVM, где идёт тест.
        val text = File("/proc/self/maps").readText()
        assertTrue("карта процесса читается и не пуста", text.contains(" r-xp ") || text.contains(" r--p "))
        val v = JitRef.cacheExecutableFromMaps(text)
        // В JVM-процессе строк jit-cache нет ⇒ null (не false): это и есть семантика j5.d.
        assertNull("нет строк jit-cache ⇒ null", v)
        // Без фильтра `w0/sa` любая исполняемая строка дала бы `true` — ровно поэтому фильтр
        // по имени кэша обязателен, а имя без проверки прав ничего не значит.
        assertEquals(true, JitRef.cacheExecutable(text))
    }

    // ─────────────────────────── тумблер ───────────────────────────

    @Test
    fun confirmedStateAndSwitchPosition() {
        assertEquals("on", JitRef.confirmedState(prop(JitRef.Prop.TRUE)))
        assertEquals("off", JitRef.confirmedState(prop(JitRef.Prop.FALSE)))
        assertEquals("unknown", JitRef.confirmedState(null))
        assertEquals("unknown", JitRef.confirmedState(JitRef.PropFact(JitRef.Prop.UNKNOWN, "", 1, "x")))
        // Подтверждённый факт побеждает preference в обе стороны.
        assertTrue(JitRef.switchPosition("on", preference = false))
        assertFalse(JitRef.switchPosition("off", preference = true))
        // Без факта — намерение водителя.
        assertTrue(JitRef.switchPosition("unknown", preference = true))
        assertFalse(JitRef.switchPosition("unknown", preference = false))
        // Программное обновление: переставляем только когда факт известен и отличается.
        assertEquals(true, JitRef.switchUpdate(false, "on"))
        assertEquals(false, JitRef.switchUpdate(true, "off"))
        assertNull("уже верно — не трогаем", JitRef.switchUpdate(true, "on"))
        assertNull("уже верно — не трогаем", JitRef.switchUpdate(false, "off"))
        assertNull("неизвестно — не выдаём за факт", JitRef.switchUpdate(true, "unknown"))
        assertNull(JitRef.switchUpdate(false, "unknown"))
    }

    // ─────────────────────────── итог транспорта ───────────────────────────

    @Test
    fun chainOutcomeDoesNotCallTimeoutSuccess() {
        assertEquals(JitRef.ChainOutcome.REPLY_RECEIVED, JitRef.chainOutcome(0, ""))
        assertEquals(JitRef.ChainOutcome.CHANNEL_LOST, JitRef.chainOutcome(137, "ERR_CHANNEL_LOST"))
        assertEquals(JitRef.ChainOutcome.CHANNEL_LOST, JitRef.chainOutcome(1, ""))
        assertEquals("reply_received", JitRef.chainOutcomeLabel(JitRef.ChainOutcome.REPLY_RECEIVED))
        assertEquals("channel_lost", JitRef.chainOutcomeLabel(JitRef.ChainOutcome.CHANNEL_LOST))
    }

    // ─────────────────────────── в ядре нет своих механизмов ───────────────────────────

    @Test
    fun noOwnMechanismsLeftInCore() {
        val core = codeNoComments("src/main/java/com/example/voiceapp3/tools/JitNetCore.kt")
        for (bad in listOf("HOLD_", "ARMED", "SENT_STATE", "enum class Phase", "jit_op", "KEY_OP",
            "ClickResult", "StartDecision", "bootId", "boot_id", "PID_CHECK", "TTL_MS",
            "FileObserver", "watchdog", "jit_verified", "jit_pending_verify", "MOUNT_STEP_ENABLED")) {
            assertFalse("ядро не содержит «$bad»", core.contains(bad))
        }
        // Никакого «лечения перезагрузкой» и никаких persist-свойств JIT.
        assertFalse(core.contains("persist."))
        assertFalse(core.contains("usejitprofiles"))
        // Единственная команда рестарта — в литерале скрипта.
        assertEquals(1, Regex("ctl\\.restart zygote").findAll(core).count())
    }

    @Test
    fun adapterIsIndependentStoresAndRealRemove() {
        val net = codeNoComments("src/main/java/com/example/voiceapp3/tools/JitNet.kt")
        assertTrue("удаление ключа настоящим remove", net.contains(".remove(key)"))
        assertTrue("каждое хранилище пишется отдельно", net.contains("for (s in all)"))
        assertTrue("снимок подтверждается чтением", net.contains("override fun confirmSnapshot"))
        assertFalse("адаптер не выключает Wi-Fi", net.contains("setWifi(false"))
        assertFalse("адаптер не трогает маршруты", net.contains("ip rule del"))
        assertTrue("карта памяти читается, а не выдумывается", net.contains("/proc/self/maps"))
    }
}
