package com.example.voiceapp3.ui.cx

import android.app.Activity
import android.view.View
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Нативные хосты, которые рисуют поверх контента на вкладках-якорях.
 *
 * Реализация в `MainActivity` — тонкая обёртка над уже существующими
 * `TelemetryHostController`, `NavigationHostController` и
 * `ProjectionRenderBridge`. Вынесено в интерфейс, чтобы корень композиции
 * [CxUi] не зависел от классов, которых нет в изолированной сборке, и
 * оставался проверяемым компилятором.
 */
interface CxHosts {
    /**
     * Показать хост своей вкладки и скрыть остальные.
     *
     * Порт обработчика `tab.change` из `UiBridge.kt:465-476`:
     * `if (id == "telemetry") telemetryHost?.show() else telemetryHost?.hide()`
     * и то же для `navi`.
     */
    fun showTab(tabId: String)

    /**
     * Отступ снизу под плавающий док.
     *
     * Замена события `telemetry.frame`: JS мерял `.tabbar` и присылал
     * `bottomInset`, а `UiBridge` передавал его в
     * `telemetryHost?.setDockInsetPx` и `navHost?.setDockInsetPx`
     * (строки 250-263). Здесь значение измеряет каркас.
     */
    fun setDockInset(px: Int)

    fun onResumed()
    fun onPaused()
    fun onStopped()
}

/** Заглушка, если хосты ещё не подключены. */
object NoHosts : CxHosts {
    override fun showTab(tabId: String) {}
    override fun setDockInset(px: Int) {}
    override fun onResumed() {}
    override fun onPaused() {}
    override fun onStopped() {}
}

/**
 * Корень композиции нативного интерфейса.
 *
 * Собирает каркас, обвязку и реестр страниц и даёт `MainActivity` ровно то,
 * что нужно: корневой `View`, приёмник пушей из нативной части, обработку
 * кнопки «Назад» и перерисовку. Всё остальное (`VehicleLiveAdapter`,
 * голосовой сервис, разрешения, телеметрия, CarPlay/AA) остаётся в
 * `MainActivity` без изменений.
 *
 * Замена для `MainActivity` получается короткой:
 *
 *     private lateinit var cx: CxUi
 *
 *     // вместо setContentView(R.layout.activity_main) и findViewById(R.id.webView)
 *     // bridge реализуется как CxEventSink и CxStateSource
 *     cx = CxUi(this, bridge, bridge, themeStore, hosts)
 *     setContentView(cx.root)
 *     cx.root.post { cx.onDockLaidOut() }
 *
 *     // вместо webView.evaluateJavascript("window.__nativeBack …")
 *     override fun onBackPressed() {
 *         if (cx.onBackPressed()) return
 *         super.onBackPressed()
 *     }
 *
 *     // вместо bridge.pushToUi(name, json)
 *     cx.onNativeEvent(name, json)
 */
