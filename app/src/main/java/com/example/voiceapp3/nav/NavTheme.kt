package com.example.voiceapp3.nav

import android.content.Context
import android.graphics.Color

/**
 * Stage NAV-MAP (тематизация хрома навигации): каталог палитр шелла
 * «Инструменты → Экран → Тема приложения», зеркало CSS-переменных
 * body[data-th="N"] из assets/ui/index.html (+ акцентная пара THEMES неона).
 *
 * Живое состояние темы приходит событием ui.theme/tab.change из WebView
 * (resolv-ом занимается шелл — включая «Следовать теме ГУ», eff=1), а в
 * рантайме до первого события — TelemetryPrefsMirror.currentThemeId()
 * (prefs «voiceapp3»). Хром и карта перекрашиваются БЕЗ перезагрузки:
 * NavigationHostController.onThemeChanged пересобирает chrome-слой, стиль
 * карты переводится между nav_day/nav_night по яркости палитры (AUTO).
 *
 * Жёстких цветов в макете больше нет: панели ← card/page1, текст ← ink/muted,
 * границы ← line, кнопки ← btn/onbtn, акценты (группы, прогресс-бары,
 * активный фаб, storage-стекло, пакер) ← accent1→accent2, треки ← track.
 * Статусные семафоры (загружено/обязателен/ошибка) остаются фиксированными
 * семантическими цветами — как фиксированный зелёный батареи в шелле.
 */
