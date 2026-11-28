package com.unhappychoice.droidflyer.infrastructure.preference

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.unhappychoice.droidflyer.domain.service.PriceAlert
import com.unhappychoice.droidflyer.domain.service.PriceAlertDirection
import io.reactivex.rxjava3.subjects.PublishSubject

internal class PriceAlertPreference(
    context: Context,
    private val gson: Gson = Gson()
) {
    private val preferences by lazy { context.getSharedPreferences("price_alerts", Context.MODE_PRIVATE) }
    private val listType = object : TypeToken<List<PriceAlert>>() {}.type

    private val changes = PublishSubject.create<List<PriceAlert>>().toSerialized()
    val alertsChanged = changes.hide()

    fun read(): List<PriceAlert> = synchronized(lock) {
        val json = preferences.getString(entryKey, null) ?: return@synchronized emptyList()
        try {
            gson.fromJson<List<PriceAlert>>(json, listType) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun add(targetPrice: Long, direction: PriceAlertDirection): PriceAlert? = synchronized(lock) {
        val alerts = read()
        if (alerts.size >= MAX_ALERTS) return@synchronized null
        val alert = PriceAlert((alerts.maxOfOrNull { it.id } ?: 0L) + 1, targetPrice, direction)
        write(alerts + alert)
        alert
    }

    fun remove(id: Long) = synchronized(lock) {
        write(read().filterNot { it.id == id })
    }

    private fun write(alerts: List<PriceAlert>) {
        preferences.edit().putString(entryKey, gson.toJson(alerts)).apply()
        changes.onNext(alerts)
    }

    companion object {
        const val MAX_ALERTS = 10
        private val lock = Any()
        private const val entryKey = "alerts"
    }
}
