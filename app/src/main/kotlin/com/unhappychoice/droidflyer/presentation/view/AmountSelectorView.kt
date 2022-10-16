package com.unhappychoice.droidflyer.presentation.view

import android.content.Context
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

class AmountSelectorView(context: Context?, attr: AttributeSet?) : BaseView(context, attr) {
    val size = Variable(1.0)
    val amount = Variable(0.0)
    val didIncrement: PublishSubject<Unit> = PublishSubject.create<Unit>()
    val didDecrement: PublishSubject<Unit> = PublishSubject.create<Unit>()

    private val binding by lazy {
        AmountSelectorViewBinding.inflate(LayoutInflater.from(context), this, true)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        setupStyle()

        size.asObservable()
            .subscribeNext { select(it) }
            .addTo(bag)

        amount.asObservable()
            .distinctUntilChanged()
            .subscribeNext { binding.amountTextView.setText(it.toString()) }
            .addTo(bag)

        binding.amountTextView.textChanges()
            .subscribeNext { amount.value = it.toString().toDouble() }
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
