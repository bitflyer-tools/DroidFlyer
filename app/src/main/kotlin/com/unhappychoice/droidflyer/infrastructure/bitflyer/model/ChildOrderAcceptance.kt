package com.unhappychoice.droidflyer.infrastructure.bitflyer.model

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import com.google.gson.annotations.JsonAdapter
import java.lang.reflect.Type

@JsonAdapter(ChildOrderAcceptance.Deserializer::class, nullSafe = false)
data class ChildOrderAcceptance(val acceptanceId: String) {
    init {
        require(acceptanceId.isNotBlank()) { "Order acceptance ID must not be blank" }
    }

    internal class Deserializer : JsonDeserializer<ChildOrderAcceptance> {
        override fun deserialize(
            json: JsonElement,
            type: Type,
            context: JsonDeserializationContext
        ): ChildOrderAcceptance {
            val id = if (json.isJsonObject) json.asJsonObject.get("child_order_acceptance_id") else null
            if (id == null || !id.isJsonPrimitive || !id.asJsonPrimitive.isString || id.asString.isBlank()) {
                throw JsonParseException("Expected a nonblank child_order_acceptance_id string")
            }
            return ChildOrderAcceptance(id.asString)
        }
    }
}
