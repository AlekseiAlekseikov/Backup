package com.example.voiceapp3.tools

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iter54 §6.1 — идентичность СВОЕЙ цепи TTL.
 *
 * Что проверяется исполнением (а не чтением текста):
 *  • реестр установки создаётся один раз, с подтверждением записи и readback;
 *  • «нет токена» и «прочитать нельзя» — РАЗНЫЕ состояния: во втором нельзя ни создавать новую
 *    идентичность, ни менять таблицу;
 *  • при неизвестном реестре production-скрипт не выполняет НИ ОДНОЙ изменяющей команды;
 *  • одноимённая цепь без нашей метки не присваивается и не изменяется (ни `-F`, ни `-X`);
 *  • историческое имя цепи только перечисляется (`TTL_LEGACY_CHAIN`), но не трогается.
 *
 * Чего эти тесты НЕ доказывают: реальное поведение iptables на ГУ, видимость правила в сети и
 * то, что чужая установка физически не сможет угадать токен (его стойкость — 32 бита).
 */
class Iter54TtlOwnershipTest {

    // ─────────────────────────── подставное хранилище ───────────────────────────

    private class FakePrefs(
        val name: String,
        var commitResult: Boolean = true,
        var throwOnCommit: Boolean = false
    ) : SharedPreferences {
        val map = LinkedHashMap<String, Any?>()
        private val removed = Any()

        override fun getAll(): MutableMap<String, *> = LinkedHashMap(map)
        override fun getString(key: String, defValue: String?): String? =
            if (map.containsKey(key)) map[key] as String? else defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?) =
            if (map.containsKey(key)) map[key] as MutableSet<String>? else defValues

        override fun getInt(key: String, defValue: Int) = if (map.containsKey(key)) map[key] as Int else defValue
        override fun getLong(key: String, defValue: Long) =
            if (map.containsKey(key)) map[key] as Long else defValue

        override fun getFloat(key: String, defValue: Float) =
            if (map.containsKey(key)) map[key] as Float else defValue

        override fun getBoolean(key: String, defValue: Boolean) =
            if (map.containsKey(key)) map[key] as Boolean else defValue

