package com.unhappychoice.droidflyer.extension

import io.reactivex.rxjava3.core.Scheduler
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers

internal fun androidMainThreadScheduler(): Scheduler = AndroidSchedulers.mainThread()
