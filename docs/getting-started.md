# Getting started with DroidFlyer

## Install DroidFlyer

DroidFlyer requires Android 6.0 (API level 23) or later.

## Use the Google Play release

Install DroidFlyer from
[Google Play](https://play.google.com/store/apps/details?id=com.unhappychoice.droidflyer).
Tap **Order**, then **Limit**, to view live Crypto CFD bids and asks without
an API key or placing a trade. Use the **+** and **-** buttons next to
**Grouping** to change how price levels are combined. To use account
features, follow the account setup below.

## Try the candlestick chart from source

The candlestick chart requires a build from source. It was added after
the latest tagged release, v2.2.0.
To try it, clone the repository and follow the
[development setup](../CONTRIBUTING.md#set-up-your-development-environment).
Install Android SDK Platform-Tools and connect an Android device with USB
debugging enabled. From the repository root, run:

```sh
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Use a device without the Google Play build installed. The debug APK uses
the same app ID with a different signing key.

Open the debug build. **Chart** opens on first launch and shows recent
BTC-CFD/JPY trades as candlesticks without an API key. Switch between
**1 minute** and **5 minutes**, then tap a candle to see its time range
and open, high, low, and close prices in JPY.

## Connect your bitFlyer account

1. Sign in to bitFlyer Lightning and create a key and secret on the
   [developer page](https://lightning.bitflyer.com/developer). Enable the
   permission to retrieve your asset balance (`GET /v1/me/getbalance`),
   which DroidFlyer uses to verify the credentials.
2. In DroidFlyer, tap **Setting**, enter the **API key** and **API secret**,
   and tap **Apply**. Wait for **Saved API key** to confirm that the
   balance request succeeded.
3. To see your Crypto CFD collateral balance on **Order**, also enable
   **Get Margin Status** (`GET /v1/me/getcollateral`) on the key.

See the [API permission reference](usage.md#private-rest-api-permissions)
before using other account or trading features. If verification fails, see
[Setting up your account](troubleshooting.md#setting-up-your-account).
