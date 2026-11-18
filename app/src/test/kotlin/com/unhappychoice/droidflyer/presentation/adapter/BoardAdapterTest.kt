package com.unhappychoice.droidflyer.presentation.adapter

import android.view.View
import android.widget.RelativeLayout
import android.widget.TextView
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Order
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class BoardAdapterTest {
    @Test
    fun clickingAskRowsEmitsTheirGroupedPricesForSellConfirmation() {
        val context = RuntimeEnvironment.getApplication()
        val adapter = BoardAdapter(context, BoardType.Ask)
        val exactAsk = Order(100L, 1.0)
        val offBucketAsk = Order(101L, 2.0)
        adapter.groupSize.value = 10L
        adapter.items.value = listOf(exactAsk, offBucketAsk)

        val row = RelativeLayout(context).apply {
            listOf(R.id.price, R.id.size, R.id.mineSize).forEach { id ->
                addView(TextView(context).apply { this.id = id })
            }
            addView(View(context).apply { id = R.id.backgroundView })
        }
        val holder = adapter.ViewHolder(row)
        val selectedAsks = adapter.clickItems.test()

        adapter.onBindViewHolder(holder, 0)
        row.performClick()
        adapter.onBindViewHolder(holder, 1)
        row.performClick()

        selectedAsks.assertValues(exactAsk, offBucketAsk.copy(price = 110L))
    }
}
