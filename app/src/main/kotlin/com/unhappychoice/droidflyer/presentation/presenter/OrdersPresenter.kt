package com.unhappychoice.droidflyer.presentation.presenter

import com.unhappychoice.droidflyer.domain.service.CurrentStatusService
import com.unhappychoice.droidflyer.domain.service.PrivateTradingState
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrder
import com.unhappychoice.droidflyer.presentation.view.OrdersView
import io.reactivex.Observable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo
import io.reactivex.subjects.PublishSubject
import mortar.ViewPresenter

class OrdersPresenter internal constructor(
    private val privateState: PrivateTradingState,
    private val refreshOrders: () -> Unit
) : ViewPresenter<OrdersView>() {
    constructor(currentStatusService: CurrentStatusService) :
        this(currentStatusService.privateState, currentStatusService::updateStatus)

    val orders = privateState.orders.asObservable()

    val cancellationFailures: Observable<ChildOrder> get() = failedCancellations.hide()

    private val failedCancellations = PublishSubject.create<ChildOrder>()
    private val bag = CompositeDisposable()

    override fun onExitScope() {
        bag.dispose()
        super.onExitScope()
    }

    fun cancelOrder(order: ChildOrder) {
        val cancellation = privateState.cancelOrder(order) ?: return
        cancellation
            .subscribe(
                { refreshOrders() },
                { failedCancellations.onNext(order) }
            )
            .addTo(bag)
    }
}
