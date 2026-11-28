package com.unhappychoice.droidflyer.presentation.view

import android.os.Build
import android.text.InputType
import android.text.Spanned
import android.text.method.NumberKeyListener
import android.widget.EditText
import androidx.core.os.ConfigurationCompat
import java.text.DecimalFormatSymbols
import java.util.Locale

internal fun EditText.configureDecimalAmountInput(): DecimalFormatSymbols {
    val configuration = resources.configuration
    val locale = ConfigurationCompat.getLocales(configuration)[0] ?: Locale.getDefault()
    val symbols = DecimalFormatSymbols.getInstance(locale)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        imeHintLocales = configuration.locales
    }
    keyListener = AmountKeyListener(symbols)
    return symbols
}

internal fun CharSequence.normalizeDecimalAmount(symbols: DecimalFormatSymbols): String? =
    map { character ->
        when (character) {
            symbols.decimalSeparator -> '.'
            else -> character.digitToIntOrNull()?.digitToChar() ?: return null
        }
    }.joinToString("")

private class AmountKeyListener(symbols: DecimalFormatSymbols) : NumberKeyListener() {
    private val separator = symbols.decimalSeparator
    private val acceptedChars = ("0123456789" + (0..9).map { symbols.zeroDigit + it }.joinToString("") +
        separator).toCharArray()

    override fun getAcceptedChars(): CharArray = acceptedChars

    override fun getInputType(): Int = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL

    override fun filter(
        source: CharSequence, start: Int, end: Int, dest: Spanned, dstart: Int, dend: Int
    ): CharSequence? {
        if (start == end) return null

        val insertion = source.subSequence(start, end)
        val result = dest.replaceRange(dstart, dend, insertion)
        val valid = insertion.all { it.isDigit() || it == separator } &&
            result.count { it == separator } <= 1
        return if (valid) null else dest.subSequence(dstart, dend)
    }
}
