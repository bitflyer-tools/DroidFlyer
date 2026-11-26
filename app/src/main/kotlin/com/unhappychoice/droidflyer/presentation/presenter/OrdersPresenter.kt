package com.unhappychoice.droidflyer.presentation.presenter

import com.unhappychoice.droidflyer.domain.service.CurrentStatusService
import com.unhappychoice.droidflyer.domain.service.PrivateTradingState
import com.unhappychoice.droidflyer.domain.service.StopOrder
import com.unhappychoice.droidflyer.domain.service.StopOrderService
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
        this(currentStatusService.privateState, currentStatusService::updateActiveOrders)

    internal val orders = privateState.orderRows()
    internal val activeOrdersStatus = privateState.activeOrdersStatus.asObservable().distinctUntilChanged()
    internal val stopOrders = privateState.stopOrders.orders.asObservable()
    internal val stopLoadFailed = privateState.stopOrders.loadFailed.asObservable()
    internal val stopCancellations = privateState.stopOrders.cancelling.asObservable()

    internal fun refreshStops() = privateState.stopOrders.refresh(recoverTriggers = true)

    internal fun stopCancellationConfirmation(order: StopOrder) =
        privateState.stopOrders.cancellationConfirmation(order)

    internal fun cancelStop(
        confirmation: StopOrderService.CancellationConfirmation,
        failed: (StopOrderService.CancellationFailure) -> Unit
    ) = privateState.stopOrders.cancel(confirmation, failed)

    internal val ordersMayBeIncomplete = privateState.ordersMayBeIncomplete

    val cancellationFailures: Observable<ChildOrder> get() = failedCancellations.hide()

    internal val fillHistory = FillHistory(privateState::getMyExecutions)

    internal val fillAccountChanges = privateState.accountChanges.doOnNext { fillHistory.clear() }

    private val failedCancellations = PublishSubject.create<ChildOrder>()
    private val bag = CompositeDisposable()

    override fun onExitScope() {
        fillHistory.clear()
        bag.dispose()
        super.onExitScope()
    }

    internal fun cancelOrder(row: PrivateTradingState.OrderRow) {
        val cancellation = privateState.cancelOrder(row) ?: return
        cancellation
            .subscribe(
                { if (privateState.isCurrent(row)) refreshOrders() },
                { failedCancellations.onNext(row.order) }
            )
            .addTo(bag)
    }
}
