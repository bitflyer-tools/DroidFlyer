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
import com.unhappychoice.droidflyer.extension.Variable
import com.unhappychoice.droidflyer.extension.subscribeNext
import com.unhappychoice.droidflyer.extension.splitByComma
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrder
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Order
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ceilBySize
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.floorBySize
import com.unhappychoice.droidflyer.presentation.style.DefaultStyle
import io.reactivex.Scheduler
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.Observables
import io.reactivex.rxkotlin.addTo
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.PublishSubject

class BoardAdapter internal constructor(
    val context: Context,
    val type: BoardType,
    groupingScheduler: Scheduler
) : RecyclerView.Adapter<BoardAdapter.ViewHolder>() {
    constructor(context: Context, type: BoardType) : this(context, type, Schedulers.computation())

    val myOrders = Variable<List<ChildOrder>>(listOf())
    val items = Variable<List<Order>>(listOf())
    val clickItems = PublishSubject.create<Order>()
    val groupSize = Variable(1L)

    private var groupedRows = GroupedRows(1L, emptyList())

    private val bag = CompositeDisposable()

    init {
        Observables.combineLatest(items.asObservable(), groupSize.asObservable())
            .observeOn(groupingScheduler)
            .map { (orders, size) -> GroupedRows(size, type.groupBySize(orders, size).take(50)) }
            .observeOn(AndroidSchedulers.mainThread())
            .subscribeNext {
                groupedRows = it
                notifyDataSetChanged()
            }
            .addTo(bag)
    }

    override fun getItemCount() = groupedRows.orders.size

    override fun onBindViewHolder(holder: BoardAdapter.ViewHolder, position: Int) {
        val item = groupedRows.orders[position]
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

            val outstandingSize = type.personalOutstandingSize(myOrders.value, order.price, groupedRows.size)
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
    private data class GroupedRows(val size: Long, val orders: List<Order>)
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
