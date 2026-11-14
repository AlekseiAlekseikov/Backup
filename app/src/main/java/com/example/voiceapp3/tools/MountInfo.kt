package com.example.voiceapp3.tools

/**
 * iter48 §В — разбор `/proc/<pid>/mountinfo` для доказательства принадлежности слоя монтирования.
 *
 * Почему не `/proc/mounts` (дефект iter47 в `LockSound.kt`):
 *  • в `/proc/mounts` ПЕРВОЕ поле — источник файловой системы (устройство, `/dev/block/...`),
 *    а не путь bind-источника. Код сравнивал его с `/data/local/tmp/carlock_custom*`, то есть
 *    искал свой слой по устройству — совпадения быть не могло. Свой слой считался чужим
 *    (`FOREIGN_MOUNT`, `break`), а чужой — «похожим на наш».
 *  • `/proc/<pid>/mountinfo` содержит то, что нужно для доказательства: ID монтирования,
 *    родительский ID, major:minor, КОРЕНЬ монтирования (для bind это путь источника),
 *    точку монтирования, ФС и источник. Владение доказывается корнем, а не догадкой.
 *
 * Классификация слоя на цели:
 *  • `OURS`    — корень монтирования начинается с нашего staging-префикса
 *    (`/data/local/tmp/carlock_custom…`) — это ровно то, что мы вправе снять;
 *  • `FOREIGN` — корень иной (другое приложение/плагин/система): НЕ снимаем и на него не
 *    монтируем (иначе «затеняем» чужую подмену);
 *  • `BASE`    — корень `/` — это обычное монтирование ФС, а не слой поверх файла. Его нельзя
 *    ни снимать, ни считать доказательством нашего слоя.
 *
 * Модуль чистый (без Android), поэтому проверяется JVM-тестами.
 */
internal object MountInfo {

    /** Наш staging-префикс. Изменение = миграция: слой со старым префиксом станет чужим. */
    const val STAGE_PREFIX = "/data/local/tmp/carlock_custom"

    /**
     * Одна строка mountinfo.
     *
     * Формат: `36 35 98:0 /mnt1 /mnt2 rw,noatime master:1 - ext3 /dev/root rw,errors=continue`
     * (поля 1..5 — ID, родитель, major:minor, корень, точка; далее опции, необязательные
     * `tag:value` до `-`, затем ФС, источник, опции суперблока). `root` и `target` в выводе
     * экранированы восьмерично (`\040` — пробел и т.п.).
     */
    data class Layer(
        val id: String,
        val parentId: String,
        val majMin: String,
        val root: String,
        val target: String,
        val fstype: String,
        val source: String
    )

    enum class Owner { OURS, FOREIGN, BASE }

