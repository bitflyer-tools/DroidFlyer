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
    private var outstandingSizes = emptyMap<Long, Double>()

    fun applyRows(rows: List<Order>, groupingSize: Long) {
        this.rows = rows
        if (this.groupingSize != groupingSize) {
            this.groupingSize = groupingSize
            outstandingSizes = type.personalOutstandingSizes(myOrders, groupingSize)
        }
        notifyDataSetChanged()
    }

    override fun getItemCount() = rows.size

    fun updateMyOrders(orders: List<ChildOrder>) {
        val previousSizes = outstandingSizes
        myOrders = orders
        outstandingSizes = type.personalOutstandingSizes(orders, groupingSize)
        rows.forEachIndexed { position, item ->
            if (previousSizes[item.price] != outstandingSizes[item.price]) notifyItemChanged(position)
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

            val outstandingSize = outstandingSizes[order.price]
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
