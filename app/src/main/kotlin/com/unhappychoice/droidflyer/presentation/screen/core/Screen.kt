package com.unhappychoice.droidflyer.presentation.screen.core

import android.support.annotation.LayoutRes
import flow.ClassKey

abstract class Screen : ClassKey() {
    @LayoutRes abstract fun getLayoutResource(): Int
    abstract fun getTitle(): String
}