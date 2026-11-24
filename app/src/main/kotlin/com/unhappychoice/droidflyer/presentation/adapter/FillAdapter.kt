package com.unhappychoice.droidflyer.presentation.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.databinding.FillListItemBinding
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.PrivateExecution
import com.unhappychoice.droidflyer.presentation.style.DefaultStyle

internal class FillAdapter : RecyclerView.Adapter<FillAdapter.ViewHolder>() {
    private val items = mutableListOf<PrivateExecution>()

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(FillListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    fun replaceAll(fills: List<PrivateExecution>) {
        val diff = DiffUtil.calculateDiff(FillDiff(items, fills))
        items.clear()
        items.addAll(fills)
        diff.dispatchUpdatesTo(this)
    }

    fun appendOlder(older: List<PrivateExecution>) {
        if (older.isEmpty()) return
        val start = items.size
        items.addAll(older)
        notifyItemRangeInserted(start, older.size)
    }

    class ViewHolder(private val binding: FillListItemBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(fill: PrivateExecution) {
            val context = itemView.context
            val side = when (fill.side) {
                "BUY" -> context.getString(R.string.buy)
                "SELL" -> context.getString(R.string.sell)
                else -> fill.side
            }
            binding.fillDetails.setTextColor(DefaultStyle.accentColor)
            binding.fillTime.setTextColor(DefaultStyle.accentColor)
            binding.fillDetails.text = context.getString(
                R.string.fill_details, side, fill.price.toPlainString(), fill.size.toPlainString()
            )
            binding.fillTime.text = fill.execDate.replace('T', ' ')
        }
    }

    private class FillDiff(
        private val old: List<PrivateExecution>,
        private val new: List<PrivateExecution>
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = old.size
        override fun getNewListSize() = new.size
        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
            old[oldItemPosition].id == new[newItemPosition].id
        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
            old[oldItemPosition] == new[newItemPosition]
    }
}
