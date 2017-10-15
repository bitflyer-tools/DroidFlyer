package com.unhappychoice.droidflyer.presentation.view

import android.content.Context
import android.content.DialogInterface
import android.support.v7.app.AlertDialog
import android.support.v7.widget.LinearLayoutManager
import android.util.AttributeSet
import com.github.salomonbrys.kodein.instance
import com.jakewharton.rxbinding2.view.clicks
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.domain.service.CurrentStatusService
import com.unhappychoice.droidflyer.extension.bindTo
import com.unhappychoice.droidflyer.extension.subscribeNext
import com.unhappychoice.droidflyer.extension.subscribeOnComputationObserveOnUI
import com.unhappychoice.droidflyer.extension.subscribeOnIoObserveOnUI
import com.unhappychoice.droidflyer.presentation.adapter.BoardAdapter
import com.unhappychoice.droidflyer.presentation.adapter.BoardType
import com.unhappychoice.droidflyer.presentation.presenter.LimitOrderPresenter
import com.unhappychoice.droidflyer.presentation.style.DefaultStyle
import com.unhappychoice.droidflyer.presentation.view.core.BaseView
import io.reactivex.Observable
import io.reactivex.rxkotlin.addTo
import kotlinx.android.synthetic.main.amount_selector_view.view.*
import kotlinx.android.synthetic.main.limit_order_view.view.*
import java.util.concurrent.TimeUnit

class LimitOrderView(context: Context, attr: AttributeSet?) : BaseView(context, attr) {
    val presenter: LimitOrderPresenter by instance()

    private val currentStatusService: CurrentStatusService by instance()
    private val askAdapter by lazy { BoardAdapter(context, BoardType.Ask) }
    private val bidAdapter by lazy { BoardAdapter(context, BoardType.Bid) }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        presenter.takeView(this)

        setupStyle()

        askList.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
            adapter = askAdapter
        }

        bidList.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
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
                    .setMessage("${context.getString(R.string.sell)} / ${it.price} / ${presenter.amount.value}")
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

        presenter.apply {
            groupingSize.asObservable()
                .subscribeNext {
                    groupingTextView.text = it.toString()
                    askAdapter.groupSize.value = it
                    bidAdapter.groupSize.value = it
                    askAdapter.notifyDataSetChanged()
                    bidAdapter.notifyDataSetChanged()
                }.addTo(bag)

            amount.asObservable().bindTo(amountSelector.amount).addTo(bag)
            unitSize.asObservable().bindTo(amountSelector.size).addTo(bag)
        }

        amountSelector.apply {
            amount.asObservable().subscribeNext { presenter.amount.setWithoutEvent(it) }.addTo(bag)
            size.asObservable().subscribeNext { presenter.unitSize.setWithoutEvent(it) }.addTo(bag)
            didIncrement.subscribeNext { presenter.incrementAmountByUnitSize() }.addTo(bag)
            didDecrement.subscribeNext { presenter.decrementAmountByUnitSize() }.addTo(bag)
        }

        plusGroupButton.clicks().subscribeNext { presenter.incrementGroupingSize() }.addTo(bag)
        minusGroupButton.clicks().subscribeNext { presenter.decrementGroupingSize() }.addTo(bag)
    }

    override fun onDetachedFromWindow() {
        presenter.dropView(this)
        super.onDetachedFromWindow()
    }

    private fun setupStyle() {
        groupingLabel.setTextColor(DefaultStyle.accentColor)
        groupingTextView.setTextColor(DefaultStyle.accentColor)
        plusGroupButton.setTextColor(DefaultStyle.accentColor)
        minusGroupButton.setTextColor(DefaultStyle.accentColor)
        plusGroupButton.setBackgroundColor(DefaultStyle.darkerPrimaryColor)
        minusGroupButton.setBackgroundColor(DefaultStyle.darkerPrimaryColor)
    }
}
