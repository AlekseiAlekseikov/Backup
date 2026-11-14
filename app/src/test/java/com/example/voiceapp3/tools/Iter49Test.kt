package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iter49 §5 — «владение доказывается, а не предполагается», и «отказ чтения ≠ правил нет».
 *
 * Уровень: JVM. Проверяется ТЕКСТ сгенерированного скрипта и РАЗБОР его вывода. Фактическое
 * поведение iptables проверялось отдельно на bash-эмуляторе (сценарии S1–S12, см. отчёт
 * `docs/agent/REPORT_iter49_…md`), а на реальной машине ГУ — НЕ проверялось.
 *
 * Чего эти тесты не доказывают: что на ГУ ядро поддерживает target TTL, что правило реально
 * меняет TTL на проводе, что цепочка VAPP_TTL не занята чужой прошивкой. Это отдельные факты.
 */
class Iter49Test {

    private val q = "\"".toString()
    private val d = "$"

    // ───────────────── §5.1: имя цепочки — не доказательство владения ─────────────────

    @Test
    fun chainOwnershipIsDecidedByContentNotByName() {
        val s = TtlScript.build(true)
        // Три различимых состояния вместо «цепочка есть → она наша».
        for (state in listOf("absent", "empty", "ours", "collision", "unknown")) {
            assertTrue("состояние '$state' должно быть выражено в скрипте", state in s)
        }
        assertTrue(
            "наша цепочка опознаётся по ТОЧНОМУ содержимому",
            s.contains("RULE4='-j TTL --ttl-set 65'") && s.contains("RULE6='-j HL --hl-set 65'") &&
                s.contains("= " + q + d + "want" + q)
        )
        assertTrue(
            "одноимённая чужая цепочка не чистится и не удаляется",
            s.contains("action=keep reason=chain_content_not_ours")
        )
        assertTrue(
            "пустая цепочка тоже не наша: происхождение не доказано",
            s.contains("action=keep reason=empty_chain_origin_unproven")
        )
    }

    @Test
    fun targetComparisonIsExactAndNeverSubstring() {
        val s = TtlScript.build(true)
        // Подстрочный поиск задевал бы VAPP_TTL_OTHER; в скрипте должно быть сравнение токена.
        assertFalse(
            "нет grep по подстроке имени цепочки",
            s.contains("grep -- " + q + "-j " + d + "OUR" + q)
        )
        assertTrue("спецификации переходов разбираются awk-сравнением", "jump_specs()" in s)
        // '-g' (goto) — тоже переход на цепочку, и его нельзя путать с чужим правилом.
        assertTrue("goto учитывается как переход", s.contains("-g") || "\"$-g\"" in s || s.contains("goto"))
    }

    @Test
    fun quotedSpecIsRefusedInsteadOfDeletedBlindly() {
        val s = TtlScript.build(false)
        assertTrue(
            "сложная спецификация (кавычки/match) не удаляется по догадке",
            s.contains("reason=quoted_spec_needs_owner")
        )
        // Снятие идёт ровно по нашим переходам, а не «по первому похожему правилу».
        assertTrue("свой переход удаляется точной спецификацией", s.contains("-D POSTROUTING"))
    }

    @Test
    fun foreignChainCollisionSkipsWholeApply() {
        val s = TtlScript.build(true)
        assertTrue(
            "при чужом/неизвестном состоянии НИ ОДНА семья не применяется",
            s.contains("action=skip_all reason=family_not_owned")
        )
        assertTrue("половинчатое включение откатывается", s.contains("TTL_ROLLBACK"))
    }

    // ───────────────── §5.2: отказ чтения ≠ «правил нет» ─────────────────

