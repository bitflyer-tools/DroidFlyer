package com.unhappychoice.droidflyer.presentation.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.databinding.PriceAlertListItemBinding
import com.unhappychoice.droidflyer.domain.service.PriceAlert
import com.unhappychoice.droidflyer.domain.service.PriceAlertDirection

internal class PriceAlertAdapter(
    private val onRemove: (PriceAlert) -> Unit
) : RecyclerView.Adapter<PriceAlertAdapter.ViewHolder>() {
    private var items = emptyList<PriceAlert>()

    fun update(alerts: List<PriceAlert>) {
        if (items == alerts) return

        items = alerts
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(PriceAlertListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], onRemove)
    }

    class ViewHolder(private val binding: PriceAlertListItemBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(alert: PriceAlert, onRemove: (PriceAlert) -> Unit) {
            val context = itemView.context
            val direction = context.getString(
                when (alert.direction) {
                    PriceAlertDirection.ABOVE -> R.string.alert_direction_above
                    PriceAlertDirection.BELOW -> R.string.alert_direction_below
                }
            )
            binding.alertDescription.text = context.getString(R.string.price_alert_description, direction, alert.targetPrice)
            binding.removeAlertButton.setOnClickListener { onRemove(alert) }
        }
    }
}
