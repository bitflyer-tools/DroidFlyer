package com.unhappychoice.droidflyer.presentation.view

import android.app.Activity
import android.os.Looper
import android.view.View
import androidx.core.view.isVisible
import com.unhappychoice.droidflyer.databinding.ChartViewBinding
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ExecutionPrice
import com.unhappychoice.droidflyer.presentation.presenter.ChartPresenter
import io.reactivex.Observable
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.schedulers.TestScheduler
import mortar.MortarScope
import mortar.bundler.BundleServiceRunner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChartViewTest {
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
    fun reattachmentDisplaysFreshPricesImmediatelyAndAllowsRetryAfterExpiredLoadFails() {
        var requests = 0
        var nowNanos = 0L
        val presenter = ChartPresenter(nowNanos = { nowNanos }) { _, _ ->
            requests++
            if (requests == 2) Observable.error(IOException("Offline"))
            else Observable.just(listOf(ExecutionPrice(requests.toLong(), 100.0 * requests)))
        }
        val module = DI { bindSingleton<ChartPresenter> { presenter } }

        Robolectric.buildActivity(ChartHost::class.java).setup().use { controller ->
            val activity = controller.get()
            val initial = activity.openChart(module)
            runRequest()
            assertTrue(initial.chartContent.isVisible)

            activity.setContentView(View(activity))
            val reopened = activity.openChart(module)
            assertTrue(reopened.chartContent.isVisible)
            assertFalse(reopened.chartLoading.isVisible)
            assertEquals(initial.chartPriceRange.text, reopened.chartPriceRange.text)
            runRequest()
            assertEquals(1, requests)

            activity.setContentView(View(activity))
            nowNanos = TimeUnit.SECONDS.toNanos(30)
            val expired = activity.openChart(module)
            assertTrue(expired.chartLoading.isVisible)
            assertFalse(expired.chartContent.isVisible)
            runRequest()
            assertEquals(2, requests)
            assertTrue(expired.chartError.isVisible)

            expired.chartRetry.performClick()
            runRequest()
            assertEquals(3, requests)
            assertTrue(expired.chartContent.isVisible)
            assertFalse(expired.chartError.isVisible)
        }
    }

    private fun runRequest() {
        ioScheduler.triggerActions()
        shadowOf(Looper.getMainLooper()).idle()
    }

    class ChartHost : Activity() {
        private val scope by lazy {
            MortarScope.buildRootScope()
                .withService(BundleServiceRunner.SERVICE_NAME, BundleServiceRunner())
                .build("chart_test")
        }

        override fun getSystemService(name: String): Any? =
            if (scope.hasService(name)) scope.getService(name) else super.getSystemService(name)

        fun openChart(module: DI): ChartViewBinding = ChartViewBinding.inflate(layoutInflater).also {
            it.root.inject(module)
            setContentView(it.root)
        }
    }
}
