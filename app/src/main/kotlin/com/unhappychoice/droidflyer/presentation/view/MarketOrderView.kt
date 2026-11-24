package com.unhappychoice.droidflyer.presentation.view

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.databinding.MarketOrderViewBinding
import com.unhappychoice.droidflyer.domain.service.CryptoCfdOrderSize
import com.unhappychoice.droidflyer.domain.service.CurrentStatusService
import com.unhappychoice.droidflyer.domain.service.PrivateTradingState.CloseConfirmation
import com.unhappychoice.droidflyer.extension.bindTo
import com.unhappychoice.droidflyer.extension.splitByComma
import com.unhappychoice.droidflyer.extension.subscribeNext
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Position
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.wholeSize
import com.unhappychoice.droidflyer.presentation.presenter.MarketOrderPresenter
import com.unhappychoice.droidflyer.presentation.style.DefaultStyle
import com.unhappychoice.droidflyer.presentation.view.core.BaseView
import com.unhappychoice.droidflyer.presentation.view.core.clicks
import com.unhappychoice.droidflyer.presentation.view.core.showTradingConfirmation
import io.reactivex.rxkotlin.Observables
import io.reactivex.rxkotlin.addTo
import kotlin.math.abs
import org.kodein.di.instance

class MarketOrderView(context: Context?, attr: AttributeSet?) : BaseView(context, attr) {
    val presenter: MarketOrderPresenter by instance()
    val currentStatusService: CurrentStatusService by instance()

    private val binding by lazy { MarketOrderViewBinding.bind(this) }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        presenter.takeView(this)

        setupStyle()

        currentStatusService.apply {
            buyPrice.asObservable().subscribeNext { binding.buyPrice.text = formatQuote(it) }.addTo(bag)
            sellPrice.asObservable().subscribeNext { binding.sellPrice.text = formatQuote(it) }.addTo(bag)
        }

        presenter.apply {
            maximumOrderSize.asObservable().bindTo(binding.amountSelector.maximumOrderSize).addTo(bag)
            amount.asObservable().bindTo(binding.amountSelector.amount).addTo(bag)
            unitSize.asObservable().bindTo(binding.amountSelector.size).addTo(bag)
        }

        val privateClientAvailable = currentStatusService.privateState.observePrivateClientAvailability()
        privateClientAvailable.subscribeNext { binding.credentialsRequired.isVisible = !it }.addTo(bag)

        Observables.combineLatest(
            presenter.amount.asObservable(),
            presenter.isLoading.asObservable(),
            presenter.maximumOrderSize.asObservable(),
            privateClientAvailable
        ) { amount, isLoading, maximum, available ->
            available && !isLoading && CryptoCfdOrderSize.isValid(amount, maximum)
        }
            .subscribeNext {
                binding.buyButton.isEnabled = it
                binding.sellButton.isEnabled = it

                binding.buyButton.alpha = if (it) 1.0f else 0.4f
                binding.sellButton.alpha = if (it) 1.0f else 0.4f
            }.addTo(bag)

        Observables.combineLatest(
            currentStatusService.position.asObservable(),
            presenter.isLoading.asObservable(),
            privateClientAvailable
        ) { position: List<Position>, isLoading: Boolean, available: Boolean ->
            val size = abs(position.wholeSize())
            val belowMinimum = size != 0.0 && !CryptoCfdOrderSize.meetsMinimum(size)
            Pair(available && !isLoading && CryptoCfdOrderSize.meetsMinimum(size), belowMinimum)
        }
            .subscribeNext { (enabled, belowMinimum) ->
                binding.clearButton.isEnabled = enabled
                binding.clearButton.alpha = if (enabled) 1.0f else 0.4f
                binding.clearPositionMessage.isVisible = belowMinimum
                if (belowMinimum) {
                    binding.clearPositionMessage.text =
                        context.getString(R.string.minimum_order_size, CryptoCfdOrderSize.MINIMUM.toString())
                }
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
            .subscribeNext { presenter.positionCloseOrder()?.let(::showCloseConfirmation) }
            .addTo(bag)
    }

    override fun onDetachedFromWindow() {
        presenter.dropView(this)
        super.onDetachedFromWindow()
    }

    private fun formatQuote(price: Long): String = if (price == 0L) "—" else price.splitByComma()

    private fun showCloseConfirmation(confirmation: CloseConfirmation) {
        val order = confirmation.order
        val side = context.getString(if (order.side == "SELL") R.string.sell else R.string.buy)
        val summary = "$side / ${order.size}"
        val message = "$summary\n\n" + context.getString(R.string.close_positions_multiple_orders)
        AlertDialog.Builder(context, R.style.DialogStyle)
            .setTitle(R.string.clear_all_positions)
            .setMessage(message)
            .setPositiveButton(R.string.ok) { _, _ ->
                presenter.clearPosition(confirmation, ::showCloseConfirmation)
            }
            .setNegativeButton(R.string.cancel, null)
            .showTradingConfirmation()
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
