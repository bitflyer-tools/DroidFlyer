package com.unhappychoice.droidflyer.presentation.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.databinding.FillListItemBinding
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.PrivateExecution
import com.unhappychoice.droidflyer.presentation.style.DefaultStyle

internal class FillAdapter : ListAdapter<PrivateExecution, FillAdapter.ViewHolder>(fillDiff) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(FillListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
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

    private companion object {
        val fillDiff = object : DiffUtil.ItemCallback<PrivateExecution>() {
            override fun areItemsTheSame(old: PrivateExecution, new: PrivateExecution) = old.id == new.id
            override fun areContentsTheSame(old: PrivateExecution, new: PrivateExecution) = old == new
        }
    }
}