        override fun contains(key: String) = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(
            l: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            l: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val ops = mutableListOf<Pair<String, Any?>>()
            override fun putString(key: String, value: String?) = apply { ops += key to value }
            override fun putStringSet(key: String, values: MutableSet<String>?) =
                apply { ops += key to values }

            override fun putInt(key: String, value: Int) = apply { ops += key to value }
            override fun putLong(key: String, value: Long) = apply { ops += key to value }
            override fun putFloat(key: String, value: Float) = apply { ops += key to value }
            override fun putBoolean(key: String, value: Boolean) = apply { ops += key to value }
            override fun remove(key: String) = apply { ops += key to removed }
            override fun clear() = apply { ops += "*" to removed }

            private fun applyToMemory() {
                for ((k, v) in ops) {
                    when {
                        k == "*" -> map.clear()
                        v === removed -> map.remove(k)
                        else -> map[k] = v
                    }
                }
            }

            override fun commit(): Boolean {
                if (throwOnCommit) throw IllegalStateException("disk I/O error")
                applyToMemory()
                return commitResult
            }

            override fun apply() = applyToMemory()
        }
    }

    private fun copies(a: FakePrefs?, b: FakePrefs?) =
        listOf(Prefs2.Copy("cps", a), Prefs2.Copy("dps", b))

    // ─────────────────────────── реестр идентичности ───────────────────────────

    @Test
    fun registryIsCreatedOnceWithConfirmedWriteAndReadback() {
        val a = FakePrefs("cps")
        val b = FakePrefs("dps")
        val cs = copies(a, b)

        val r1 = TtlOwnership.ensure(cs)
        assertEquals("реестр создан: ${r1.wire()}", TtlOwnership.RegistryState.PRESENT, r1.state)
        assertTrue("токен валиден: ${r1.wire()}", TtlOwnership.isValidToken(r1.token))
        assertEquals("имя цепи построено из токена", "VAPP_TTL_" + r1.token, r1.chain)
        assertTrue("имя цепи в пределах iptables: ${r1.chain}", r1.chain!!.length <= 28)
        assertEquals("токен записан в ОБЕ копии", r1.token, a.getString(TtlOwnership.PREF_TOKEN, null))
        assertEquals(r1.token, b.getString(TtlOwnership.PREF_TOKEN, null))
        assertTrue("метка владения содержит токен",
            TtlOwnership.markerOf(r1.token).endsWith(r1.token!!))

        // Повтор НЕ создаёт новую идентичность (иначе каждая перезагрузка оставляла бы свою цепь).
        val r2 = TtlOwnership.ensure(cs)
        assertEquals("токен стабилен", r1.token, r2.token)
    }

    @Test
    fun unknownRegistryNeverCreatesANewIdentity() {
        // Обе копии недоступны: это «прочитать нельзя», а НЕ «токена нет».
        val r = TtlOwnership.ensure(copies(null, null))
        assertEquals("реестр неизвестен: ${r.wire()}", TtlOwnership.RegistryState.UNKNOWN, r.state)
        assertEquals("имени цепи нет", null, r.chain)

        // Запись сорвалась — тоже UNKNOWN и никакого «успеха».
        val a = FakePrefs("cps", commitResult = false)
        val b = FakePrefs("dps", commitResult = false)
        val r2 = TtlOwnership.ensure(copies(a, b))
        assertEquals("неподтверждённая запись не выдаётся за реестр: ${r2.wire()}",
            TtlOwnership.RegistryState.UNKNOWN, r2.state)
        // Значение могло попасть в память копий (платформа пишет до возврата commit), но
        // ИДЕНТИЧНОСТЬ не установлена: имени цепи нет, и таблица по такому реестру не меняется.
        assertEquals("имя цепи не выведено из неподтверждённой записи", null, r2.chain)
    }

    @Test
    fun conflictingCopiesAreUnknownNotAFreshToken() {
        // Одинаковая ревизия, разные значения: выбрать «правильный» токен нельзя.
        val a = FakePrefs("cps").apply {
            map[TtlOwnership.PREF_TOKEN] = "aaaaaaaa"
            map[TtlOwnership.PREF_TOKEN + "#rev"] = 5L
        }
        val b = FakePrefs("dps").apply {
            map[TtlOwnership.PREF_TOKEN] = "bbbbbbbb"
            map[TtlOwnership.PREF_TOKEN + "#rev"] = 5L
        }
        val r = TtlOwnership.ensure(copies(a, b))
        assertEquals("конфликт = неизвестно: ${r.wire()}", TtlOwnership.RegistryState.UNKNOWN, r.state)
        assertEquals("токен не переизбран", "aaaaaaaa", a.getString(TtlOwnership.PREF_TOKEN, null))
        assertEquals("токен не переизбран", "bbbbbbbb", b.getString(TtlOwnership.PREF_TOKEN, null))
    }

    @Test
    fun twoInstallationsDoNotShareChainName() {
        val x = TtlOwnership.ensure(copies(FakePrefs("cps"), FakePrefs("dps")))
        val y = TtlOwnership.ensure(copies(FakePrefs("cps"), FakePrefs("dps")))
        assertNotEquals("имена цепей установок независимы", x.chain, y.chain)
    }

    // ─────────────────────────── production-скрипт ───────────────────────────

    private fun seedBase(st: Iter50Stand) {
        st.mountinfo.writeText(
            "1 0 8:1 / / rw - ext4 /dev/root rw\n" +
                "2 1 253:0 /local /data rw - f2fs /dev/block/sda1 rw\n"
        )
    }

    /**
     * Изменяющие команды iptables/ip6tables: -A, -D, -N, -F, -X, -I, -R.
     * Проба поддержки `-m comment` во временной цепи `VAPP_TTLPROBE` — не изменение таблицы
     * mangle: она создаётся, используется и удаляется в том же проходе.
     */
    private fun mutating(st: Iter50Stand): List<String> =
        st.argv().filterNot { it.contains("VAPP_TTLPROBE") }.filter { line ->
            line.contains("<-A>") || line.contains("<-D>") || line.contains("<-N>") ||
                line.contains("<-F>") || line.contains("<-X>") || line.contains("<-I>") ||
                line.contains("<-R>")
        }

    @Test
    fun unknownRegistryTouchesNothingAtAll() {
        val st = Iter50Stand()
        seedBase(st)
        // В таблице уже есть наша (по имени) цепь и переход: их нельзя тронуть «на всякий случай».
        st.seedRule("iptables", "VAPP_TTL", "-j TTL --ttl-set 65")
        st.seedRule("iptables", "POSTROUTING", "-o wlan0 -j VAPP_TTL")
        val before = st.rules("iptables")

        for (enable in listOf(true, false)) {
            st.resetLogs()
            val script = TtlScript.build(
                enable = enable,
                gen = "unknown",
                ownChain = TtlScript.OUR_CHAIN,
                legacyChain = TtlOwnership.LEGACY_CHAIN,
                registryUnknown = true
            )
            val (rc, out) = st.exec(script, "reg_unknown.sh")
            assertEquals("скрипт дошёл до конца: $out", 0, rc)
            assertTrue("причина названа: $out",
                out.contains("action=skip_all reason=registry_unknown"))
            assertEquals("ни одной изменяющей команды (enable=$enable): ${st.argv()}", emptyList<String>(),
                mutating(st))
            assertFalse("состояние не выдано за применённое (enable=$enable): $out",
                out.contains("VERIFY_V4=OK"))
        }
        assertEquals("таблица не изменилась", before, st.rules("iptables"))
    }

    @Test
    fun ownChainWithTokenIsAdoptedAndLegacyChainIsOnlyReported() {
        val st = Iter50Stand()
        seedBase(st)
        val token = "deadbeef"
        val chain = TtlOwnership.chainFor(token)
        // «Историческая» цепь: имя прежнее, ТЕЛО совпадает байт в байт, метки нет.
        st.seedRule("iptables", "VAPP_TTL", "-j TTL --ttl-set 65")
        st.seedRule("iptables", "POSTROUTING", "-o wlan0 -j VAPP_TTL")

        val script = TtlScript.build(true, gen = token, ownChain = chain,
            legacyChain = TtlOwnership.LEGACY_CHAIN)
        assertEquals("синтаксис: ", 0, st.syntaxCheck(script, "own.sh").first)
        val (_, out) = st.exec(script, "own.sh")

        assertTrue("историческая цепь названа и не тронута: $out",
            out.contains("TTL_LEGACY_CHAIN family=iptables chain=VAPP_TTL"))
        assertTrue("наша уникальная цепь создана и помечена: $out",
            st.rules("iptables").any { it.startsWith("$chain ") && it.contains("voiceapp3:ttl:$token") })
        assertTrue("переход ставится на НАШУ цепь: $out",
            st.rules("iptables").contains("POSTROUTING -o wlan0 -j $chain"))
        assertTrue("историческое правило осталось как было",
            st.rules("iptables").contains("VAPP_TTL -j TTL --ttl-set 65"))
        assertTrue("исторический переход остался как был",
            st.rules("iptables").contains("POSTROUTING -o wlan0 -j VAPP_TTL"))
        assertFalse("историческая цепь не удалялась: ${st.argv()}",
            st.argv().any { it.contains("<-X>") && it.contains("<VAPP_TTL>") })
    }

    @Test
    fun sameNameChainWithoutOurMarkerIsNeverDeclaimed() {
        // Цепь с НАШИМ именем установки, но без метки: значит, её создал кто-то другой
        // (или метка потеряна). Тело совпадает — и всё равно это не доказательство.
        val st = Iter50Stand()
        seedBase(st)
        val token = "0a0b0c0d"
        val chain = TtlOwnership.chainFor(token)
        st.seedRule("iptables", chain, "-j TTL --ttl-set 65")
        st.seedRule("iptables", "POSTROUTING", "-o wlan0 -j $chain")
        val before = st.rules("iptables")

        for (enable in listOf(false, true)) {
            st.resetLogs()
            val script = TtlScript.build(enable, gen = token, ownChain = chain,
                legacyChain = TtlOwnership.LEGACY_CHAIN)
            val (rc, out) = st.exec(script, "coll.sh")
            assertEquals("скрипт дошёл до конца: $out", 0, rc)
            assertTrue("состояние названо: $out", out.contains("v4=unproven") ||
                out.contains("v4=collision"))
            assertFalse("своё не заявлено (enable=$enable): $out",
                out.contains("VERIFY_V4=OK"))
            assertFalse("цепь не очищалась и не удалялась (enable=$enable): ${st.argv()}",
                st.argv().any {
                    (it.contains("<-F>") || it.contains("<-X>")) && it.contains("<$chain>")
                })
            assertEquals("таблица не изменилась (enable=$enable)", before, st.rules("iptables"))
        }
    }

    @Test
    fun registryUnknownScriptStillPassesShellSyntaxCheck() {
        val st = Iter50Stand()
        val (rc, err) = st.syntaxCheck(
            TtlScript.build(false, registryUnknown = true), "syntax_reg_unknown.sh"
        )
        assertEquals("синтаксис production-скрипта: $err", 0, rc)
    }

    @Test
    fun markerOfDefaultIsNotProofOfOwnership() {
        // Совместимость: сборка без токена помечает правило `voiceapp3:ttl:default`. Это
        // по-прежнему метка, но она НЕ уникальна для установки и не должна считаться
        // доказательством в production-пути (там токен обязателен).
        val st = Iter50Stand()
        seedBase(st)
        val script = TtlScript.build(true, gen = "default", ownChain = "VAPP_TTL_PROD",
            legacyChain = TtlOwnership.LEGACY_CHAIN)
        val (_, out) = st.exec(script, "defaultgen.sh")
        assertTrue("сборка с default работоспособна: $out",
            st.rules("iptables").any { it.startsWith("VAPP_TTL_PROD ") })
        assertEquals("default-метка названа именно так",
            "voiceapp3:ttl:default", TtlOwnership.markerOf(null))
    }
}
