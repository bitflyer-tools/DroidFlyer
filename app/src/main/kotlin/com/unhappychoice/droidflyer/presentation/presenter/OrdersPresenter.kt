package com.unhappychoice.droidflyer.presentation.presenter

import com.unhappychoice.droidflyer.domain.service.CurrentStatusService
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.CancelChildOrderRequest
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrder
import com.unhappychoice.droidflyer.presentation.view.OrdersView
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.PublishSubject
import mortar.ViewPresenter

class OrdersPresenter internal constructor(
    private val api: APIClientV1,
    val orders: Observable<List<ChildOrder>>,
    private val refreshOrders: () -> Unit
) : ViewPresenter<OrdersView>() {
    constructor(api: APIClientV1, currentStatusService: CurrentStatusService) :
        this(api, currentStatusService.orders.asObservable(), currentStatusService::updateStatus)

    val cancellationFailures: Observable<ChildOrder> get() = failedCancellations.hide()

    private val failedCancellations = PublishSubject.create<ChildOrder>()
    private val bag = CompositeDisposable()

    override fun onExitScope() {
        bag.dispose()
        super.onExitScope()
    }

    fun cancelOrder(order: ChildOrder) {
        val request = CancelChildOrderRequest(order.productCode, order.childOrderAcceptanceId)
        api.cancelChildOrder(request)
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { refreshOrders() },
                { failedCancellations.onNext(order) }
            )
            .addTo(bag)
    }
}
