package com.unhappychoice.droidflyer.presentation.view.core

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.kodein.di.DI
import org.kodein.di.bindProvider
import org.kodein.di.instance
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BaseViewTest {
    @Test
    fun resolvesDependenciesWhenInjectedAndReusesThemOnAccess() {
        var lookups = 0
        val di = DI { bindProvider { ++lookups } }
        val view = TestView(RuntimeEnvironment.getApplication())

        assertEquals(0, lookups)
        view.inject(di)

        assertEquals(1, lookups)
        view.inject(DI {})

        assertSame(di, view.di)
        assertEquals(1, view.dependency)
        assertEquals(1, lookups)
    }

    private class TestView(context: Context) : BaseView(context, null) {
        val dependency: Int by instance()
    }
}
