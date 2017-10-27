package com.unhappychoice.droidflyer.presentation.adapter

import android.content.Context
import android.support.v7.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.extension.Variable
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrder
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.subjects.PublishSubject

class OrderAdapter(val context: Context?) : RecyclerView.Adapter<OrderAdapter.ViewHolder>() {
    val items = Variable<List<ChildOrder>>(listOf())
    val clickItems = PublishSubject.create<ChildOrder>()

    override fun getItemCount() = items.value.size

    override fun onBindViewHolder(holder: OrderAdapter.ViewHolder, position: Int) {
        val item = items.value[position]
        holder.bind(item)
        holder.view.setOnClickListener { clickItems.onNext(item) }
    }

    override fun onCreateViewHolder(parent: ViewGroup?, viewType: Int): OrderAdapter.ViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.child_order_list_item, parent, false)
        return ViewHolder(view)
    }

    inner class ViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
        fun bind(order: ChildOrder) {
            priceTextView.text = order.price.toString()
            sizeTextView.text = order.size.toString()
        }

        private val priceTextView = view.findViewById<TextView>(R.id.price)
        private val sizeTextView = view.findViewById<TextView>(R.id.size)
    }
}
