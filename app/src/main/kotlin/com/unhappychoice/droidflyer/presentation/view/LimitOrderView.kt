package com.unhappychoice.droidflyer.presentation.view

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager.widget.ViewPager
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.databinding.LimitOrderViewBinding
import com.unhappychoice.droidflyer.domain.service.CurrentStatusService
import com.unhappychoice.droidflyer.extension.bindTo
import com.unhappychoice.droidflyer.extension.subscribeNext
import com.unhappychoice.droidflyer.presentation.adapter.BoardAdapter
import com.unhappychoice.droidflyer.presentation.adapter.BoardType
import com.unhappychoice.droidflyer.presentation.adapter.OrderViewPagerAdapter.Companion.LIMIT_POSITION
import com.unhappychoice.droidflyer.presentation.presenter.LimitOrderPresenter
import com.unhappychoice.droidflyer.presentation.presenter.LimitOrderSide
import com.unhappychoice.droidflyer.presentation.style.DefaultStyle
import com.unhappychoice.droidflyer.presentation.view.core.BaseView
import com.unhappychoice.droidflyer.presentation.view.core.clicks
import com.unhappychoice.droidflyer.presentation.view.core.showTradingConfirmation
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.rxkotlin.addTo
import org.kodein.di.instance

class LimitOrderView(context: Context, attr: AttributeSet?) : BaseView(context, attr) {
    val presenter: LimitOrderPresenter by instance()

    private val currentStatusService: CurrentStatusService by instance()
    private val askAdapter by lazy { BoardAdapter(context, BoardType.Ask) }
    private val bidAdapter by lazy { BoardAdapter(context, BoardType.Bid) }
    private val binding by lazy { LimitOrderViewBinding.bind(this) }

    private var confirmationDialog: AlertDialog? = null

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

        askAdapter.clickItems.subscribeNext { confirmOrder(LimitOrderSide.SELL, it.price) }.addTo(bag)
        bidAdapter.clickItems.subscribeNext { confirmOrder(LimitOrderSide.BUY, it.price) }.addTo(bag)

        bindOrderEntry()

        // replay(1).autoConnect() so both subscribers below share one ViewPager listener while each
        // still receives the current page state immediately, like two independent subscriptions would.
        // The connection is tied to bag so the listener is removed on detach instead of leaking.
        val pageSelection = observePageSelection().distinctUntilChanged().replay(1).autoConnect(1) { it.addTo(bag) }
        pageSelection
            .subscribeNext { currentStatusService.limitVisible.value = it }
            .addTo(bag)

        observeLimitOrderRows(
            pageSelection,
            currentStatusService.board.asObservable(),
            presenter.groupingSize.asObservable(),
            currentStatusService.orders.asObservable(),
            AndroidSchedulers.mainThread()
        )
            .subscribeNext { (rows, orders) ->
                askAdapter.applyRows(rows.asks, rows.groupingSize, orders)
                bidAdapter.applyRows(rows.bids, rows.groupingSize, orders)
                val groupingText = rows.groupingSize.toString()
                if (binding.groupingTextView.text.toString() != groupingText) {
                    binding.groupingTextView.text = groupingText
                }
            }.addTo(bag)

        presenter.apply {
            maximumOrderSize.asObservable().bindTo(binding.amountSelector.maximumOrderSize).addTo(bag)
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
        confirmationDialog?.dismiss()
        confirmationDialog = null
        presenter.dropView(this)
        currentStatusService.limitVisible.value = false
        super.onDetachedFromWindow()
    }

    private fun bindOrderEntry() {
        presenter.privateClientAvailable.subscribeNext { binding.credentialsRequired.isVisible = !it }.addTo(bag)
        presenter.orderEntryEnabled
            .subscribeNext {
                askAdapter.orderEntryEnabled = it
                bidAdapter.orderEntryEnabled = it
            }.addTo(bag)
    }

    private fun observePageSelection(): Observable<Boolean> {
        val pager = parent as? ViewPager ?: return Observable.just(false)
        return Observable.create { emitter ->
            val listener = object : ViewPager.SimpleOnPageChangeListener() {
                override fun onPageSelected(position: Int) {
                    emitter.onNext(position == LIMIT_POSITION)
                }
            }
            pager.addOnPageChangeListener(listener)
            emitter.setCancellable { pager.removeOnPageChangeListener(listener) }
            emitter.onNext(pager.currentItem == LIMIT_POSITION)
        }
    }

    private fun confirmOrder(side: LimitOrderSide, price: Long) {
        val confirmation = presenter.prepareOrder(side, price) ?: return
        val order = confirmation.order
        confirmationDialog?.dismiss()
        val dialog = AlertDialog.Builder(context, R.style.DialogStyle)
            .setTitle(R.string.create_limit_order)
            .setMessage("${context.getString(confirmation.side.label)} / ${order.request.price} / ${order.request.size}")
            .setPositiveButton(R.string.ok) { _, _ -> presenter.submitOrder(confirmation) }
            .setNegativeButton(R.string.cancel, null)
            .showTradingConfirmation()
        confirmationDialog = dialog
        val sessionEnd = order.sessionEnded.subscribe { dialog.dismiss() }
        dialog.setOnDismissListener {
            sessionEnd.dispose()
            if (confirmationDialog === dialog) confirmationDialog = null
        }
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
