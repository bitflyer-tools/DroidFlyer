package com.unhappychoice.droidflyer.domain.service

import com.unhappychoice.droidflyer.extension.Variable
import com.unhappychoice.droidflyer.extension.androidMainThreadScheduler
import com.unhappychoice.droidflyer.extension.subscribeNext
import com.unhappychoice.droidflyer.extension.subscribeOnIoObserveOnUI
import com.unhappychoice.droidflyer.infrastructure.bitflyer.PrivateEvent
import com.unhappychoice.droidflyer.infrastructure.bitflyer.RealtimeClient
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.*
import com.unhappychoice.droidflyer.infrastructure.preference.APITokenPreference
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.CompletableSubject
import io.reactivex.subjects.PublishSubject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class CurrentStatusService internal constructor(
    val apiClient: APIClientV1,
    executions: Observable<List<Execution>>,
    boardUpdates: Observable<BoardUpdate>,
    orderEvents: Observable<PrivateEvent<List<ChildOrderEvent>>>,
    privateClient: APIClientV1? = apiClient,
    parentEvents: Observable<PrivateEvent<List<ParentOrderEvent>>> = Observable.empty(),
    privateChannelAvailability: Observable<PrivateEvent<Boolean>> = Observable.empty(),
    submissionGuard: StopSubmissionGuard = StopSubmissionGuard.inMemory(),
    publicFeedDisconnects: Observable<Unit> = Observable.never(),
    maximumOrderSize: Variable<Double> = Variable(CryptoCfdOrderSize.INDIVIDUAL_MAXIMUM),
    private val now: () -> Long = System::currentTimeMillis
) {
    constructor(apiClient: APIClientV1, realtimeClient: RealtimeClient, privateClient: APIClientV1?) :
        this(apiClient, realtimeClient, privateClient, StopSubmissionGuard.inMemory())

    internal constructor(
        apiClient: APIClientV1, realtimeClient: RealtimeClient, privateClient: APIClientV1?,
        submissionGuard: StopSubmissionGuard = StopSubmissionGuard.inMemory(),
        maximumOrderSize: Variable<Double> = Variable(CryptoCfdOrderSize.INDIVIDUAL_MAXIMUM)
    ) : this(
        apiClient,
        realtimeClient.executions,
        boardUpdates(realtimeClient.boardSnapshot, realtimeClient.board),
        realtimeClient.childOrderEvents,
        privateClient,
        realtimeClient.parentOrderEvents,
        realtimeClient.privateChannelAvailability,
        submissionGuard,
        realtimeClient.publicFeedDisconnects,
        maximumOrderSize
    )

    internal val privateState = PrivateTradingState(
        privateClient, submissionGuard = submissionGuard, maximumOrderSize = maximumOrderSize
    )

    internal val currentPrice = Variable(MarketPrice(null))
    val buyPrice = Variable(0L)
    val sellPrice = Variable(0L)
    val position = privateState.position
    internal val tradingHeader = observeTradingHeader(
        currentPrice.asObservable(),
        position.asObservable(),
        privateState.collateral.asObservable(),
        scheduler = androidMainThreadScheduler()
    )
    val tradingTotals = tradingHeader.map { it.totals }.distinctUntilChanged()
    val board = Variable(Board(0.0, listOf(), listOf()))
    val limitVisible = Variable(true)
    val orders = privateState.orders

    private val restBoards = PublishSubject.create<BoardUpdate>().toSerialized()
    private val bag = CompositeDisposable()
    private var accountChanged = CompletableSubject.create()
    private var appliedGeneration: Long? = null
    @Volatile private var explicitAccountSwitch = 0L
    private var channelAvailability: PrivateEvent<Boolean>? = null
    private val statusRefresh = StatusRefreshCoordinator(::refreshStatus)
    private val activeOrderRefresh = StatusRefreshCoordinator(privateState::refreshActiveOrders)
    private val boardFallback = OrderBookFallback()
    private val boardRefresh = StatusRefreshCoordinator(::refreshBoard)
    private val fundingStatus = AtomicReference(FundingStatus())

    init {
        Observable.merge(
            executions.map { batch ->
                val execution = batch.firstOrNull { it.price.isFinite() && it.price > 0.0 }
                MarketPrice(execution?.price)
            }.filter { it.yen != null },
            publicFeedDisconnects.map { MarketPrice(null) }
        )
            .observeOn(androidMainThreadScheduler())
            .subscribeNext { currentPrice.value = it }
            .addTo(bag)

        val realtimeBoards = Observable.merge(boardUpdates, publicFeedDisconnects.map { BoardUpdate.disconnected() })
        val boards = Observable.merge(realtimeBoards, restBoards).filter(boardFallback::acceptUpdate)
        observeOrderBook(boards, Schedulers.single(), boardFallback::isCurrent, limitVisible.asObservable())
            .observeOn(androidMainThreadScheduler())
            .subscribeNext { event ->
                when (event) {
                    is OrderBookEvent.View -> board.value = event.board
                    is OrderBookEvent.Quotes -> {
                        if (buyPrice.value != event.bestAsk) buyPrice.value = event.bestAsk
                        if (sellPrice.value != event.bestBid) sellPrice.value = event.bestBid
                    }
                }
            }
            .addTo(bag)

        val parentUpdates = parentEvents.filter { batch -> batch.value.any { it.productCode == "FX_BTC_JPY" } }
        Observable.merge<PrivateEvent<*>>(orderEvents, parentUpdates)
            .debounce(1, TimeUnit.SECONDS)
            .subscribeOnIoObserveOnUI()
            .filter { it.isCurrent() }
            .subscribeNext { updateStatus() }
            .addTo(bag)

        val stopOrders = privateState.stopOrders
        val childUpdates = orderEvents.filter { batch ->
            batch.value.any { it.productCode == "FX_BTC_JPY" && it.affectsStopOrderStatus() }
        }
        parentUpdates
            .observeOn(androidMainThreadScheduler())
            .filter { it.isCurrent() }
            .doOnNext { stopOrders.onEvents(it.value) }
            .sample(1, TimeUnit.SECONDS)
            .observeOn(androidMainThreadScheduler())
            .filter { it.isCurrent() }
            .subscribeNext { stopOrders.onParentUpdates() }
            .addTo(bag)

        childUpdates.observeOn(androidMainThreadScheduler())
            .filter { it.isCurrent() }
            .doOnNext { stopOrders.onChildEvents(it.value) }
            .sample(1, TimeUnit.SECONDS, true)
            .observeOn(androidMainThreadScheduler())
            .filter { it.isCurrent() }
            .subscribeNext { stopOrders.onChildUpdates() }
            .addTo(bag)

        privateChannelAvailability.observeOn(androidMainThreadScheduler())
            .filter { it.isCurrent() }
            .subscribeNext {
                channelAvailability = it
                stopOrders.onPrivateChannelAvailability(it.value)
            }
            .addTo(bag)

        updateStatus()
    }

    fun dispose() {
        bag.dispose()
        statusRefresh.dispose()
        activeOrderRefresh.dispose()
        boardRefresh.dispose()
        privateState.dispose()
    }

    fun updateStatus() = requestRefresh(followUp = true)

    fun pollStatus() = requestRefresh(followUp = false)

    internal fun updateActiveOrders() = activeOrderRefresh.request(followUp = true)

    private fun requestRefresh(followUp: Boolean) {
        statusRefresh.request(followUp)
        activeOrderRefresh.request(followUp)
        boardRefresh.request(followUp = false)
    }

    internal fun initializeAccount(
        preference: APITokenPreference,
        loadAccount: (APITokenPreference.Snapshot) -> Pair<APIClientV1, StopSubmissionGuard>?
    ) = loadSavedAccount(preference, loadAccount, clearIfUnconfigured = false)

    internal fun applyAccountChange(
        preference: APITokenPreference,
        loadAccount: (APITokenPreference.Snapshot) -> Pair<APIClientV1, StopSubmissionGuard>?
    ) = loadSavedAccount(preference, loadAccount, clearIfUnconfigured = true)

    private fun loadSavedAccount(
        preference: APITokenPreference,
        loadAccount: (APITokenPreference.Snapshot) -> Pair<APIClientV1, StopSubmissionGuard>?,
        clearIfUnconfigured: Boolean
    ) {
        val explicitSwitch = explicitAccountSwitch
        Observable.fromCallable {
            val snapshot = preference.readSnapshot()
            snapshot.generation to loadAccount(snapshot)
        }
            .subscribeOnIoObserveOnUI()
            .subscribeNext { (generation, account) ->
                if (explicitSwitch != explicitAccountSwitch || generation != preference.credentialsGeneration ||
                    generation == appliedGeneration || (account == null && !clearIfUnconfigured)
                ) return@subscribeNext

                appliedGeneration = generation
                replaceAccount(account?.first, account?.second ?: StopSubmissionGuard.inMemory())
            }
            .addTo(bag)
    }

    internal fun switchAccount(client: APIClientV1?, guard: StopSubmissionGuard = StopSubmissionGuard.inMemory()) {
        explicitAccountSwitch++
        appliedGeneration = null
        replaceAccount(client, guard)
    }

    private fun replaceAccount(client: APIClientV1?, guard: StopSubmissionGuard) {
        if (bag.isDisposed) return

        val previous = accountChanged
        accountChanged = CompletableSubject.create()
        privateState.switchAccount(client, guard) {
            channelAvailability?.takeIf { client != null && it.value && it.isCurrent() }?.let {
                privateState.stopOrders.onPrivateChannelAvailability(true)
            }
            // Queue the new account before ending an in-flight refresh.
            updateStatus()
            previous.onComplete()
        }
    }

    internal fun observeFunding(visibility: Observable<Boolean>): Observable<FundingStatus> =
        Observable.defer {
            visibility.distinctUntilChanged()
                .switchMap { visible ->
                    if (visible) {
                        Observable.merge(pollFunding().map(::updateFundingStatus), expiryTicks())
                            .startWith(unexpiredFundingStatus())
                    } else {
                        Observable.empty()
                    }
                }
                .map { unexpiredFundingStatus() }
                .startWith(unexpiredFundingStatus())
                .distinctUntilChanged()
        }

    private fun updateFundingStatus(update: FundingStatus): FundingStatus {
        do {
            val previous = fundingStatus.get()
            val next = if (update.funding != null) update else previous.copy(stale = previous.funding != null)
            if (fundingStatus.compareAndSet(previous, next)) return next
        } while (true)
    }

    private fun unexpiredFundingStatus(): FundingStatus {
        val status = fundingStatus.get()
        val funding = status.funding
        return if (funding != null && funding.isExpired(now())) status.copy(funding = null) else status
    }

    private fun expiryTicks(): Observable<FundingStatus> =
        Observable.interval(1, TimeUnit.SECONDS)
            .observeOn(androidMainThreadScheduler())
            .map { unexpiredFundingStatus() }

    private fun pollFunding(): Observable<FundingStatus> =
        Observable.defer { apiClient.getFundingRate("FX_BTC_JPY") }
            .firstOrError()
            .map { FundingStatus(it.toNextFunding()) }
            .onErrorReturnItem(FundingStatus())
            .toObservable()
            .subscribeOnIoObserveOnUI()
            .repeatWhen { it.delay(1, TimeUnit.MINUTES) }

    private fun refreshStatus(): Completable = Completable.ambArray(
        accountChanged,
        privateState.refreshPositionsAndCollateral()
    )

    private fun refreshBoard(): Completable =
        Observable.defer {
            val revision = boardFallback.requestRevision() ?: return@defer Observable.empty<BoardUpdate>()
            apiClient.getBoard("FX_BTC_JPY").map { BoardUpdate(it, BoardSource.REST, revision) }
        }
            .subscribeOnIoObserveOnUI()
            .publishStatus(restBoards::onNext)
}
