package com.example.voiceapp3.tools

/**
 * iter67 §A — «SUCCESS» только при ДВУХ независимых доказательствах.
 *
 * Что было не так (измерено на живой ГУ):
 *
 * ```text
 * VERIFY settings write: key=screen_brightness expected=67 got=67 → SUCCESS   (при ok=false от API)
 * VERIFY EXEC ... rc=0 ... cmd=getprop dalvik.vm.usejit → SUCCESS             (при значении false)
 * ```
 *
 * Причина: `ScreenBrightness.writeSystemInt` печатал успех, если совпал readback, **игнорируя**
 * `ok` от `Settings.System.putInt`, а `AdbClient.classify` печатал успех по `rc=0`, не сравнивая
 * вывод с ожидаемым значением. `rc=0` у `getprop` не значит «свойство равно тому, что мы хотели».
 *
 * Здесь два факта разделены явно:
 *  • `writeAccepted` — что вернул API/команда (запись принята);
 *  • `readbackOk` — совпал ли фактически прочитанный результат с ожидаемым.
 *
 * `SUCCESS` печатается только когда истинны ОБА. Любое расхождение — `PARTIAL` с названной
 * стороной, полностью неудачный случай — `FAILED`. Модуль чистый (без Android), поэтому правило
 * проверяется JVM-тестом на тех же значениях, что даёт боевой код.
 */
object WriteResultVerdict {

    /** Три исхода записи: обе стороны совпали, одна сторона, ни одной. */
    fun label(writeAccepted: Boolean, readbackOk: Boolean): String = when {
        writeAccepted && readbackOk -> "SUCCESS"
        writeAccepted && !readbackOk -> "PARTIAL write=ok readback=fail"
        !writeAccepted && readbackOk -> "PARTIAL write=fail readback=ok"
        else -> "FAILED"
    }

    /** Успех — только два факта одновременно. */
    fun ok(writeAccepted: Boolean, readbackOk: Boolean): Boolean = writeAccepted && readbackOk
}

/**
 * iter67 §A.2 — сравнение вывода команды с ожидаемым значением.
 *
 * Зачем отдельный объект: правило «rc=0 И вывод совпал» должно быть одно на все чтения, которые
 * раньше считались успешными по одному `rc`. Проверяется JVM-тестом без устройства.
 */
object ExecExpect {

    /**
     * @param rc код возврата команды (0 — команда отработала).
     * @param out вывод команды как есть.
     * @param expect ожидаемое значение.
     * @return true — только если команда отработала И вывод совпал с ожидаемым.
     */
    fun evaluate(rc: Int, out: String, expect: String): Boolean =
        rc == 0 && out.trim() == expect.trim()

    /** Строка журнала для [evaluate]: в ней ВСЕГДА есть `expect=`, `got=` и итог. */
    fun logLine(rc: Int, out: String, expect: String, cmd: String = ""): String {
        val got = out.trim()
        val ok = evaluate(rc, got, expect)
        val verdict = if (ok) "SUCCESS" else "FAILED (rc=$rc expect=«$expect» got=«${got.take(40)}»)"
        val tail = if (cmd.isEmpty()) "" else " cmd=${LogRedact.cmd(cmd, 200)}"
        return "EXEC_EXPECT rc=$rc expect=«$expect» got=«${got.take(40)}» ok=$ok → $verdict$tail"
    }
}

/**
 * iter67 §A — результат записи как ДВА независимых факта, а не одно булево.
 *
 * Дефект: прежний `WriteResult(ok = ok && verified, …)` не позволял отличить «API отказал, а
 * readback совпал случайно» от «API принял, но значение не изменилось»; журнал печатал
 * `→ SUCCESS` по одному совпавшему readback.
 *
 * Класс вынесен на уровень пакета (а не вложен в `ScreenBrightness`), чтобы правило проверялось
 * JVM-тестом без Android-зависимостей.
 */
data class WriteResult(
    val writeAccepted: Boolean,
    val readbackOk: Boolean,
    val readback: Int? = null,
    val error: String? = null
) {
    /** Успех — только при двух доказательствах ([WriteResultVerdict.ok]). */
    val ok: Boolean get() = WriteResultVerdict.ok(writeAccepted, readbackOk)
    fun logLabel(): String = WriteResultVerdict.label(writeAccepted, readbackOk)

    companion object {
        /** Совместимость с прежними вызовами: одно булево без второго факта. */
        fun ofLegacy(ok: Boolean, readback: Int?, error: String?): WriteResult =
            WriteResult(ok, ok, readback, error)
    }
}
