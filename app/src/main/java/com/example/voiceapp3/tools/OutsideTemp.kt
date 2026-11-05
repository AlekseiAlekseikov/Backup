package com.example.voiceapp3.tools

import java.util.Locale

/**
 * iter33h F1 — нормализация и формат НАРУЖНОЙ температуры.
 *
 * Правила взяты из референса CentralEXAuto 3.3.9
 * (`com.geely.hvacstepperv2.TempStepperService.i(int)`), а не придуманы:
 *  • float-ветка: значение как есть, если оно в диапазоне −55…70;
 *  • int-ветка: −550…700 ⇒ /10 (шаг 0.1 °C), иначе −45…65 как есть;
 *  • печать — `%.1f`.
 *
 * Вынесено из [StatusIcons] отдельными функциями верхнего уровня: их проверяет
 * юнит-тест `TempFormatTest`, которому не нужно поднимать объект с Android-полями.
 */
internal fun envOutsideToCelsius(fRaw: Float?, iRaw: Int?): Float? {
    if (fRaw != null && !fRaw.isNaN() && fRaw > -55f && fRaw < 70f) return fRaw
    if (iRaw == null) return null
    val v = when {
        iRaw > -550 && iRaw < 700 -> iRaw / 10.0f
        iRaw >= -45 && iRaw <= 65 -> iRaw.toFloat()
        else -> return null
    }
    return if (v > -55f && v < 70f) v else null
}

/** F1/F2: единый формат наружной — `%.1f`, а при отсутствии значения честное `н/д`. */
internal fun formatOutsideTempC(v: Float?): String =
    if (v == null) "н/д" else String.format(Locale.US, "%.1f", v)
