package com.unhappychoice.droidflyer.extension

import android.app.Application
import android.os.Looper
import io.reactivex.rxjava3.core.Observable
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AndroidSchedulersTest {
    @Test
    fun observeOnDeliversOnlyActiveSubscriptionsWhenMainLooperRuns() {
        val source = Observable.just(1).observeOn(androidMainThreadScheduler())
        val active = source.test()
        val cancelled = source.test()
        active.assertNoValues()
        cancelled.dispose()

        shadowOf(Looper.getMainLooper()).idle()

        active.assertResult(1)
        cancelled.assertNoValues().assertNotComplete()
    }
}
