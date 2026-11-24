package com.unhappychoice.droidflyer.presentation.view

import android.content.Context
import android.util.AttributeSet
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.databinding.OrdersViewBinding
import com.unhappychoice.droidflyer.domain.service.PrivateTradingState.ActiveOrdersStatus
import com.unhappychoice.droidflyer.extension.subscribeNext
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrder
import com.unhappychoice.droidflyer.presentation.adapter.FillAdapter
import com.unhappychoice.droidflyer.presentation.adapter.OrderAdapter
import com.unhappychoice.droidflyer.presentation.presenter.FillHistory
import com.unhappychoice.droidflyer.presentation.presenter.OrdersPresenter
import com.unhappychoice.droidflyer.presentation.style.DefaultStyle
import com.unhappychoice.droidflyer.presentation.view.core.BaseView
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo
import org.kodein.di.instance

class OrdersView(context: Context?, attr: AttributeSet?) : BaseView(context, attr) {
    val presenter: OrdersPresenter by instance()
    private val binding by lazy { OrdersViewBinding.bind(this) }
    private val orderAdapter by lazy { OrderAdapter(context) }
    private val fillAdapter = FillAdapter()
    private val subscriptions = CompositeDisposable()
    private var appliedFillsUpdate: FillHistory.State.Update? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        presenter.takeView(this)
        setupFills()

        binding.orderList.apply {
            layoutManager = LinearLayoutManager(context, RecyclerView.VERTICAL, false)
            adapter = orderAdapter
        }

        presenter.orders
            .subscribeNext { orderAdapter.updateOrders(it) }
            .addTo(subscriptions)

        binding.activeOrdersStatus.setTextColor(DefaultStyle.accentColor)
        presenter.activeOrdersStatus
            .subscribeNext(::renderActiveOrdersStatus)
            .addTo(subscriptions)

        presenter.ordersMayBeIncomplete
            .subscribeNext { binding.orderLimitWarning.isVisible = it }
            .addTo(subscriptions)

        presenter.cancellationFailures
            .subscribeNext(::showCancellationFailure)
            .addTo(subscriptions)

        orderAdapter.clickCancel
            .subscribeNext { presenter.cancelOrder(it) }
            .addTo(subscriptions)
    }

    override fun onDetachedFromWindow() {
        subscriptions.clear()
        binding.ordersTabs.removeOnTabSelectedListener(tabListener)
        presenter.fillHistory.clear()
        presenter.dropView(this)
        super.onDetachedFromWindow()
    }

    private fun renderActiveOrdersStatus(status: ActiveOrdersStatus) {
        val message = when (status) {
            ActiveOrdersStatus.EMPTY -> R.string.no_active_orders
            ActiveOrdersStatus.UNAVAILABLE -> R.string.active_orders_unavailable
            ActiveOrdersStatus.STALE -> R.string.active_orders_stale
            ActiveOrdersStatus.INITIAL, ActiveOrdersStatus.AVAILABLE -> null
        }
        message?.let { binding.activeOrdersStatus.setText(it) }
        binding.activeOrdersStatus.isVisible = message != null
    }

    private fun setupFills() {
        binding.fillsList.layoutManager = LinearLayoutManager(context)
        binding.fillsList.adapter = fillAdapter
        binding.fillStatus.setTextColor(DefaultStyle.accentColor)
        binding.refreshFills.setOnClickListener { presenter.fillHistory.refresh() }
        binding.loadOlderFills.setOnClickListener { presenter.fillHistory.loadOlder() }
        presenter.fillHistory.state.asObservable().subscribeNext(::renderFills).addTo(subscriptions)

        presenter.fillAccountChanges.subscribeNext {
            if (binding.ordersTabs.selectedTabPosition == 1) presenter.fillHistory.refresh()
        }.addTo(subscriptions)

        binding.ordersTabs.apply {
            setBackgroundColor(DefaultStyle.darkerPrimaryColor)
            setTabTextColors(DefaultStyle.darkerAccentColor, DefaultStyle.accentColor)
            if (tabCount == 0) {
                addTab(newTab().setText(R.string.active_orders))
                addTab(newTab().setText(R.string.fills))
            }
            addOnTabSelectedListener(tabListener)
            showFills(selectedTabPosition == 1)
        }
    }

    private fun showFills(selected: Boolean) {
        binding.activeOrdersPanel.isVisible = !selected
        binding.fillsPanel.isVisible = selected
        if (selected && !presenter.fillHistory.state.value.loaded) presenter.fillHistory.refresh()
    }

    private fun renderFills(state: FillHistory.State) {
        if (state.update !== appliedFillsUpdate) {
            when (val update = state.update) {
                is FillHistory.State.Update.Replace -> fillAdapter.replaceAll(update.fills)
                is FillHistory.State.Update.Append -> fillAdapter.appendOlder(update.older)
            }
            appliedFillsUpdate = state.update
        }
        binding.refreshFills.isEnabled = !state.loading
        binding.loadOlderFills.isVisible = state.nextCursor != null
        binding.loadOlderFills.isEnabled = !state.loading
        val message = when {
            state.loading -> R.string.loading_fills
            state.error == FillHistory.Error.ACCESS_DENIED -> R.string.fill_access_denied
            state.error == FillHistory.Error.REQUEST_FAILED -> R.string.fill_request_failed
            state.loaded && state.fills.isEmpty() -> R.string.no_fills
            else -> null
        }
        binding.fillStatus.isVisible = message != null
        message?.let { binding.fillStatus.setText(it) }
    }

    private val tabListener = object : TabLayout.OnTabSelectedListener {
        override fun onTabSelected(tab: TabLayout.Tab) = showFills(tab.position == 1)
        override fun onTabUnselected(tab: TabLayout.Tab) = Unit
        override fun onTabReselected(tab: TabLayout.Tab) = Unit
    }

    private fun showCancellationFailure(order: ChildOrder) {
        val message = context.getString(R.string.order_cancellation_failed, order.childOrderAcceptanceId)
        Snackbar.make(this, message, Snackbar.LENGTH_LONG).show()
    }
}
