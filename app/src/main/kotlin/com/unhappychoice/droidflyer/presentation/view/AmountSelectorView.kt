package com.unhappychoice.droidflyer.presentation.view

import android.content.Context
import android.os.Build
import android.text.InputType
import android.text.Spanned
import android.text.method.NumberKeyListener
import android.util.AttributeSet
import android.view.LayoutInflater
import androidx.core.os.ConfigurationCompat
import com.unhappychoice.droidflyer.databinding.AmountSelectorViewBinding
import com.unhappychoice.droidflyer.extension.Variable
import com.unhappychoice.droidflyer.extension.bindTo
import com.unhappychoice.droidflyer.extension.subscribeNext
import com.unhappychoice.droidflyer.presentation.style.DefaultStyle
import com.unhappychoice.droidflyer.presentation.view.core.BaseView
import com.unhappychoice.droidflyer.presentation.view.core.clicks
import com.unhappychoice.droidflyer.presentation.view.core.textChanges
import io.reactivex.rxkotlin.addTo
import io.reactivex.subjects.PublishSubject
import java.math.BigDecimal
import java.text.DecimalFormatSymbols
import java.util.Locale

class AmountSelectorView(context: Context?, attr: AttributeSet?) : BaseView(context, attr) {
    val size = Variable(1.0)
    val amount = Variable(0.0)
    val didIncrement: PublishSubject<Unit> = PublishSubject.create<Unit>()
    val didDecrement: PublishSubject<Unit> = PublishSubject.create<Unit>()

    private val binding by lazy {
        AmountSelectorViewBinding.inflate(LayoutInflater.from(context), this, true)
    }
    private var amountSymbols = DecimalFormatSymbols.getInstance(Locale.US)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        setupStyle()

        val amountField = binding.amountTextView
        val configuration = resources.configuration
        val locale = ConfigurationCompat.getLocales(configuration)[0] ?: Locale.getDefault()
        amountSymbols = DecimalFormatSymbols.getInstance(locale)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            amountField.imeHintLocales = configuration.locales
        }
        amountField.keyListener = AmountKeyListener(amountSymbols)
        amountField.setText(formatAmount(amount.value))

        size.asObservable()
            .subscribeNext { select(it) }
            .addTo(bag)

        amount.asObservable()
            .distinctUntilChanged()
            .filter { it != parseAmount(amountField.text) }
            .subscribeNext { amountField.setText(formatAmount(it)) }
            .addTo(bag)

        amountField.textChanges()
            .map(::parseAmount)
            .filter { it != amount.value }
            .bindTo(amount)
            .addTo(bag)

        binding.dotOneButton.clicks()
            .subscribeNext { size.value = 0.01 }
            .addTo(bag)

        binding.dotFiveButton.clicks()
            .subscribeNext { size.value = 0.1 }
            .addTo(bag)

        binding.oneButton.clicks()
            .subscribeNext { size.value = 1.0 }
            .addTo(bag)

        binding.fiveButton.clicks()
            .subscribeNext { size.value = 5.0 }
            .addTo(bag)

        binding.tenButton.clicks()
            .subscribeNext { size.value = 10.0 }
            .addTo(bag)

        binding.plusButton.clicks().bindTo(didIncrement).addTo(bag)
        binding.minusButton.clicks().bindTo(didDecrement).addTo(bag)
    }

    private fun parseAmount(text: CharSequence): Double {
        return text.map { character ->
            when (character) {
                amountSymbols.decimalSeparator -> '.'
                else -> character.digitToIntOrNull()?.digitToChar() ?: return 0.0
            }
        }.joinToString("").toOrderAmount()
    }

    private fun formatAmount(amount: Double): String =
        BigDecimal.valueOf(amount).toPlainString().map { character ->
            when (character) {
                '.' -> amountSymbols.decimalSeparator
                in '0'..'9' -> amountSymbols.zeroDigit + (character - '0')
                else -> character
            }
        }.joinToString("")

    private fun select(size: Double) {
        binding.dotOneButton.alpha = 0.4f
        binding.dotFiveButton.alpha = 0.4f
        binding.oneButton.alpha = 0.4f
        binding.fiveButton.alpha = 0.4f
        binding.tenButton.alpha = 0.4f
        when (size) {
            0.01 -> binding.dotOneButton.alpha = 1.0f
            0.1 -> binding.dotFiveButton.alpha = 1.0f
            1.0 -> binding.oneButton.alpha = 1.0f
            5.0 -> binding.fiveButton.alpha = 1.0f
            10.0 -> binding.tenButton.alpha = 1.0f
        }
    }

    private fun setupStyle() {
        binding.dotOneButton.setTextColor(DefaultStyle.accentColor)
        binding.dotFiveButton.setTextColor(DefaultStyle.accentColor)
        binding.oneButton.setTextColor(DefaultStyle.accentColor)
        binding.fiveButton.setTextColor(DefaultStyle.accentColor)
        binding.tenButton.setTextColor(DefaultStyle.accentColor)

        binding.amountTextView.setTextColor(DefaultStyle.accentColor)
        binding.amountTextView.setTextColor(DefaultStyle.accentColor)

        binding.plusButton.setBackgroundColor(DefaultStyle.primaryColor)
        binding.plusButton.setTextColor(DefaultStyle.accentColor)
        binding.minusButton.setBackgroundColor(DefaultStyle.primaryColor)
        binding.minusButton.setTextColor(DefaultStyle.accentColor)
    }
}

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
