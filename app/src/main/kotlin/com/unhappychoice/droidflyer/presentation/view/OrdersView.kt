package com.unhappychoice.droidflyer.presentation.view


import android.content.Context
import android.util.AttributeSet
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.salomonbrys.kodein.instance
import com.unhappychoice.droidflyer.databinding.OrdersViewBinding
import com.unhappychoice.droidflyer.extension.subscribeNext
import com.unhappychoice.droidflyer.extension.subscribeOnIoObserveOnUI
import com.unhappychoice.droidflyer.presentation.adapter.OrderAdapter
import com.unhappychoice.droidflyer.presentation.presenter.OrdersPresenter
import com.unhappychoice.droidflyer.presentation.view.core.BaseView
import io.reactivex.rxkotlin.addTo

class OrdersView(context: Context?, attr: AttributeSet?) : BaseView(context, attr) {
    val presenter: OrdersPresenter by instance()
    private val binding by lazy { OrdersViewBinding.bind(this) }
    private val orderAdapter by lazy { OrderAdapter(context) }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        presenter.takeView(this)

        binding.orderList.apply {
            layoutManager = LinearLayoutManager(context, RecyclerView.VERTICAL, false)
            adapter = orderAdapter
        }

        presenter.currentStatusService.orders.asObservable()
            .subscribeOnIoObserveOnUI()
            .subscribeNext {
                orderAdapter.items.value = it
                orderAdapter.notifyDataSetChanged()
            }.addTo(bag)

        orderAdapter.clickCancel
            .subscribeOnIoObserveOnUI()
            .subscribeNext { presenter.cancelOrder(it) }
            .addTo(bag)
    }

    override fun onDetachedFromWindow() {
        presenter.dropView(this)
        super.onDetachedFromWindow()
    }
}
