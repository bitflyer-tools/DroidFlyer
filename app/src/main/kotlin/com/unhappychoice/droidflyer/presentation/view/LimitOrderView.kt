package com.unhappychoice.droidflyer.presentation.view

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.databinding.LimitOrderViewBinding
import com.unhappychoice.droidflyer.domain.service.CurrentStatusService
import com.unhappychoice.droidflyer.extension.bindTo
import com.unhappychoice.droidflyer.extension.subscribeNext
import com.unhappychoice.droidflyer.presentation.adapter.BoardAdapter
import com.unhappychoice.droidflyer.presentation.adapter.BoardType
import com.unhappychoice.droidflyer.presentation.presenter.LimitOrderPresenter
import com.unhappychoice.droidflyer.presentation.presenter.LimitOrderSide
import com.unhappychoice.droidflyer.presentation.presenter.LimitOrderTerms
import com.unhappychoice.droidflyer.presentation.style.DefaultStyle
import com.unhappychoice.droidflyer.presentation.view.core.BaseView
import com.unhappychoice.droidflyer.presentation.view.core.clicks
import com.unhappychoice.droidflyer.presentation.view.core.showTradingConfirmation
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.rxkotlin.Observables
import io.reactivex.rxkotlin.addTo
import org.kodein.di.instance

class LimitOrderView(context: Context, attr: AttributeSet?) : BaseView(context, attr) {
    val presenter: LimitOrderPresenter by instance()

    private val currentStatusService: CurrentStatusService by instance()
    private val askAdapter by lazy { BoardAdapter(context, BoardType.Ask) }
    private val bidAdapter by lazy { BoardAdapter(context, BoardType.Bid) }
    private val binding by lazy { LimitOrderViewBinding.bind(this) }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        presenter.takeView(this)

        setupStyle()

        binding.askList.apply {
            layoutManager = LinearLayoutManager(context, RecyclerView.VERTICAL, false)
            adapter = askAdapter
        }

        binding.bidList.apply {
            layoutManager = LinearLayoutManager(context, RecyclerView.VERTICAL, false)
            adapter = bidAdapter
        }

        askAdapter.clickItems
            .filter { presenter.canSubmitOrder }
            .subscribeNext { confirmOrder(LimitOrderSide.SELL, it.price) }.addTo(bag)

        bidAdapter.clickItems
            .filter { presenter.canSubmitOrder }
            .subscribeNext { confirmOrder(LimitOrderSide.BUY, it.price) }.addTo(bag)

        Observables.combineLatest(
            observeLimitOrderRows(currentStatusService.board.asObservable(), presenter.groupingSize.asObservable()),
            currentStatusService.orders.asObservable()
        ) { rows, orders -> rows to orders }
            .observeOn(AndroidSchedulers.mainThread())
            .subscribeNext { (rows, orders) ->
                askAdapter.applyRows(rows.asks, rows.groupingSize, orders)
                bidAdapter.applyRows(rows.bids, rows.groupingSize, orders)
                val groupingText = rows.groupingSize.toString()
                if (binding.groupingTextView.text.toString() != groupingText) {
                    binding.groupingTextView.text = groupingText
                }
            }.addTo(bag)

        presenter.apply {
            amount.asObservable().distinctUntilChanged().bindTo(binding.amountSelector.amount).addTo(bag)
            unitSize.asObservable().distinctUntilChanged().bindTo(binding.amountSelector.size).addTo(bag)
        }

        binding.amountSelector.apply {
            amount.asObservable().distinctUntilChanged().bindTo(presenter.amount).addTo(bag)
            size.asObservable().distinctUntilChanged().bindTo(presenter.unitSize).addTo(bag)
            didIncrement.subscribeNext { presenter.incrementAmountByUnitSize() }.addTo(bag)
            didDecrement.subscribeNext { presenter.decrementAmountByUnitSize() }.addTo(bag)
        }

        binding.plusGroupButton.clicks().subscribeNext { presenter.incrementGroupingSize() }.addTo(bag)
        binding.minusGroupButton.clicks().subscribeNext { presenter.decrementGroupingSize() }.addTo(bag)
    }

    override fun onDetachedFromWindow() {
        presenter.dropView(this)
        super.onDetachedFromWindow()
    }

    private fun confirmOrder(side: LimitOrderSide, price: Long) {
        val terms = LimitOrderTerms(side, price, Math.abs(presenter.amount.value))
        AlertDialog.Builder(context, R.style.DialogStyle)
            .setTitle(R.string.create_limit_order)
            .setMessage("${context.getString(terms.side.label)} / ${terms.price} / ${terms.size}")
            .setPositiveButton(R.string.ok) { _, _ -> presenter.submitOrder(terms) }
            .setNegativeButton(R.string.cancel, null)
            .showTradingConfirmation()
    }

    private fun setupStyle() {
        binding.groupingLabel.setTextColor(DefaultStyle.accentColor)
        binding.groupingTextView.setTextColor(DefaultStyle.accentColor)
        binding.plusGroupButton.setTextColor(DefaultStyle.accentColor)
        binding.minusGroupButton.setTextColor(DefaultStyle.accentColor)
        binding.plusGroupButton.setBackgroundColor(DefaultStyle.darkerPrimaryColor)
        binding.minusGroupButton.setBackgroundColor(DefaultStyle.darkerPrimaryColor)
    }
}
