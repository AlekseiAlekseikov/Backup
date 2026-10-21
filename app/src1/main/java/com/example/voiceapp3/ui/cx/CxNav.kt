package com.example.voiceapp3.ui.cx

/**
 * Модель навигации — перенос констант `MENU` и `TOOLS_TREE` из шелла.
 *
 * Два уровня:
 *  * док из одиннадцати вкладок (`MENU`); часть вкладок — пустые панели-якоря,
 *    поверх которых рисуют нативные хосты (Flutter-телеметрия, MapLibre,
 *    CarPlay/Android Auto);
 *  * внутри вкладки «Инструменты» — дерево `TOOLS_TREE` из шести групп и
 *    десяти страниц.
 *
 * Идентификаторы сохранены один в один, поэтому сохранённый в SharedPreferences
 * выбор раздела и маршрутизация событий `dev.*` / `audio.*` продолжают работать.
 */
object CxNav {

    /** Пункт дока. `anchor = true` — панель-якорь под нативный хост. */
    data class Tab(val id: String, val icon: String, val label: String, val anchor: Boolean)

    /** Узел дерева «Инструментов»: либо группа, либо страница. */
    sealed class Node {
        abstract val id: String
        abstract val label: String

        data class Group(
            override val id: String,
            override val label: String,
            val icon: String?,
            val children: List<Node>,
            /** у группы может быть собственная страница (как у `perf` и `dev`) */
            val page: String? = null,
        ) : Node()

        data class Page(
            override val id: String,
            override val label: String,
            val page: String,
        ) : Node()
    }

    /** `MENU` из шелла, порядок сохранён. */
    val TABS: List<Tab> = listOf(
        Tab("quick", "⌂", "Быстрый доступ", false),
        Tab("voice", "🎙", "Голос", false),
        Tab("carplay", "🍎", "CarPlay", true),
        Tab("aa", "🤖", "Android Auto", true),
        Tab("apps", "▦", "Приложения", true),
        Tab("telemetry", "📊", "Телеметрия", true),
        Tab("navi", "🧭", "Навигация", true),
        Tab("tools", "🧰", "Инструменты", false),
        Tab("dashcam", "📹", "Видеорегистратор", false),
        Tab("service", "⚙", "Сервис", false),
        Tab("updates", "↓", "Обновления", false),
    )

    /** `TOOLS_TREE` из шелла, порядок и вложенность сохранены. */
    val TOOLS: List<Node> = listOf(
        Node.Group("interface", "Интерфейс", "screen", listOf(
            Node.Page("screen", "Экран", "screen"),
            Node.Page("notifications", "Панель уведомлений", "notifications"),
            Node.Page("status", "Строка состояния", "status"),
        )),
        Node.Group("car", "Автомобиль", "car", listOf(
            Node.Group("audio", "Аудио", null, listOf(
                Node.Page("lockSound", "Звук блокировки замка", "lockSound"),
                Node.Page("projection", "CarPlay / Android Auto", "projection"),
            )),
            Node.Group("network", "Сеть", null, listOf(
                Node.Page("ttl", "Фиксация TTL", "networkTtl"),
            )),
        )),
        Node.Group("climate", "Климат и окна", "climate", listOf(
            Node.Page("windows", "Окна и двери", "windows"),
        )),
        Node.Group("security", "Безопасность", "security", listOf(
            Node.Page("turnCamera", "Камера при повороте", "turnCamera"),
        )),
        Node.Group("perf", "Быстродействие и фоновая работа", "perf", emptyList(),
            page = "performance"),
        Node.Group("dev", "Для разработчика", "settings", emptyList(),
            page = "devDiagnostics"),
    )

    /** Все страницы дерева обходом в глубину — как раскрытое меню. */
    val PAGES: List<Node.Page> = flatten(TOOLS)

    /** Узел по идентификатору. */
    fun find(id: String, nodes: List<Node> = TOOLS): Node? {
        for (n in nodes) {
            if (n.id == id) return n
            if (n is Node.Group) find(id, n.children)?.let { return it }
        }
        return null
    }

    /**
     * Путь от корня до узла — аналог `cxOpenPath`: выбор вложенной страницы
     * раскрывает все группы над ней, иначе подсветка слева теряется.
     */
    fun pathTo(id: String, nodes: List<Node> = TOOLS,
               acc: List<Node> = emptyList()): List<Node>? {
        for (n in nodes) {
            val here = acc + n
            if (n.id == id) return here
            if (n is Node.Group) pathTo(id, n.children, here)?.let { return it }
        }
        return null
    }

    private fun flatten(nodes: List<Node>): List<Node.Page> {
        val out = ArrayList<Node.Page>()
        for (n in nodes) {
            when (n) {
                is Node.Page -> out.add(n)
                is Node.Group -> {
                    n.page?.let { p -> out.add(Node.Page(n.id, n.label, p)) }
                    out.addAll(flatten(n.children))
                }
            }
        }
        return out
    }
}
