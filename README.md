# DroidFlyer
[![play 
store](https://raw.githubusercontent.com/unhappychoice/Norimaki/image/google-play-badge.png)](https://play.google.com/store/apps/details?id=com.unhappychoice.droidflyer)

[![CircleCI](https://circleci.com/gh/unhappychoice/DroidFlyer.svg?style=shield)](https://circleci.com/gh/unhappychoice/DroidFlyer)
[![Latest release](https://img.shields.io/github/v/release/bitflyer-tools/DroidFlyer.svg)](https://github.com/bitflyer-tools/DroidFlyer/releases/latest)
[![Libraries.io dependency status for GitHub repo](https://img.shields.io/librariesio/github/unhappychoice/DroidFlyer.svg)](https://libraries.io/github/unhappychoice/DroidFlyer)
![GitHub](https://img.shields.io/github/license/unhappychoice/DroidFlyer.svg)

DroidFlyer is a [bitFlyer Lightning](https://lightning.bitflyer.com) client for Android.

## Features

- Chart recent bitFlyer Crypto CFD trade prices in JPY with 1-minute and 5-minute candlesticks.
- Watch live bids and asks in the order book and adjust how price levels are grouped.
- Place market and limit orders, and view or cancel active orders.

## Installation

Install [DroidFlyer from Google Play](https://play.google.com/store/apps/details?id=com.unhappychoice.droidflyer).

To build from source, clone this repository and follow the [development setup](CONTRIBUTING.md#set-up-your-development-environment). Install Android SDK Platform-Tools for `adb`, then run these commands from the repository root with a USB debugging-enabled Android device connected.

```sh
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Quick start

1. [Install DroidFlyer](#installation).
2. Sign in to bitFlyer Lightning and create an API key and secret on the [developer page](https://lightning.bitflyer.com/developer). Give the key permission to retrieve your asset balance so DroidFlyer can verify it. To show your CFD collateral balance on the Order screen, also grant [Get Margin Status](https://lightning.bitflyer.com/docs?lang=en#get-margin-status) permission (`GET /v1/me/getcollateral`).
3. Open DroidFlyer and tap **Setting**. Enter the **API key** and **API secret**, then tap **Apply**. The app shows **Saved API key** after the balance request succeeds and the credentials are saved on the device.
4. Tap **Order**, then **Limit**, to view the bitFlyer Crypto CFD order book. You can read the bids and asks without placing a trade.

See [Using DroidFlyer](docs/usage.md) for screen controls, account settings, and API permissions.

## Author

[@unhappychoice](https://unhappychoice.com)
