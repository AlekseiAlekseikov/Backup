package com.example.voiceapp3.ui.cx

import com.example.voiceapp3.TelemetryPrefsMirror
import com.example.voiceapp3.nav.NavigationHostController
import com.example.voiceapp3.ui.TelemetryHostController
import android.content.Context

/**
 * Адаптер нативных хостов поверх уже существующих контроллеров.
 *
 * Порядок и состав вызовов — дословно из обработчика `tab.change` в
 * `UiBridge.kt:465-476` и из `onResume`/`onPause` `MainActivity`:
 *
 *     if (id == "telemetry") telemetryHost?.show() else telemetryHost?.hide()
 *     if (id == "navi")      navHost?.show()       else navHost?.hide()
 *
 * Хосты передаются как уже созданные экземпляры, потому что их жизненный цикл
 * (`dispose()`, `onActivityPaused()`) остаётся за `MainActivity`.
 *
 * Активация рендер-моста CarPlay/Android Auto (`ProjectionRenderBridge.activate`
 * / `deactivate` / `refresh`) сюда НЕ включена: в шелле ею занималась функция
 * `syncProjectionActivation()` по ребру вкладки, и она оперирует типом
 * `ProjectionStack` из capy-пакетов. Это отдельный шаг, см. PLAN.md.
 */
class CxHostsAdapter(
    private val telemetry: TelemetryHostController?,
    private val nav: NavigationHostController?,
) : CxHosts {

    override fun showTab(tabId: String) {
        if (tabId == "telemetry") telemetry?.show() else telemetry?.hide()
        if (tabId == "navi") nav?.show() else nav?.hide()
    }

    override fun setDockInset(px: Int) {
        telemetry?.setDockInsetPx(px)
        nav?.setDockInsetPx(px)
    }

    override fun onResumed() {
        telemetry?.onActivityResumed()
        nav?.onActivityResumed()
    }

    override fun onPaused() {
        telemetry?.onActivityPaused()
        nav?.onActivityPaused()
    }

    override fun onStopped() {}
}

/**
 * Хранилище палитры поверх `TelemetryPrefsMirror`.
 *
 * Делегирует целиком: ключи `ui_shell_theme_id` и `ui_theme_effective_id`,
 * зеркало в `FlutterSharedPreferences` и список допустимых идентификаторов уже
 * есть там, и дублировать их здесь нельзя — иначе нативная оболочка и движок
 * телеметрии разъехались бы по палитрам.
 *
 * `onThemeChanged` проброшен в `NavigationHostController`: хром и подложка карты меняют
 * палитру сразу, без пересоздания. В шелле это делал обработчик `ui.theme`
 * (`UiBridge.kt:237-248`); самого события больше нет, поэтому вызов здесь.
 */
class CxThemeStoreMirror(
    private val ctx: Context,
    private val nav: NavigationHostController?,
) : CxThemeStore {

    override fun chosenId(): String = TelemetryPrefsMirror.chosenThemeId(ctx)

    override fun effectiveId(): String = TelemetryPrefsMirror.effectiveThemeId(ctx)

    override fun rememberShell(id: String) = TelemetryPrefsMirror.rememberShellTheme(ctx, id)

    override fun rememberEffective(id: String) =
        TelemetryPrefsMirror.rememberEffectiveTheme(ctx, id)

    override fun mirror() = TelemetryPrefsMirror.mirror(ctx)

    override fun onThemeChanged(id: String) {
        nav?.onThemeChanged(id)
    }
}
