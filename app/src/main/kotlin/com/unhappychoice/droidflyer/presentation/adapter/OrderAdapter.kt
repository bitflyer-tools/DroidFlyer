package com.unhappychoice.droidflyer.presentation.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.domain.service.PrivateTradingState.OrderRow
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrder
import com.unhappychoice.droidflyer.presentation.style.DefaultStyle
import io.reactivex.subjects.PublishSubject

internal class OrderAdapter(val context: Context?) : RecyclerView.Adapter<OrderAdapter.ViewHolder>() {
    val clickCancel = PublishSubject.create<OrderRow>()
    private var items = emptyList<OrderRow>()

    fun updateOrders(orders: List<OrderRow>) {
        if (items == orders) return

        items = orders
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: OrderAdapter.ViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OrderAdapter.ViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.child_order_list_item, parent, false)
        return ViewHolder(view)
    }

    inner class ViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
        fun bind(row: OrderRow) {
            val order = row.order
            setupStyle(order)
            typeTextView.text = if (order.side == "BUY") context?.getString(R.string.buy) else context?.getString(R.string.sell)
            priceTextView.text = "${order.price} JPY"
            sizeTextView.text = "${order.outstandingSize} ɃFX"
            cancelTextView.setOnClickListener { clickCancel.onNext(row) }
        }

        private fun setupStyle(order: ChildOrder) {
            if (order.side == "BUY") {
                typeTextView.setBackgroundColor(DefaultStyle.buyColor)
            } else {
                typeTextView.setBackgroundColor(DefaultStyle.sellColor)
            }

            typeTextView.setTextColor(DefaultStyle.accentColor)
            priceTextView.setTextColor(DefaultStyle.accentColor)
            sizeTextView.setTextColor(DefaultStyle.accentColor)
            cancelTextView.setTextColor(DefaultStyle.accentColor)
        }

        private val typeTextView = view.findViewById<TextView>(R.id.type)
        private val priceTextView = view.findViewById<TextView>(R.id.price)
        private val sizeTextView = view.findViewById<TextView>(R.id.size)
        private val cancelTextView = view.findViewById<TextView>(R.id.cancel)
    }
}
