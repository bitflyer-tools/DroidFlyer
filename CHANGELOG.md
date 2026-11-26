# Changelog

All notable changes to this project are documented in this file, newest first.

## [v2.2.0] - 2026-11-07

### Added
- Subscribe to private realtime order events ([#560](https://github.com/bitflyer-tools/DroidFlyer/pull/560))

### Changed
- Migrate CircleCI config to version 2.1 ([#556](https://github.com/bitflyer-tools/DroidFlyer/pull/556))
- Use a large CircleCI resource class for the test job ([#565](https://github.com/bitflyer-tools/DroidFlyer/pull/565))

## [v2.1.0] - 2026-09-12

### Changed
- Migrate to Gradle 9.x and AGP 9.0 with built-in Kotlin support ([#490](https://github.com/bitflyer-tools/DroidFlyer/pull/490))
- Bump `compileSdk`/`targetSdk` to 36, then `compileSdk` to 37 ([#524](https://github.com/bitflyer-tools/DroidFlyer/pull/524), [#540](https://github.com/bitflyer-tools/DroidFlyer/pull/540))
- Use a large CircleCI resource class for the deploy job to fix a Gradle out-of-memory failure ([#510](https://github.com/bitflyer-tools/DroidFlyer/pull/510))

### Fixed
- Bump `minSdk` from 21 to 23 ([#517](https://github.com/bitflyer-tools/DroidFlyer/pull/517))
- Apply the `kotlin-android` plugin 2.4.0 explicitly ([#521](https://github.com/bitflyer-tools/DroidFlyer/pull/521))

[v2.2.0]: https://github.com/bitflyer-tools/DroidFlyer/compare/v2.1.0...v2.2.0
[v2.1.0]: https://github.com/bitflyer-tools/DroidFlyer/compare/v2.0.1...v2.1.0
