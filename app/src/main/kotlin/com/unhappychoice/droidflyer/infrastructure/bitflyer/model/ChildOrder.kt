package com.unhappychoice.droidflyer.infrastructure.bitflyer.model

data class ChildOrder(
    val id: Int,
    val childOrderId: String,
    val productCode: String,
    val side: String,
    val childOrderType: String,
    val price: Long,
    val average_price: Long,
    val size: Double,
    val childOrderState: String,
    //val expireDate: Date,
    //val childOrderDate: Date,
    val childOrderAcceptanceId: String,
    val outstandingSize: Double,
    val cancelSize: Double,
    val executedSize: Double,
    val totalCommission: Double
) {
    fun flooredPrice(size: Long) = (price / size) * size
    fun ceiledPrice(size: Long) = ((price + size) / size) * size
}

