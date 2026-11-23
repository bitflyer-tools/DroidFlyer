package com.unhappychoice.droidflyer.presentation.view

import android.app.Activity
import android.app.Application
import android.content.DialogInterface
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager.widget.PagerAdapter
import androidx.viewpager.widget.ViewPager
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.domain.service.CurrentStatusService
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.SendChildOrderRequest
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Board
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrderAcceptance
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Order
import com.unhappychoice.droidflyer.infrastructure.preference.CryptoCfdAccountPreference
import com.unhappychoice.droidflyer.presentation.adapter.BoardAdapter
import com.unhappychoice.droidflyer.presentation.adapter.OrderViewPagerAdapter.Companion.LIMIT_POSITION
import com.unhappychoice.droidflyer.presentation.presenter.LimitOrderPresenter
import io.reactivex.Observable
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.PublishSubject
import mortar.MortarScope
import mortar.bundler.BundleServiceRunner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.kodein.di.DI
import org.kodein.di.bindInstance
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class LimitOrderViewTest {
    private val orderClient = mockAccount()
    private lateinit var presenter: LimitOrderPresenter
    private val accountPreference = CryptoCfdAccountPreference(RuntimeEnvironment.getApplication())
    private val pending = PublishSubject.create<ChildOrderAcceptance>()
    private lateinit var status: CurrentStatusService

    @Before
    fun setUp() {
        RxJavaPlugins.setIoSchedulerHandler { Schedulers.trampoline() }
        whenever(orderClient.sendChildOrder(any())).thenReturn(pending, Observable.never())
        status = CurrentStatusService(mockAccount(), Observable.never(), Observable.never(), Observable.never(), orderClient)
        presenter = LimitOrderPresenter(
            status.privateState,
            accountPreference,
            status::updateStatus,
            RuntimeEnvironment.getApplication().resources
        ) { _, _ -> }
    }

    @After
    fun tearDown() {
        status.dispose()
        RxJavaPlugins.reset()
    }

    @Test
    fun publicRowsRemainReadableAndEnableOrderEntryWhenCredentialsAreSaved() {
        status.switchAccount(null)
        status.board.value = Board(150.0, listOf(Order(100, 1.0)), listOf(Order(200, 2.0)))
        RxJavaPlugins.setComputationSchedulerHandler { Schedulers.trampoline() }

        withLimitView(inPager = true) { view ->
            val notice = view.findViewById<TextView>(R.id.credentialsRequired)
            presenter.amount.value = 0.01
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(View.VISIBLE, notice.visibility)
            assertEquals(view.context.getString(R.string.trading_credentials_required), notice.text.toString())
            val bid = view.bindRow(R.id.bidList)
            val ask = view.bindRow(R.id.askList)
            assertEquals("100", bid.findViewById<TextView>(R.id.price).text.toString())
            assertEquals("200", ask.findViewById<TextView>(R.id.price).text.toString())
            assertFalse(bid.isClickable)
            assertFalse(ask.isClickable)

            status.switchAccount(orderClient)
            assertEquals(View.GONE, notice.visibility)
            presenter.amount.value = 0.0
            assertFalse(view.bindRow(R.id.bidList).isClickable)
            presenter.amount.value = 0.01
            assertTrue(view.bindRow(R.id.askList).isClickable)
            assertTrue(view.bindRow(R.id.bidList).performClick())
            (ShadowDialog.getLatestDialog() as AlertDialog).accept()
            verify(orderClient).sendChildOrder(request("BUY", 100, 0.01))
        }
    }

    @Test
    fun confirmationsKeepTheirTermsAndAreDismissedOnAccountSwitch() {
        withLimitView { view ->
            presenter.amount.value = 0.01
            val previousSell = view.confirmation(R.id.askList, R.string.sell, 200, 0.01)
            val buy = view.confirmation(R.id.bidList, R.string.buy, 100, 0.01)
            assertFalse(previousSell.isShowing)
            presenter.amount.value = 0.0
            buy.accept()

            verify(orderClient).sendChildOrder(request("BUY", 100, 0.01))
            pending.onComplete()
            shadowOf(Looper.getMainLooper()).idle()

            presenter.amount.value = 0.03
            val staleSell = view.confirmation(R.id.askList, R.string.sell, 201, 0.03)
            val newAccount = mockAccount()
            whenever(newAccount.sendChildOrder(any())).thenReturn(Observable.empty())
            status.switchAccount(newAccount)
            assertFalse(staleSell.isShowing)
            verify(newAccount, never()).sendChildOrder(any())

            val latestSell = view.confirmation(R.id.askList, R.string.sell, 202, 0.03)
            presenter.amount.value = 0.04
            latestSell.accept()
            verify(newAccount).sendChildOrder(request("SELL", 202, 0.03))
            verify(orderClient, times(1)).sendChildOrder(any())
        }
    }

    @Test
    fun limitConfirmationAndSubmissionUseTheCurrentAccountMaximum() {
        withLimitView { view ->
            presenter.amount.value = 10.0
            val individual = view.confirmation(R.id.bidList, R.string.buy, 100, 10.0)
            presenter.amount.value = 10.001
            view.clickRow(R.id.askList, 200)
            assertSame(individual, ShadowDialog.getLatestDialog())

            individual.accept()
            verify(orderClient).sendChildOrder(request("BUY", 100, 10.0))
            pending.onComplete()
            shadowOf(Looper.getMainLooper()).idle()

            accountPreference.isCorporate = true
            presenter.amount.value = 40.0
            val corporate = view.confirmation(R.id.askList, R.string.sell, 200, 40.0)
            presenter.amount.value = 41.0
            view.clickRow(R.id.bidList, 100)
            assertSame(corporate, ShadowDialog.getLatestDialog())

            accountPreference.isCorporate = false
            corporate.accept()
            verify(orderClient, times(1)).sendChildOrder(any())

            accountPreference.isCorporate = true
            presenter.amount.value = 40.0
            view.confirmation(R.id.askList, R.string.sell, 201, 40.0).accept()
            verify(orderClient).sendChildOrder(request("SELL", 201, 40.0))
            verify(orderClient, times(2)).sendChildOrder(any())
        }
    }

    @Test
    @Config(sdk = [30])
    fun obscuredConfirmationDoesNotSubmitAndClearTapSubmitsOnce() {
        withLimitView { view ->
            presenter.amount.value = 0.01
            val dialog = view.confirmation(R.id.bidList, R.string.buy, 100, 0.01)
            val button = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
            val window = requireNotNull(dialog.window)

            window.tapWithObscuration(button)
            verify(orderClient, never()).sendChildOrder(any())
            window.tap(button)
            verify(orderClient).sendChildOrder(request("BUY", 100, 0.01))
        }
    }

    private fun withLimitView(inPager: Boolean = false, test: (LimitOrderView) -> Unit) {
        Robolectric.buildActivity(LimitOrderHost::class.java).setup().use { controller ->
            val host = controller.get()
            host.setTheme(R.style.AppTheme)
            val view = host.layoutInflater.inflate(R.layout.limit_order_view, null) as LimitOrderView
            view.inject(DI {
                bindInstance { presenter }
                bindInstance { status }
            })
            if (inPager) {
                val pager = ViewPager(host)
                pager.adapter = object : PagerAdapter() {
                    override fun getCount() = 2
                    override fun isViewFromObject(view: View, item: Any) = view === item
                    override fun instantiateItem(container: ViewGroup, position: Int): Any =
                        (if (position == LIMIT_POSITION) view else View(host)).also { container.addView(it) }

                    override fun destroyItem(container: ViewGroup, position: Int, item: Any) {
                        container.removeView(item as View)
                    }
                }
                pager.currentItem = LIMIT_POSITION
                host.setContentView(pager)
                val width = View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY)
                val height = View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY)
                pager.measure(width, height)
                pager.layout(0, 0, pager.measuredWidth, pager.measuredHeight)
            } else {
                host.setContentView(view)
            }
            test(view)
        }
    }

    private fun LimitOrderView.bindRow(listId: Int): View {
        val list = findViewById<RecyclerView>(listId)
        val adapter = list.adapter as BoardAdapter
        assertEquals(1, adapter.itemCount)
        return adapter.onCreateViewHolder(list, 0).also { adapter.onBindViewHolder(it, 0) }.itemView
    }

    private fun LimitOrderView.clickRow(list: Int, price: Long) {
        val adapter = findViewById<RecyclerView>(list).adapter as BoardAdapter
        adapter.clickItems.onNext(Order(price, 1.0))
    }

    private fun LimitOrderView.confirmation(list: Int, label: Int, price: Long, size: Double): AlertDialog {
        clickRow(list, price)
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val message = dialog.findViewById<TextView>(android.R.id.message)?.text.toString()
        assertEquals("${context.getString(label)} / $price / $size", message)
        return dialog
    }

    private fun AlertDialog.accept() {
        getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun mockAccount() = mock<APIClientV1>().also {
        whenever(it.getPositions()).thenReturn(Observable.empty())
        whenever(it.getCollateral()).thenReturn(Observable.empty())
        whenever(it.getChildOrders("FX_BTC_JPY", count = 500)).thenReturn(Observable.empty())
        whenever(it.getBoard("FX_BTC_JPY")).thenReturn(Observable.empty())
    }

    private fun request(side: String, price: Long, size: Double) =
        SendChildOrderRequest("FX_BTC_JPY", "LIMIT", side, price, size)

    class LimitOrderHost : Activity() {
        private val scope by lazy {
            MortarScope.buildRootScope()
                .withService(BundleServiceRunner.SERVICE_NAME, BundleServiceRunner())
                .build("limit_order_view_test")
        }

        override fun getSystemService(name: String): Any? =
            if (scope.hasService(name)) scope.getService(name) else super.getSystemService(name)

        override fun onDestroy() {
            super.onDestroy()
            scope.destroy()
        }
    }
}
