package com.unhappychoice.droidflyer.presentation.view

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import com.jakewharton.rxbinding2.view.clicks
import com.jakewharton.rxbinding2.widget.textChanges
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.extension.Variable
import com.unhappychoice.droidflyer.extension.bindTo
import com.unhappychoice.droidflyer.extension.subscribeNext
import com.unhappychoice.droidflyer.presentation.style.DefaultStyle
import com.unhappychoice.droidflyer.presentation.view.core.BaseView
import io.reactivex.rxkotlin.addTo
import io.reactivex.subjects.PublishSubject
import kotlinx.android.synthetic.main.amount_selector_view.view.*

class AmountSelectorView(context: Context?, attr: AttributeSet?) : BaseView(context, attr) {
    init {
        LayoutInflater.from(context).inflate(R.layout.amount_selector_view, this)
    }

    val size = Variable(1.0)
    val amount = Variable(0.0)
    val didIncrement: PublishSubject<Unit> = PublishSubject.create<Unit>()
    val didDecrement: PublishSubject<Unit> = PublishSubject.create<Unit>()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        setupStyle()

        size.asObservable()
            .subscribeNext { select(it) }
            .addTo(bag)

        amount.asObservable()
            .subscribeNext { amountTextView.setText(it.toString()) }
            .addTo(bag)

        amountTextView.textChanges()
            .subscribeNext { amount.setWithoutEvent(it.toString().toDouble()) }
            .addTo(bag)

        dotOneButton.clicks()
            .subscribeNext { size.value = 0.1 }
            .addTo(bag)

        dotFiveButton.clicks()
            .subscribeNext { size.value = 0.5 }
            .addTo(bag)

        oneButton.clicks()
            .subscribeNext { size.value = 1.0 }
            .addTo(bag)

        fiveButton.clicks()
            .subscribeNext { size.value = 5.0 }
            .addTo(bag)

        tenButton.clicks()
            .subscribeNext { size.value = 10.0 }
            .addTo(bag)

        plusButton.clicks().bindTo(didIncrement).addTo(bag)
        minusButton.clicks().bindTo(didDecrement).addTo(bag)
    }

    private fun select(size: Double) {
        dotOneButton.alpha = 0.4f
        dotFiveButton.alpha = 0.4f
        oneButton.alpha = 0.4f
        fiveButton.alpha = 0.4f
        tenButton.alpha = 0.4f
        when (size) {
            0.1 -> dotOneButton.alpha = 1.0f
            0.5 -> dotFiveButton.alpha = 1.0f
            1.0 -> oneButton.alpha = 1.0f
            5.0 -> fiveButton.alpha = 1.0f
            10.0 -> tenButton.alpha = 1.0f
        }
    }

    private fun setupStyle() {
        dotOneButton.setTextColor(DefaultStyle.accentColor)
        dotFiveButton.setTextColor(DefaultStyle.accentColor)
        oneButton.setTextColor(DefaultStyle.accentColor)
        fiveButton.setTextColor(DefaultStyle.accentColor)
        tenButton.setTextColor(DefaultStyle.accentColor)

        amountTextView.setTextColor(DefaultStyle.accentColor)
        amountTextView.setTextColor(DefaultStyle.accentColor)

        plusButton.setBackgroundColor(DefaultStyle.primaryColor)
        plusButton.setTextColor(DefaultStyle.accentColor)
        minusButton.setBackgroundColor(DefaultStyle.primaryColor)
        minusButton.setTextColor(DefaultStyle.accentColor)
    }
}