package com.unhappychoice.droidflyer.infrastructure.bitflyer.model

data class Order(val price: Long, val size: Double) {
    fun flooredPrice(size: Long) = (price / size) * size
    fun ceiledPrice(size: Long) = ((price + size) / size) * size
}

fun List<Order>.groupBy(fn: (Order) -> Long) =
    fold(mutableMapOf<Long, Double>()) { map, order ->
        val price = fn(order)
        map[price] = (map[price] ?: 0.0) + order.size
        map
    }.map { Order(it.key, it.value) }

fun List<Order>.floorBySize(size: Long) =
    groupBy { order -> order.flooredPrice(size) }

fun List<Order>.ceilBySize(size: Long) =
    groupBy { order -> order.ceiledPrice(size) }