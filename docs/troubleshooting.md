# FAQ and troubleshooting

## Setting up your account

**I tapped Apply under Setting and nothing was saved.**
DroidFlyer verifies the key and secret by calling `GET /v1/me/getbalance` before saving them.
The message tells you what went wrong:

| Message | Cause | Fix |
| --- | --- | --- |
| "bitFlyer rejected access with these API credentials" | bitFlyer returned 401 or 403 for the key and secret | Check that the key and secret were copied correctly and that the key is still enabled on the [developer page](https://lightning.bitflyer.com/developer) |
| "Could not verify API credentials with bitFlyer. Try again." | The balance request failed for another reason (for example a network error) | Check your connection and try again |
| "Could not save API credentials on this device" | The balance request succeeded but saving to the device failed | Try again; if it keeps failing, the device may be low on storage |

**I tapped Unlink and it says "Could not remove saved credentials."**
Try unlinking again. The app only clears the saved key and secret from the device; it does not
change anything on your bitFlyer account.

**The Order screen doesn't show my CFD collateral balance.**
Showing collateral requires the [Get Margin Status](https://lightning.bitflyer.com/docs?lang=en#get-margin-status)
permission (`GET /v1/me/getcollateral`) on your API key, in addition to the balance permission
used to verify credentials. See [Private REST API permissions](usage.md#private-rest-api-permissions).

## Placing and managing orders

**Why is the buy/sell button disabled?**
Order entry is enabled only while a private API client is connected, no order is in flight, and
the amount is between the minimum and maximum order size. The minimum Crypto CFD order size is
0.001 BTC-CFD. The maximum is 10 BTC-CFD, or 40 BTC-CFD if **Use corporate Crypto CFD account limits**
is enabled under **Setting**.

**I got "Order acceptance could not be confirmed. Check your orders before submitting again."**
DroidFlyer submitted the order but could not confirm bitFlyer accepted it, for example because the
confirmation request timed out. Check **Order** → **Orders** → **Active** and your fills before
submitting again, to avoid placing the same order twice.

**I got "Could not submit market order" or "Could not submit limit order."**
bitFlyer rejected the order submission itself (for example insufficient balance or an invalid
price). Check your balance and the order details, then try again.

**Clear all positions showed "Could not confirm that all positions were closed. Check your positions and orders."**
DroidFlyer rereads your positions before submitting a closing order. If the live net position
size is still nonzero and differs from the size you confirmed, the app stops before sending
an order. The same message can also appear if the app cannot verify closure after sending a
closing order.

The message alone cannot tell you whether a closing order was sent. Before retrying, check
your positions and orders, including **Order** → **Orders** → **Active** and any recent fills.
Retry only if a position remains and no closing order is still active.
If the position is smaller than the minimum order size (0.001 BTC-CFD), DroidFlyer cannot submit a
closing order for it.

**Cancelling an order says "Could not cancel order `<id>`"**
The cancellation request failed, for example because the order already filled or was already
cancelled. Refresh **Active** to see its current state.

## Stop orders

**Why does reviewing a stop order show "Order events unavailable"?**
Stop orders need a live connection to bitFlyer's private order events so DroidFlyer can track and
cancel orders waiting to trigger. Enable **Receive order events** on your API key on the
[developer page](https://lightning.bitflyer.com/developer), or create a key with that permission,
then enter it under **Setting** and tap **Apply**. Once connected, review the stop order again.

**I got "The stop order submission result is unknown."**
DroidFlyer submitted the stop order but could not confirm bitFlyer accepted it before the
connection was interrupted. Check its status in bitFlyer Lightning, then tap **Check order status
and resume** once you have confirmed whether it was accepted. Resuming without checking could
submit the same order twice.

**I got "bitFlyer rejected your stop order."**
bitFlyer rejected the submission itself (for example an invalid trigger price or insufficient
balance). You can try again.

**Cancelling a stop order asks me to confirm it's still waiting to trigger.**
DroidFlyer's connection to bitFlyer order events was interrupted, so it cannot be sure the order
hasn't already triggered. Confirm in bitFlyer Lightning that the order is still waiting, then tap
**Still waiting — cancel order**.

**Cancelling a stop order failed because the account changed, the order is no longer available,
a cancellation is already in progress, or the order is no longer eligible for cancellation.**
These mean the credentials changed, the order could not be found, a cancellation for it was
already running, or the order is no longer eligible (for example it already triggered or was
already canceled). Refresh **Active** to see its current state.

## Reading orders and fills

**The active orders list shows "Some active orders may be missing because the request limit was reached."**
DroidFlyer lists up to 500 active orders per request. If you have 500 or more active orders, the
list may not show all of them.

**Fills show "Check your API key and enable permission to list executions."**
Enable the permission for `GET /v1/me/getexecutions` on your API key, then tap **Refresh**.

**Fills show "Could not load fills. Try again."**
The request to `GET /v1/me/getexecutions` failed for another reason, such as a network error.
Tap **Refresh** to retry.

**Active orders show "Could not refresh active child orders. The child orders shown may be out of date."**
A refresh request failed after an earlier load succeeded. The list keeps showing the last orders
it loaded; tap into **Active** again or reopen the Order screen to retry.

## Order fill notifications

**I'm not getting a notification when an order fills, is rejected, or is canceled.**
Check that notifications and both order notification channels are enabled in Android's
settings. In DroidFlyer, open **Setting** and tap **Start order monitoring** while at
least one Crypto CFD order is active. Verify your network connection and check that your
API key can [read orders and receive order events](usage.md#private-rest-api-permissions).
DroidFlyer stops monitoring after two consecutive checks find no active orders or
when required order checks fail. If the realtime connection cannot be established
or restored within 30 seconds, DroidFlyer stops monitoring and posts a notification
explaining why.
If you get a warning that DroidFlyer cannot check for active parent orders, enable
`GET /v1/me/getparentorders` on your API key. Without this permission, DroidFlyer stops monitoring
after two consecutive checks find no active child orders.

On Android 15 and later, Android can stop monitoring after six hours of background
use in a 24-hour period. DroidFlyer posts a notification when it reaches this limit.
Bring DroidFlyer to the foreground to reset the allowance, then tap
**Start order monitoring** again.

**Does watching for order updates use more battery or data?**
Yes. While monitoring, DroidFlyer keeps a realtime connection open and checks for active
orders periodically. Tap **Stop order monitoring** in **Setting** to stop.