data class NavTheme(
    val id: String,
    /** true → карта в ночном стиле, хром тёмного стека; false → дневной. */
    val dark: Boolean,
    // ── переменные CSS шелла (data-th) ──
    val page1: Int,
    val page2: Int,
    val card: Int,
    val card2: Int,
    val ink: Int,
    val muted: Int,
    val muted2: Int,
    val line: Int,
    val track: Int,
    val btn: Int,
    val onbtn: Int,
    // ── акцентная пара плитки темы (tdots) ──
    val accent1: Int,
    val accent2: Int
) {

    // ═══════════ производные для хрома навигации (без новых цветов) ════

    /** Панель-градиент (карточка точки назначения / шторка сверху). */
    val panelTop: Int get() = alphaOf(card, 0xEB)
    val panelBot: Int get() = alphaOf(card2, 0xE6)

    /** Фон листа офлайн-карт: почти опаковый градиент как .sheet макета. */
    val sheetTop: Int get() = alphaOf(card, 0xF6)
    val sheetBot: Int get() = alphaOf(page1, 0xF8)

    /** Стекло фаб-капсул (полупрозрачная карточка). */
    val glassFab: Int get() = alphaOf(card, 0xC8)
    val glassBanner: Int get() = alphaOf(card, 0xB8)

    /** Волосяные границы (1dp) и заливки капсул. */
    val borderHairline: Int get() = alphaOf(line, 0x9E)
    val rowBg: Int get() = alphaOf(ink, 0x0A)
    val chipBg: Int get() = alphaOf(ink, 0x0F)
    val ghostBg: Int get() = alphaOf(ink, 0x12)
    val gripColor: Int get() = alphaOf(ink, 0x2E)

    /** Акцент-стекло хранилища (bg ~8%, кайма ~20% акцента). */
    val storageBg: Int get() = alphaOf(accent1, 0x14)
    val storageEdge: Int get() = alphaOf(accent1, 0x33)

    /** Низ градиента залитой кнопки/активного фаба (без собственных цветов). */
    val btnShadowed: Int get() = shade(btn, 0.80f)

    /** Текст второго порядка на панелях и sub-подписи. */
    val textSecondary: Int get() = muted

    companion object {

        /** Смешивание альфы ≥8 бит с цветом (Color.argb без зависимостей). */
        fun alphaOf(color: Int, alpha: Int): Int =
            (color and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)

        /** Затенение цвета (0..1 к чёрному) — градиентные низы кнопок. */
        fun shade(color: Int, factor: Float): Int {
            val a = Color.alpha(color)
            val r = (Color.red(color) * factor).toInt().coerceIn(0, 255)
            val g = (Color.green(color) * factor).toInt().coerceIn(0, 255)
            val b = (Color.blue(color) * factor).toInt().coerceIn(0, 255)
            return Color.argb(a, r, g, b)
        }

        // ── палитры = точная копия data-th из index.html (НЕ менять врозь) ──
        val LIGHT = NavTheme(
            "light", false,
            rgb(0xEFEFED), rgb(0xDFDFDD), rgb(0xFFFFFF), rgb(0xEBEBEB),
            rgb(0x0D0D0D), rgb(0x6B6B6B), rgb(0x9A9A9A), rgb(0xDEDEDC),
            rgb(0xE3E3E1), rgb(0x262626), rgb(0xFFFFFF),
            rgb(0xEFA83A), rgb(0x6DC24B)
        )
        val DARK = NavTheme(
            "dark", true,
            rgb(0x17323E), rgb(0x0E222B), rgb(0x294854), rgb(0x3A5964),
            rgb(0xF7F7F0), rgb(0xD2DDDA), rgb(0x9EB2B1), rgb(0x466570),
            rgb(0x3A5964), rgb(0xD7E8E5), rgb(0x17313B),
            rgb(0xEFA83A), rgb(0x6DC24B)
        )
        val MIDNIGHT = NavTheme(
            "midnight", true,
            rgb(0x0A0A0A), rgb(0x000000), rgb(0x151515), rgb(0x232323),
            rgb(0xF2F2F2), rgb(0xAFAFAF), rgb(0x7A7A7A), rgb(0x2C2C2C),
            rgb(0x2A2A2A), rgb(0xE8E8E8), rgb(0x0A0A0A),
            rgb(0xEFA83A), rgb(0x6DC24B)
        )
        val SEPIA = NavTheme(
            "sepia", false,
            rgb(0xF0E9DC), rgb(0xE0D3BA), rgb(0xFBF6EC), rgb(0xE9E0CF),
            rgb(0x231C0F), rgb(0x6E6353), rgb(0x9C9081), rgb(0xD8CCB4),
            rgb(0xE4DACA), rgb(0x3A2F1E), rgb(0xFBF6EC),
            rgb(0xEFA83A), rgb(0x6DC24B)
        )
        val NORDIC = NavTheme(
            "nordic", true,
            rgb(0x20242C), rgb(0x14171D), rgb(0x2C323C), rgb(0x3A414D),
            rgb(0xE7ECF3), rgb(0xB6BFCD), rgb(0x8792A3), rgb(0x434B58),
            rgb(0x3A414D), rgb(0xD5DEEB), rgb(0x20242C),
            rgb(0xEFA83A), rgb(0x6DC24B)
        )
        val DAYLIGHT = NavTheme(
            "daylight", false,
            rgb(0xFFFFFF), rgb(0xE4E4E4), rgb(0xFFFFFF), rgb(0xE2E2E2),
            rgb(0x000000), rgb(0x3D3D3D), rgb(0x5E5E5E), rgb(0xD8D8D8),
            rgb(0xD8D8D8), rgb(0x000000), rgb(0xFFFFFF),
            rgb(0xEFA83A), rgb(0x6DC24B)
        )
        val TOKYO_NEON = NavTheme(
            "tokyoNeon", true,
            rgb(0x0B0714), rgb(0x05030B), rgb(0x150E24), rgb(0x221838),
            rgb(0xF2ECFF), rgb(0xB9A8E0), rgb(0x7E6BA8), rgb(0x2B2043),
            rgb(0x251A3C), rgb(0xE9DDFF), rgb(0x150E24),
            rgb(0xFF3D9A), rgb(0x21E7E0)
        )
        val SUNSET_DRIVE = NavTheme(
            "sunsetDrive", true,
            rgb(0x1B0F1C), rgb(0x120812), rgb(0x2A1728), rgb(0x3A2036),
            rgb(0xFFEDE4), rgb(0xE0B9AE), rgb(0xA9807C), rgb(0x3E2238),
            rgb(0x3E2238), rgb(0xFFE0CF), rgb(0x2A1728),
            rgb(0xFF7A5C), rgb(0x34D8A6)
        )
        val PEARL = NavTheme(
            "pearl", true,
            rgb(0xFAFAFA), rgb(0xF2F0EE), rgb(0xFFFFFF), rgb(0xF5F3F1),
            rgb(0x1A1A1A), rgb(0x777777), rgb(0xB9B9B9), rgb(0xEAEAEA),
            rgb(0xEAE6E6), rgb(0x1A1A1A), rgb(0xFFFFFF),
            rgb(0xE8B4C8), rgb(0x9FD9C8)
        )

        private val ALL = mapOf(
            LIGHT.id to LIGHT, DARK.id to DARK, MIDNIGHT.id to MIDNIGHT,
            SEPIA.id to SEPIA, NORDIC.id to NORDIC, DAYLIGHT.id to DAYLIGHT,
            TOKYO_NEON.id to TOKYO_NEON, SUNSET_DRIVE.id to SUNSET_DRIVE,
            PEARL.id to PEARL
        )

        /** Палитра по id из ui.theme/tab.change; незнакомый id → sepia-дефолт. */
        fun byId(id: String?): NavTheme = ALL[id] ?: SEPIA

        /** Палитра из последнего памятого состояния шелла (загрузка до JS). */
        fun current(context: Context): NavTheme =
            byId(com.example.voiceapp3.TelemetryPrefsMirror.currentThemeId(context))

        /** Фон WebView на прочих вкладках (верхняя ступень градиента страницы). */
        fun pageBackdrop(id: String?): Int = byId(id).page1

        private fun rgb(hex: Int): Int = hex or 0xFF000000.toInt()
    }
}
