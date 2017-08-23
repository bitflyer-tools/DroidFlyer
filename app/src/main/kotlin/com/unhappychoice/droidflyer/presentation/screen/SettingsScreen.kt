package com.unhappychoice.droidflyer.presentation.screen

import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.presentation.screen.core.Screen

class SettingsScreen: Screen() {
    override fun getLayoutResource() = R.layout.settings_view
    override fun getTitle(): String = "Setting"
}