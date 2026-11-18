package com.unhappychoice.droidflyer.presentation.view

import android.app.Activity
import android.app.Application
import android.view.View
import android.widget.EditText
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.presentation.presenter.core.HasAmount
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "en-rUS")
class AmountSelectorViewTest {
    private lateinit var selector: AmountSelectorView
    private lateinit var field: EditText

    @Before
    fun setUp() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        selector = AmountSelectorView(activity, null)
        activity.setContentView(selector)
        field = selector.findViewById(R.id.amountTextView)
    }

    @Test
    fun typingFractionPreservesIntermediateTextAndUpdatesAmount() {
        field.text.clear()
        "0.0".forEach { field.text.append(it) }
        assertEquals("0.0", field.text.toString())

        field.text.append("15")
        assertEquals("0.015", field.text.toString())
        assertEquals(0.015, selector.amount.value, 0.0)

        field.text.clear()
        field.text.append('.')
        assertEquals(".", field.text.toString())
        assertEquals(0.0, selector.amount.value, 0.0)

        field.text.append("1")
        assertEquals(".1", field.text.toString())
        assertEquals(0.1, selector.amount.value, 0.0)
    }

    @Test
    fun localizedDecimalTextUpdatesAmount() {
        field.setText("0,015")
        assertEquals(0.015, selector.amount.value, 0.0)

        field.setText("\u0660\u066B\u0660\u0662\u0665")
        assertEquals(0.025, selector.amount.value, 0.0)
    }

    @Test
    fun fractionalPresetsSetTheStepUsedByBothButtons() {
        connectAmountButtons()
        field.text.replace(0, field.length(), "0.015")

        selector.findViewById<View>(R.id.dotOneButton).performClick()
        selector.findViewById<View>(R.id.plusButton).performClick()
        assertEquals("0.025", field.text.toString())

        selector.findViewById<View>(R.id.dotFiveButton).performClick()
        selector.findViewById<View>(R.id.plusButton).performClick()
        assertEquals("0.125", field.text.toString())

        selector.findViewById<View>(R.id.minusButton).performClick()
        assertEquals("0.025", field.text.toString())
    }

    @Test
    @Config(qualifiers = "de-rDE")
    fun editingAfterButtonClicksKeepsOneLocalizedDecimalSeparator() {
        connectAmountButtons()
        field.setText("0,015")
        assertEquals(0.015, selector.amount.value, 0.0)

        selector.findViewById<View>(R.id.dotOneButton).performClick()
        selector.findViewById<View>(R.id.plusButton).performClick()
        assertEquals("0,025", field.text.toString())

        field.text.append(',')
        field.text.append('5')
        assertEquals("0,0255", field.text.toString())
        assertEquals(0.0255, selector.amount.value, 0.0)

        field.text.replace(1, field.length(), ",0355")
        assertEquals("0,0355", field.text.toString())
        assertEquals(0.0355, selector.amount.value, 0.0)

        selector.findViewById<View>(R.id.minusButton).performClick()
        assertEquals("0,0255", field.text.toString())
    }

    private fun connectAmountButtons() {
        val order = object : HasAmount {
            override val unitSize = selector.size
            override val amount = selector.amount
        }
        selector.didIncrement.subscribe { order.incrementAmountByUnitSize() }
        selector.didDecrement.subscribe { order.decrementAmountByUnitSize() }
    }
}
