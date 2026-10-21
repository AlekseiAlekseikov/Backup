package com.example.voiceapp3.ui.cx

import android.app.Activity
import android.content.Context
import androidx.annotation.StyleRes
import com.example.voiceapp3.R

/**
 * Реестр палитр — перенос каталога `THEMES` / `THEME_IDS` из шелла.
 *
 * Идентификаторы совпадают с `TelemetryPrefsMirror.THEME_IDS` (тот же набор из
 * девяти строк: light, dark, midnight, sepia, nordic, daylight, tokyoNeon,
 * sunsetDrive, bubblegum), поэтому сохранённый выбор переживает перенос, а
 * движок телеметрии и хром карты продолжают получать те же значения.
 *
 * Классификация светлых палитр тоже совпадает: вычисленная по относительной
 * яркости `page1` четвёрка — light, sepia, daylight, bubblegum — ровно та, что
 * перечислена в `TelemetryPrefsMirror.LIGHT_THEME_IDS`.
 *
 * @property id      идентификатор из `THEME_IDS` (в шелле — `data-cxtheme`)
 * @property title   русское название из `THEMES[i][0]`
 * @property style   ресурс темы; токены взяты из `body[data-th="i"]`
 * @property preview цвета плитки-превью: page1, card, ink, muted, accent, green
 */
data class CxPalette(
    val index: Int,
    val id: String,
    val title: String,
    @StyleRes val style: Int,
    val preview: IntArray,
) {
    override fun equals(other: Any?): Boolean = other is CxPalette && other.index == index

    override fun hashCode(): Int = index
}

/**
 * Хранилище и зеркало палитры.
 *
 * Реализуется слоем обвязки через `TelemetryPrefsMirror` — там уже есть и ключи
 * (`ui_shell_theme_id` для выбора, `ui_theme_effective_id` для действующей), и
 * зеркало в `FlutterSharedPreferences`. Дублировать ключи здесь нельзя: движок
 * телеметрии и хром карты читают именно их.
 */
interface CxThemeStore {
    /** Выбор пользователя (плитки) — `TelemetryPrefsMirror.chosenThemeId`. */
    fun chosenId(): String

    /** Действующая палитра — `TelemetryPrefsMirror.effectiveThemeId`. */
    fun effectiveId(): String

    /** `TelemetryPrefsMirror.rememberShellTheme`. */
    fun rememberShell(id: String)

    /** `TelemetryPrefsMirror.rememberEffectiveTheme`. */
    fun rememberEffective(id: String)

    /** `TelemetryPrefsMirror.mirror` — запись во Flutter-настройки. */
    fun mirror()

    /**
     * `NavigationHostController.onThemeChanged` — хром и подложка карты меняют
     * палитру сразу, без пересоздания.
     */
    fun onThemeChanged(id: String) {}
}

/** Заглушка для изолированной проверки; в приложении ставится настоящая. */
object NoThemeStore : CxThemeStore {
    override fun chosenId(): String = CxPalettes.ALL[0].id
    override fun effectiveId(): String = CxPalettes.ALL[0].id
    override fun rememberShell(id: String) {}
    override fun rememberEffective(id: String) {}
    override fun mirror() {}
}

object CxPalettes {

    val ALL: List<CxPalette> = listOf(
        CxPalette(0, "light", "Светлая", R.style.Theme_GeelyTools_P0,
            intArrayOf(0xEFEFED, 0xFFFFFF, 0x0D0D0D, 0x6B6B6B, 0xEFA83A, 0x6DC24B)),
        CxPalette(1, "dark", "Петроль", R.style.Theme_GeelyTools_P1,
            intArrayOf(0x17323E, 0x294854, 0xF7F7F0, 0xD2DDDA, 0xEFA83A, 0x6DC24B)),
        CxPalette(2, "midnight", "Полночь", R.style.Theme_GeelyTools_P2,
            intArrayOf(0x060606, 0x151515, 0xF2F2F2, 0xAFAFAF, 0xEFA83A, 0x6DC24B)),
        CxPalette(3, "sepia", "Сепия", R.style.Theme_GeelyTools_P3,
            intArrayOf(0xF0E9DC, 0xFBF6EC, 0x231C0F, 0x6E6353, 0xEFA83A, 0x6DC24B)),
        CxPalette(4, "nordic", "Нордик", R.style.Theme_GeelyTools_P4,
            intArrayOf(0x20242C, 0x2C323C, 0xE7ECF3, 0xB6BFCD, 0xEFA83A, 0x6DC24B)),
        CxPalette(5, "daylight", "Дневной свет", R.style.Theme_GeelyTools_P5,
            intArrayOf(0xFFFFFF, 0xFFFFFF, 0x000000, 0x3D3D3D, 0xEFA83A, 0x6DC24B)),
        CxPalette(6, "tokyoNeon", "Токийский неон", R.style.Theme_GeelyTools_P6,
            intArrayOf(0x0B0714, 0x150E24, 0xF2ECFF, 0xB9A8E0, 0xFF3D9A, 0x21E7E0)),
        CxPalette(7, "sunsetDrive", "Закат", R.style.Theme_GeelyTools_P7,
            intArrayOf(0x1B0F1C, 0x2A1728, 0xFFEDE4, 0xE0B9AE, 0xFF7A5C, 0x34D8A6)),
        CxPalette(8, "bubblegum", "Жвачка", R.style.Theme_GeelyTools_P8,
            intArrayOf(0xFDF2FA, 0xFFFFFF, 0x2B1030, 0x7A5478, 0xE8398B, 0x16BFA6)),
    )

