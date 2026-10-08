package com.example.voiceapp3.tools

/**
 * Проект только под Geely EX2, ГУ IHU629G.
 *
 * Дамп 2026-09-05 с этой машины раньше помечался COOLRAY, потому что
 * детектор принимал префикс IHU62 за Coolray. Ветки Starship / E5 / Coolray
 * больше не используются.
 */
enum class ModelEnum(val value: String) {
    EX2("EX2"),
}

object CarModel {
    fun getCarModel(): ModelEnum = ModelEnum.EX2

    fun getModelName(modelEnum: ModelEnum): String = "Geely EX2"

    val isEx2: Boolean = true

    /** Оставлено false, чтобы случайно оставшиеся старые файлы не уходили в чужие ветки. */
    val isCoolray: Boolean = false
    val isE5: Boolean = false
}

val isCoolray: Boolean get() = false
val isE5: Boolean get() = false
