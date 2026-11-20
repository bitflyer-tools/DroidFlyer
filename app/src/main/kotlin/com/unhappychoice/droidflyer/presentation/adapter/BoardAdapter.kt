package com.unhappychoice.droidflyer.presentation.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.extension.splitByComma
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrder
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Order
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ceilBySize
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.floorBySize
import com.unhappychoice.droidflyer.presentation.style.DefaultStyle
import io.reactivex.subjects.PublishSubject

class BoardAdapter(val context: Context, val type: BoardType) : RecyclerView.Adapter<BoardAdapter.ViewHolder>() {
    val clickItems = PublishSubject.create<Order>()

    private var rows = emptyList<Order>()
    private var groupingSize = 1L
    private var myOrders = emptyList<ChildOrder>()

    fun applyRows(rows: List<Order>, groupingSize: Long) {
        this.rows = rows
        this.groupingSize = groupingSize
        notifyDataSetChanged()
    }

    override fun getItemCount() = rows.size

    fun updateMyOrders(orders: List<ChildOrder>) {
        val previousOrders = myOrders
        myOrders = orders
        rows.forEachIndexed { position, item ->
            val previousSize = type.personalOutstandingSize(previousOrders, item.price, groupingSize)
            val currentSize = type.personalOutstandingSize(orders, item.price, groupingSize)
            if (previousSize != currentSize) notifyItemChanged(position)
        }
    }

    override fun onBindViewHolder(holder: BoardAdapter.ViewHolder, position: Int) {
        val item = rows[position]
        holder.bind(item)
        holder.view.setOnClickListener { clickItems.onNext(item) }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BoardAdapter.ViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.order_list_item, parent, false)
        return ViewHolder(view)
    }

    inner class ViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
        fun bind(order: Order) {
            priceTextView.text = order.price.splitByComma()
            sizeTextView.text = "%.3f".format(order.size)

            priceTextView.setTextColor(type.color())
            sizeTextView.setTextColor(type.color())

            priceTextView.alignParent(type.priceDirection())
            sizeTextView.alignParent(type.sizeDirection())

            val screenWidth = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.width
            val width = screenWidth / 2.0 * Math.min(Math.log10(order.size + 1) / 2.0, 1.0) - 1.0
            backgroundView.setBackgroundColor(type.color())
            backgroundView.alignParent(type.sizeDirection(), width.toInt())

            val outstandingSize = type.personalOutstandingSize(myOrders, order.price, groupingSize)
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

        private val priceTextView = view.findViewById<TextView>(R.id.price)
        private val mineSizeTextView = view.findViewById<TextView>(R.id.mineSize)
        private val sizeTextView = view.findViewById<TextView>(R.id.size)
        private val backgroundView = view.findViewById<View>(R.id.backgroundView)
    }
}

sealed class BoardType(private val side: String) {
    internal fun personalOutstandingSize(orders: List<ChildOrder>, price: Long, groupSize: Long): Double? =
        orders.filter { it.side == side && groupedPrice(it, groupSize) == price }
            .takeIf { it.isNotEmpty() }
            ?.sumOf { it.outstandingSize }

    abstract fun groupedPrice(order: ChildOrder, size: Long): Long
    abstract fun groupBySize(orders: List<Order>, size: Long): List<Order>
    abstract fun sizeDirection(): Int
    abstract fun priceDirection(): Int
    abstract fun color(): Int

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
