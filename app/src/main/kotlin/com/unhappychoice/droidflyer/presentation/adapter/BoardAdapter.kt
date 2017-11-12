package com.unhappychoice.droidflyer.presentation.adapter

import android.content.Context
import android.support.v7.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.RelativeLayout
import android.widget.TextView
import com.jakewharton.rxbinding2.view.clicks
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.extension.Variable
import com.unhappychoice.droidflyer.extension.bindTo
import com.unhappychoice.droidflyer.extension.splitByComma
import com.unhappychoice.droidflyer.extension.subscribeNext
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrder
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Order
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ceilBySize
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.floorBySize
import com.unhappychoice.droidflyer.presentation.style.DefaultStyle
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo
import io.reactivex.subjects.PublishSubject

class BoardAdapter(val context: Context, val type: BoardType) : RecyclerView.Adapter<BoardAdapter.ViewHolder>() {
    val myOrders = Variable<List<ChildOrder>>(listOf())
    val items = Variable<List<Order>>(listOf())
    val clickItems = PublishSubject.create<Order>()
    val groupSize = Variable(1L)

    private val groupedItems = Variable<List<Order>>(listOf())

    private val bag = CompositeDisposable()

    init {
        items.asObservable()
            .map { type.groupBySize(it, groupSize.value).take(50) }
            .bindTo(groupedItems)
            .addTo(bag)

        groupSize.asObservable()
            .map { type.groupBySize(items.value, groupSize.value).take(50) }
            .bindTo(groupedItems)
            .addTo(bag)
    }

    override fun getItemCount() = groupedItems.value.size

    override fun onBindViewHolder(holder: BoardAdapter.ViewHolder, position: Int) {
        val item = groupedItems.value[position]
        holder.bind(item)
        holder.view.setOnClickListener { clickItems.onNext(item) }
    }

    override fun onCreateViewHolder(parent: ViewGroup?, viewType: Int): BoardAdapter.ViewHolder {
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

            val myOrders = myOrders.value.filter { type.groupedPrice(it, groupSize.value) == order.price }
            mineSizeTextView.visibility = if (myOrders.isEmpty()) View.GONE else View.VISIBLE
            mineSizeTextView.text = myOrders.map { it.size }.sum().toString()
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

sealed class BoardType {
    abstract fun groupedPrice(order: ChildOrder, size: Long): Long
    abstract fun groupBySize(orders: List<Order>, size: Long): List<Order>
    abstract fun sizeDirection(): Int
    abstract fun priceDirection(): Int
    abstract fun color(): Int

    object Ask : BoardType() {
        override fun groupedPrice(order: ChildOrder, size: Long) = order.ceiledPrice(size)
        override fun groupBySize(orders: List<Order>, size: Long) = orders.ceilBySize(size)
        override fun color() = DefaultStyle.sellColor
        override fun priceDirection() = RelativeLayout.ALIGN_PARENT_RIGHT
        override fun sizeDirection() = RelativeLayout.ALIGN_PARENT_LEFT
    }

    object Bid : BoardType() {
        override fun groupedPrice(order: ChildOrder, size: Long) = order.flooredPrice(size)
        override fun groupBySize(orders: List<Order>, size: Long) = orders.floorBySize(size)
        override fun color() = DefaultStyle.buyColor
        override fun priceDirection() = RelativeLayout.ALIGN_PARENT_LEFT
        override fun sizeDirection() = RelativeLayout.ALIGN_PARENT_RIGHT
    }
}