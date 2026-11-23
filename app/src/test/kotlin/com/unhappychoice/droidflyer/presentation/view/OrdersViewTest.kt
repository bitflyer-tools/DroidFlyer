package com.unhappychoice.droidflyer.presentation.view

import android.app.Activity
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.databinding.OrdersViewBinding
import com.unhappychoice.droidflyer.domain.service.PrivateTradingState
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.CancelChildOrderRequest
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrder
import com.unhappychoice.droidflyer.presentation.presenter.OrdersPresenter
import io.reactivex.Completable
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.schedulers.TestScheduler
import mortar.MortarScope
import mortar.bundler.BundleServiceRunner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.kodein.di.DI
import org.kodein.di.bindInstance
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrdersViewTest {
    private val ioScheduler = TestScheduler()

    @Before
    fun setUp() {
        RxJavaPlugins.setIoSchedulerHandler { ioScheduler }
    }

    @After
    fun tearDown() {
        RxJavaPlugins.reset()
    }

    @Test
    fun failedCancellationShowsTheOrderAcceptanceIdAndKeepsTheOrderListed() {
        val request = CancelChildOrderRequest("FX_BTC_JPY", "JRF-active")
        val api = mock<APIClientV1>()
        whenever(api.cancelChildOrder(request)).thenReturn(Completable.error(IOException("Connection lost")))
        val state = PrivateTradingState(api).apply { orders.value = listOf(activeOrder()) }
        val presenter = OrdersPresenter(state) { error("Unexpected refresh after failed cancellation") }

        Robolectric.buildActivity(OrdersHost::class.java).setup().use { controller ->
            val host = controller.get()
            host.setTheme(R.style.AppTheme)
            val binding = OrdersViewBinding.inflate(host.layoutInflater)
            binding.root.inject(DI { bindInstance { presenter } })
            host.setContentView(binding.root)

            val row = binding.orderList.bindFirstRow()
            row.findViewById<View>(R.id.cancel).performClick()
            ioScheduler.triggerActions()
            shadowOf(Looper.getMainLooper()).idle()

            verify(api).cancelChildOrder(request)
            val message = host.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)
            assertEquals(host.getString(R.string.order_cancellation_failed, "JRF-active"), message?.text?.toString())
            assertEquals(1, binding.orderList.adapter?.itemCount)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun RecyclerView.bindFirstRow(): View {
        val orders = requireNotNull(adapter) as RecyclerView.Adapter<RecyclerView.ViewHolder>
        return orders.createViewHolder(this, orders.getItemViewType(0)).also {
            orders.bindViewHolder(it, 0)
        }.itemView
    }

    private fun activeOrder() = ChildOrder(
        id = 1,
        childOrderId = "order-1",
        productCode = "FX_BTC_JPY",
        side = "BUY",
        childOrderType = "LIMIT",
        price = 100,
        average_price = 0,
        size = 0.1,
        childOrderState = "ACTIVE",
        childOrderAcceptanceId = "JRF-active",
        outstandingSize = 0.1,
        cancelSize = 0.0,
        executedSize = 0.0,
        totalCommission = 0.0
    )

    class OrdersHost : Activity() {
        private val scope by lazy {
            MortarScope.buildRootScope()
                .withService(BundleServiceRunner.SERVICE_NAME, BundleServiceRunner())
                .build("orders_view_test")
        }

        override fun getSystemService(name: String): Any? =
            if (scope.hasService(name)) scope.getService(name) else super.getSystemService(name)

        override fun onDestroy() {
            super.onDestroy()
            scope.destroy()
        }
    }
}
