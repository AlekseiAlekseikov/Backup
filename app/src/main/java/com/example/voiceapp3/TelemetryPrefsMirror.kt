package com.example.voiceapp3

import android.content.Context

/**
 * GeelyTools deviation (зафиксировано в отчёте миграции).
 *
 * Настройки, уже существующие в общем меню GeelyTools, не дублируются в
 * приложении телеметрии: перед стартом Dart-движка они зеркалируются из
 * SharedPreferences GeelyTools в FlutterSharedPreferences (источник, который
 * читают ThemeController / AppExperienceController capy). Источник истины —
 * GeelyTools; зеркало одностороннее и выполняется при каждом запуске
 * TelemetryActivity.
 *
 * Ключи Flutter-стороны (shared_preferences, префикс "flutter."):
 *  - theme_mode  ('dark'|'light') — ThemeController._themeModeKey;
 *    theme_id (палитра) = ДЕЙСТВУЮЩАЯ палитра шелла: с учётом «Следовать теме
 *    ГУ» это может быть тёмный вариант, хотя выбрана светлая. Выбор
 *    пользователя лежит отдельно (ui_shell_theme_id) и не затирается, иначе
 *    возврат с тёмной темы ГУ некуда было бы откатывать;
 *  - app_immersive_mode_enabled — «Полный экран» (AppExperienceController);
 *  - app_climate_bar_enabled — «Полоса климата» (персистится UiBridge при
 *    переключении тумблера в WebView-шелле);
 *  - carplay_beta_enabled — тумблер CarPlay/AA (бета): значение движка
 *    (TelemetrySettings.projectionBetaEnabled).
 */
object TelemetryPrefsMirror {

    /**
     * Текущий режим темы GeelyTools в терминах Flutter ('light'|'dark').
     * Используется и зеркалом, и TelemetryHostController — чтобы понять,
     * что движок надо пересоздать после смены темы в общем меню.
     */
    fun currentThemeMode(context: Context): String = when (AppTheme.mode(context)) {
        AppTheme.Mode.LIGHT -> "light"
        AppTheme.Mode.DARK -> "dark"
        else ->
            // SYSTEM: на ГУ системная тема де-факто постоянна; тёмная —
            // дефолт GeelyTools.
            if (context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                android.content.res.Configuration.UI_MODE_NIGHT_NO
            ) "light" else "dark"
    }

    private const val GT_PREFS = "voiceapp3"
    private const val GT_IMMERSIVE = "app_immersive_mode_enabled"
    private const val GT_CLIMATE_BAR = "app_climate_bar_enabled"
    private const val MIRRORED_MODE = "telemetry_mirrored_theme_mode"
    /**
     * Ключ ВЫБРАННОЙ палитры (плитки «Тема приложения» вкладки
     * «Инструменты»). Читается реестром настроек как значение настройки и
     * как то, куда шелл возвращается при выключении тёмной темы ГУ.
     */
    const val KEY_SHELL_THEME = "ui_shell_theme_id"

    /**
     * Ключ ДЕЙСТВУЮЩЕЙ палитры — что реально нарисовано прямо сейчас, с учётом
     * «Следовать теме ГУ». Отдельно от [KEY_SHELL_THEME]: событие ui.theme
     * приносит именно её, и если писать её в ключ выбора, тёмная тема ГУ
     * необратимо затирала бы выбранный светлый вариант (замечание с ГУ:
     * «обратно на светлую не переключается»).
     */
    const val KEY_EFFECTIVE_THEME = "ui_theme_effective_id"

    /**
     * Палитры каталога THEMES шелла (index.html) в терминах AppThemeId capy.
     * Порядок плиток шелла — Светлая/Петроль/Полночь/Сепия/Нордик/Дневной
     * свет/Токийский неон/Закат/Жвачка — повторяет каталог capy_ui 1:1.
     */
    val THEME_IDS = setOf(
        "light", "dark", "midnight", "sepia", "nordic",
        "daylight", "tokyoNeon", "sunsetDrive", "bubblegum"
    )
    private val LIGHT_THEME_IDS = setOf("light", "sepia", "daylight", "bubblegum")

