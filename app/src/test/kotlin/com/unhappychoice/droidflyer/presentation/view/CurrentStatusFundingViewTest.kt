package com.unhappychoice.droidflyer.presentation.view

import android.app.Activity
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.domain.service.CurrentStatusService
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.FundingRate
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.plugins.RxJavaPlugins
import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins
import io.reactivex.rxjava3.schedulers.TestScheduler
import io.reactivex.rxjava3.subjects.PublishSubject
import java.io.IOException
import java.math.BigDecimal
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CurrentStatusFundingViewTest {
    @Test
    fun showsSignedFundingAtLocalTimeAndMarksItStaleAfterTheHeaderReturns() {
        val scheduler = TestScheduler()
        val previousLocale = Locale.getDefault()
        val previousTimeZone = TimeZone.getDefault()
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
        RxJavaPlugins.setIoSchedulerHandler { scheduler }
        RxJavaPlugins.setComputationSchedulerHandler { scheduler }
        RxAndroidPlugins.setMainThreadSchedulerHandler { scheduler }

        val api = mock<APIClientV1>()
        whenever(api.getBoard("FX_BTC_JPY")).thenReturn(Observable.empty())
        whenever(api.getPositions()).thenReturn(Observable.empty())
        whenever(api.getCollateral()).thenReturn(Observable.empty())
        whenever(api.getChildOrders("FX_BTC_JPY", count = 500)).thenReturn(Observable.empty())
        val refresh = PublishSubject.create<FundingRate>()
        whenever(api.getFundingRate("FX_BTC_JPY")).thenReturn(
            Observable.just(FundingRate(BigDecimal("0.00375"), "2024-04-15T13:00:00")),
            refresh
        )
        val beforeSettlement = 1713186000000L - TimeUnit.HOURS.toMillis(1)
        val service = CurrentStatusService(
            api, Observable.empty(), Observable.empty(), Observable.empty(), now = { beforeSettlement }
        )
        try {
            Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
                val activity = controller.get()
                val di = DI { bindSingleton { service } }
                val header = CurrentStatusView(activity, null).apply { inject(di) }
                val container = FrameLayout(activity).apply { addView(header) }
                activity.setContentView(container)
                shadowOf(Looper.getMainLooper()).idle()
                val window = ReflectionHelpers.getField<Any>(header, "mAttachInfo")
                ReflectionHelpers.setField(window, "mWindowVisibility", View.VISIBLE)
                header.dispatchWindowVisibilityChanged(View.VISIBLE)
                scheduler.triggerActions()

                val text = header.findViewById<TextView>(R.id.funding)
                assertTrue(text.text.toString(), text.text.contains("+0.375%"))
                assertTrue(text.text.toString(), text.text.contains("6:00"))

                val details = text.text.toString()
                container.removeView(header)
                val replacement = CurrentStatusView(activity, null).apply { inject(di) }
                container.addView(replacement)
                scheduler.triggerActions()

                val restored = replacement.findViewById<TextView>(R.id.funding)
                assertEquals(details, restored.text.toString())

                refresh.onError(IOException("Offline"))
                scheduler.triggerActions()
                assertEquals(activity.getString(R.string.funding_stale, details), restored.text.toString())
            }
        } finally {
            service.dispose()
            RxAndroidPlugins.reset()
            RxJavaPlugins.reset()
            Locale.setDefault(previousLocale)
            TimeZone.setDefault(previousTimeZone)
        }
    }
}
