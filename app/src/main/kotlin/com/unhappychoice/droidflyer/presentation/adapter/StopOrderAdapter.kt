package com.unhappychoice.droidflyer.presentation.adapter

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.domain.service.StopOrder
import com.unhappychoice.droidflyer.domain.service.StopOrderStatus
import com.unhappychoice.droidflyer.presentation.style.DefaultStyle

internal class StopOrderAdapter(private val cancel: (StopOrder) -> Unit) :
    RecyclerView.Adapter<StopOrderAdapter.Holder>() {
    private var orders = emptyList<StopOrder>()
    private var cancelling = emptySet<String>()
    private var rows = emptyList<Row>()

    fun updateOrders(newOrders: List<StopOrder>) {
        if (orders == newOrders) return
        orders = newOrders
        applyRows()
    }

    fun updateCancellations(ids: Set<String>) {
        if (cancelling == ids) return
        cancelling = ids
        applyRows()
    }

    private fun applyRows() {
        val next = orders.map { Row(it, it.acceptanceId in cancelling) }
        if (rows == next) return
        val diff = DiffUtil.calculateDiff(RowDiff(rows, next))
        rows = next
        diff.dispatchUpdatesTo(this)
    }

    override fun getItemCount() = rows.size

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(rows[position])

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

        fun bind(row: Row) {
            val order = row.order
            val side = context.getString(if (order.parent.side == "BUY") R.string.buy else R.string.sell)
            val trigger = order.triggerPrice?.let { context.getString(R.string.stop_price, it) }
                ?: context.getString(R.string.stop_price_unavailable)
            details.text = context.getString(
                R.string.stop_order_row, side, order.parent.size.toString(), trigger,
                statusText(order), order.acceptanceId
            )
            cancelButton.setText(if (order.canConfirmRecovery) R.string.stop_verify_cancel else R.string.cancel)
            cancelButton.visibility = if (order.canCancel || order.canConfirmRecovery) View.VISIBLE else View.GONE
            cancelButton.isEnabled = !row.cancelling
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

    internal data class Row(val order: StopOrder, val cancelling: Boolean)

    private class RowDiff(private val previous: List<Row>, private val next: List<Row>) : DiffUtil.Callback() {
        override fun getOldListSize() = previous.size
        override fun getNewListSize() = next.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
            previous[oldItemPosition].order.acceptanceId == next[newItemPosition].order.acceptanceId

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
            previous[oldItemPosition] == next[newItemPosition]
    }
}
