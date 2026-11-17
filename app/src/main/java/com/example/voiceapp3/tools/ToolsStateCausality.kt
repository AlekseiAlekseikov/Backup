package com.example.voiceapp3.tools

import com.google.gson.JsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * iter53 §9.1 — ПРИЧИННАЯ СВЕЖЕСТЬ СНИМКА СОСТОЯНИЯ (вместо «два ответа — значит факт»).
 *
 * Что было не так (iter52 §8.2): решение «снимок перетирает клик или нет» принималось по
 * КОЛИЧЕСТВУ несогласных ответов (`disagreements >= 1`). Это эвристика: два ответа, оба
 * посчитанные ДО клика, ничего не доказывают про запись — количество не превращается в
 * причинность. И наоборот, честный факт (backend сохранил не то, что просили) приходил через
 * ту же эвристику, без доказательства, что снимок вообще видел нашу запись.
 *
 * Теперь у каждого пути есть доказуемый признак:
 *
 * ```
 * click(key, value, clickId) → запись → WriteAck(key, clickId, ok, rev = writeRevision после записи)
 * snapshot { rev = writeRevision на момент чтения, $acks[ключ] = WriteAck }
 * ```
 *
 * `ToolsBackend.writeRevision` растёт на КАЖДОЙ записи, а `state()` читается ПОСЛЕ фиксации
 * этой ревизии. Поэтому `snapshot.rev >= ack.rev` доказывает: снимок прочитан после того, как
 * запись завершилась, то есть отражает её результат. Ничего «считать по количеству» не нужно.
 *
 * Важная честность: ревизия — это «сколько записей прошло», а не «сколько ответов пришло».
 * Она не доказывает, что снимок прочитал именно наше значение из хранилища (жизненный цикл
 * записи в prefs — отдельный шаг, см. readback в [ToolsBackend.set]); поэтому подтверждением
 * считается только `ok=true` ack от записи, а `ok=false` показывается как настоящая ошибка
 * записи, без ложного успеха.
 */
object ToolsStateCausality {

    /** Сколько ждём подтверждение записи, прежде чем честно назвать его неподтверждённым. */
    const val UNCONFIRMED_AFTER_MS = 8_000L

    /** Номер клика. Монотонный в пределах процесса; переживает process death как «начали заново». */
    private val clickSeq = AtomicLong(0)

    fun nextClickId(): Long = clickSeq.incrementAndGet()

    init {
        // §9.1 process death/холодный старт: номера начинаются заново. Уникальность в пределах
        // процесса достаточна, потому что pending-клики и ack живут только в памяти процесса.
        clickSeq.set(0)
    }

    /** Выбор владельца, ожидающий доказательства записи. */
    data class PendingClick(
        val key: String,
        val value: Boolean,
        val clickId: Long,
        val requestedAtMs: Long
    )

    /**
     * Результат конкретной записи. `rev` — ревизия ПОСЛЕ записи.
     *
     * iter54 §10: `ok` — сводка «сохранение подтверждено», а рядом лежат ДВА независимых факта:
     * [intent] (сохранилось ли намерение владельца) и [applied] (применился ли side effect).
     * Раньше `ok` был статусом ОБЩЕГО действия, поэтому TTL с сохранённым ON и ожидающим
     * uplink выглядел как «запись не удалась». Старые снимки без `intent`/`applied` читаются
     * по `ok` (совместимость), новые — по фактам.
     */
    data class WriteAck(
        val key: String,
        val clickId: Long,
        val ok: Boolean,
        val rev: Long,
        val detail: String = "",
        val intent: ToggleIntent.Intent =
            if (ok) ToggleIntent.Intent.WRITTEN else ToggleIntent.Intent.WRITE_FAILED,
        val applied: ToggleIntent.Applied = ToggleIntent.Applied.UNKNOWN
    ) {
        fun toJson(): JsonObject = JsonObject().apply {
            addProperty("clickId", clickId)
            addProperty("ok", ok)
            addProperty("rev", rev)
            addProperty("intent", intent.wire)
            addProperty("applied", applied.wire)
            if (detail.isNotEmpty()) addProperty("detail", detail)
        }

        /** Нейтральная подпись состояния для UI (см. [ToggleIntent.note]). */
        fun note(): String = ToggleIntent.note(intent, applied)
    }