    /** Раскрытие восьмеричных escape-последовательностей mountinfo (`\040`, `\011`, `\012`, `\134`). */
    fun unescape(field: String): String {
        if (!field.contains('\\')) return field
        val sb = StringBuilder(field.length)
        var i = 0
        while (i < field.length) {
            val c = field[i]
            if (c == '\\' && i + 3 < field.length) {
                val oct = field.substring(i + 1, i + 4)
                val v = oct.toIntOrNull(8)
                if (v != null) {
                    sb.append(v.toChar())
                    i += 4
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    /** Разбор вывода mountinfo. Строки, которые не удалось разобрать, ПРОПУСКАЮТСЯ (не догадки). */
    fun parse(text: String): List<Layer> = text.lineSequence().mapNotNull { parseLine(it) }.toList()

    fun parseLine(line: String): Layer? {
        val f = line.trim().split(' ').filter { it.isNotEmpty() }
        if (f.size < 7) return null
        val sep = f.indexOf("-")
        if (sep < 6 || sep + 2 >= f.size) return null
        val fstype = f[sep + 1]
        val source = unescape(f[sep + 2])
        return Layer(
            id = f[0],
            parentId = f[1],
            majMin = f[2],
            root = unescape(f[3]),
            target = unescape(f[4]),
            fstype = fstype,
            source = source
        )
    }

    /** Слои ИМЕННО на этой цели (в порядке mountinfo; верхний — последний). */
    fun layersAt(text: String, target: String, stagePrefix: String = STAGE_PREFIX): List<Layer> {
        val clean = if (target.length > 1 && target.endsWith("/")) target.dropLast(1) else target
        return parse(text).filter {
            (it.target == clean || it.target == target) && ownerOf(it, stagePrefix) != Owner.BASE
        }
    }

    fun ownerOf(layer: Layer, stagePrefix: String = STAGE_PREFIX): Owner = when {
        layer.root == "/" || layer.root.isEmpty() -> Owner.BASE
        layer.root.startsWith(stagePrefix) -> Owner.OURS
        else -> Owner.FOREIGN
    }

    /** Верхний слой на цели (`null` — слоёв нет; тогда на пути либо файл, либо монтирование ФС). */
    fun topLayer(text: String, target: String, stagePrefix: String = STAGE_PREFIX): Layer? =
        layersAt(text, target, stagePrefix).lastOrNull()

    /**
     * Доказан ли НАШ слой на цели: верхний слой — наш и его корень совпадает с ожидаемым
     * источником (если источник известен).
     */
    fun ownLayerProven(
        text: String,
        target: String,
        expectedSource: String = "",
        stagePrefix: String = STAGE_PREFIX
    ): Boolean {
        val top = topLayer(text, target, stagePrefix) ?: return false
        if (ownerOf(top, stagePrefix) != Owner.OURS) return false
        return expectedSource.isEmpty() || top.root == expectedSource
    }

    /** Есть ли на цели ЧУЖОЙ слой (на него монтировать нельзя). */
    fun foreignOnTarget(text: String, target: String, stagePrefix: String = STAGE_PREFIX): Boolean =
        layersAt(text, target, stagePrefix).any { ownerOf(it, stagePrefix) == Owner.FOREIGN }

    /**
     * Разбор МАРКЕРОВ скрипта (bind): что реально смонтировано.
     * `MOUNTED_OK:<target>|<source> mid=… inode=…` — слой подтверждён mountinfo.
     */
    fun parseMountedOk(output: String): List<Pair<String, String>> =
        output.lineSequence().map { it.trim() }
            .filter { it.startsWith("MOUNTED_OK:") }
            .mapNotNull { line -> parsePair(line.removePrefix("MOUNTED_OK:")) }
            .toList()

    /**
     * iter50 §4.3: ФАКТ слоя после успешного `mount` — независимо от результата проверки.
     *
     * Прежде реестр пополнялся ТОЛЬКО строками `MOUNTED_OK`. Любой отказ проверки (или дефект
     * разбора, который и был найден в iter50) оставлял реально созданный bind-слой без учёта —
     * снять его потом было нечем. Теперь `MOUNT_FACT` печатается после каждого успешного
     * `mount`, и запись попадает в реестр с честным состоянием.
     */
    enum class FactState {
        /** Проверка подтвердила именно наш слой (root + dev:inode). */
        VERIFIED,
        /** Слой есть, но проверка не сошлась (чужой/не тот root, другой inode). */
        UNVERIFIED,
        /** Слой мог появиться, а доказать нельзя (нет таблицы/stat, повреждённая строка). */
        UNKNOWN
    }

    data class MountFact(
        val target: String,
        val source: String,
        val state: FactState,
        val mid: String,
        val root: String,
        val devIno: String,
        val reason: String,
        val mountRc: String
    ) {
        fun wire(): String = "state=${state.name.lowercase()} mid=$mid root=$root " +
            "dev_ino=$devIno reason=$reason mount_rc=$mountRc"

        /**
         * Слой НАДО помнить: он существует (mount вернул 0). `VERIFIED` — подтверждён,
         * `UNVERIFIED`/`UNKNOWN` — запись сохраняется как pending, чтобы её можно было снять
         * позже, не выдавая её за доказанно нашу.
         */
        @Suppress("unused")
        val remember: Boolean get() = true
    }

    private fun parsePair(body: String): Pair<String, String>? {
        val cut = body.substringBefore(" mid=").trim()
        val target = cut.substringBefore('|').trim()
        val source = cut.substringAfter('|', "").trim()
        return if (target.isEmpty()) null else target to source
    }

    /** Разбор строк `MOUNT_FACT:<target>|<stage> state=… mid=… root=… dev_ino=… reason=… mount_rc=…`. */
    fun parseMountFacts(output: String): List<MountFact> =
        output.lineSequence().map { it.trim() }
            .filter { it.startsWith("MOUNT_FACT:") }
            .mapNotNull { line ->
                val rest = line.removePrefix("MOUNT_FACT:")
                val head = rest.substringBefore(' ').trim()
                val target = head.substringBefore('|').trim()
                val source = head.substringAfter('|', "").trim()
                if (target.isEmpty()) return@mapNotNull null
                fun field(k: String): String {
                    val m = Regex("(?:^|\\s)" + Regex.escape(k) + "=(\\S+)").find(rest)
                    return m?.groupValues?.get(1) ?: "unknown"
                }
                val state = when (field("state")) {
                    "verified" -> FactState.VERIFIED
                    "unverified" -> FactState.UNVERIFIED
                    else -> FactState.UNKNOWN
                }
                MountFact(target, source, state, field("mid"), field("root"), field("dev_ino"),
                    field("reason"), field("mount_rc"))
            }
            .toList()

    /**
     * Разбор снятия (restore): по каждому пути — доказано ли, что нашего слоя больше нет.
     * `STOCK_OK:<t>` / `STOCK_FOREIGN_LEFT:<t> layers=N` / `STOCK_UNPROVEN:<t> reason=…`.
     */
    data class RestoreResult(val proven: List<String>, val foreignLeft: List<String>, val unproven: List<String>)

    fun parseRestore(output: String): RestoreResult {
        val proven = ArrayList<String>()
        val foreign = ArrayList<String>()
        val unproven = ArrayList<String>()
        for (raw in output.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("STOCK_OK:") -> proven += line.removePrefix("STOCK_OK:").trim()
                line.startsWith("STOCK_FOREIGN_LEFT:") ->
                    foreign += line.removePrefix("STOCK_FOREIGN_LEFT:").substringBefore(' ').trim()
                line.startsWith("STOCK_UNPROVEN:") ->
                    unproven += line.removePrefix("STOCK_UNPROVEN:").substringBefore(' ').trim()
            }
        }
        return RestoreResult(proven, foreign, unproven)
    }
}