    @Test
    fun readFailureIsUnknownNotOffOrOk() {
        val out = listOf(
            "TTL_FOREIGN v4 read=failed reason=iptables_S_failed",
            "TTL_FOREIGN v6 read=failed reason=ip6tables_S_failed",
            "TTL_V4=-1 TTL_V6=-1 OWN_V4=-1 OWN_V6=-1 OWN_J4=-1 OWN_J6=-1 " +
                "FOREIGN_V4=-1 FOREIGN_V6=-1 VERIFY_V4=UNKNOWN VERIFY_V6=UNKNOWN " +
                "CHAIN4=unknown CHAIN6=unknown READ_V4=failed READ_V6=failed",
            "TTL_OFF own_v4=-1 own_v6=-1 result=UNKNOWN reason=read_failed " +
                "read_v4=failed read_v6=failed",
            "TTL_CHECK: rule_v4=-1 rule_v6=-1 real=UNKNOWN method=rules_not_wire " +
                "\u2192 READ_UNKNOWN"
        ).joinToString("\n")

        val p = TtlVerify.parse(false, out)
        assertEquals(
            "не «снято», не «OK» и не «нет правил» — FAILED с явной неизвестностью",
            TtlVerify.Verdict.FAILED, p.verdict
        )
        assertNotNull("причина обязана называть неизвестность", p.reason)
        assertTrue("факт отказа чтения сохранён", p.facts["read_v4"] == "failed")
        assertTrue("состояние цепочки — unknown, а не absent", p.facts["chain_v4"] == "unknown")
    }

    @Test
    fun readFailureOnEnableIsNotReportedAsSuccess() {
        val out = listOf(
            "TTL_V4=-1 TTL_V6=-1 OWN_J4=-1 OWN_J6=-1 FOREIGN_V4=-1 FOREIGN_V6=-1 " +
                "VERIFY_V4=UNKNOWN VERIFY_V6=UNKNOWN CHAIN4=unknown CHAIN6=unknown " +
                "READ_V4=failed READ_V6=failed",
            "TTL_CHECK: rule_v4=-1 real=UNKNOWN \u2192 READ_UNKNOWN"
        ).joinToString("\n")
        val p = TtlVerify.parse(true, out)
        assertEquals(TtlVerify.Verdict.FAILED, p.verdict)
        assertNotNull(p.reason)
    }

    // ───────────────── §5.1: подтверждение ON только по точным значениям ─────────────────

    @Test
    fun scopedOkRequiresOwnChainAndExactJumpSpec() {
        val out = listOf(
            "TTL_TRY variant=uplink scope=\"-o wlan0\" carplay=none",
            "TTL_V4=1 TTL_V6=1 OWN_V4=1 OWN_V6=1 OWN_J4=1 OWN_J6=1 FOREIGN_V4=0 FOREIGN_V6=0 " +
                "VERIFY_V4=OK VERIFY_V6=OK CHAIN4=ours CHAIN6=ours READ_V4=ok READ_V6=ok",
            "TTL_SCOPE result=scoped scoped=yes uplink_only=yes uplink=wlan0 " +
                "rule=\"-o wlan0 -j VAPP_TTL\"",
            "TTL_CHECK: rule_v4=65 rule_v6=65 real=UNKNOWN expected=65 method=rules_not_wire " +
                "reason=wire_not_measured \u2192 SCOPED_OK"
        ).joinToString("\n")
        val p = TtlVerify.parse(true, out)
        assertEquals(TtlVerify.Verdict.OK, p.verdict)
        assertTrue("провод не измерялся — так и написано", p.detail.contains("wire=not_measured"))
        assertEquals("ours", p.facts["chain_v4"])
    }

    @Test
    fun v4OnlyIsScopedOkForV4NotFullOk() {
        val out = listOf(
            "TTL_V4=1 TTL_V6=0 OWN_J4=1 OWN_J6=0 VERIFY_V4=OK VERIFY_V6=SKIPPED " +
                "CHAIN4=ours CHAIN6=none READ_V4=ok READ_V6=ok",
            "TTL_SCOPE result=scoped scoped=yes uplink_only=yes uplink=wlan0",
            "TTL_CHECK: rule_v4=65 real=UNKNOWN \u2192 SCOPED_V4_ONLY"
        ).joinToString("\n")
        val p = TtlVerify.parse(true, out)
        assertEquals(TtlVerify.Verdict.OK, p.verdict)
        assertEquals("SCOPED_V4_ONLY", p.facts["verdict"])
    }

