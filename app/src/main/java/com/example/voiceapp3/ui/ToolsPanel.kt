package com.example.voiceapp3.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.CheckBox
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.example.voiceapp3.R
import com.example.voiceapp3.nav.NavTheme
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.math.roundToInt

/**
 * Нативная вкладка «Инструменты» — этап 5a (замена HTML-панели tools в index.html).
 *
 * Что перенесено в 5a: дерево настроек (видимые узлы как в JS), страницы
 * «Экран», «Панель уведомлений», «Строка состояния», «Окна и двери»,
 * «Сеть и TTL», справки (кнопка «?») и примечания результата под контролами.
 *
 * Запись — тот же канал `settings.change` → ToolsBackend, что и в JS. Значения
 * берутся из снимка `tools.state` (приходит из UiBridge.pushToolsState), а при
 * его отсутствии — из значения по умолчанию, как в cxToggleRow/cxSegRow.
 *
 * Страницы «Звук блокировки», «CarPlay / Android Auto», «Быстродействие» и
 * «Для разработчика» — этапы 5b/5c. До них вкладка в index.html остаётся
 * HTML-панелью (NATIVE_TABS без `tools`), поэтому эта панель не показывается.
 */
class ToolsPanel(
    private val activity: Activity,
    /** Отправка события в JS-мост, как у остальных нативных вкладок. */
    private val send: (name: String, json: String) -> Unit,
) {
    private val view: FrameLayout = activity.findViewById(R.id.toolsPanel)
    private val nav: LinearLayout = activity.findViewById(R.id.toolsNav)
    private val page: LinearLayout = activity.findViewById(R.id.toolsPage)
    private val density = activity.resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())

    private var palette: NavTheme = NavTheme.byId(null)
    private var selBg: Int = QuickPanel.SEL_SEPIA.first
    private var selFg: Int = QuickPanel.SEL_SEPIA.second
    private var visible = false

    /** Выбранный узел дерева (как TOOLS.sel). */
    private var sel = "screen"
    /** Раскрытые группы (как TOOLS.open). */
    private val open = HashMap<String, Boolean>().apply { put("interface", true) }
    /** Снимок настроек (как TOOLS.state), без служебного поля `last`. */
    private val state = JsonObject()
    /** Результат последнего действия (как TOOLS.last). */
    private var last: JsonObject? = null
    private val sliderPending = HashMap<String, Runnable>()

    init {
        view.visibility = View.GONE
        applyPalette()
    }

    /** Тема шелла (ui.theme): палитра и перерисовка видимой панели. */
    fun setTheme(id: String?) {
        palette = NavTheme.byId(id)
        val pair = QuickPanel.SEL_BY_THEME[palette.id] ?: QuickPanel.SEL_SEPIA
        selBg = pair.first
        selFg = pair.second
        applyPalette()
        if (visible) rebuild()
    }

    /** Геометрия из JS (`content.frame`), как у остальных нативных вкладок. */
    fun onFrame(active: Boolean, x: Float, y: Float, w: Float, h: Float, fresh: Boolean) {
        if (!active) {
            hide()
            return
        }
        if (!visible) show()
        else if (fresh) rebuild()
        if (w >= 1f && h >= 1f) {
            val lp = view.layoutParams as? FrameLayout.LayoutParams
                ?: FrameLayout.LayoutParams(0, 0)
            lp.width = w.roundToInt()
            lp.height = h.roundToInt()
            lp.leftMargin = x.roundToInt()
            lp.topMargin = y.roundToInt()
            view.layoutParams = lp
        }
    }

    /**
     * Снимок `tools.state` из UiBridge (JSON-объект, опционально с полем `last`).
     * Слияние — как Object.assign в toolsApplyState.
     */
    fun onToolsState(json: String) {
        val d = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return
        for ((k, v) in d.entrySet()) {
            if (k == "last") {
                last = if (v.isJsonObject) v.asJsonObject else last
            } else {
                state.add(k, v)
            }
        }
        if (soundBusy) finishSoundIfDone()
        if (visible) rebuild()
    }

    private fun show() {
        visible = true
        ensureOpen(sel)
        rebuild()
        view.visibility = View.VISIBLE
        view.bringToFront()
    }

    private fun hide() {
        if (!visible) return
        visible = false
        view.visibility = View.GONE
    }

    private fun applyPalette() {
        view.setBackgroundColor(palette.page1)
        activity.findViewById<TextView>(R.id.toolsTitle).setTextColor(palette.ink)
        activity.findViewById<TextView>(R.id.toolsSubtitle).setTextColor(palette.muted)
        if (visible) rebuild()
    }

    private fun rebuild() {
        if (!isVisibleNode(findNode(sel))) {
            sel = firstVisiblePage()?.id ?: sel
        }
        ensureOpen(sel)
        buildNav()
        buildPage()
    }

    // ── Дерево настроек ───────────────────────────────────────────────────

    private fun buildNav() {
        nav.removeAllViews()
        ToolsCatalog.TREE.filter { isVisibleNode(it) }.forEachIndexed { i, cat ->
            if (i > 0) nav.addView(divider(), matchWrap(dp(1f)))
            addNavNode(cat, depth = 0)
        }
    }

    private fun addNavNode(node: ToolsCatalog.Node, depth: Int) {
        if (node.children.isNotEmpty()) {
            val isOpen = open[node.id] == true
            nav.addView(navButton(node.label, bold = depth == 0, indent = depth, selected = false) {
                open[node.id] = !isOpen
                buildNav()
            }, matchWrap())
            if (isOpen) {
                node.children.filter { isVisibleNode(it) }.forEach { addNavNode(it, depth + 1) }
            }
        } else {
            nav.addView(navButton(node.label, bold = depth == 0, indent = depth, selected = sel == node.id) {
                select(node.id)
            }, matchWrap())
        }
    }

    private fun navButton(label: String, bold: Boolean, indent: Int, selected: Boolean, onClick: () -> Unit): TextView =
        text(label, 14f, if (selected) selFg else palette.ink, bold).apply {
            setPadding(dp(12f) + dp(14f) * indent, dp(10f), dp(8f), dp(10f))
            background = if (selected) rounded(selBg, 10f) else null
            setOnClickListener { onClick() }
        }

    private fun select(id: String) {
        val node = findNode(id) ?: return
        if (!hasPage(node)) return
        sel = id
        ensureOpen(id)
        buildNav()
        buildPage()
        page.parent?.let { (it as? android.widget.ScrollView)?.scrollTo(0, 0) }
    }

    // ── Страница ──────────────────────────────────────────────────────────

    private fun buildPage() {
        page.removeAllViews()
        val node = findNode(sel)
        val key = node?.page
        when {
            node == null || key == null -> Unit
            key == "screen" -> pageScreen()
            key == "notifications" -> pageNotifications()
            key == "status" -> pageStatus()
            key == "windows" -> pageWindows()
            key == "networkTtl" -> pageNetworkTtl()
            key == "lockSound" -> pageLockSound()
            key == "performance" -> pagePerformance()
            key == "projection" -> pageProjection()
            key == "devDiagnostics" -> pageDevDiagnostics()
            else -> addText("Раздел переносится", "Эта страница переносится в этапах 5b/5c.")
        }
    }

    private fun pageScreen() {
        addHead("page.screen", "screen")
        addSect("page.sectionScreen")
        addToggle("imm", "Полный экран", "desc.imm", def = true)
        addDiv()
        addToggle("wake", "Запуск при пробуждении", "desc.wake", def = false)
        addDiv()
        addToggle("cb", "Полоса климата", "desc.cb", def = false)
        addDiv()
        addSlider("bright", "Яркость", min = 5, max = 100, unit = "%", def = 50)
        addDiv()
        addToggle("remember", "Запоминать яркость по фарам", "desc.remember", def = false)
        addDiv()
        addSeg(
            "rest", "Время заставки", def = "0", width = 420,
            opts = listOf("0" to "Выкл", "10" to "10 с", "30" to "30 с", "60" to "1 мин", "120" to "2 мин"),
        )
        addDiv()
        addSect("page.sectionTheme")
        addSeg("gu", ToolsCatalog.TEXT["page.themeHu"].orEmpty(), def = "light", width = 260,
            opts = listOf("light" to "Светлая", "dark" to "Тёмная"))
        addDiv()
        addToggle("follow", "Следовать теме ГУ", "desc.follow", def = true)
        addDiv()
        addText(ToolsCatalog.TEXT["page.themeApp"].orEmpty(), ToolsCatalog.TEXT["page.themeAppHint"].orEmpty())
        addThemes()
    }

    private fun pageNotifications() {
        addHead("page.notifications", "notifications")
        addSect("page.sectionNotifications")
        addToggle("notifBatt", "Батарея автомобиля", "desc.notifBatt", def = false)
        addDiv()
        addToggle("notifTemp", "Наружная температура", "desc.notifTemp", def = false)
        addDiv()
        addToggle("notifWifi", "Wi‑Fi", "desc.notifWifi", def = false)
        addDiv()
        addText(ToolsCatalog.TEXT["page.notifHelpTitle"].orEmpty(), ToolsCatalog.TEXT["page.notifHelpText"].orEmpty())
    }

    private fun pageStatus() {
        addHead("page.status", "status")
        addSect("page.sectionStatus")
        addToggle("hvacBatt", "Процент заряда", "desc.hvacBatt", def = false)
        addDiv()
        addToggle("hvacTemp", "Цифровой климат-контроль", "desc.hvacTemp", def = false)
        addDiv()
        addToggle("hvacExtTemp", "Цифровая наружная температура", "desc.hvacExtTemp", def = false)
        addDiv()
        addText(ToolsCatalog.TEXT["page.statusHelpTitle"].orEmpty(), ToolsCatalog.TEXT["page.statusHelpText"].orEmpty())
    }

    private fun pageWindows() {
        addHead("page.windows", "windows")
        addSect("page.sectionWindowsVent")
        addToggle("windowDriverOnDoor", "Стекло водителя при открытии двери", "desc.windowDriverOnDoor", def = false)
        addDiv()
        addToggle("windowOppositeOnDoor", "Противоположное стекло", "desc.windowOppositeOnDoor", def = false)
        addDiv()
        addText(ToolsCatalog.TEXT["page.windowsHelpTitle"].orEmpty(), ToolsCatalog.TEXT["page.windowsHelpText"].orEmpty())
    }

    private fun pageNetworkTtl() {
        addHead("page.networkTtl", "networkTtl")
        addSect("page.sectionTtl")
        addToggle("ttl65", "Фиксация TTL 65 (обход раздачи)", "desc.ttl65", def = false)
        addDiv()
        addText(ToolsCatalog.TEXT["page.ttlHelpTitle"].orEmpty(), ToolsCatalog.TEXT["page.ttlHelpText"].orEmpty())
    }

    // ── Звук блокировки замка (5b, как cxPageLockSound / cxSoundAction) ──

    /** Состояние операции: __soundOpBusy / __soundOpMsg / __soundOpSuccess в JS. */
    private var soundBusy = false
    private var soundMsg = ""
    private var soundOk: Boolean? = null
    /** Значение `last` на момент запуска: результат ждём только новый. */
    private var lastAtStart: String? = null

    private fun startSound(event: String, json: String, msg: String) {
        if (soundBusy) return
        soundBusy = true
        soundMsg = msg
        soundOk = null
        lastAtStart = last?.toString()
        if (visible) rebuild()
        send(event, json)
    }

    /** Системный пикер закрыт без выбора: снимаем busy, иначе кнопки останутся заблокированы. */
    fun onSoundPickCancelled() {
        if (!soundBusy) return
        soundBusy = false
        soundMsg = ""
        soundOk = null
        if (visible) rebuild()
    }

    private fun isSoundAction(action: String) = action.startsWith("audio.")

    /** Итог операции из tools.state.last — тексты как в toolsApplyResult (JS). */
    private fun finishSoundIfDone() {
        val r = last ?: return
        if (r.toString() == lastAtStart) return
        val action = r.get("action")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        if (!isSoundAction(action)) return
        val ok = r.get("ok")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true
        val err = (r.get("error")?.takeIf { it.isJsonPrimitive }?.asString
            ?: r.get("summary")?.takeIf { it.isJsonPrimitive }?.asString).orEmpty()
        soundBusy = false
        soundOk = ok
        soundMsg = when (action) {
            "audio.copyFromUsb" ->
                if (ok) "Копирование завершено! Звуки добавлены в список."
                else "Ошибка копирования: " + err.ifEmpty { "файлы не найдены" }
            "audio.restaurarSomTravaPersistente", "audio.restoreFactoryUnlock" ->
                if (ok) "Заводской звук успешно восстановлен!"
                else "Ошибка сброса звука: " + err.ifEmpty { "сбой операции" }
            "audio.selectFile", "audio.selectUnlockFile", "audio.selectSoundName" ->
                if (ok) "Звуковой файл успешно выбран и применен!"
                else "Ошибка выбора файла: " + err.ifEmpty { "файл не скопирован" }
            "audio.preview", "audio.previewUnlock" ->
                if (ok) "Звук воспроизведен в салоне"
                else "Ошибка воспроизведения: " + err.ifEmpty { "проверьте файл" }
            else ->
                if (ok) "Настройки успешно применены!"
                else "Ошибка применения: " + err.ifEmpty { "сбой" }
        }
    }

    private fun soundBanner() {
        val bg = when {
            soundBusy -> "#2c5282"
            soundOk == true -> "#22543d"
            else -> "#742a2a"
        }
        val icon = when {
            soundBusy -> "⏳ "
            soundOk == true -> "✓ "
            else -> "⚠️ "
        }
        page.addView(text(icon + soundMsg, 14f, Color.WHITE, bold = true).apply {
            background = rounded(Color.parseColor(bg), 8f)
            setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
        }, matchWrap().apply { setMargins(0, dp(8f), 0, dp(8f)) })
    }

    private fun soundButton(label: String, kind: Int, onClick: () -> Unit): TextView {
        val (bg, fg) = when (kind) {
            0 -> palette.btn to palette.onbtn
            2 -> Color.parseColor("#E5484D") to Color.WHITE
            else -> palette.card2 to palette.ink
        }
        return text(label, 12.5f, fg, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
            background = rounded(bg, 10f)
            setOnClickListener { onClick() }
        }
    }

    private fun soundButtons(vararg buttons: TextView) {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6f), 0, dp(10f))
        }
        buttons.forEachIndexed { i, b ->
            row.addView(b, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { if (i > 0) leftMargin = dp(8f) })
        }
        page.addView(HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }, matchWrap())
    }

    private fun soundInfoRow(title: String, sub: String, control: View? = null) {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10f), 0, dp(10f))
        }
        val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(title, 14.5f, palette.ink, bold = true))
        if (sub.isNotEmpty()) col.addView(text(sub, 12f, palette.muted, bold = false))
        row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (control != null) {
            row.addView(control, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { leftMargin = dp(12f) })
        }
        page.addView(row, matchWrap())
    }

    /** Список скопированных звуков; выбор шлёт audio.selectSoundName (как cxSelectSoundItem). */
    private fun soundSpinner(isLock: Boolean, avail: List<String>, cur: String): Spinner {
        val items = listOf("-- Выберите звук из скопированных с USB --") + avail
        val sp = Spinner(activity)
        sp.adapter = object : ArrayAdapter<String>(activity, android.R.layout.simple_spinner_item, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getView(position, convertView, parent) as TextView).apply {
                    setTextColor(palette.ink)
                    textSize = 14f
                }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getDropDownView(position, convertView, parent) as TextView).apply {
                    setTextColor(palette.ink)
                    setBackgroundColor(palette.card)
                    textSize = 14f
                    setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
                }
        }
        val idx = items.indexOf(cur)
        if (idx > 0) sp.setSelection(idx, false)
        sp.background = rounded(palette.card2, 10f, palette.line, 1f)
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                // Программная установка выбранного пункта и плейсхолдер не отправляются.
                if (pos <= 0 || items[pos] == cur || soundBusy) return
                val json = JsonObject().apply {
                    addProperty("name", items[pos])
                    addProperty("isLock", isLock)
                }.toString()
                startSound("audio.selectSoundName", json, "Применение звука...")
            }
            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }
        return sp
    }

    private fun soundName(key: String, fallback: String): String =
        strState("${key}Name")?.takeIf { it.isNotEmpty() }
            ?: strState("${key}Path")?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }
            ?: fallback

    private fun pageLockSound() {
        addHead("page.lockSound", "lockSound")
        if (soundMsg.isNotEmpty()) soundBanner()
        val exempt = page.childCount
        val avail = state.get("availableSounds")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString } ?: emptyList()

        // Блок 0: USB
        addSect("page.sectionUsb")
        soundInfoRow(
            "USB-накопитель",
            "Вставьте флешку с файлами .wav, .mp3 или .ogg и нажмите «Копировать»",
            soundButton("КОПИРОВАТЬ", 0) { startSound("audio.copyFromUsb", "{}", "Копирование звуков с USB-накопителя...") },
        )
        addDiv()

        // Блок 1: открытие дверей
        addSect("page.sectionUnlockSound")
        addToggle("unlockSound", "Звук открытия / разблокировки", "desc.unlockSound", def = false)
        addDiv()
        val unlockName = soundName("unlockSound", "По умолчанию (заводской)")
        soundInfoRow("Звук открытия", unlockName)
        addDiv()
        soundInfoRow("Список скопированных звуков", "Доступно: ${avail.size} файлов")
        page.addView(soundSpinner(false, avail, unlockName), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(8f) })
        addDiv()
        soundButtons(
            soundButton("ВЫБРАТЬ ИЗ ФАЙЛОВ", 0) { startSound("audio.selectUnlockFile", "{}", "Выбор и сохранение звука открытия...") },
            soundButton("ПРОСЛУШАТЬ", 1) { startSound("audio.previewUnlock", "{}", "Воспроизведение звука открытия в салоне...") },
            soundButton("СБРОС К ЗАВОДСКОМУ", 2) { startSound("audio.restoreFactoryUnlock", "{}", "Сброс звука открытия к заводскому...") },
        )
        addDiv()

        // Блок 2: закрытие замка
        addSect("page.sectionLockSoundClose")
        addToggle("lockSound", "Кастомный звук замка", "desc.lockSound", def = false)
        addDiv()
        val lockName = soundName("lockSound", "Заводской (Geely OEM)")
        soundInfoRow("Выбранный звук", lockName)
        addDiv()
        soundInfoRow("Список скопированных звуков", "Доступно: ${avail.size} файлов")
        page.addView(soundSpinner(true, avail, lockName), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(8f) })
        addDiv()
        soundButtons(
            soundButton("ВЫБРАТЬ ИЗ ФАЙЛОВ", 0) { startSound("audio.selectFile", "{}", "Выбор и сохранение звука закрытия...") },
            soundButton("ПРОСЛУШАТЬ", 1) { startSound("audio.preview", "{}", "Воспроизведение звука закрытия в салоне...") },
            soundButton("СБРОС К ЗАВОДСКОМУ", 2) { startSound("audio.restaurarSomTravaPersistente", "{}", "Восстановление заводского звука Geely OEM...") },
        )
        addDiv()

        // Блок 3: громкость и задержка
        addSect("page.sectionVolDelay")
        addSlider("lockSoundVol", "Громкость", min = 5, max = 100, unit = "%", def = 80)
        addDiv()
        addSeg(
            "lockSoundDelay", "Задержка звука", def = "0", width = 360,
            opts = listOf("0" to "0 мс", "150" to "150 мс", "300" to "300 мс"),
        )
        addDiv()
        addText(ToolsCatalog.TEXT["page.lockSoundHelpTitle"].orEmpty(), ToolsCatalog.TEXT["page.lockSoundHelpText"].orEmpty())

        // Во время операции контролы выключены (как disAttr в JS); заголовок и банер — нет.
        if (soundBusy) {
            for (i in exempt until page.childCount) {
                val c = page.getChildAt(i)
                c.alpha = 0.4f
                setSubtreeEnabled(c, false)
            }
        }
    }

    private fun setSubtreeEnabled(v: View, enabled: Boolean) {
        v.isEnabled = enabled
        (v as? ViewGroup)?.let { g -> for (i in 0 until g.childCount) setSubtreeEnabled(g.getChildAt(i), enabled) }
    }

    // ── Общие элементы 5c ─────────────────────────────────────────────────

    private fun okButton(label: String, onClick: () -> Unit): TextView =
        text(label, 13f, Color.parseColor("#18963E"), bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
            background = rounded(Color.parseColor("#EBF7EE"), 12f)
            setOnClickListener { onClick() }
        }

    private fun statePill(label: String, ok: Boolean): TextView =
        text(label, 12f, if (ok) Color.parseColor("#18963E") else palette.muted, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
            background = rounded(if (ok) Color.parseColor("#EBF7EE") else palette.card2, 100f)
        }

    /** Текстовый блок моноширинным шрифтом в прокручиваемой рамке (как <pre> в JS). */
    private fun monoBox(initial: String, color: String, heightDp: Float): TextView {
        val tv = TextView(activity).apply {
            setText(initial)
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(Color.parseColor(color))
            setTextIsSelectable(true)
            setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
            background = rounded(Color.parseColor("#1E1E1E"), 10f)
        }
        page.addView(ScrollView(activity).apply { addView(tv) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp)).apply {
                topMargin = dp(10f)
                bottomMargin = dp(10f)
            })
        return tv
    }

    // ── Быстродействие (5c, как cxPagePerformance) ────────────────────────

    /** Ожидаем список приложений для диалога Keep-Alive (perf.getKeepAliveList). */
    private var keepAliveWaiting = false

    private fun pagePerformance() {
        addHead("page.performance", "performance")
        addToggle("perfVideo", "Оптимизация видео (1080p 60fps)", "desc.perfVideo", def = false)
        addDiv()
        addToggle("perfJit", "Учиться и оптимизировать самостоятельно (JIT)", "desc.perfJit", def = false)
        addDiv()
        // Как в JS: длина списка либо 4 (если список пуст)
        val keepCount = state.get("keepAliveApps")?.takeIf { it.isJsonArray }?.asJsonArray?.size()
            ?.takeIf { it > 0 } ?: 4
        soundInfoRow(
            "Всегда активные приложения (Keep-Alive)",
            "Не даёт системе закрывать отмеченные приложения в фоне при потере фокуса (например, Spotify во время воспроизведения музыки).",
            okButton("Управление списком ($keepCount) ›") {
                keepAliveWaiting = true
                send("perf.getKeepAliveList", "{}")
            },
        )
    }

    /** Список от UiBridge: [{label, packageName, enabled}]. Диалог с чекбоксами, как в cxOpenKeepAliveModal. */
    fun onKeepAliveApps(json: String) {
        if (!keepAliveWaiting) return
        keepAliveWaiting = false
        val items = runCatching { JsonParser.parseString(json).asJsonArray }.getOrNull()
            ?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject } ?: emptyList()
        if (items.isEmpty()) {
            themedDialog(
                "Всегда активные приложения",
                text("Нет доступных приложений", 13f, palette.muted, bold = false),
                bodyHeightDp = null,
                buttons = listOf("ЗАКРЫТЬ" to {}),
            )
            return
        }
        val checked = BooleanArray(items.size) {
            items[it].get("enabled")?.takeIf { v -> v.isJsonPrimitive }?.asBoolean ?: false
        }
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        list.addView(text(
            "Отметьте приложения, которые система не должна выгружать из памяти при работе в фоне:",
            13f, palette.muted, bold = false,
        ).apply { setPadding(0, 0, 0, dp(10f)) }, matchWrap())
        items.forEachIndexed { i, o ->
            val label = o.get("label")?.takeIf { v -> v.isJsonPrimitive }?.asString.orEmpty()
            val pkg = o.get("packageName")?.takeIf { v -> v.isJsonPrimitive }?.asString.orEmpty()
            list.addView(CheckBox(activity).apply {
                text = "$label\n$pkg"
                setTextColor(palette.ink)
                buttonTintList = android.content.res.ColorStateList.valueOf(palette.btn)
                isChecked = checked[i]
                setOnCheckedChangeListener { _, on -> checked[i] = on }
            }, matchWrap())
        }
        themedDialog(
            "Всегда активные приложения",
            list,
            bodyHeightDp = 340f,
            buttons = listOf(
                "ОТМЕНА" to {},
                "СОХРАНИТЬ" to {
                    val pkgs = JsonArray()
                    items.forEachIndexed { i, o ->
                        if (checked[i]) o.get("packageName")?.takeIf { v -> v.isJsonPrimitive }?.asString?.let { pkgs.add(it) }
                    }
                    send("perf.saveKeepAlive", JsonObject().apply { add("packages", pkgs) }.toString())
                },
            ),
        )
    }

    // ── Проекция: CarPlay / Android Auto (5c, как cxPageProjection) ───────

    /** Последний снимок projection.presence (ключи как в JS PROJ). */
    private var presence: JsonObject? = null

    fun onProjectionPresence(json: String) {
        presence = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return
        if (visible) rebuild()
    }

    private fun presStr(key: String): String =
        presence?.get(key)?.takeIf { it.isJsonPrimitive }?.asString ?: "unknown"

    private fun presBool(key: String): Boolean =
        presence?.get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false

    private fun cpLabel(s: String): String = when (s) {
        "connected" -> "подключено"
        "advertising" -> "ожидание подключения"
        "disconnected" -> "не подключено"
        "unavailable" -> "недоступно на этом ГУ"
        else -> "нет данных"
    }

    private fun pageProjection() {
        addHead("page.projection", "projection")
        addToggle("proj", "CarPlay / Android Auto (бета)", "desc.proj", def = false)
        addDiv()
        val cp = presStr("carplay")
        val aa = presStr("androidAuto")
        soundInfoRow("CarPlay", "", statePill(cpLabel(cp), cp == "connected"))
        addDiv()
        soundInfoRow("Android Auto", "", statePill(cpLabel(aa), aa == "connected"))
        addDiv()
        val bound = presBool("carplayAvailable") || presBool("androidAutoAvailable")
        soundInfoRow(
            "Вкладки подключения",
            "Вкладка подключённого телефона появляется в нижнем доке автоматически; здесь настраивается только доступность стека.",
            statePill(if (bound) "телефон подключён" else "нет подключённого телефона", bound),
        )
    }

    // ── Диагностика разработчика (5c, как cxPageDevDiagnostics) ───────────

    private var devCmdText = ""
    private var devCmdOutput = "Готов к вводу команд..."
    private var devLogOutput = "Нажмите «ПОКАЗАТЬ ПОСЛЕДНИЙ LOGCAT» для чтения журнала..."
    private var cmdInputView: EditText? = null
    private var devOutView: TextView? = null
    private var logView: TextView? = null

    /** Как cxDevRunCmd / cxDevQuickCmd: команда уходит в AdbClient через dev.runCommand. */
    private fun runDevCommand(raw: String) {
        val cmd = raw.trim()
        if (cmd.isEmpty()) return
        devCmdText = cmd
        cmdInputView?.setText(cmd)
        devCmdOutput = "Выполняется: $cmd..."
        devOutView?.text = devCmdOutput
        send("dev.runCommand", JsonObject().apply { addProperty("command", cmd) }.toString())
    }

    fun onDevCmdResult(exitCode: Int, output: String) {
        devCmdOutput = output.ifBlank { "Команда выполнена (вывод пуст, exitCode=$exitCode)" }
        devOutView?.text = devCmdOutput
    }

    fun onLogcat(output: String) {
        devLogOutput = output.ifBlank { "Логи отсутствуют" }
        logView?.text = devLogOutput
    }

    private fun pageDevDiagnostics() {
        addHead("page.devDiagnostics", "dev")

        addSect("page.devShell")
        page.addView(
            text("Выполнить команду напрямую в ADB root (сокет 5555) или Shell:", 14f, palette.muted, bold = false)
                .apply { setPadding(0, 0, 0, dp(8f)) },
            matchWrap(),
        )
        val input = EditText(activity).apply {
            hint = "например: iptables -t mangle -L POSTROUTING -v -n"
            setHintTextColor(palette.muted)
            setTextColor(palette.ink)
            typeface = Typeface.MONOSPACE
            textSize = 14f
            setSingleLine(true)
            setText(devCmdText)
            setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
            background = rounded(palette.card2, 10f, palette.line, 1f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    devCmdText = s?.toString().orEmpty()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        cmdInputView = input
        val runRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        runRow.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        runRow.addView(
            soundButton("ВЫПОЛНИТЬ", 0) { runDevCommand(devCmdText) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { leftMargin = dp(8f) },
        )
        page.addView(runRow, matchWrap())
        soundButtons(
            soundButton("Проверить TTL", 1) { runDevCommand("iptables -t mangle -L POSTROUTING -v -n") },
            soundButton("Белый список Keep-Alive", 1) { runDevCommand("dumpsys deviceidle whitelist") },
            soundButton("Статус JIT & Модель", 1) { runDevCommand("getprop | grep -E \"dalvik.vm.usejit|ro.product.model\"") },
            soundButton("Файлы звуков", 1) { runDevCommand("ls -la /vendor/etc/carlock/ /system/etc/avas/") },
            soundButton("Процессы", 1) { runDevCommand("ps -ef | grep -E \"mediacodec|voiceapp3\"") },
        )
        devOutView = monoBox(devCmdOutput, "#85e89d", 240f)

        addDiv()
        addSect("page.devAvas")
        soundButtons(
            // Имена событий — те, что обрабатывает UiBridge (см. комментарий в отчёте этапа 5c).
            soundButton("Тест звука открытия (AVAS 29)", 1) { send("dev.testAvasSound", "{}") },
            soundButton("Тест звука закрытия (Lock)", 1) { send("audio.preview", "{}") },
            soundButton("Тест штатного gong.wav", 2) {
                // Обработчика нет ни в JS, ни в Kotlin — как и в HTML.
                send("tools.action", JsonObject().apply { addProperty("action", "dev.playAllCarnos") }.toString())
            },
        )

        addDiv()
        addSect("page.devVhal")
        soundButtons(
            soundButton("Эмуляция: Дверь открыта (POS 10)", 1) { send("dev.simulateDoorOpen", "{}") },
            soundButton("Эмуляция: Дверь закрыта (POS 0)", 1) { send("dev.simulateDoorClose", "{}") },
            soundButton("Обновить иконки статус-бара", 1) { send("dev.testNotificationIcons", "{}") },
        )

        addDiv()
        addSect("page.devLogs")
        val logRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        logRow.addView(soundButton("ПОКАЗАТЬ ПОСЛЕДНИЙ LOGCAT", 0) {
            devLogOutput = "Чтение logcat..."
            logView?.text = devLogOutput
            send("dev.getLogcat", "{}")
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        logRow.addView(soundButton("ОЧИСТИТЬ", 1) {
            devLogOutput = "Журнал очищен."
            logView?.text = devLogOutput
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { leftMargin = dp(8f) })
        page.addView(logRow, matchWrap())
        logView = monoBox(devLogOutput, "#9ecbff", 260f)
    }

    // ── Строки страницы ───────────────────────────────────────────────────

    private fun addHead(titleKey: String, helpKey: String) {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4f), 0, dp(10f))
        }
        row.addView(text(ToolsCatalog.TEXT[titleKey].orEmpty(), 20f, palette.ink, bold = true),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(text("?", 16f, palette.onbtn, bold = true).apply {
            gravity = Gravity.CENTER
            background = rounded(palette.btn, 100f)
            setOnClickListener { showHelp(helpKey) }
        }, LinearLayout.LayoutParams(dp(34f), dp(34f)))
        page.addView(row, matchWrap())
    }

    private fun addSect(textKey: String) {
        page.addView(text(ToolsCatalog.TEXT[textKey].orEmpty().uppercase(), 11.5f, palette.muted, bold = true).apply {
            letterSpacing = 0.1f
            setPadding(0, dp(15f), 0, dp(8f))
        }, matchWrap())
    }

    private fun addDiv() = page.addView(divider(), matchWrap(dp(1f)))

    private fun addToggle(key: String, title: String, descKey: String?, def: Boolean) {
        val on = boolState(key) ?: def
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10f), 0, dp(10f))
        }
        val info = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        info.addView(text(title, 14.5f, palette.ink, bold = true))
        descKey?.let { ToolsCatalog.TEXT[it] }?.let { info.addView(text(it, 12f, palette.muted, bold = false)) }
        addNote(info, key)
        row.addView(info, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val sw = Switch(activity).apply {
            isChecked = on
            // Слушатель ставим после начального значения: программная установка не шлёт запись.
            setOnCheckedChangeListener { _, checked ->
                state.addProperty(key, checked)
                send("settings.change", JsonObject().apply {
                    addProperty("key", key)
                    addProperty("value", checked)
                }.toString())
                toast("$title · ${if (checked) "вкл" else "выкл"}")
            }
        }
        row.addView(sw, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        page.addView(row, matchWrap())
    }

    private fun addSeg(key: String, title: String, def: String, width: Int, opts: List<Pair<String, String>>) {
        val cur = strState(key) ?: def
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10f), 0, dp(10f))
        }
        row.addView(text(title, 14.5f, palette.ink, bold = true))
        val seg = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(palette.card2, 13f)
            setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
        }
        opts.forEach { (value, label) ->
            val isSel = value == cur
            seg.addView(text(label, 13.5f, if (isSel) selFg else palette.muted, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(6f), dp(9f), dp(6f), dp(9f))
                background = if (isSel) rounded(selBg, 9f) else null
                setOnClickListener {
                    state.addProperty(key, value)
                    send("settings.change", JsonObject().apply {
                        addProperty("key", key)
                        addProperty("value", value)
                    }.toString())
                    buildPage()
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        row.addView(seg, LinearLayout.LayoutParams(dp(width.toFloat()), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8f)
        })
        addNote(row, key)
        page.addView(row, matchWrap())
    }

    private fun addSlider(key: String, title: String, min: Int, max: Int, unit: String, def: Int) {
        val start = intState(key) ?: def
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10f), 0, dp(10f))
        }
        val head = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(text(title, 14.5f, palette.ink, bold = true),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val value = text("$start$unit", 13f, palette.muted, bold = false)
        head.addView(value)
        row.addView(head, matchWrap())
        val bar = SeekBar(activity).apply {
            this.max = max - min
            progress = start - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    value.text = "${p + min}$unit"
                    if (fromUser) schedule(key, p + min)
                }
                override fun onStartTrackingTouch(s: SeekBar?) = Unit
                override fun onStopTrackingTouch(s: SeekBar?) {
                    schedule(key, progress + min, immediate = true)
                }
            })
        }
        row.addView(bar, matchWrap())
        addNote(row, key)
        page.addView(row, matchWrap())
    }

    /** Пауза 220 мс, как cxPendingSlider в JS: без шторма записей яркости. */
    private fun schedule(key: String, v: Int, immediate: Boolean = false) {
        sliderPending.remove(key)?.let { handler.removeCallbacks(it) }
        val task = Runnable {
            sliderPending.remove(key)
            state.addProperty(key, v)
            send("settings.change", JsonObject().apply {
                addProperty("key", key)
                addProperty("value", v)
            }.toString())
        }
        sliderPending[key] = task
        if (immediate) task.run() else handler.postDelayed(task, 220L)
    }

    private fun addThemes() {
        val grid = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val current = strState("theme")
        ToolsCatalog.THEMES.chunked(3).forEach { rowThemes ->
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            rowThemes.forEach { t ->
                row.addView(themeTile(t, selected = t.id == current), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(4f), dp(4f), dp(4f), dp(4f))
                })
            }
            repeat(3 - rowThemes.size) {
                row.addView(View(activity), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            grid.addView(row, matchWrap())
        }
        page.addView(grid, matchWrap())
    }

    /** Плитка палитры: превью страницы, карточки и акцентов — цвета из THEMES (JS). */
    private fun themeTile(t: ToolsCatalog.Theme, selected: Boolean): View {
        val c = t.colors
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4f), dp(4f), dp(4f), dp(6f))
            background = rounded(palette.card2, 13f, strokeColor = if (selected) palette.ink else null, strokeDp = 2f)
            setOnClickListener {
                state.addProperty("theme", t.id)
                send("settings.change", JsonObject().apply {
                    addProperty("key", "theme")
                    addProperty("value", t.id)
                }.toString())
                buildPage()
            }
        }
        val preview = FrameLayout(activity).apply { background = rounded(Color.parseColor(c[0]), 10f) }
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
            background = rounded(Color.parseColor(c[1]), 8f)
        }
        card.addView(View(activity).apply { background = rounded(Color.parseColor(c[2]), 3f) },
            LinearLayout.LayoutParams(dp(40f), dp(5f)))
        card.addView(View(activity).apply { background = rounded(Color.parseColor(c[4]), 3f) },
            LinearLayout.LayoutParams(dp(24f), dp(5f)).apply { topMargin = dp(5f) })
        preview.addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(64f)).apply {
            setMargins(dp(8f), dp(8f), dp(8f), dp(8f))
        })
        box.addView(preview, matchWrap())
        box.addView(text(t.name + if (selected) " ✓" else "", 12f, palette.ink, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(6f), 0, 0)
        }, matchWrap())
        return box
    }

    private fun addText(title: String, body: String) {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10f), 0, dp(10f))
        }
        box.addView(text(title, 14.5f, palette.ink, bold = true))
        box.addView(text(body, 12f, palette.muted, bold = false))
        page.addView(box, matchWrap())
    }

    /** Примечание результата под контролом (cxNote): итог последнего действия по этому ключу. */
    private fun addNote(parent: LinearLayout, key: String) {
        val r = last ?: return
        if (!r.has("action") || r.get("action").asString != "settings.$key") return
        val summary = r.get("summary")?.takeIf { it.isJsonPrimitive }?.asString ?: return
        val ok = r.get("ok")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true
        parent.addView(text(summary, 12f, if (ok) palette.muted else Color.parseColor("#E5484D"), bold = false).apply {
            setPadding(0, dp(4f), 0, 0)
        }, matchWrap())
    }

    // ── Справка ───────────────────────────────────────────────────────────

    private fun showHelp(key: String) {
        val h = ToolsCatalog.HELP[key] ?: return
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        h.rows.forEach { (title, desc) ->
            body.addView(text(title, 14.5f, palette.ink, bold = true), matchWrap())
            body.addView(text(desc, 13f, palette.muted, bold = false).apply {
                setPadding(0, dp(2f), 0, dp(14f))
            }, matchWrap())
        }
        themedDialog(h.title, body, bodyHeightDp = 360f, buttons = listOf("ЗАКРЫТЬ" to {}))
    }

    /**
     * Диалог в цветах панели (palette). Системный AlertDialog берёт цвет текста из темы приложения,
     * поэтому на тёмных палитрах текст сливался с фоном.
     */
    private fun themedDialog(
        title: String,
        body: View,
        bodyHeightDp: Float?,
        buttons: List<Pair<String, () -> Unit>>,
    ): AlertDialog {
        lateinit var dialog: AlertDialog
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(18f), dp(20f), dp(12f))
            background = rounded(palette.card, 16f)
        }
        box.addView(text(title, 18f, palette.ink, bold = true), matchWrap().apply { bottomMargin = dp(12f) })
        val content: View = if (bodyHeightDp != null) ScrollView(activity).apply { addView(body) } else body
        box.addView(content, matchWrap(
            if (bodyHeightDp != null) dp(bodyHeightDp) else LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        buttons.forEach { (label, action) ->
            row.addView(text(label, 14f, palette.ink, bold = true).apply {
                setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
                setOnClickListener { dialog.dismiss(); action() }
            })
        }
        box.addView(row, matchWrap().apply { topMargin = dp(8f) })
        dialog = AlertDialog.Builder(activity).setView(box).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        return dialog
    }

    // ── Дерево: видимость и поиск (как cxVisible / cxFind в JS) ───────────

    private fun hasPage(node: ToolsCatalog.Node?): Boolean =
        node?.page != null && node.page in JS_PAGES

    private fun isVisibleNode(node: ToolsCatalog.Node?): Boolean =
        node != null && (hasPage(node) || node.children.any { isVisibleNode(it) })

    private fun firstVisiblePage(): ToolsCatalog.Node? {
        fun walk(list: List<ToolsCatalog.Node>): ToolsCatalog.Node? {
            for (n in list) {
                if (hasPage(n)) return n
                walk(n.children)?.let { return it }
            }
            return null
        }
        return walk(ToolsCatalog.TREE)
    }

    private fun findNode(id: String): ToolsCatalog.Node? {
        fun walk(list: List<ToolsCatalog.Node>): ToolsCatalog.Node? {
            for (n in list) {
                if (n.id == id) return n
                walk(n.children)?.let { return it }
            }
            return null
        }
        return walk(ToolsCatalog.TREE)
    }

    /** Раскрывает всех предков узла, чтобы выбранный пункт был виден (cxOpenPath). */
    private fun ensureOpen(id: String) {
        fun walk(list: List<ToolsCatalog.Node>, path: List<String>): List<String>? {
            for (n in list) {
                if (n.id == id) return path
                walk(n.children, path + n.id)?.let { return it }
            }
            return null
        }
        walk(ToolsCatalog.TREE, emptyList())?.forEach { open[it] = true }
    }

    // ── Значения снимка ───────────────────────────────────────────────────

    /** Как JS: '0' и 0 — выключено; прочие примитивы — по truthiness. */
    private fun boolState(key: String): Boolean? {
        val el = state.get(key)?.takeIf { it.isJsonPrimitive } ?: return null
        val p = el.asJsonPrimitive
        return when {
            p.isBoolean -> p.asBoolean
            p.isNumber -> p.asInt != 0
            else -> {
                val s = p.asString
                s.isNotEmpty() && s != "0"
            }
        }
    }

    private fun strState(key: String): String? =
        state.get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun intState(key: String): Int? =
        state.get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() }

    // ── Общие элементы ────────────────────────────────────────────────────

    private fun divider(): View = View(activity).apply { setBackgroundColor(palette.line) }

    private fun matchWrap(heightPx: Int = LinearLayout.LayoutParams.WRAP_CONTENT) =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, heightPx)

    private fun text(value: String, sizeSp: Float, color: Int, bold: Boolean): TextView =
        TextView(activity).apply {
            text = value
            textSize = sizeSp
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun rounded(color: Int, radiusDp: Float, strokeColor: Int? = null, strokeDp: Float = 0f): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != null && strokeDp > 0f) setStroke(dp(strokeDp), strokeColor)
        }

    private fun toast(message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Float): Int = (v * density + 0.5f).toInt()

    private companion object {
        /** Страницы, которые существуют в TOOLS_PAGES (index.html): по ним строится видимость дерева. */
        val JS_PAGES = setOf(
            "devDiagnostics", "screen", "projection", "notifications", "status",
            "lockSound", "windows", "networkTtl", "performance",
        )
    }
}
