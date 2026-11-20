package com.example.voiceapp3.tools

import org.junit.Assert.assertTrue
import org.junit.Test

/** iter67c §4.1 — обязательный «сторож размера» (дословно по заданию). */
class Iter67cClaimSizeTest {

    private fun sampleOp() = JitOwn.Op(
        "mwp6qj6c-1-2nh9p", 1_795_191_614_900L, true, JitOwn.State.CREATED,
        identity = JitOwn.ClaimIdentity("mwp6qj6c-1-2nh9p-1795191614900", "", "", ""),
        seq = 1
    )

    @Test
    fun claimCmdFitsInlineLimit() {
        val bytes = JitOwn.claimCmd(sampleOp()).toByteArray(Charsets.UTF_8).size
        // Красный ДО: 44 454 (на 1.0.63), 18 054 после чистки комментариев. Зелёный ПОСЛЕ: ≤ 3500.
        assertTrue("claimCmd = $bytes байт, предел ${JitOwn.INLINE_SAFE_BYTES}", bytes <= JitOwn.INLINE_SAFE_BYTES)
        assertTrue("INLINE_SAFE_BYTES обязан быть 3500 (§1.5), а он ${JitOwn.INLINE_SAFE_BYTES}",
            JitOwn.INLINE_SAFE_BYTES == 3_500)
    }

    @Test
    fun helpersContainNoShellComments() {
        val h = JitOwn.ownHelpersForTest()
        val bad = h.lines().filter { it.trimStart().startsWith("#") }
        assertTrue("в отправляемом тексте ${bad.size} комментариев, первый: ${bad.firstOrNull()}", bad.isEmpty())
    }

    @Test
    fun wrapperOverheadAccounting() {
        val bytes = JitOwn.claimCmd(sampleOp()).toByteArray(Charsets.UTF_8).size
        assertTrue("${bytes + 64} должно быть < 4096", bytes + 64 < 4096)
        // И по точной формуле транспорта — пакет инлайн-пути влезает в 4096.
        val packet = AdbClient.inlinePayloadBytes(JitOwn.claimCmd(sampleOp()))
        assertTrue("пакет claim = $packet байт", packet <= 4_096)
    }

    @Test
    fun sentClaimTextHasNoShellComments() {
        val bad = JitOwn.claimCmd(sampleOp()).lines().filter { it.trimStart().startsWith("#") }
        assertTrue("в тексте claim ${bad.size} комментариев, первый: ${bad.firstOrNull()}", bad.isEmpty())
    }
}
