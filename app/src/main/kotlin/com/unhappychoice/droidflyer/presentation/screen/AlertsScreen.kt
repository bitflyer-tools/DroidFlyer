package com.unhappychoice.droidflyer.presentation.screen

import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.presentation.screen.core.Screen

class AlertsScreen : Screen() {
    override fun getLayoutResource() = R.layout.alerts_view
    override fun getTitle(): String = "Price Alerts"
}
