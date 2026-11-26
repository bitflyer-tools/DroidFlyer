package com.unhappychoice.droidflyer.extension

import hu.akarnokd.rxjava3.bridge.RxJavaBridge.toV2Scheduler
import io.reactivex.Scheduler
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers

internal fun androidMainThreadScheduler(): Scheduler = toV2Scheduler(AndroidSchedulers.mainThread())
