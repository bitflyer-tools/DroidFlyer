package com.unhappychoice.droidflyer.di

import com.unhappychoice.droidflyer.DroidFlyerApplication
import com.unhappychoice.droidflyer.MainActivity
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClient
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.presentation.presenter.LimitOrderPresenter
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.kodein.di.DI
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
    fun sharesRestClientWithOrderPresenter() {
        val application = RuntimeEnvironment.getApplication() as DroidFlyerApplication
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        val graph = DI {
            import(restModule(application))
            import(activityModule(activity))
        }

        val client = graph.direct.instance<APIClientV1>()
        assertSame(client, graph.direct.instance<APIClient>().client())
        assertSame(client, graph.direct.instance<LimitOrderPresenter>().apiClient)
    }
}
