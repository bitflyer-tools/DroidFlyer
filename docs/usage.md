# Using DroidFlyer

DroidFlyer displays and trades bitFlyer Crypto CFD (BTC-CFD/JPY, product
code `FX_BTC_JPY`). Follow the [quick start](../README.md#quick-start) to
connect a bitFlyer account. You can open **Chart** or view the order book
under **Order** → **Limit** before connecting an account.

## Screens and controls

- **Chart** displays recent Crypto CFD trades as 1-minute or 5-minute candlesticks
  in JPY. Select a candle to see its time range and open, high, low, and close
  prices. The oldest candle may be incomplete.
- **Order** → **Market** submits a buy or sell market order. **Clear all
  positions** closes your open position with a market order: it reads your
  open positions, submits the closing order, then reads the child order by
  its acceptance ID to confirm the order filled. **Limit** shows the live
  order book and lets you select a price level for a limit order. Use
  **Grouping** to change how the book combines price levels.
- **Order** → **Stop** places a Crypto CFD stop order: choose Buy or Sell, a
  quantity, and a trigger price, then review and submit. When the trigger
  price is reached, bitFlyer submits a market order; the execution price may
  differ from the trigger price. Reviewing and canceling stop orders needs
  the **Receive order events** permission; see
  [Stop orders](troubleshooting.md#stop-orders) if it's unavailable.
- **Order** → **Orders** → **Active** lists active child orders and stop
  orders waiting to trigger, and lets you cancel them. **Fills** shows recent
  executions for your account. Tap **Refresh** to reload them or **Load
  older fills** to see earlier executions.

## Account settings

Enter your **API key** and **API secret** under **Setting**. Tap **Apply**
to verify and save the credentials. Enable **Use corporate Crypto CFD
account limits** to raise the app's maximum quantity for market and limit
order entry from 10 to 40 BTC-CFD. This setting is saved when toggled and
does not change your account type at bitFlyer.

## Order fill notifications

With an account linked and an open Crypto CFD order, open **Setting** and tap
**Start order monitoring**. If Android notifications are disabled, tap
**Enable order notifications** first. If Android does not show a permission prompt,
tap **Open notification settings** and allow notifications for DroidFlyer. Return to
**Setting** and tap **Start order monitoring**. DroidFlyer posts system notifications for fills,
rejections, and cancellations received while monitoring, including when the app is
in the background or the screen is off.

Tap **Stop order monitoring** in **Setting** to disconnect. DroidFlyer checks for
active child and parent orders once the realtime subscriptions are ready and then
approximately every 15 seconds. Two consecutive checks with no active orders end
monitoring. Monitoring also ends if notification access is lost, the account is
unlinked, or the app cannot verify active orders or establish the realtime subscriptions.
The realtime connection and order checks use extra battery and data.

Android 15 and later also limit background monitoring to six hours in a 24-hour
period. Bring DroidFlyer to the foreground to reset the allowance and start monitoring
again in **Setting**. Notifications cover events received during monitoring.
See [Android's foreground service time limits](https://developer.android.com/develop/background-work/services/fgs/timeout)
and [FAQ and troubleshooting](troubleshooting.md#order-fill-notifications).

## Private REST API permissions

Enable the permissions for the features you use on your bitFlyer API key.
The endpoint paths identify the corresponding operations in the bitFlyer
Lightning API.

| Feature | Endpoint |
| --- | --- |
| Verify credentials when tapping **Apply** | `GET /v1/me/getbalance` |
| Show collateral | `GET /v1/me/getcollateral` |
| Show open positions | `GET /v1/me/getpositions` |
| List active orders | `GET /v1/me/getchildorders` |
| Monitor order fills | `GET /v1/me/getchildorders`; enable `GET /v1/me/getparentorders` to monitor orders waiting to trigger |
| List fills | `GET /v1/me/getexecutions` |
| Submit a market or limit order | `POST /v1/me/sendchildorder` |
| Cancel an active order | `POST /v1/me/cancelchildorder` |
| Clear all positions | `GET /v1/me/getpositions`, `POST /v1/me/sendchildorder`, `GET /v1/me/getchildorders` |
| Submit or track a stop order | `POST /v1/me/sendparentorder`, `GET /v1/me/getparentorders`, `GET /v1/me/getparentorder`, `GET /v1/me/getchildorders` |
| Cancel a stop order | `POST /v1/me/cancelparentorder` |

Stop orders and order monitoring also need the **Receive order events** permission,
enabled separately from the REST permissions above, to receive updates over
bitFlyer's private realtime channels.

See [FAQ and troubleshooting](troubleshooting.md) for what each in-app error message means and how to fix it.
