package com.unhappychoice.droidflyer.presentation.presenter

import com.unhappychoice.droidflyer.domain.service.CurrentStatusService
import com.unhappychoice.droidflyer.extension.androidMainThreadScheduler
import com.unhappychoice.droidflyer.extension.subscribeNext
import com.unhappychoice.droidflyer.presentation.view.OrderView
import io.reactivex.Observable
import io.reactivex.disposables.Disposable
import mortar.ViewPresenter
import java.util.concurrent.TimeUnit

class OrderPresenter(val currentStatusService: CurrentStatusService) : ViewPresenter<OrderView>() {
    private var polling: Disposable? = null

    fun startPolling() {
        if (polling != null) return

        polling = Observable.interval(0, 6, TimeUnit.SECONDS)
            .observeOn(androidMainThreadScheduler())
            .subscribeNext { tick ->
                if (tick == 0L) currentStatusService.updateStatus() else currentStatusService.pollStatus()
            }
    }

    fun stopPolling() {
        polling?.dispose()
        polling = null
    }
}
