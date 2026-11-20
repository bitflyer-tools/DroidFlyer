package com.unhappychoice.droidflyer.presentation.view

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.databinding.OrdersViewBinding
import com.unhappychoice.droidflyer.domain.service.StopOrder
import com.unhappychoice.droidflyer.extension.subscribeNext
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrder
import com.unhappychoice.droidflyer.presentation.adapter.OrderAdapter
import com.unhappychoice.droidflyer.presentation.adapter.StopOrderAdapter
import com.unhappychoice.droidflyer.presentation.presenter.OrdersPresenter
import com.unhappychoice.droidflyer.presentation.view.core.BaseView
import io.reactivex.rxkotlin.addTo
import org.kodein.di.instance

class OrdersView(context: Context?, attr: AttributeSet?) : BaseView(context, attr) {
    val presenter: OrdersPresenter by instance()
    private val binding by lazy { OrdersViewBinding.bind(this) }
    private val orderAdapter by lazy { OrderAdapter(context) }

    private val stopAdapter by lazy { StopOrderAdapter(::confirmStopCancellation) }
    private var cancellationDialog: AlertDialog? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        presenter.takeView(this)

        binding.orderList.apply {
            layoutManager = LinearLayoutManager(context, RecyclerView.VERTICAL, false)
            adapter = ConcatAdapter(stopAdapter, orderAdapter)
        }

        presenter.stopOrders.subscribeNext(stopAdapter::updateOrders).addTo(bag)
        presenter.stopCancellations.subscribeNext(stopAdapter::updateCancellations).addTo(bag)
        presenter.stopLoadFailed.subscribeNext {
            binding.retryStops.visibility = if (it) View.VISIBLE else View.GONE
        }.addTo(bag)
        binding.retryStops.setOnClickListener { presenter.refreshStops() }
        presenter.refreshStops()

        presenter.orders
            .subscribeNext { orderAdapter.updateOrders(it) }
            .addTo(bag)

        presenter.cancellationFailures
            .subscribeNext(::showCancellationFailure)
            .addTo(bag)

        orderAdapter.clickCancel
            .subscribeNext { presenter.cancelOrder(it) }
            .addTo(bag)
    }

    override fun onDetachedFromWindow() {
        cancellationDialog?.dismiss()
        cancellationDialog = null
        presenter.dropView(this)
        super.onDetachedFromWindow()
    }

    private fun confirmStopCancellation(order: StopOrder) {
        cancellationDialog = AlertDialog.Builder(context, R.style.DialogStyle)
            .setTitle(R.string.stop_cancel_title)
            .setMessage(order.acceptanceId)
            .setPositiveButton(R.string.ok) { _, _ ->
                presenter.cancelStop(order) { showCancellationFailure(order.acceptanceId) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showCancellationFailure(order: ChildOrder) = showCancellationFailure(order.childOrderAcceptanceId)

    private fun showCancellationFailure(acceptanceId: String) {
        if (!isAttachedToWindow) return
        val message = context.getString(R.string.order_cancellation_failed, acceptanceId)
        Snackbar.make(this, message, Snackbar.LENGTH_LONG).show()
    }
}