    @Test
    fun halfAppliedIsApplyFailedWithRollback() {
        val out = listOf(
            "TTL_ADD family=v4 chain=VAPP_TTL rc=4",
            "TTL_ADD family=v4 jump scope=\"-o wlan0\" rc=4",
            "TTL_ROLLBACK reason=incomplete rc4=4/4 rc6=4/4",
            "TTL_ROLLBACK family=v4 jump_del_rc=1 chain_flush_rc=0 chain_del_rc=0",
            "TTL_V4=0 TTL_V6=0 VERIFY_V4=NOT_APPLIED VERIFY_V6=NOT_APPLIED " +
                "CHAIN4=absent CHAIN6=absent READ_V4=ok READ_V6=ok",
            "TTL_CHECK: rule_v4=-1 rule_v6=-1 real=UNKNOWN \u2192 APPLY_FAILED"
        ).joinToString("\n")
        val p = TtlVerify.parse(true, out)
        assertEquals("включение не состоялось и это сказано прямо", TtlVerify.Verdict.FAILED, p.verdict)
        assertTrue("причина — откат, а не «правило не встало» без деталей", "откат" in p.detail)
    }

    @Test
    fun foreignChainCollisionIsNamedAsCollision() {
        val out = listOf(
            "TTL_TRY action=skip_all reason=family_not_owned v4=collision",
            "TTL_OWN family=v4 state=collision action=keep reason=chain_content_not_ours",
            "TTL_V4=0 TTL_V6=0 OWN_J4=1 OWN_J6=0 VERIFY_V4=COLLISION VERIFY_V6=NOT_APPLIED " +
                "CHAIN4=collision CHAIN6=absent READ_V4=ok READ_V6=ok",
            "TTL_CHECK: rule_v4=-1 real=UNKNOWN \u2192 CHAIN_COLLISION"
        ).joinToString("\n")
        val p = TtlVerify.parse(true, out)
        assertEquals(TtlVerify.Verdict.FAILED, p.verdict)
        assertTrue("про чужие правила сказано, что их не трогали", "не нами" in p.detail)
        assertEquals("collision", p.facts["chain_v4"])
    }

    // ───────────────── §5.2: OFF подтверждается только достоверным чтением ─────────────────

    @Test
    fun offOkOnlyWhenNothingOwnLeftAndReadWasReal() {
        val out = listOf(
            "TTL_OWN family=v4 state=absent removed=ok jumps_left=0 rules_left=0",
            "TTL_OWN family=v6 state=absent removed=ok jumps_left=0 rules_left=0",
            "TTL_V4=0 TTL_V6=0 OWN_J4=0 OWN_J6=0 FOREIGN_V4=0 FOREIGN_V6=0 " +
                "VERIFY_V4=OFF_OK VERIFY_V6=OFF_OK CHAIN4=absent CHAIN6=absent " +
                "READ_V4=ok READ_V6=ok",
            "TTL_OFF own_v4=0 own_v6=0 foreign_v4=0 foreign_v6=0 result=OK v6=OFF_OK",
            "TTL_CHECK: rule_v4=-1 real=UNKNOWN method=rules_not_wire \u2192 OFF_OK"
        ).joinToString("\n")
        assertEquals(TtlVerify.Verdict.OK, TtlVerify.parse(false, out).verdict)
    }

    @Test
    fun offWithUnremovableOwnJumpIsPartialNotOk() {
        val out = listOf(
            "TTL_DEL family=v4 spec=\"-o wlan0 -m comment --comment \"a b\" -j VAPP_TTL\" " +
                "rc=refused reason=quoted_spec_needs_owner",
            "TTL_OWN family=v4 state=partial removed=partial jumps_failed=1 " +
                "reason=jumps_not_removed",
            "TTL_V4=1 TTL_V6=0 OWN_J4=1 OWN_J6=0 VERIFY_V4=FAIL VERIFY_V6=OFF_OK " +
                "CHAIN4=ours CHAIN6=absent READ_V4=ok READ_V6=ok",
            "TTL_OFF own_v4=1 own_v6=0 result=FAILED reason=own_rules_left"
        ).joinToString("\n")
        val p = TtlVerify.parse(false, out)
        assertTrue(
            "не OK: TTL мог остаться включённым",
            p.verdict != TtlVerify.Verdict.OK
        )
        assertTrue("сказано, что переход оставлен и почему", "не разобран" in p.detail)
    }

    // ───────────────── §7: выход из неопределённости — политика, а не текст ─────────────────

    private fun JitFake.Store.seedOldUndefined(op: String, ageMs: Long) = apply {
        val mark = JitRef.OpMark.of(op, true, JitRef.OpMark.UNDEFINED,
            System.currentTimeMillis() - ageMs).wire()
        for (i in listOf(0, 1)) {
            setIn(i, JitRef.KEY_RESTORE, "wifi=1")
            setIn(i, JitRef.KEY_MARK, mark)
        }
    }

