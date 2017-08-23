package com.unhappychoice.droidflyer.presentation.core

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.unhappychoice.droidflyer.MainActivity
import com.unhappychoice.droidflyer.presentation.screen.core.Screen
import com.unhappychoice.droidflyer.presentation.view.core.BaseView
import flow.*
import kotlinx.android.synthetic.main.activity_main.*

class ScreenChanger(val activity: MainActivity) : KeyChanger {
    private val containerView: ViewGroup
        get() = activity.containerView

    private val contentView: View
        get() = containerView.getChildAt(0)

    override fun changeKey(
        outgoingState: State?,
        incomingState: State,
        direction: Direction,
        incomingContexts: MutableMap<Any, Context>,
        callback: TraversalCallback
    ) {
        outgoingState?.save(contentView)
        containerView.removeAllViews()

        val screen = incomingState.getScreen()

        screen?.inflateView()?.let {
            inject(it)
            containerView.addView(it)
            incomingState.restore(it)
        }

        callback.onTraversalCompleted()

        screen?.let { updateActionBar(it) }
    }

    private fun updateActionBar(screen: Screen) {
        activity.supportActionBar?.setHomeButtonEnabled(true)
        activity.supportActionBar?.setDisplayHomeAsUpEnabled(hasScreens())
        activity.supportActionBar?.setDisplayShowHomeEnabled(false)
        activity.supportActionBar?.title = screen.getTitle()
        activity.invalidateOptionsMenu()
    }

    private fun inject(view: View) = (view as? BaseView)?.inject(activity.module)

    private fun hasScreens(): Boolean = false

    private fun State.getScreen(): Screen? = getKey<Any?>() as? Screen

    private fun Screen.inflateView(): View =
        LayoutInflater.from(activity).inflate(getLayoutResource(), containerView, false)
}