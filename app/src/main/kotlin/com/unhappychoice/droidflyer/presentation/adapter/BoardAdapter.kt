package com.unhappychoice.droidflyer.presentation.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.extension.splitByComma
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrder
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Order
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ceilBySize
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.floorBySize
import com.unhappychoice.droidflyer.presentation.style.DefaultStyle
import io.reactivex.rxjava3.subjects.PublishSubject

class BoardAdapter(val context: Context, val type: BoardType) : RecyclerView.Adapter<BoardAdapter.ViewHolder>() {
    val clickItems = PublishSubject.create<Order>()

    private val screenWidth = context.resources.displayMetrics.widthPixels

    internal var orderEntryEnabled = true
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount)
        }

    private var rows = emptyList<Row>()
    private var groupingSize = 1L
    private var myOrders = emptyList<ChildOrder>()
    private var outstandingSizes = emptyMap<Long, Double>()

    fun applyRows(orders: List<Order>, groupingSize: Long, myOrders: List<ChildOrder>) {
        if (this.groupingSize != groupingSize || this.myOrders !== myOrders) {
            outstandingSizes = type.personalOutstandingSizes(myOrders, groupingSize)
            this.groupingSize = groupingSize
            this.myOrders = myOrders
        }
        val previous = rows
        val next = orders.map { order ->
            Row(order, groupingSize, outstandingSizes[order.price])
        }
        if (previous == next) return
        val diff = DiffUtil.calculateDiff(RowDiff(previous, next))
        rows = next
        diff.dispatchUpdatesTo(this)
    }

    override fun getItemCount() = rows.size

    override fun onBindViewHolder(holder: BoardAdapter.ViewHolder, position: Int) {
        val item = rows[position]
        holder.bind(item.order, item.outstandingSize)
        holder.view.setOnClickListener { if (orderEntryEnabled) clickItems.onNext(item.order) }
        holder.view.isClickable = orderEntryEnabled
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BoardAdapter.ViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.order_list_item, parent, false)
        return ViewHolder(view)
    }

    inner class ViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
        private val priceTextView = view.findViewById<TextView>(R.id.price)
        private val mineSizeTextView = view.findViewById<TextView>(R.id.mineSize)
        private val sizeTextView = view.findViewById<TextView>(R.id.size)
        private val backgroundView = view.findViewById<View>(R.id.backgroundView)
        private var backgroundWidth: Int? = null

        init {
            priceTextView.alignParent(type.priceDirection())
            sizeTextView.alignParent(type.sizeDirection())
        }

        fun bind(order: Order, outstandingSize: Double?) {
            priceTextView.text = order.price.splitByComma()
            sizeTextView.text = "%.3f".format(order.size)

            priceTextView.setTextColor(type.color())
            sizeTextView.setTextColor(type.color())

            val width = (screenWidth / 2.0 * Math.min(Math.log10(order.size + 1) / 2.0, 1.0) - 1.0).toInt()
            backgroundView.setBackgroundColor(type.color())
            if (backgroundWidth != width) {
                backgroundWidth = width
                backgroundView.alignParent(type.sizeDirection(), width)
            }

            mineSizeTextView.visibility = if (outstandingSize == null) View.GONE else View.VISIBLE
            mineSizeTextView.text = outstandingSize?.toString().orEmpty()
            mineSizeTextView.setTextColor(DefaultStyle.accentColor)
        }

        private fun View.alignParent(direction: Int, width: Int? = null) {
            val params = RelativeLayout.LayoutParams(
                width ?: RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            )
            params.addRule(direction)
            params.alignWithParent = true
            layoutParams = params
        }
    }

    private data class Row(val order: Order, val groupingSize: Long, val outstandingSize: Double?)

    private class RowDiff(private val previous: List<Row>, private val next: List<Row>) : DiffUtil.Callback() {
        override fun getOldListSize() = previous.size
        override fun getNewListSize() = next.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
            previous[oldItemPosition].order.price == next[newItemPosition].order.price

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
            previous[oldItemPosition] == next[newItemPosition]
    }
}

sealed class BoardType(private val side: String) {
    abstract fun groupedPrice(order: ChildOrder, size: Long): Long
    abstract fun groupBySize(orders: List<Order>, size: Long): List<Order>
    abstract fun sizeDirection(): Int
    abstract fun priceDirection(): Int
    abstract fun color(): Int

    internal fun personalOutstandingSizes(orders: List<ChildOrder>, groupSize: Long): Map<Long, Double> =
        orders.asSequence()
            .filter { it.side == side }
            .groupingBy { groupedPrice(it, groupSize) }
            .fold(0.0) { total, order -> total + order.outstandingSize }

    object Ask : BoardType("SELL") {
        override fun groupedPrice(order: ChildOrder, size: Long) = order.ceiledPrice(size)
        override fun groupBySize(orders: List<Order>, size: Long) = orders.ceilBySize(size)
        override fun color() = DefaultStyle.sellColor
        override fun priceDirection() = RelativeLayout.ALIGN_PARENT_RIGHT
        override fun sizeDirection() = RelativeLayout.ALIGN_PARENT_LEFT
    }

    object Bid : BoardType("BUY") {
        override fun groupedPrice(order: ChildOrder, size: Long) = order.flooredPrice(size)
        override fun groupBySize(orders: List<Order>, size: Long) = orders.floorBySize(size)
        override fun color() = DefaultStyle.buyColor
        override fun priceDirection() = RelativeLayout.ALIGN_PARENT_LEFT
        override fun sizeDirection() = RelativeLayout.ALIGN_PARENT_RIGHT
    }
}
