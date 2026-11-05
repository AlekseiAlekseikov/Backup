package com.example.voiceapp3.tools

/**
 * iter33i P0-a — СЕМАНТИКА `persist.sys.hvacdigital.*`.
 *
 * Что делает референс (CentralEXAuto 3.3.9, `w0/x7.java:137-147`, метод `i(boolean,boolean,boolean)`):
 *
 *     StringBuilder sb = new StringBuilder("setprop persist.sys.hvacdigital.temp ");
 *     sb.append(z2 ? "1" : "0");
 *     sb.append(" && setprop persist.sys.hvacdigital.batt ");
 *     sb.append(z3 ? "1" : "0");
 *     sb.append(" && setprop persist.sys.hvacdigital.exttemp ");
 *     sb.append(z4 ? "1" : "0");
 *     sb.append(" && echo FLAGS_OK");
 *
 * То есть это ТРИ ФЛАГА `0|1` (включён ли цифровой слот), а не значения. Наша 1.0.53 писала туда
 * значения (`batt=71`, `exttemp=9.5`) — это нарушало контракт.
 *
 * Проверено по нашему плагину (`app/src/main/res/raw/geely_auto_systemui_plugin_digital.apk`,
 * 2714 ресурсов, `classes.dex` 6 799 536 Б): строки `hvacdigital` в APK НЕТ НИ ОДНОЙ
 * (`b'hvacdigital' in entry` → пусто по всем 2714 записям). Значит наш плагин эти props не читает
 * вовсе, а флаги нужны тому плагину, который ставит сам референс (манифест
 * `hvacdigital.json` → `GeelyAutoSystemUIPlugin-digital.apk`).
 *
 * Отсюда правила для нас:
 *  • пишем РОВНО флаги `0|1` для `temp`, `batt`, `exttemp` — 1:1 к референсу;
 *  • значение уставки климата в чужой prop НЕ пишем никогда (её читает сам плагин:
 *    `HvacVehicleTempWidget.getPropertyId() → 358614275`, `HvacVehicleTempWidget.java:578`);
 *  • `persist.sys.hvacdigital.via` — механизм установки плагина (`bind`/`install`, `w0/o.java:73`),
 *    маршрут «Домой» туда не пишется (см. отчёт, вопрос владельцу).
 */
internal object HvacDigitalProps {
    const val PROP_TEMP = "persist.sys.hvacdigital.temp"
    const val PROP_BATT = "persist.sys.hvacdigital.batt"
    const val PROP_EXT = "persist.sys.hvacdigital.exttemp"
    const val PROP_VIA = "persist.sys.hvacdigital.via"

    /**
     * Команда установки флагов — ровно как у референса (порядок temp → batt → exttemp, `&& echo`).
     * Покрыта тестом `HvacDigitalPropsTest`.
     */
    fun flagsCommand(tempOn: Boolean, battOn: Boolean, extOn: Boolean): String {
        val t = if (tempOn) "1" else "0"
        val b = if (battOn) "1" else "0"
        val e = if (extOn) "1" else "0"
        return "setprop $PROP_TEMP $t && setprop $PROP_BATT $b && setprop $PROP_EXT $e && echo FLAGS_OK"
    }

    /** Тот же смысл в виде «снимка» для сравнения с прошлым состоянием (dirty-флаг). */
    fun flagsFingerprint(tempOn: Boolean, battOn: Boolean, extOn: Boolean): String =
        "temp=${if (tempOn) 1 else 0} batt=${if (battOn) 1 else 0} ext=${if (extOn) 1 else 0}"

    /**
     * Значение «чужого» prop — ВСЕГДА флаг. Любая попытка положить сюда число (°C, %) считается
     * дефектом: этот хелпер используется и в тесте, который ловит такие значения.
     */
    fun isFlagValue(v: String): Boolean = v == "0" || v == "1"
}
