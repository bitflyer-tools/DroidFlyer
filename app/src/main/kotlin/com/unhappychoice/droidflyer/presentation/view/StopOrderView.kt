package com.unhappychoice.droidflyer.presentation.view

import android.content.Context
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.snackbar.Snackbar
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.domain.service.CurrentStatusService
import com.unhappychoice.droidflyer.domain.service.StopOrderService
import com.unhappychoice.droidflyer.extension.subscribeNext
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.StopOrderRequest
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.StopSide
import com.unhappychoice.droidflyer.presentation.style.DefaultStyle
import com.unhappychoice.droidflyer.presentation.view.core.BaseView
import io.reactivex.rxkotlin.addTo
import org.kodein.di.instance

class StopOrderView(context: Context) : BaseView(context, null) {
    private val status: CurrentStatusService by instance()
    private val stops get() = status.privateState.stopOrders
    private val quantity = EditText(context).apply {
        setHint(R.string.stop_quantity)
        setTextColor(DefaultStyle.accentColor)
        setHintTextColor(DefaultStyle.darkerAccentColor)
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
    }
    private val price = EditText(context).apply {
        setHint(R.string.stop_trigger_price)
        setTextColor(DefaultStyle.accentColor)
        setHintTextColor(DefaultStyle.darkerAccentColor)
        inputType = InputType.TYPE_CLASS_NUMBER
    }
    private val buy = RadioButton(context).apply {
        id = generateViewId()
        setText(R.string.buy)
        setTextColor(DefaultStyle.accentColor)
    }
    private val sell = RadioButton(context).apply {
        id = generateViewId()
        setText(R.string.sell)
        setTextColor(DefaultStyle.accentColor)
    }
    private val sides = RadioGroup(context).apply {
        orientation = HORIZONTAL
        addView(buy)
        addView(sell)
    }
    private val review = Button(context).apply {
        setText(R.string.stop_review)
        setOnClickListener { reviewOrder() }
    }
    private var dialog: AlertDialog? = null

    init {
        orientation = VERTICAL
        val form = LinearLayout(context).apply {
            orientation = VERTICAL
            val padding = resources.getDimensionPixelSize(R.dimen.horizontal_margin)
            setPadding(padding, padding, padding, padding)
            addView(sides)
            addView(quantity)
            addView(price)
            addView(review)
        }
        addView(
            ScrollView(context).apply { addView(form) },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        stops.submitting.asObservable().subscribeNext { review.isEnabled = !it }.addTo(bag)
    }

    override fun onDetachedFromWindow() {
        dialog?.dismiss()
        dialog = null
        super.onDetachedFromWindow()
    }

    private fun reviewOrder() {
        val side = when (sides.checkedRadioButtonId) {
            buy.id -> StopSide.BUY
            sell.id -> StopSide.SELL
            else -> return showMessage(context.getString(R.string.stop_choose_side))
        }
        val order = StopOrderRequest.fromInput(side, quantity.text.toString(), price.text.toString())
            ?: return showMessage(context.getString(R.string.stop_invalid_input))
        val sideLabel = context.getString(if (side == StopSide.BUY) R.string.buy else R.string.sell)
        val summary = context.getString(R.string.stop_review_summary, sideLabel, order.size.toPlainString(), order.triggerPrice)
        val confirmation = stops.confirmation(order)
        dialog = AlertDialog.Builder(context, R.style.DialogStyle)
            .setTitle(R.string.stop_review)
            .setMessage(summary)
            .setPositiveButton(R.string.stop_submit) { _, _ -> submit(confirmation) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun submit(confirmation: StopOrderService.Confirmation) {
        val started = stops.submit(confirmation) { result ->
            result.onSuccess {
                quantity.text.clear()
                price.text.clear()
                sides.clearCheck()
                showMessage(context.getString(R.string.stop_accepted, it))
            }.onFailure { showMessage(context.getString(R.string.stop_submit_unconfirmed)) }
        }
        if (!started) showMessage(context.getString(R.string.stop_review_again))
    }

    private fun showMessage(message: String) {
        if (isAttachedToWindow) Snackbar.make(this, message, Snackbar.LENGTH_LONG).show()
    }
}
