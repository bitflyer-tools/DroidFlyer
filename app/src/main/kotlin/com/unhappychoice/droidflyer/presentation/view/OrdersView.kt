package com.unhappychoice.droidflyer.presentation.view

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager.widget.ViewPager
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.databinding.OrdersViewBinding
import com.unhappychoice.droidflyer.domain.service.PrivateTradingState.ActiveOrdersStatus
import com.unhappychoice.droidflyer.domain.service.StopOrder
import com.unhappychoice.droidflyer.domain.service.StopOrderService.CancellationFailure
import com.unhappychoice.droidflyer.extension.subscribeNext
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrder
import com.unhappychoice.droidflyer.presentation.adapter.FillAdapter
import com.unhappychoice.droidflyer.presentation.adapter.OrderAdapter
import com.unhappychoice.droidflyer.presentation.adapter.OrderViewPagerAdapter.Companion.ORDERS_POSITION
import com.unhappychoice.droidflyer.presentation.adapter.StopOrderAdapter
import com.unhappychoice.droidflyer.presentation.presenter.FillHistory
import com.unhappychoice.droidflyer.presentation.presenter.OrdersPresenter
import com.unhappychoice.droidflyer.presentation.style.DefaultStyle
import com.unhappychoice.droidflyer.presentation.view.core.BaseView
import com.unhappychoice.droidflyer.presentation.view.core.showTradingConfirmation
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

    private val stopAdapter by lazy { StopOrderAdapter(::confirmStopCancellation) }
    private var cancellationDialog: AlertDialog? = null
    private var pager: ViewPager? = null
    private var lifecycleOwner: LifecycleOwner? = null
    private var started = false
    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            started = true
            updateActiveOrdersVisibility()
        }

        override fun onStop(owner: LifecycleOwner) {
            started = false
            updateActiveOrdersVisibility()
        }
    }
    private val pageListener = object : ViewPager.SimpleOnPageChangeListener() {
        override fun onPageSelected(position: Int) = updateActiveOrdersVisibility()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        presenter.takeView(this)
        setupFills()

        binding.orderList.apply {
            layoutManager = LinearLayoutManager(context, RecyclerView.VERTICAL, false)
            adapter = ConcatAdapter(stopAdapter, orderAdapter)
        }

        presenter.stopOrders.subscribeNext(stopAdapter::updateOrders).addTo(subscriptions)
        presenter.stopCancellations.subscribeNext(stopAdapter::updateCancellations).addTo(subscriptions)
        presenter.stopLoadFailed.subscribeNext {
            binding.retryStops.isVisible = it
        }.addTo(subscriptions)
        binding.retryStops.setOnClickListener { presenter.refreshStops() }

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

        pager = (parent as? ViewPager)?.also { it.addOnPageChangeListener(pageListener) }
        lifecycleOwner = findViewTreeLifecycleOwner()?.also { it.lifecycle.addObserver(lifecycleObserver) }
    }

    override fun onDetachedFromWindow() {
        lifecycleOwner?.lifecycle?.removeObserver(lifecycleObserver)
        lifecycleOwner = null
        pager?.removeOnPageChangeListener(pageListener)
        pager = null
        started = false
        updateActiveOrdersVisibility()
        cancellationDialog?.dismiss()
        cancellationDialog = null
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
        updateActiveOrdersVisibility()
        if (selected && !presenter.fillHistory.state.value.loaded) presenter.fillHistory.refresh()
    }

    private fun updateActiveOrdersVisibility() {
        presenter.setActiveOrdersVisible(
            started && pager?.currentItem == ORDERS_POSITION && binding.ordersTabs.selectedTabPosition == 0
        )
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

    private fun confirmStopCancellation(order: StopOrder) {
        val confirmation = presenter.stopCancellationConfirmation(order)
        cancellationDialog = AlertDialog.Builder(context, R.style.DialogStyle)
            .setTitle(R.string.stop_cancel_title)
            .setMessage(
                if (order.canConfirmRecovery) context.getString(R.string.stop_verify_cancel_message, order.acceptanceId)
                else order.acceptanceId
            )
            .setPositiveButton(if (order.canConfirmRecovery) R.string.stop_confirm_waiting_and_cancel else R.string.ok) { _, _ ->
                presenter.cancelStop(confirmation) { failure ->
                    showCancellationFailure(confirmation.acceptanceId, failure.message())
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .showTradingConfirmation()
    }

    private fun showCancellationFailure(order: ChildOrder) = showCancellationFailure(order.childOrderAcceptanceId)

    private fun CancellationFailure.message() = when (this) {
        CancellationFailure.ACCOUNT_CHANGED -> R.string.stop_cancel_account_changed
        CancellationFailure.UNAVAILABLE -> R.string.stop_cancel_unavailable
        CancellationFailure.IN_PROGRESS -> R.string.stop_cancel_in_progress
        CancellationFailure.INELIGIBLE -> R.string.stop_cancel_ineligible
        CancellationFailure.REQUEST_FAILED -> R.string.order_cancellation_failed
    }

    private fun showCancellationFailure(acceptanceId: String, messageId: Int = R.string.order_cancellation_failed) {
        if (!isAttachedToWindow) return
        val message = context.getString(messageId, acceptanceId)
        Snackbar.make(this, message, Snackbar.LENGTH_LONG).show()
    }
}
