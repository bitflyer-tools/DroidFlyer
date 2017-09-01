package com.unhappychoice.droidflyer.presentation.screen

import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.presentation.screen.core.Screen

class OrderScreen : Screen() {
    override fun getLayoutResource(): Int = R.layout.order_view
    override fun getTitle(): String = "Order"
}