    /** Индекс, на который шелл падал при неизвестной действующей палитре. */
    const val FALLBACK_INDEX = 1

    /** Базовая тема (`:root`) — на случай, если палитра ещё ни разу не выбрана. */
    @StyleRes val BASE_STYLE: Int = R.style.Theme_GeelyTools

    /** Светлые палитры — совпадает с `TelemetryPrefsMirror.LIGHT_THEME_IDS`. */
    val LIGHT_IDS: Set<String> = setOf("light", "sepia", "daylight", "bubblegum")

    fun byIndex(i: Int): CxPalette? = ALL.firstOrNull { it.index == i }

    fun byId(id: String): CxPalette? = ALL.firstOrNull { it.id == id }

    fun indexOfId(id: String): Int = byId(id)?.index ?: FALLBACK_INDEX

    fun isLight(id: String): Boolean = id in LIGHT_IDS

    /**
     * Действующая палитра — дословный порт `applyTheme()`:
     *
     *     const eff = (SET.follow && SET.gu === 'dark') ? 1 : setTheme;
     *
     * То есть при включённом «Следовать теме ГУ» и тёмной теме оболочки ГУ
     * рисуется палитра с индексом 1 («Петроль») независимо от выбора плиток;
     * иначе рисуется выбранная. Индекс 1 взят из исходника, а не выведен.
     */
    fun effectiveIndex(chosenIndex: Int, follow: Boolean, guDark: Boolean): Int =
        if (follow && guDark) 1 else chosenIndex

    /**
     * Применить действующую палитру к активности. Вызывать ДО `setContentView`,
     * как это делал `AppTheme.applySaved` в `MainActivity.onCreate`.
     *
     * @return индекс применённой палитры — нужен, чтобы показать пометку
     *   «действует не выбранная» (в шелле это `themeNote`, видимый при
     *   `eff !== setTheme`)
     */
    fun apply(activity: Activity, store: CxThemeStore, follow: Boolean, guDark: Boolean): Int {
        val chosen = indexOfId(store.chosenId())
        val eff = effectiveIndex(chosen, follow, guDark)
        val pal = byIndex(eff)
        activity.setTheme(if (pal != null) pal.style else BASE_STYLE)
        return eff
    }

    /**
     * Выбор плитки — порт `cxSetTheme(id)`:
     *
     *   1. запомнить выбор (`ui_shell_theme_id`);
     *   2. пересчитать действующую палитру и запомнить её (`ui_theme_effective_id`);
     *   3. зеркалить во Flutter-настройки, иначе экран телеметрии разъедется;
     *   4. сообщить хрому карты;
     *   5. пересоздать активность — тема применяется только до `setContentView`.
     *
     * Запись выбора должна идти тем же единственным каналом, что и остальные
     * настройки (`CxWrite.set("theme", id)` -> `settings.change`), поэтому
     * здесь она не выполняется: это делает слой обвязки, а [select] отвечает
     * только за пересчёт действующей палитры, зеркало и пересоздание.
     */
    fun select(activity: Activity, store: CxThemeStore, id: String,
               follow: Boolean, guDark: Boolean) {
        val chosen = indexOfId(id)
        if (chosen < 0) return
        store.rememberShell(id)
        val eff = effectiveIndex(chosen, follow, guDark)
        byIndex(eff)?.let { store.rememberEffective(it.id) }
        store.mirror()
        byIndex(eff)?.let { store.onThemeChanged(it.id) }
        activity.recreate()
    }

    /** Видна ли пометка «действует не выбранная палитра». */
    fun showOverrideNote(chosenIndex: Int, effectiveIdx: Int): Boolean =
        chosenIndex != effectiveIdx
}
