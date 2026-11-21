package com.unhappychoice.droidflyer.presentation.adapter

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.domain.service.StopOrder
import com.unhappychoice.droidflyer.domain.service.StopOrderStatus
import com.unhappychoice.droidflyer.presentation.style.DefaultStyle

internal class StopOrderAdapter(private val cancel: (StopOrder) -> Unit) :
    RecyclerView.Adapter<StopOrderAdapter.Holder>() {
    private var orders = emptyList<StopOrder>()
    private var cancelling = emptySet<String>()

    fun updateOrders(rows: List<StopOrder>) {
        if (orders == rows) return
        orders = rows
        notifyDataSetChanged()
    }

    fun updateCancellations(ids: Set<String>) {
        if (cancelling == ids) return
        cancelling = ids
        notifyDataSetChanged()
    }

    override fun getItemCount() = orders.size

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(orders[position])

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LinearLayout(parent.context))

    inner class Holder(row: LinearLayout) : RecyclerView.ViewHolder(row) {
        private val context = row.context
        private val details = TextView(context).apply { setTextColor(DefaultStyle.accentColor) }
        private val cancelButton = Button(context).apply { setText(R.string.cancel) }

        init {
            val padding = context.resources.getDimensionPixelSize(R.dimen.horizontal_margin)
            row.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            row.setPadding(padding, padding, padding, padding)
            row.gravity = android.view.Gravity.CENTER_VERTICAL
            row.addView(details, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(cancelButton)
        }

        fun bind(order: StopOrder) {
            val side = context.getString(if (order.parent.side == "BUY") R.string.buy else R.string.sell)
            val trigger = order.triggerPrice?.let { context.getString(R.string.stop_price, it) }
                ?: context.getString(R.string.stop_price_unavailable)
            details.text = context.getString(
                R.string.stop_order_row, side, order.parent.size.toString(), trigger,
                statusText(order), order.acceptanceId
            )
            cancelButton.visibility = if (order.canCancel) View.VISIBLE else View.GONE
            cancelButton.isEnabled = order.acceptanceId !in cancelling
            cancelButton.setOnClickListener { cancel(order) }
        }

        private fun statusText(order: StopOrder): String {
            val status = order.status
            val label = context.getString(status.label())
            val ended = status == StopOrderStatus.CANCELED ||
                status == StopOrderStatus.EXPIRED || status == StopOrderStatus.REJECTED
            return if (order.isPartiallyExecuted && ended) {
                context.getString(R.string.stop_status_with_executed_size, label, order.parent.executedSize.toString())
            } else {
                label
            }
        }
    }

    private fun StopOrderStatus.label() = when (this) {
        StopOrderStatus.WAITING -> R.string.stop_waiting
        StopOrderStatus.TRIGGERED -> R.string.stop_triggered
        StopOrderStatus.PARTIALLY_EXECUTED -> R.string.stop_partially_executed
        StopOrderStatus.EXECUTED -> R.string.stop_executed
        StopOrderStatus.CANCELED -> R.string.stop_canceled
        StopOrderStatus.EXPIRED -> R.string.stop_expired
        StopOrderStatus.REJECTED -> R.string.stop_rejected
        StopOrderStatus.UNKNOWN -> R.string.stop_unknown
    }
}
