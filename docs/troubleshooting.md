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

**Clear all positions failed with "Could not confirm that all positions were closed."**
DroidFlyer reads your open positions, submits a market order to close them, then reads the
resulting child order to confirm it filled. This message means the confirmation step failed;
check **Order** → **Orders** → **Active** and your positions, then retry if a position remains.
If the position is smaller than the minimum order size (0.001 BTC-CFD), DroidFlyer cannot submit a
closing order for it.

**Cancelling an order says "Could not cancel order `<id>`"**
The cancellation request failed, for example because the order already filled or was already
cancelled. Refresh **Active** to see its current state.

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
