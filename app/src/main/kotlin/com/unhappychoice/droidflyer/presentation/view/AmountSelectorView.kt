package com.unhappychoice.droidflyer.presentation.view

import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.view.LayoutInflater
import com.jakewharton.rxbinding2.view.clicks
import com.jakewharton.rxbinding2.widget.textChanges
import com.unhappychoice.droidflyer.databinding.AmountSelectorViewBinding
import com.unhappychoice.droidflyer.extension.Variable
import com.unhappychoice.droidflyer.extension.bindTo
import com.unhappychoice.droidflyer.extension.subscribeNext
import com.unhappychoice.droidflyer.presentation.style.DefaultStyle
import com.unhappychoice.droidflyer.presentation.view.core.BaseView
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
        amountField.setText(formatAmount(amount.value))

        size.asObservable()
            .subscribeNext { select(it) }
            .addTo(bag)

        amount.asObservable()
            .distinctUntilChanged()
            .subscribeNext {
                if (parseAmount(amountField.text) != it) amountField.setText(formatAmount(it))
            }
            .addTo(bag)

        amountField.textChanges()
            .map(::parseAmount)
            .subscribeNext { if (amount.value != it) amount.value = it }
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

    private fun parseAmount(text: CharSequence): Double = text.map { character ->
        when (character) {
            amountSymbols.decimalSeparator, ',', '\u066B' -> '.'
            else -> character.digitToIntOrNull()?.digitToChar() ?: character
        }
    }.joinToString("").toDoubleOrNull()?.takeIf { it.isFinite() } ?: 0.0

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
