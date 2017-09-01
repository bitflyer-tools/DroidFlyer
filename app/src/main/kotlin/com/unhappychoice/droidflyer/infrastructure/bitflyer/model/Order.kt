package com.unhappychoice.droidflyer.infrastructure.bitflyer.model

data class Order(val price: Long, val size: Double)

fun List<Order>.floorBySize(size: Long) =
    fold(mutableMapOf<Long, Double>()) { map, order ->
        val price = (order.price / size) * size
        map[price] = map[price] ?: 0.0 + order.size
        map
    }.map { Order(it.key, it.value) }

fun List<Order>.ceilBySize(size: Long) =
    fold(mutableMapOf<Long, Double>()) { map, order ->
        val price = ((order.price + size) / size) * size
        map[price] = map[price] ?: 0.0 + order.size
        map
    }.map { Order(it.key, it.value) }
