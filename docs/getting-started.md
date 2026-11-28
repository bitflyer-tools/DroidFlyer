# Getting started with DroidFlyer

## Install DroidFlyer

DroidFlyer requires Android 6.0 (API level 23) or later. Install it from
[Google Play](https://play.google.com/store/apps/details?id=com.unhappychoice.droidflyer)
or follow the [build and install steps](../README.md#installation) to install
a debug APK from source.

## Explore the Crypto CFD market

1. Open DroidFlyer. **Chart** opens on first launch and shows recent
   BTC-CFD/JPY trades as candlesticks. Switch between **1 minute** and
   **5 minutes**, then tap a candle to see its time range and open, high,
   low, and close prices in JPY.
2. Tap **Order**, then **Limit**, to open the live order book. Look at the
   bid and ask rows, then use the **+** and **-** buttons next to
   **Grouping** to change how price levels are combined.

You can follow both steps without an API key or placing an order.

## Connect your bitFlyer account

1. Sign in to bitFlyer Lightning and create a key and secret on the
   [developer page](https://lightning.bitflyer.com/developer). Enable the
   permission to retrieve your asset balance (`GET /v1/me/getbalance`),
   which DroidFlyer uses to verify the credentials.
2. In DroidFlyer, tap **Setting**, enter the **API key** and **API secret**,
   and tap **Apply**. Wait for **Saved API key** to confirm the app has
   verified and saved the credentials.
3. To see your Crypto CFD collateral balance on **Order**, also enable
   **Get Margin Status** (`GET /v1/me/getcollateral`) on the key.

See the [API permission reference](usage.md#private-rest-api-permissions)
before using other account or trading features. If verification fails, see
[Setting up your account](troubleshooting.md#setting-up-your-account).
