package com.example.voiceapp3.ui.cx

import com.google.gson.JsonObject

/**
 * Снимок состояния интерфейса — перенос `TOOLS.state` из шелла.
 *
 * Единственный источник истины для значений контролов: `ToolsBackend.state()`
 * читает реестр ГУ, а не локальное состояние экрана. Порядок разрешения
 * значения сохранён один в один с `cxToggleRow` / `cxSegRow` / `cxSliderRow`:
 *
 *   1. наложение — оптимистичные записи и значения, полученные из событий;
 *   2. снимок состояния (`ToolsBackend.state()`, то есть реестр ГУ);
 *   3. значение по умолчанию из спецификации строки.
 *
 * Правило приведения к булеву тоже из шелла: строка `"0"` и число `0` — это
 * `false`, всё прочее непустое — `true`.
 */
class CxState(
    private val json: JsonObject?,
    private val overlay: Map<String, Any> = emptyMap(),
) {

    fun has(key: String): Boolean = overlay.containsKey(key) || json?.has(key) == true

    /**
     * Сырое значение как строка или null, если ключа нет ни в наложении, ни в
     * снимке.
     *
     * НАЛОЖЕНИЕ ВАЖНЕЕ СНИМКА — и это не произвол. В шелле `cxSet` писал
     * оптимистичное значение прямо в `TOOLS.state[key]`, то есть поверх
     * снимка, а следующий снимок его затирал фактическим значением из реестра.
     * Обратный порядок (снимок важнее) ломал бы и оптимистичный отклик, и
     * откат при FAILED: запись не попадала бы в снимок до прихода ответа.
     *
     * Слой обвязки чистит оптимистичные записи по приходе нового `tools.state`,
     * поэтому после readback значение снова берётся из реестра.
     */
    fun raw(key: String): String? {
        overlay[key]?.let { return it.toString() }
        json?.get(key)?.let { e ->
            if (!e.isJsonNull) return e.asString
        }
        return null
    }

    fun bool(key: String, def: Boolean): Boolean {
        val v = raw(key) ?: return def
        if (v == "0" || v == "false") return false
        return v.isNotEmpty()
    }

    fun int(key: String, def: Int): Int = raw(key)?.toDoubleOrNull()?.toInt() ?: def

    fun string(key: String, def: String): String = raw(key)?.takeIf { it.isNotEmpty() } ?: def

    /**
     * Значение-массив как список строк. В шелле это `TOOLS.state.availableSounds`
     * — список файлов, скопированных с USB; он приходит массивом, поэтому
     * обычного `raw()` недостаточно.
     */
    fun list(key: String): List<String> {
        val e = json?.get(key) ?: return emptyList()
        if (!e.isJsonArray) return emptyList()
        val out = ArrayList<String>()
        for (el in e.asJsonArray) if (!el.isJsonNull) out.add(el.asString)
        return out
    }

    /**
     * Оптимистичное значение до прихода нового снимка — аналог
     * `TOOLS.state[key] = value` в `cxSet`. Хранится отдельно от снимка, чтобы
     * перерисовка не стирала его до ответа бэкенда; при FAILED бэкенд присылает
     * фактическое значение и [CxState] пересоздаётся из нового снимка, то есть
     * откат происходит сам.
     */
    fun withPending(key: String, value: Any): CxState {
        val next = LinkedHashMap(overlay)
        next[key] = value
        return CxState(json, next)
    }

    companion object {
        val EMPTY = CxState(null)
        fun of(json: JsonObject?): CxState = if (json == null) EMPTY else CxState(json)
    }
}

/**
 * Подпись под контролом — аналог `cxNote` / `cxResultFor` в шелле: краткий
 * итог последней операции и её статус. Пустая подпись, если операции ещё не
 * было.
 */
data class CxNote(val text: String, val status: String) {
    val isFailure: Boolean get() = status == STATUS_FAILED || status == STATUS_BLOCKED

    companion object {
        const val STATUS_OK = "ok"
        const val STATUS_FAILED = "failed"
        const val STATUS_PARTIAL = "partial"
        const val STATUS_BLOCKED = "blocked"
    }
}

/**
 * Источник подписей. Реализуется слоем, у которого есть последний
 * `ToolsResult`; сам UI от него не зависит, поэтому остаётся проверяемым.
 *
 * Особые случаи из `cxResultFor` переносить сюда: для ключа `lockSound`
 * подходят действия `audio.preview` и `settings.lockSound`, для `unlockSound` —
 * `audio.previewUnlock` и `settings.unlockSound`.
 */
interface CxNoteSource {
    fun noteFor(key: String): CxNote?
}

object NoNotes : CxNoteSource {
    override fun noteFor(key: String): CxNote? = null
}
