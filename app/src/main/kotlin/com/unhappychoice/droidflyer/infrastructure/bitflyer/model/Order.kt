package com.unhappychoice.droidflyer.infrastructure.bitflyer.model

data class Order(val price: Long, val size: Double)

fun List<Order>.floorBySize(size: Long) =
    map { it.copy(price = (it.price / size) * size) }
        .groupBy { it.price }
        .map { Order(it.value.first().price, it.value.sumByDouble { it.size }) }

fun List<Order>.ceilBySize(size: Long) =
    map { it.copy(price = ((it.price + size)/ size) * size) }
        .groupBy { it.price }
        .map { Order(it.value.first().price, it.value.sumByDouble { it.size }) }
