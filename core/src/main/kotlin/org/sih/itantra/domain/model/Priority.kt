package org.sih.itantra.domain.model

enum class Priority(val code: Byte, val label: String) {
    NORMAL(0, "NORMAL"),
    IMPORTANT(1, "IMPORTANT"),
    EMERGENCY(2, "EMERGENCY");

    companion object {
        fun fromCode(code: Byte): Priority = entries.firstOrNull { it.code == code } ?: NORMAL
    }
}
