package com.unhappychoice.droidflyer.infrastructure.bitflyer.model

import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.winterbe.expekt.should
import org.junit.Test

class ParentOrderEventTest {
    private val gson = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create()

    private val body = """
        [{
            "product_code": "FX_BTC_JPY",
            "parent_order_id": "JCP20180101-000000-000001",
            "parent_order_acceptance_id": "JRF20180101-000000-000010",
            "event_date": "2018-01-01T00:00:00.1234567Z",
            "event_type": "TRIGGER",
            "parent_order_type": "IFD",
            "parameter_index": 2,
            "child_order_type": "LIMIT",
            "child_order_acceptance_id": "JRF20180101-000000-000011",
            "side": "SELL",
            "price": 1100000,
            "size": 0.01
        }]
    """

    @Test
    fun parsesTriggerEvent() {
        val events: List<ParentOrderEvent> = gson.fromJson(body, object : TypeToken<List<ParentOrderEvent>>() {}.type)
        val event = events.first()
        event.eventType.should.equal("TRIGGER")
        event.parameterIndex.should.equal(2)
        event.childOrderAcceptanceId.should.equal("JRF20180101-000000-000011")
    }
}
