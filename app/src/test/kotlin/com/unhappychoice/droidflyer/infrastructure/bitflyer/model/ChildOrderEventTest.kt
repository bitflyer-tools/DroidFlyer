package com.unhappychoice.droidflyer.infrastructure.bitflyer.model

import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.winterbe.expekt.should
import org.junit.Test

class ChildOrderEventTest {
    private val gson = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create()

    private val body = """
        [{
            "product_code": "FX_BTC_JPY",
            "child_order_id": "JFX20180101-000000-000001F",
            "child_order_acceptance_id": "JRF20180101-000000-000001",
            "event_date": "2018-01-01T00:00:00.1234567Z",
            "event_type": "EXECUTION",
            "exec_id": 123,
            "side": "BUY",
            "price": 1000000,
            "size": 0.01,
            "commission": 0,
            "sfd": 0
        }, {
            "product_code": "FX_BTC_JPY",
            "child_order_id": "JFX20180101-000000-000002F",
            "child_order_acceptance_id": "JRF20180101-000000-000002",
            "event_date": "2018-01-01T00:00:00.1234567Z",
            "event_type": "CANCEL"
        }]
    """

    private fun parse(): List<ChildOrderEvent> =
        gson.fromJson(body, object : TypeToken<List<ChildOrderEvent>>() {}.type)

    @Test
    fun parsesExecutionEvent() {
        val event = parse().first()
        event.childOrderAcceptanceId.should.equal("JRF20180101-000000-000001")
        event.price.should.equal(1000000L)
        event.size.should.equal(0.01)
    }

    @Test
    fun isExecutionMatchesEventType() {
        parse().map { it.isExecution() }.should.equal(listOf(true, false))
    }
}
