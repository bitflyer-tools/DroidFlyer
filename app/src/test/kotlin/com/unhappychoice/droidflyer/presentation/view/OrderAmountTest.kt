package com.unhappychoice.droidflyer.presentation.view

import io.reactivex.subjects.PublishSubject
import org.junit.Test

class OrderAmountTest {
    @Test
    fun clearingQuantityResetsTheAmountAndAllowsFurtherEdits() {
        val textChanges = PublishSubject.create<CharSequence>()
        val amounts = textChanges.orderAmounts().test()

        listOf("12", "", "3").forEach { textChanges.onNext(it) }

        amounts.assertValues(12.0, 0.0, 3.0).assertNoErrors().assertNotComplete()
    }

    @Test
    fun invalidAndNonFiniteQuantitiesResetTheAmount() {
        val textChanges = PublishSubject.create<CharSequence>()
        val amounts = textChanges.orderAmounts().test()

        listOf("1", ".", "2", "NaN", "3", "1e999", "4").forEach { textChanges.onNext(it) }

        amounts.assertValues(1.0, 0.0, 2.0, 0.0, 3.0, 0.0, 4.0).assertNoErrors().assertNotComplete()
    }
}
