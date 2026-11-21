package com.unhappychoice.droidflyer.di

import com.unhappychoice.droidflyer.DroidFlyerApplication
import com.unhappychoice.droidflyer.MainActivity
import com.unhappychoice.droidflyer.domain.service.CurrentStatusService
import com.unhappychoice.droidflyer.infrastructure.bitflyer.RealtimeClient
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClient
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.presentation.presenter.LimitOrderPresenter
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.schedulers.TestScheduler
import io.socket.client.Manager
import io.socket.client.Socket
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.direct
import org.kodein.di.instance
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = DroidFlyerApplication::class, sdk = [30])
class RestModuleTest {
    @Test
    fun sharesRestClientWithOrderPresenterAndStatusService() {
        val scheduler = TestScheduler()
        RxJavaPlugins.setIoSchedulerHandler { scheduler }
        try {
            assertSharedClient()
        } finally {
            RxJavaPlugins.reset()
        }
    }

    private fun assertSharedClient() {
        val application = RuntimeEnvironment.getApplication() as DroidFlyerApplication
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        fun disconnectedSocket() = object : Socket(Manager(), "/", Manager.Options()) {
            override fun connect(): Socket = this
        }
        val graph = DI {
            import(applicationModule(application))
            import(activityModule(activity))
            bindSingleton<RealtimeClient>(overrides = true) {
                RealtimeClient(instance(), instance(), disconnectedSocket(), ::disconnectedSocket)
            }
        }

        val status = graph.direct.instance<CurrentStatusService>()
        val client = graph.direct.instance<APIClientV1>()
        try {
            assertSame(client, graph.direct.instance<APIClient>().client())
            assertSame(client, graph.direct.instance<LimitOrderPresenter>().apiClient)
            assertSame(client, status.apiClient)
        } finally {
            status.dispose()
        }
    }
}
