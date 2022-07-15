package com.unhappychoice.droidflyer.presentation.view

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.app.AlertDialog
import com.github.salomonbrys.kodein.instance
import com.jakewharton.rxbinding2.view.clicks
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.databinding.MarketOrderViewBinding
import com.unhappychoice.droidflyer.domain.service.CurrentStatusService
import com.unhappychoice.droidflyer.extension.bindTo
import com.unhappychoice.droidflyer.extension.splitByComma
import com.unhappychoice.droidflyer.extension.subscribeNext
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Position
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.wholeSize
import com.unhappychoice.droidflyer.presentation.presenter.MarketOrderPresenter
import com.unhappychoice.droidflyer.presentation.style.DefaultStyle
import com.unhappychoice.droidflyer.presentation.view.core.BaseView
import io.reactivex.rxkotlin.Observables
import io.reactivex.rxkotlin.addTo

class MarketOrderView(context: Context?, attr: AttributeSet?) : BaseView(context, attr) {
    val presenter: MarketOrderPresenter by instance()
    val currentStatusService: CurrentStatusService by instance()

    private val binding by lazy { MarketOrderViewBinding.bind(this) }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        presenter.takeView(this)

        setupStyle()

        currentStatusService.apply {
            buyPrice.asObservable().subscribeNext { binding.buyPrice.text = it.splitByComma() }.addTo(bag)
            sellPrice.asObservable().subscribeNext { binding.sellPrice.text = it.splitByComma() }.addTo(bag)
        }

        presenter.apply {
            amount.asObservable().bindTo(binding.amountSelector.amount).addTo(bag)
            unitSize.asObservable().bindTo(binding.amountSelector.size).addTo(bag)
        }

        Observables.combineLatest(
            presenter.amount.asObservable(),
            presenter.isLoading.asObservable()
        ) { amount, isLoading -> amount != 0.0 && !isLoading }
            .subscribeNext {
                binding.buyButton.isEnabled = it
                binding.sellButton.isEnabled = it

                binding.buyButton.alpha = if (it) 1.0f else 0.4f
                binding.sellButton.alpha = if (it) 1.0f else 0.4f
            }.addTo(bag)

        Observables.combineLatest(
            currentStatusService.position.asObservable(),
            presenter.isLoading.asObservable()
        ) { position: List<Position>, isLoading: Boolean -> position.wholeSize() != 0.0 && !isLoading }
            .subscribeNext {
                binding.clearButton.isEnabled = it
                binding.clearButton.alpha = if (it) 1.0f else 0.4f
            }.addTo(bag)

        binding.amountSelector.apply {
            amount.asObservable().distinctUntilChanged().bindTo(presenter.amount).addTo(bag)
            size.asObservable().distinctUntilChanged().bindTo(presenter.unitSize).addTo(bag)
            didIncrement.subscribeNext { presenter.incrementAmountByUnitSize() }.addTo(bag)
            didDecrement.subscribeNext { presenter.decrementAmountByUnitSize() }.addTo(bag)
        }

        binding.buyButton.clicks().subscribeNext { presenter.buy() }.addTo(bag)
        binding.sellButton.clicks().subscribeNext { presenter.sell() }.addTo(bag)

        binding.clearButton.clicks()
            .filter { currentStatusService.position.value.wholeSize() != 0.0 }
            .subscribeNext {
                val size = currentStatusService.position.value.wholeSize()
                val side = if (size > 0.0) context.getString(R.string.sell) else context.getString(R.string.buy)
                AlertDialog.Builder(context, R.style.DialogStyle)
                    .setTitle(R.string.clear_all_positions)
                    .setMessage("$side / ${Math.abs(size)}")
                    .setPositiveButton(context.getString(R.string.ok)) { _, _ -> presenter.clearPosition() }
                    .setNegativeButton(context.getString(R.string.cancel), null)
                    .show()
            }.addTo(bag)
    }

    override fun onDetachedFromWindow() {
        presenter.dropView(this)
        super.onDetachedFromWindow()
    }

    private fun setupStyle() {
        binding.buyPrice.setTextColor(DefaultStyle.accentColor)
        binding.sellPrice.setTextColor(DefaultStyle.accentColor)
        binding.buyButton.setBackgroundColor(DefaultStyle.buyColor)
        binding.buyButtonText.setTextColor(DefaultStyle.darkerAccentColor)
        binding.sellButton.setBackgroundColor(DefaultStyle.sellColor)
        binding.sellButtonText.setTextColor(DefaultStyle.darkerAccentColor)
        binding.clearButton.setBackgroundColor(DefaultStyle.darkerPrimaryColor)
        binding.clearButton.setTextColor(DefaultStyle.accentColor)
    }
}
