package ru.pivoskan.app.util

import java.util.Locale

private val WHITESPACE = Regex("[\\s\\u00A0]+")

/** Money from store APIs is always represented by exact integer kopecks. */
fun formatMoney(kopecks: Int): String {
    val absolute = kotlin.math.abs(kopecks.toLong())
    val whole = formatPrice((absolute / 100).toInt()).removeSuffix("\u00A0₽")
    val fraction = (absolute % 100).toInt()
    return (if (kopecks < 0) "-" else "") + whole +
        (if (fraction == 0) "" else ",${fraction.toString().padStart(2, '0')}") + "\u00A0₽"
}

/** Formats whole rubles as "1 299 ₽" with non-breaking spaces. */
fun formatPrice(rub: Int): String {
    val negative = rub < 0
    val digits = kotlin.math.abs(rub.toLong()).toString()
    val grouped = buildString {
        digits.forEachIndexed { index, c ->
            if (index > 0 && (digits.length - index) % 3 == 0) append('\u00A0')
            append(c)
        }
    }
    return (if (negative) "-" else "") + grouped + "\u00A0₽"
}

/** Formats millilitres as litres with a comma: 500 -> "0,5 л", 330 -> "0,33 л". */
fun formatVolume(ml: Int): String {
    val whole = ml / 1000
    val fraction = ml % 1000
    val fractionText = when {
        fraction == 0 -> ""
        fraction % 100 == 0 -> ",${fraction / 100}"
        fraction % 10 == 0 -> ",${(fraction / 10).toString().padStart(2, '0')}"
        else -> ",${fraction.toString().padStart(3, '0')}"
    }
    return "$whole$fractionText л"
}

/** Lowercases, maps ё to е, collapses internal whitespace and trims. */
fun normalize(s: String): String =
    s.lowercase(Locale.ROOT)
        .replace('ё', 'е')
        .replace(WHITESPACE, " ")
        .trim()
