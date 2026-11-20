# DroidFlyer architecture

DroidFlyer is a Kotlin Android app in the `app` Gradle module. See the
[contributing guide](../CONTRIBUTING.md) for development setup and test commands.

## App structure

- [MainActivity](../app/src/main/kotlin/com/unhappychoice/droidflyer/MainActivity.kt)
  uses Flow to navigate among Chart, Order, and Settings. It creates the
  [realtime session](../app/src/main/kotlin/com/unhappychoice/droidflyer/RealtimeSession.kt)
  for execution notifications and closes it when the activity is destroyed.
- The Kodein [activity module](../app/src/main/kotlin/com/unhappychoice/droidflyer/di/ActivityModule.kt)
  wires presenters to services and clients. Screens choose XML layouts, views
  bind widgets and observe state, and presenters handle interactions.
- [CurrentStatusService](../app/src/main/kotlin/com/unhappychoice/droidflyer/domain/service/CurrentStatusService.kt)
  maintains shared trading state for the order screen.
  [PrivateTradingState](../app/src/main/kotlin/com/unhappychoice/droidflyer/domain/service/PrivateTradingState.kt)
  tracks account-specific orders, positions, and balance.
- [APIClient](../app/src/main/kotlin/com/unhappychoice/droidflyer/infrastructure/bitflyer/http/APIClient.kt)
  builds the Retrofit REST client and signs private requests.
  [RealtimeClient](../app/src/main/kotlin/com/unhappychoice/droidflyer/infrastructure/bitflyer/RealtimeClient.kt)
  exposes Socket.IO market and order events as RxJava streams.

## Trace an order book update

1. Start in `CurrentStatusService`. It fetches the Crypto CFD order book over
   REST and applies snapshots and deltas from `RealtimeClient`. Each snapshot
   replaces the book, and each delta merges into the current book.
2. [LimitOrderView](../app/src/main/kotlin/com/unhappychoice/droidflyer/presentation/view/LimitOrderView.kt)
   observes the shared book and the presenter's price grouping size. It passes
   the resulting ask and bid rows to the adapters.
3. Read [BoardUpdatesTest](../app/src/test/kotlin/com/unhappychoice/droidflyer/domain/service/BoardUpdatesTest.kt)
   and [LimitOrderRowsTest](../app/src/test/kotlin/com/unhappychoice/droidflyer/presentation/view/LimitOrderRowsTest.kt)
   for examples of book updates and price grouping.
