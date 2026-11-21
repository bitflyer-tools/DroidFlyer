package com.unhappychoice.droidflyer.presentation.view

import android.content.Context
import android.os.Build
import android.text.InputType
import android.text.Spanned
import android.text.method.NumberKeyListener
import android.util.AttributeSet
import android.view.LayoutInflater
import androidx.core.view.isVisible
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.databinding.AmountSelectorViewBinding
import com.unhappychoice.droidflyer.domain.service.CryptoCfdOrderSize
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val locales = resources.configuration.locales
            amountField.imeHintLocales = locales
            amountSymbols = DecimalFormatSymbols.getInstance(locales[0])
        }
        amountField.keyListener = AmountKeyListener(amountSymbols)
        amountField.setText(formatAmount(amount.value))

        binding.minimumOrderSizeMessage.text =
            context.getString(R.string.minimum_order_size, formatAmount(CryptoCfdOrderSize.MINIMUM))
        amount.asObservable()
            .subscribeNext { binding.minimumOrderSizeMessage.isVisible = it != 0.0 && !CryptoCfdOrderSize.isValid(it) }
            .addTo(bag)

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
        val separators = decimalSeparators(amountSymbols)
        return text.map { character ->
            when (character) {
                in separators -> '.'
                else -> character.digitToIntOrNull()?.digitToChar() ?: character
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

private fun decimalSeparators(symbols: DecimalFormatSymbols): String = ".,\u066B${symbols.decimalSeparator}"

private class AmountKeyListener(symbols: DecimalFormatSymbols) : NumberKeyListener() {
    private val separators = decimalSeparators(symbols)
    private val acceptedChars = ("0123456789" + (0..9).map { symbols.zeroDigit + it }.joinToString("") +
        separators).toCharArray()

    override fun getAcceptedChars(): CharArray = acceptedChars

    override fun getInputType(): Int = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL

    override fun filter(
        source: CharSequence, start: Int, end: Int, dest: Spanned, dstart: Int, dend: Int
    ): CharSequence? {
        if (start == end) return null

        val insertion = source.subSequence(start, end)
        val result = dest.replaceRange(dstart, dend, insertion)
        val valid = insertion.all { it.isDigit() || it in separators } &&
            result.count { it in separators } <= 1
        return if (valid) null else dest.subSequence(dstart, dend)
    }
}