    @Test
    fun expiredUndefinedIsAnExplicitExitNotAProofOfNoLateRun() {
        val logs = mutableListOf<String>()
        JitOp.resetStateForTest()
        val store = JitFake.Store().seedOldUndefined("op-old", 16 * 60_000L)
        val net = JitFake.Net(wifi = true)
        val shell = JitFake.QueueShell()

        val c = JitOp.click(store, net, shell, want = true, log = { synchronized(logs) { logs.add(it) } })
        val text = synchronized(logs) { logs.joinToString("\n") }

        assertTrue("клик разрешён (бессрочного запрета нет)", c.blocked == null)
        assertTrue("выход назван явно", text.contains("JIT_OP_ABANDONED") && text.contains("exit="))
        assertTrue("возможность позднего ответа признана", text.contains("late_answer_possible=1"))
        assertFalse(
            "нельзя утверждать, что команда не может выполниться позже",
            text.contains("не может оставаться в очереди")
        )
        assertTrue("старый снимок не применяется", text.contains("snapshot_reused=no"))
        assertEquals("op-old", JitOp.lastAbandonedOp())
    }

    @Test
    fun lateAnswerOfAbandonedOperationIsNotAttributedToTheNewOne() {
        val logs = mutableListOf<String>()
        JitOp.resetStateForTest()
        val store = JitFake.Store().seedOldUndefined("op-old", 16 * 60_000L)
        val net = JitFake.Net(wifi = true)
        JitOp.click(store, net, JitFake.QueueShell(), want = true, log = { })
        // Поздний ответ приходит с id СТАРОЙ операции, а в хранилище уже другая отметка.
        store.setIn(0, JitRef.KEY_MARK, JitRef.OpMark.of("op-new", true, JitRef.OpMark.SENDING,
            System.currentTimeMillis()).wire())
        store.setIn(1, JitRef.KEY_MARK, store.maps[0][JitRef.KEY_MARK])
        JitOp.onLateOutcome(store, "op-old", 0, "done", log = { synchronized(logs) { logs.add(it) } })
        val text = synchronized(logs) { logs.joinToString("\n") }
        assertTrue("ответ помечен как не приписанный", text.contains("attributed=no"))
        assertTrue("видно, какая операция оставлена", text.contains("abandoned_op=op-old"))
    }

    // ───────────────── §4: монтирования — владение по inode, namespace до действий ─────────────────

    private fun src(path: String): String {
        val rel = "src/main/java/com/example/voiceapp3/tools/$path"
        return listOf(java.io.File(rel), java.io.File("app/$rel")).first { it.isFile }.readText()
    }

    @Test
    fun mountOwnershipUsesDeviceAndInodeNotJustThePrefix() {
        val b = LockMountScripts.bindScript("/data/local/tmp/carlock_custom_g1_ab.wav")
        assertTrue("staging dev:inode снимается", b.contains("STAGE_DEV_INO"))
        assertTrue("владение доказывается dev:inode", b.contains("method=dev_ino"))
        assertTrue("несовпадение инода — не наше", b.contains("reason=inode_mismatch"))
        assertTrue("порядок слоёв не выдаётся за гарантию",
            b.contains("top_guaranteed=no"))
    }

    @Test
    fun namespaceIsCheckedBeforeAnyMountAction() {
        val l = src("LockSound.kt")
        val pre = l.indexOf("NS_PRE")
        val act = l.indexOf("AdbClient.execute(script, 20000)")
        assertTrue("есть проверка namespace", pre > 0)
        assertTrue("проверка идёт ДО снятия", pre in 1 until act)
        assertTrue("при несовпадении actions нет", l.contains("reason=namespace_differs"))
    }

    @Test
    fun foreignLayerAboveKeepsOurRecordPending() {
        val l = src("LockSound.kt")
        assertFalse(
            "чужой верхний слой больше не исключается из недоказанных",
            l.contains("it.target !in provenTargets && it.target !in foreignLeft")
        )
        assertTrue("недоказанное остаётся в реестре", l.contains("val unproven = entries.filter { it.target !in provenTargets }"))
        assertTrue("причина названа", l.contains("pending_foreign_layer_above"))
    }

    // ───────────────── §8: ротация не даёт дубликатов, NUL внутри файла ─────────────────