    enum class Decision {
        /** Клика нет (или он уже разрешён) — снимок применяется как обычно. */
        APPLY_SNAPSHOT,
        /** Нашей записи ещё нет в доказательствах снимка — держим выбор владельца. */
        KEEP_CLICK,
        /** Запись подтверждена и снимок её увидел, значение совпадает — выбор применён. */
        CLEAR_CONFIRMED,
        /** Запись подтверждена и снимок её увидел, но значение ДРУГОЕ — показываем факт. */
        SNAPSHOT_CONTRADICTS_WRITE,
        /** Запись вернула ошибку — честно показываем «не применено», без ложного успеха. */
        WRITE_FAILED,
        /** Подтверждения нет дольше [UNCONFIRMED_AFTER_MS] — выбор держим, но помечаем. */
        UNCONFIRMED
    }

    /**
     * Решение по одному ключу.
     *
     * @param pending выбор владельца (или `null`)
     * @param ack результат последней записи по этому ключу (или `null`)
     * @param snapshotValue значение из снимка (`null` — в снимке `unknown`/ключа нет)
     * @param snapshotRev ревизия записи на момент ЧТЕНИЯ снимка (`null` — снимок без причины)
     * @param ageMs сколько миллисекунд ждём подтверждения
     */
    fun decide(
        pending: PendingClick?,
        ack: WriteAck?,
        snapshotValue: Boolean?,
        snapshotRev: Long?,
        ageMs: Long,
        unconfirmedAfterMs: Long = UNCONFIRMED_AFTER_MS
    ): Decision {
        // Неизвестность — не факт: она не отменяет выбор владельца ни в какой ветке.
        if (snapshotValue == null) return Decision.KEEP_CLICK
        if (pending == null) return Decision.APPLY_SNAPSHOT

        // Подтверждение обязано быть НАШИМ: тот же КЛЮЧ и тот же номер клика.
        //
        // iter63 §P0-2 (cross-talk): раньше сравнивался только `clickId`, поэтому подтверждение
        // ЛЮБОГО другого переключателя с большим номером объявляло наш выбор «перекрытым»
        // (`APPLY_SNAPSHOT`) — и строка перерисовывалась по снимку, хотя её никто не трогал.
        // Именно это выглядело у владельца как «клик одного включает другие».
        val sameKey = ack != null && ack.key == pending.key
        val ours = sameKey && ack!!.clickId == pending.clickId
        if (!ours) {
            // Пришло подтверждение БОЛЕЕ ПОЗДНЕГО клика ПО ЭТОМУ ЖЕ КЛЮЧУ: наш выбор перекрыт.
            if (sameKey && ack!!.clickId > pending.clickId) return Decision.APPLY_SNAPSHOT
            // iter63 §P0-2: подтверждение ДРУГОГО ключа (или отсутствие своего) не даёт права
            // менять положение этой строки. Выбор владельца держим, и до истечения бюджета это
            // НОРМАЛЬНОЕ состояние (`KEEP_CLICK`), а не «не подтверждено».
            return if (ageMs >= unconfirmedAfterMs) Decision.UNCONFIRMED else Decision.KEEP_CLICK
        }
        // iter54 §10: решает факт СОХРАНЕНИЯ намерения, а не успех side effect.
        when (ack!!.intent) {
            ToggleIntent.Intent.WRITE_FAILED -> return Decision.WRITE_FAILED
            // Неизвестность не опровергает выбор: держим его, пока не истечёт бюджет.
            ToggleIntent.Intent.UNKNOWN ->
                return if (ageMs >= unconfirmedAfterMs) Decision.UNCONFIRMED else Decision.KEEP_CLICK
            ToggleIntent.Intent.WRITTEN -> Unit
        }
        // Снимок без причины не доказывает, что он видел нашу запись.
        if (snapshotRev == null || snapshotRev < ack.rev) return Decision.KEEP_CLICK
        return if (snapshotValue == pending.value) Decision.CLEAR_CONFIRMED
        else Decision.SNAPSHOT_CONTRADICTS_WRITE
    }