    /** Запомнить ВЫБОР пользователя (плитки палитры). Только эта запись
     *  меняет ключ настройки; ui.theme/tab.change пишут действующую палитру. */
    fun rememberShellTheme(context: Context, id: String) {
        if (id !in THEME_IDS) return // вне каталога — не пишем
        context.getSharedPreferences(GT_PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SHELL_THEME, id).apply()
    }

    /** Запомнить действующую палитру (что нарисовано) — для зеркала движку,
     *  хрома карты и фона WebView. Выбор пользователя не трогает. */
    fun rememberEffectiveTheme(context: Context, id: String) {
        if (id !in THEME_IDS) return
        context.getSharedPreferences(GT_PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_EFFECTIVE_THEME, id).apply()
    }

    /** Выбор пользователя (плитки) — куда шелл вернётся, когда тёмная тема
     *  ГУ выключится. Если плитки ещё не трогали, считается действующая. */
    fun chosenThemeId(context: Context): String {
        val gt = context.getSharedPreferences(GT_PREFS, Context.MODE_PRIVATE)
        gt.getString(KEY_SHELL_THEME, null)?.let { if (it in THEME_IDS) return it }
        gt.getString(KEY_EFFECTIVE_THEME, null)?.let { if (it in THEME_IDS) return it }
        return if (currentThemeMode(context) == "light") "light" else "dark"
    }

    /**
     * Действующая палитра шелла как AppThemeId capy — то, что надо отдать
     * движку телеметрии, карте и фону WebView. Если шелл ещё ни разу не
     * сообщал палитру (страница не грузилась после обновления), падаем на
     * выбор плиток, а затем на бинарный режим AppTheme.
     */
    fun effectiveThemeId(context: Context): String {
        val stored = context.getSharedPreferences(GT_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_EFFECTIVE_THEME, null)
        if (stored != null && stored in THEME_IDS) return stored
        return chosenThemeId(context)
    }

    /** Совместимость: все потребители «какая тема сейчас» хотят действующую. */
    fun currentThemeId(context: Context): String = effectiveThemeId(context)

    private const val FLUTTER_PREFS = "FlutterSharedPreferences"

    fun mirror(context: Context) {
        runCatching {
            val gt = context.getSharedPreferences(GT_PREFS, Context.MODE_PRIVATE)
            val flutter = context.getSharedPreferences(FLUTTER_PREFS, Context.MODE_PRIVATE)
            val edit = flutter.edit()

            // ── Тема ────────────────────────────────────────────────────────
            // Точная палитра шелла (каталог THEMES ↔ AppThemeId capy):
            // ThemeController читает theme_id первым, поэтому телеметрия
            // стартует ровно в той палитре, что и шелл.
            val themeId = effectiveThemeId(context)
            edit.putString("flutter.theme_id", themeId)
            edit.putString(
                "flutter.theme_mode",
                if (themeId in LIGHT_THEME_IDS) "light" else "dark"
            )
            if (gt.getString(MIRRORED_MODE, null) != themeId) {
                // Палитра сменилась на стороне GeelyTools: launch-фон
                // пересчитает ThemeController.load() при старте движка.
                edit.remove("flutter.launch_bg_color")
                gt.edit().putString(MIRRORED_MODE, themeId).apply()
            }

            // ── Полный экран ────────────────────────────────────────────────
            edit.putBoolean(
                "flutter.app_immersive_mode_enabled",
                gt.getBoolean(GT_IMMERSIVE, true)
            )

            // ── Полоса климата ─────────────────────────────────────────────
            if (gt.contains(GT_CLIMATE_BAR)) {
                edit.putBoolean(
                    "flutter.app_climate_bar_enabled",
                    gt.getBoolean(GT_CLIMATE_BAR, false)
                )
            }

            // ── CarPlay / Android Auto (бета) ──────────────────────────────
            val projection = com.timhss.capyenergy.telemetry
                .TelemetrySettings(context).projectionBetaEnabled()
            edit.putBoolean("flutter.carplay_beta_enabled", projection)

            edit.apply()
        }
    }
}
