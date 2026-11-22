package com.unhappychoice.droidflyer.presentation.presenter

import com.unhappychoice.droidflyer.domain.service.CurrentStatusService
import com.unhappychoice.droidflyer.domain.service.PrivateTradingState
import com.unhappychoice.droidflyer.extension.androidMainThreadScheduler
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrder
import com.unhappychoice.droidflyer.presentation.view.OrdersView
import io.reactivex.Observable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.PublishSubject
import mortar.ViewPresenter

class OrdersPresenter internal constructor(
    private val privateState: PrivateTradingState,
    private val refreshOrders: () -> Unit
) : ViewPresenter<OrdersView>() {
    constructor(currentStatusService: CurrentStatusService) :
        this(currentStatusService.privateState, currentStatusService::updateStatus)

    internal val orders = privateState.orderRows()
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
            .subscribeOn(Schedulers.io())
            .observeOn(androidMainThreadScheduler())
            .subscribe(
                { refreshOrders() },
                { failedCancellations.onNext(row.order) }
            )
            .addTo(bag)
    }
}