    /**
     * Хранилище подтверждений записи — ОДНО на процесс, потому что снимок собирается в
     * `UiBridge`, а записи идут через `ToolsBackend.applyFromUi`. Обе стороны должны видеть один
     * объект: иначе подтверждение «теряется» и UI держит клик бесконечно.
     */
    private val acks = ConcurrentHashMap<String, WriteAck>()

    fun recordAck(ack: WriteAck) {
        if (ack.clickId < 0) return
        acks.merge(ack.key, ack) { old, new -> if (new.clickId >= old.clickId) new else old }
    }

    fun ackFor(key: String): WriteAck? = acks[key]

    /**
     * iter63 §P0-2 (B1) — ПОДТВЕРЖДЕНИЯ, ПРИШЕДШИЕ В СНИМКЕ, ВОССТАНАВЛИВАЮТСЯ В ПАМЯТЬ UI.
     *
     * Дефект, который это закрывает (cross-talk и «запись не подтверждена» после пересоздания):
     * `recordAck` живёт в процессе backend. UI-процесс мог быть пересоздан (Activity/панель),
     * тогда `ackFor(key) == null` для всех ключей; решение по клику уходило в
     * `UNCONFIRMED`/`KEEP_CLICK` по возрасту, хотя подтверждение УЖЕ есть в снимке (`$acks`).
     * Наблюдаемый симптом: сразу после клика по одному тумблеру экран перерисовывался по
     * устаревшим признакам, и соседние строки меняли подпись/положение — «клик одного меняет
     * другие».
     *
     * Правило приёма — то же, что у `recordAck`: побеждает подтверждение с БОЛЬШИМ `clickId`.
     */
    fun applyAcks(fromSnapshot: Map<String, WriteAck>) {
        for ((k, a) in fromSnapshot) {
            if (a.clickId < 0) continue
            acks.merge(k, a) { old, new -> if (new.clickId >= old.clickId) new else old }
        }
    }

    /** Все подтверждения одним объектом для снимка (`$acks`). */
    fun acksJson(): JsonObject = JsonObject().apply {
        for ((k, v) in acks) add(k, v.toJson())
    }

    /** Разбор `$acks` из снимка. Нечитаемое поле — «подтверждений нет», а не «ошибка записи». */
    fun readAcks(o: JsonObject?): Map<String, WriteAck> {
        val src = o ?: return emptyMap()
        val out = HashMap<String, WriteAck>()
        for ((k, e) in src.entrySet()) {
            val je = e.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val clickId = je.get("clickId")?.takeIf { it.isJsonPrimitive }?.let {
                runCatching { it.asLong }.getOrNull()
            } ?: continue
            val ok = je.get("ok")?.takeIf { it.isJsonPrimitive }?.let {
                runCatching { it.asBoolean }.getOrNull()
            } ?: continue
            val rev = je.get("rev")?.takeIf { it.isJsonPrimitive }?.let {
                runCatching { it.asLong }.getOrNull()
            } ?: continue
            val detail = je.get("detail")?.takeIf { it.isJsonPrimitive }
                ?.let { runCatching { it.asString }.getOrNull() } ?: ""
            // Новые снимки несут явные факты; старые — только `ok`, из него и выводим.
            val intent = je.get("intent")?.takeIf { it.isJsonPrimitive }
                ?.let { runCatching { it.asString }.getOrNull() }
                ?.let { ToggleIntent.Intent.of(it) }
                ?: (if (ok) ToggleIntent.Intent.WRITTEN else ToggleIntent.Intent.WRITE_FAILED)
            val applied = je.get("applied")?.takeIf { it.isJsonPrimitive }
                ?.let { runCatching { it.asString }.getOrNull() }
                ?.let { ToggleIntent.Applied.of(it) }
                ?: ToggleIntent.Applied.UNKNOWN
            out[k] = WriteAck(k, clickId, ok, rev, detail, intent, applied)
        }
        return out
    }

