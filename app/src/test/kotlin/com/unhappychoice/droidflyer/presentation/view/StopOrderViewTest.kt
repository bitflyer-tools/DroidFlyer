package com.unhappychoice.droidflyer.presentation.view

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.children
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.domain.service.CurrentStatusService
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.SendParentOrderRequest
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.StopSide
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ParentOrderAcceptance
import io.reactivex.Observable
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.schedulers.TestScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.kodein.di.DI
import org.kodein.di.bindInstance
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.math.BigDecimal

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class StopOrderViewTest {
    private val api = mock<APIClientV1>()
    private val requests = TestScheduler()
    private lateinit var status: CurrentStatusService

    @Before
    fun setUp() {
        RxJavaPlugins.setIoSchedulerHandler { requests }
        whenever(api.getPositions()).thenReturn(Observable.empty())
        whenever(api.getCollateral()).thenReturn(Observable.empty())
        whenever(api.getChildOrders("FX_BTC_JPY", count = 500)).thenReturn(Observable.empty())
        whenever(api.getBoard("FX_BTC_JPY")).thenReturn(Observable.empty())
        status = CurrentStatusService(api, Observable.never(), Observable.never(), Observable.never())
    }

    @After
    fun tearDown() {
        status.dispose()
        RxJavaPlugins.reset()
    }

    @Test
    fun reviewShowsSetupInstructionsWhenOrderEventsAreUnavailable() = withForm { form ->
        form.review()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val message = dialog.findViewById<TextView>(android.R.id.message)?.text.toString()
        assertEquals(form.resources.getString(R.string.stop_order_events_setup), message)
        verify(api, never()).sendParentOrder(any())
    }

    @Test
    fun acceptsReviewedStopTermsAndClearsTheFormAfterSubmission() {
        status.privateState.stopOrders.onPrivateChannelAvailability(true)
        whenever(api.getParentOrders(orderState = "ACTIVE", count = 100)).thenReturn(Observable.just(emptyList()))
        whenever(api.sendParentOrder(any())).thenReturn(Observable.just(ParentOrderAcceptance("accepted-stop")))

        withForm { form ->
            val sides = form.children.filterIsInstance<RadioGroup>().single()
            sides.children.filterIsInstance<RadioButton>()
                .single { it.text == form.resources.getString(R.string.sell) }.performClick()
            val (quantity, triggerPrice) = form.children.filterIsInstance<EditText>().toList()
            quantity.setText("0.01")
            triggerPrice.setText("5000000")

            form.review()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            verify(api, never()).sendParentOrder(any())
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            requests.triggerActions()
            shadowOf(Looper.getMainLooper()).idle()

            val request = argumentCaptor<SendParentOrderRequest>()
            verify(api).sendParentOrder(request.capture())
            val order = request.firstValue.parameters.single()
            assertEquals(StopSide.SELL, order.side)
            assertEquals(BigDecimal("0.01"), order.size)
            assertEquals(5_000_000L, order.triggerPrice)
            assertTrue(quantity.text.isEmpty())
            assertTrue(triggerPrice.text.isEmpty())
            assertEquals(-1, sides.checkedRadioButtonId)
        }
    }

    private fun withForm(test: (LinearLayout) -> Unit) {
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            val activity = controller.get()
            activity.setTheme(R.style.AppTheme)
            val view = StopOrderView(activity).apply { inject(DI { bindInstance { status } }) }
            activity.setContentView(view)
            val scroll = view.children.filterIsInstance<ScrollView>().single()
            test(scroll.getChildAt(0) as LinearLayout)
        }
    }

    private fun LinearLayout.review() {
        children.filterIsInstance<Button>()
            .single { it.text == resources.getString(R.string.stop_review) }.performClick()
    }
}
