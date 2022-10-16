package com.unhappychoice.droidflyer.presentation.view

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.salomonbrys.kodein.instance
import com.jakewharton.rxbinding2.view.clicks
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.databinding.LimitOrderViewBinding
import com.unhappychoice.droidflyer.domain.service.CurrentStatusService
import com.unhappychoice.droidflyer.extension.bindTo
import com.unhappychoice.droidflyer.extension.subscribeNext
import com.unhappychoice.droidflyer.extension.subscribeOnComputationObserveOnUI
import com.unhappychoice.droidflyer.presentation.adapter.BoardAdapter
import com.unhappychoice.droidflyer.presentation.adapter.BoardType
import com.unhappychoice.droidflyer.presentation.presenter.LimitOrderPresenter
import com.unhappychoice.droidflyer.presentation.style.DefaultStyle
import com.unhappychoice.droidflyer.presentation.view.core.BaseView
import io.reactivex.rxkotlin.addTo
import java.util.concurrent.TimeUnit

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
            .filter { presenter.amount.value != 0.0 }
            .subscribeNext {
                AlertDialog.Builder(context, R.style.DialogStyle)
                    .setTitle(R.string.create_limit_order)
                    .setMessage("${context.getString(R.string.sell)} / ${it.price} / ${presenter.amount.value}")
                    .setPositiveButton(context.getString(R.string.ok)) { _, _ -> presenter.sell(it.price) }
                    .setNegativeButton(context.getString(R.string.cancel), null)
                    .show()
            }.addTo(bag)

        bidAdapter.clickItems
            .filter { presenter.amount.value != 0.0 }
            .subscribeNext {
                AlertDialog.Builder(context, R.style.DialogStyle)
                    .setTitle(R.string.create_limit_order)
                    .setMessage("${context.getString(R.string.buy)} / ${it.price} / ${presenter.amount.value}")
                    .setPositiveButton(context.getString(R.string.ok)) { _, _ -> presenter.buy(it.price) }
                    .setNegativeButton(context.getString(R.string.cancel), null)
                    .show()
            }.addTo(bag)

        currentStatusService.board.asObservable()
            .throttleLast(300, TimeUnit.MILLISECONDS)
            .subscribeOnComputationObserveOnUI()
            .subscribeNext {
                askAdapter.items.value = it.asks.sortedBy { it.price }
                bidAdapter.items.value = it.bids.sortedByDescending { it.price }
                askAdapter.notifyDataSetChanged()
                bidAdapter.notifyDataSetChanged()
            }.addTo(bag)

        currentStatusService.orders.asObservable().bindTo(askAdapter.myOrders).addTo(bag)
        currentStatusService.orders.asObservable().bindTo(bidAdapter.myOrders).addTo(bag)

        presenter.apply {
            groupingSize.asObservable()
                .subscribeNext {
                    binding.groupingTextView.text = it.toString()
                    askAdapter.groupSize.value = it
                    bidAdapter.groupSize.value = it
                    askAdapter.notifyDataSetChanged()
                    bidAdapter.notifyDataSetChanged()
                }.addTo(bag)

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

    private fun setupStyle() {
        binding.groupingLabel.setTextColor(DefaultStyle.accentColor)
        binding.groupingTextView.setTextColor(DefaultStyle.accentColor)
        binding.plusGroupButton.setTextColor(DefaultStyle.accentColor)
        binding.minusGroupButton.setTextColor(DefaultStyle.accentColor)
        binding.plusGroupButton.setBackgroundColor(DefaultStyle.darkerPrimaryColor)
        binding.minusGroupButton.setBackgroundColor(DefaultStyle.darkerPrimaryColor)
    }
}
