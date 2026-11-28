# Contributing to DroidFlyer

## Set up your development environment

1. Install JDK 21 and Android SDK Platform 37. Set `JAVA_HOME` to the JDK and `ANDROID_HOME` to the Android SDK directory.
2. Fork `bitflyer-tools/DroidFlyer`, clone your fork, and create a branch from `master`.
3. Open the repository root in Android Studio or run the checked-in Gradle wrapper from the repository root.

To build a debug APK, run `./gradlew assembleDebug`. The APK is written to `app/build/outputs/apk/debug/`.

## Run the checks

Run the same build and test tasks used by [CircleCI](.circleci/config.yml) before opening a pull request:

```sh
./gradlew assembleDebug assembleDebugUnitTest
./gradlew jacoco
python3 scripts/ci/report-coverage.py app/build/reports/jacoco/jacoco/jacoco.xml
```

The `jacoco` task runs the debug unit tests and generates coverage reports. `scripts/ci/report-coverage.py` then enforces the same 87% line coverage threshold CircleCI requires, so a report that falls short exits with a non-zero status locally too.

## Open a pull request

Push your branch to your fork and open a pull request against `bitflyer-tools/DroidFlyer:master`. Describe what changed, how you verified it, and any checks you could not run. Follow the [Code of Conduct](CODE_OF_CONDUCT.md) when participating.

## Publish to Google Play

After tests pass on `master`, CircleCI holds deployment at `approve-play-release`.
Before approving a release that includes background price alerts, verify in Play Console under
**App content** that the `specialUse` foreground service declaration for price alerts is complete.
Leave the job on hold until this is confirmed. See the
[Google Play foreground service requirements](https://support.google.com/googleplay/android-developer/answer/13392821?hl=en).
