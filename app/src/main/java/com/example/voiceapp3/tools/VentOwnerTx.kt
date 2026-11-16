package com.example.voiceapp3.tools

import java.util.concurrent.atomic.AtomicLong

/**
 * iter54 §8 — СОСТОЯНИЕ ВЛАДЕЛЬЦЕВ ОКОН КАК ОДНА ТРАНЗАКЦИЯ.
 *
 * Почему отдельный класс, а не поля внутри `WindowDoors`: у `WindowDoors` инициализатор тянет
 * Android-рантайм (`Handler`, `SystemClock`), поэтому проверить «одна критическая секция» на
 * настоящих параллельных вызовах было невозможно — оставались текстовые проверки. Здесь то же
 * самое состояние живёт без Android-зависимостей, и production использует РОВНО его.
 *
 * Правило одно: любое чтение и запись набора владельцев + поколения идёт под ОДНИМ lock, а
 * `before → переход → установка → поколение` выполняются одной операцией ([planCloseInstall]).
 */
internal class VentOwnerTx {

    /** Итог одной транзакции закрытия: что было, что стало, при каком поколении. */
    data class ClosePlan(
        val before: Set<Int>,
        val after: Set<Int>,
        val added: Set<Int>,
        val allOwners: Map<Int, Set<Int>>,
        val gen: Long,
        val legacy: Boolean,
        val states: Map<Int, Boolean>
    )

    private val lock = Any()
    private val owners = LinkedHashMap<Int, MutableSet<Int>>()

    /**
     * Поколение набора: растёт при КАЖДОЙ мутации, в том числе когда содержимое не изменилось.
     * Это ABA-защита поздних readback: A→B→A даёт равный набор, но другое поколение.
     */
    private val gen = AtomicLong(0)

    fun generation(): Long = gen.get()

    fun snapshot(): Map<Int, Set<Int>> = synchronized(lock) { owners.mapValues { it.value.toSet() } }

    fun snapshotWithGen(): Pair<Map<Int, Set<Int>>, Long> = synchronized(lock) {
        owners.mapValues { it.value.toSet() } to gen.get()
    }

    fun contains(window: Int): Boolean = synchronized(lock) { owners.containsKey(window) }


    /**
     * Единственная точка мутации: блок выполняется под lock, затем двигается поколение.
     *
     * iter60 §7.4: блок получает КОПИЮ, а не живые коллекции. Дефект: вызывающий мог сохранить
     * ссылку на `owners`/`HashSet` из блока и менять её позже, вне lock и без нового поколения —
     * то есть мутация владельцев, невидимая для ABA-проверок (mutable-set escape). Копия под
     * lock и коммит в конце сохраняют атомарность и делают утёкшую ссылку безвредной.
     */
    fun mutate(block: (MutableMap<Int, MutableSet<Int>>) -> Unit): Long = synchronized(lock) {
        val working = LinkedHashMap<Int, MutableSet<Int>>()
        owners.forEach { (w, ds) -> working[w] = HashSet(ds) }
        block(working)
        owners.clear()
        working.forEach { (w, ds) -> owners[w] = HashSet(ds) }
        gen.incrementAndGet()
    }

    /**
     * Выполнить произвольное чтение под тем же lock (без мутации и без нового поколения).
     *
     * iter57 §10: блок получает СНИМОК (копии множеств), а не живые `HashSet`-ы. Раньше
     * вызывающий мог утащить коллекцию `read`-блока и менять её БЕЗ lock и БЕЗ поколения —
     * мутация, невидимая для ABA-проверок (mutable-set escape). Прежний `ownersOf(w)` отдавал
     * живой набор ещё и напрямую; он не использовался и удалён.
     */
    fun <T> read(block: (Map<Int, Set<Int>>) -> T): T = synchronized(lock) {
        block(owners.mapValues { it.value.toSet() })
    }

    /** Только для тестов/восстановления из prefs: задать состояние целиком. */
    fun seed(state: Map<Int, Set<Int>>): Long = synchronized(lock) {
        owners.clear()
        state.forEach { (w, ds) -> owners[w] = HashSet(ds) }
        gen.incrementAndGet()
    }

    /**
     * `before → переход → установка → поколение` ОДНОЙ критической секцией.
     *
     * Дефект, который это закрывает: раньше `before` читалось отдельным lock, набор вычислялся
     * вне его и записывался третьим вызовом. Между шагами другая дверь успевала добавить
     * владельца — её запись затиралась (потерянное обновление), окно оставалось без владельца.
     */
    fun planCloseInstall(
        door: Int,
        window: Int,
        states: Map<Int, Boolean>,
        doorToWindow: Map<Int, Int>
    ): ClosePlan = synchronized(lock) {
        val before = owners[window]?.toSet() ?: emptySet()
        val legacy = !owners.containsKey(window) || before.isEmpty()
        val after = ventOwnersAfterClose(
            door = door, window = window, owners = before,
            doorStates = states, doorToWindow = doorToWindow, legacy = legacy
        )
        val set = owners.getOrPut(window) { HashSet() }
        set.clear()
        set.addAll(after)
        if (set.isEmpty() && legacy) owners.remove(window)
        val genNow = gen.incrementAndGet()
        ClosePlan(
            before = before,
            after = owners[window]?.toSet() ?: emptySet(),
            added = after - (before - door),
            allOwners = owners.mapValues { it.value.toSet() },
            gen = genNow,
            legacy = legacy,
            states = states
        )
    }
}