class CxUi(
    private val activity: Activity,
    private val events: CxEventSink,
    private val stateSource: CxStateSource,
    private val themeStore: CxThemeStore,
    private val hosts: CxHosts = NoHosts,
    private val onSay: ((String) -> Unit)? = null,
    pages: CxPageProvider = CxPages(CxStrings { activity.getString(it) }),
) {

    val wiring = CxWiring(events, stateSource, themeStore, onSay)

    val shell = CxShell(activity, pages, wiring, wiring, onSay).also {
        it.setThemeStore(themeStore)
        it.setPaletteSink { id ->
            val st = wiring.state()
            CxPalettes.select(activity, themeStore, id,
                st.bool("follow", true), st.string("gu", "light") == "dark")
        }
        it.setSelectSink { key, value ->
            /* Выбор файла из списка скопированных: в шелле
               `cxSelectSoundItem(value, isLock)` отправлял
               `audio.selectSoundName` с полями name и isLock. Ключ строки
               определяет, какой из двух звуков выбирается. */
            val isLock = key == "lockSoundFile"
            events.emit("audio.selectSoundName",
                obj("name" to value, "isLock" to isLock).toString())
        }
        it.onTabChanged = { tabId ->
            wiring.currentTab = tabId
            hosts.showTab(tabId)
            /* В шелле событие tab.change запускало живой поток телеметрии
               (vehicleLive.startLive) — это делает слой обвязки в UiBridge. */
            syncProjectionActivation(tabId)
        }
    }

    /**
     * Активный стек проекции — порт переменной `ACTIVE_STACK` из шелла.
     */
    private var activeProjectionStack: String? = null

    /**
     * Ребро активации рендер-моста CarPlay/Android Auto.
     *
     * Точный порт `select()` из index.html:1012-1013:
     *
     *     if(ACTIVE_STACK&&window.__geelyEmit)
     *         window.__geelyEmit('projection.deactivate',{stack:ACTIVE_STACK});
     *     if(cur&&window.__geelyEmit)
     *         window.__geelyEmit('projection.activate',{stack:cur});
     *
     * Без этого ребра `UiBridge.activeProjectionStack` останется null, а
     * `ProjectionRenderBridge.frame` на таком стеке не отработает
     * (`if (stack != activeStack) return@post`) — то есть видео не появится.
     * Порядок сохранён: сначала deactivate прежнего, потом activate нового.
     */
    private fun syncProjectionActivation(tabId: String) {
        val cur = stackOf(tabId)
        if (cur == activeProjectionStack) return
        activeProjectionStack?.let {
            events.emit("projection.deactivate", obj("stack" to it).toString())
        }
        activeProjectionStack = cur
        cur?.let {
            events.emit("projection.activate", obj("stack" to it).toString())
        }
    }

    /**
     * Соответствие вкладки стеку — порт `stackOf(id)` из index.html:
     * `id==='carplay' ? 'carplay' : (id==='aa' ? 'androidAuto' : null)`.
     *
     * Имена стеков — wire-формат `ProjectionRenderBridge.stackFromWire`,
     * поэтому `aa` отображается в `androidAuto`, а не в `aa`.
     */
    private fun stackOf(tabId: String): String? = when (tabId) {
        "carplay" -> "carplay"
        "aa" -> "androidAuto"
        else -> null
    }

    /**
     * Виджет плавающего дока — с гарантией, что каркас к этому моменту
     * построен.
     *
     * `CxShell.build()` вызывается лениво, при первом обращении к [root].
     * Прямой вызов `cx.shell.dockView()` из `MainActivity.onCreate` до
     * `setContentView` давал `UninitializedPropertyAccessException: lateinit
     * property dockHost has not been initialized`. Обращение к [root] здесь
     * заставляет lazy отработать, причём вместе с установкой
     * `onLayoutChanged` — если вызвать `shell.build()` напрямую, отступ под
     * док и прямоугольник проекции перестанут публиковаться.
     */
    fun dockView(): View {
        root
        return shell.dockView()
    }

    val root: View get() = shellRoot

    private val shellRoot: View by lazy {
        shell.onLayoutChanged = { onDockLaidOut() }
        shell.build()
    }

    /**
     * Отступ под док для хостов. Вызывать после раскладки — например
     * `cx.root.post { cx.onDockLaidOut() }`.
     */
    fun onDockLaidOut() {
        hosts.setDockInset(shell.dockInsetPx())
        onProjectionFrame?.invoke(shell.projectionFramePx())
    }

    /**
     * Приёмник прямоугольника области проекции. Реализация в `MainActivity`:
     *
     *     cx.onProjectionFrame = { r ->
     *         projectionRender?.frame(activeStack, r[0].toFloat(), r[1].toFloat(),
     *                                 r[2].toFloat(), r[3].toFloat())
     *     }
     *
     * Замена `UiBridge.projectionFrame(stack, x, y, w, h)`, куда значения
     * приходили из JS. Имя стека каркас не знает — его определяет слой
     * активации проекции.
     */
    var onProjectionFrame: ((IntArray) -> Unit)? = null

    /**
     * Запрос первого снимка — замена пары событий, которые шелл отправлял при
     * загрузке страницы (index.html:2689-2690):
     *
     *     emit('ui.ready', {});
     *     emit('telemetry.request', {});
     *
     * `ui.ready` в `UiBridge.dispatchEvent` не обрабатывался вовсе — ветки в
     * `when` нет, событие просто исчезало (проверено поиском по мосту).
     * `telemetry.request` запускал `vehicleLive.request()` и `startLive()`,
     * `ProjectionPresenceBridge.publishLatest()` и
     * `projectionRender.publishStatuses()`. Всё это нужно сделать ровно один
     * раз, когда каркас построен, поэтому вызов отсюда, а не из конструктора:
     * на момент конструктора мост ещё не создан.
     */
    fun requestInitialState() {
        refresh()
        events.emit("telemetry.request", "{}")
    }

    /** Перечитать снимок и перерисовать — аналог `cxRepaintTools()` + `cxSyncControls()`. */
    fun refresh() {
        shell.bind(wiring.state(), wiring)
    }

    /** Полная перерисовка содержимого — аналог `render()` в шелле. */
    fun repaint() {
        shell.repaint()
        shell.bind(wiring.state(), wiring)
    }

    /**
     * Приёмник пушей из нативной части — замена `window.__nativeEvent` и
     * слушателей `native:*`.
     *
     * @return true, если интерфейс перерисован
     */
    fun onNativeEvent(name: String, json: String): Boolean {
        val data: JsonObject? = try {
            JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject
        } catch (t: Throwable) {
            null
        }
        val changed = wiring.onNativeEvent(name, data)
        if (changed) refresh()
        return changed
    }

    /** Кнопка «Назад»: сначала модальное окно, потом выход. */
    fun onBackPressed(): Boolean = shell.onBackPressed()

    /** Открыть справку раздела. */
    fun showHelp(pageId: String) {
        shell.modals()?.showHelp(pageId)
    }

    fun onResumed() { hosts.onResumed() }
    fun onPaused() { hosts.onPaused() }
    fun onStopped() { hosts.onStopped() }

    private fun obj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject().apply {
        for ((k, v) in pairs) when (v) {
            null -> Unit
            is Boolean -> addProperty(k, v)
            is Number -> addProperty(k, v)
            else -> addProperty(k, v.toString())
        }
    }
}
