package com.example.voiceapp3.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iter33h H1.4 — `redactTrace`: строка трассы, попадающая в `_SUPPRESSED … last=`, не должна
 * содержать `hex=`, `hex4=`, `ascii=`, `sum=`, `calc=` (мандат: последняя строка из лога
 * 1.0.53 приходила как `hex=43 4e 58 4e…`).
 */
class RedactTraceTest {

    private val fromLog =
        "ADB_RX attempt=1 variant=0 cmd=CNXN hex4=43 4e 58 4e ascii=CNXN arg0=0x01000001 " +
            "arg1=262144 len=112 hex=43 4e 58 4e 01 00 sum=0 dt=21ms"

    @Test
    fun fieldsRemoved() {
        val red = LogKit.redactTrace(fromLog)
        for (bad in listOf("hex=", "hex4=", "ascii=", "sum=", "calc=")) {
            assertFalse("в redactTrace осталось поле $bad → $red", red.contains(bad))
        }
        // Смысловые поля остаются на месте.
        assertTrue(red.contains("cmd=CNXN"))
        assertTrue(red.contains("arg1=262144"))
        assertTrue(red.contains("dt=21ms"))
    }

    @Test
    fun noFieldsNoChange() {
        val plain = "ADB_HANDSHAKE ok=1 maxdata=262144 ver=0x01000001"
        assertTrue(LogKit.redactTrace(plain) == plain)
    }
}