    fun resetForTest() {
        acks.clear()
        clickSeq.set(0)
    }
}

/**
 * iter53 §9.1/§9.3 — разбор production-снимка `tools.state` и решения по КАЖДОМУ ключу отдельно.
 *
 * Панель не «изобретает» решения по ходу отрисовки: она зовёт [decideAll] и раскладывает
 * результат по своим картам. Поэтому тест на реальном JSON снимка исполняет ту же самую логику,
 * что и production-путь (а не её копию).
 */
object ToolsSnapshotFold {

    /** Служебные поля снимка: не настройки и не должны попадать в таблицу/решения. */
    private val SERVICE = setOf("seq", "rev", "last", "stateLoaded", "\$keys", "\$acks")

    data class Meta(
        val rev: Long?,
        val seq: Long?,
        val acks: Map<String, ToolsStateCausality.WriteAck>,
        /** Все реальные ключи настроек, пришедшие в снимке (каждый — отдельно). */
        val keys: Set<String>,
        val values: Map<String, Boolean?>,
        val last: JsonObject?
    )

    fun read(snapshot: JsonObject): Meta {
        val rev = snapshot.get("rev")?.takeIf { it.isJsonPrimitive }
            ?.let { runCatching { it.asLong }.getOrNull() }
        val seq = snapshot.get("seq")?.takeIf { it.isJsonPrimitive }
            ?.let { runCatching { it.asLong }.getOrNull() }
        val acks = ToolsStateCausality.readAcks(
            snapshot.get("\$acks")?.takeIf { it.isJsonObject }?.asJsonObject
        )
        val values = LinkedHashMap<String, Boolean?>()
        val keys = LinkedHashSet<String>()
        for ((k, v) in snapshot.entrySet()) {
            if (k.startsWith("$") || k in SERVICE) continue
            // Служебные суффиксы backend (`<key>State`) — не отдельные переключатели, но и не
            // «остальные»: они относятся к своему ключу (см. ToolsPanel.addToggle).
            keys += k
            if (v.isJsonPrimitive && v.asJsonPrimitive.isBoolean) {
                values[k] = v.asBoolean
            } else if (v.isJsonPrimitive && v.asJsonPrimitive.isString) {
                values[k] = null
            } else {
                values[k] = null
            }
        }
        val last = snapshot.get("last")?.takeIf { it.isJsonObject }?.asJsonObject
        return Meta(rev, seq, acks, keys, values, last)
    }

    /**
     * Решения по ключам, для которых есть неподтверждённый выбор владельца.
     * Ключи без клика сюда не попадают: их снимок применяется безусловно.
     */
    fun decideAll(
        snapshot: JsonObject,
        pending: Map<String, ToolsStateCausality.PendingClick>,
        nowMs: Long
    ): Map<String, ToolsStateCausality.Decision> {
        if (pending.isEmpty()) return emptyMap()
        val meta = read(snapshot)
        val out = LinkedHashMap<String, ToolsStateCausality.Decision>()
        for ((key, pend) in pending) {
            out[key] = ToolsStateCausality.decide(
                pending = pend,
                ack = meta.acks[key],
                snapshotValue = meta.values[key],
                snapshotRev = meta.rev,
                ageMs = nowMs - pend.requestedAtMs
            )
        }
        return out
    }
}