    @Test
    fun rotationDoesNotDuplicateLineWhenNewPartKept() {
        val dir = java.io.File.createTempFile("it49", "").let { it.delete(); it.mkdirs(); it }
        val active = java.io.File(dir, "v.log")
        active.writeText("old\n")
        // `.1` — непустой каталог: переименование поверх невозможно.
        java.io.File(dir, "v.log.1").apply { mkdir(); java.io.File(this, "x").writeText("busy") }
        // `.new` уже содержит данные прошлой неудачной ротации. Раньше строка писалась и в
        // активный файл, и оставалась в `.new` → при следующем повышении `.new` появлялся дубль.
        java.io.File(dir, "v.log.new").writeText("prev-line\n")

        val r = FileLogCore.append(dir, "v.log", "line\n", maxBytes = 4)
        assertTrue("строка не потеряна", r.written)
        assertFalse("ротация не заявлена", r.rotated)
        assertTrue("причина названа", r.reason.contains("rotate_rename_failed"))
        assertFalse("в активный файл дубль не писался",
            active.readText().contains("line\n"))
        assertTrue("строка легла в .new после предыдущих данных",
            java.io.File(dir, "v.log.new").readText() == "prev-line\nline\n")
        dir.deleteRecursively()
    }

    @Test
    fun nulBytesAreFoundInsideFileNotOnlyAtTail() {
        val f = java.io.File.createTempFile("it49nul", ".log")
        f.writeBytes(byteArrayOf('a'.code.toByte(), 0, 'b'.code.toByte(), 0, 'c'.code.toByte(),
            '\n'.code.toByte()))
        assertEquals("нули внутри найдены", 2, FileLogCore.nulInside(f))
        assertEquals("хвост чистый", 0, FileLogCore.tailNulBytes(f))
        f.delete()
    }

    @Test
    fun drainFileIsNotDeletedWhenSpillRestoreFails() {
        val core = src("FileLogCore.kt")
        assertTrue("отказ возврата данных не скрывается",
            core.contains("if (restored) runCatching { work.delete() }"))
    }

    // ───────────────── §9: ABA у владельцев окна ─────────────────

    @Test
    fun lateReadbackChecksOwnersGenerationNotOnlyContent() {
        val w = src("WindowDoors.kt")
        assertTrue("поколение набора владельцев есть", w.contains("ventGen"))
        assertTrue("сверяется содержимое И поколение",
            w.contains("ventGen.get() == genAtAttempt"))
        assertTrue("ABA-проверка видна в журнале", w.contains("aba_checked=yes"))
        assertFalse(
            "одного сравнения содержимого недостаточно",
            w.contains("if (now == ownersAtAttempt && now.isEmpty()) {")
        )
    }

    // ───────────────── §6: unknown доходит до видимого UI ─────────────────

    @Test
    fun backendUnknownReachesTheVisibleToggle() {
        val panel = java.io.File("src/main/java/com/example/voiceapp3/ui/ToolsPanel.kt")
            .takeIf { it.isFile }
            ?: java.io.File("app/src/main/java/com/example/voiceapp3/ui/ToolsPanel.kt")
        val p = panel.readText()
        assertTrue("UI читает трёхзначный <key>State", p.contains("strState(key + \"State\")"))
        assertTrue("unknown из снимка делает тумблер неизвестным",
            p.contains("prefsUnknownByBackend"))
        assertTrue("подпись объясняет состояние без красного текста",
            p.contains("состояние не подтверждено"))
        val backend = src("ToolsBackend.kt")
        assertTrue("backend печатает State и prefsUnknown",
            backend.contains("\"State\"") && backend.contains("prefsUnknown"))
    }

    // ───────────────── §5: скрипт не выходит за свои границы ─────────────────

    @Test
    fun scriptDeclaresItsOwnLimits() {
        val s = TtlScript.build(true)
        assertTrue("правило ≠ TTL на проводе", s.contains("wire_v4=not_measured"))
        assertTrue("метод назван честно", s.contains("method=rules_not_wire"))
        assertTrue("Wi-Fi не выключается", !s.contains("svc wifi"))
        for (bad in listOf("p2p", "rndis", "ncm", "usb", "ap0")) {
            assertTrue("интерфейс '$bad' отклоняется как uplink", bad in s)
        }
    }
}
