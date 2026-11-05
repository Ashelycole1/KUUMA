package ug.kuuma.contracts

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** JSON encoding shared by every Kotlin build. Optional fields are omitted, never null. */
object ContractJson {
    /** Lenient on input so newer, additive event versions don't crash older readers. */
    val json: Json = Json {
        explicitNulls = false
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Strict: what the contract tests use. */
    val strict: Json = Json {
        explicitNulls = false
        ignoreUnknownKeys = false
        encodeDefaults = true
    }

    fun <P : EventPayload> encode(type: EventType<P>, envelope: Envelope<P>, format: Json = json): String {
        require(envelope.type == type.type && envelope.version == type.version) {
            "envelope says ${envelope.type}.v${envelope.version} but type is $type"
        }
        return format.encodeToString(Envelope.serializer(type.serializer), envelope)
    }

    /** Decode any known event. Returns the event type alongside, so callers can dispatch. */
    fun decode(text: String, format: Json = json): Pair<EventType<out EventPayload>, Envelope<out EventPayload>> {
        val obj = format.parseToJsonElement(text).jsonObject
        val typeName = obj.getValue("type").jsonPrimitive.content
        val version = obj.getValue("version").jsonPrimitive.int
        val type = Events.byType(typeName, version)
            ?: throw IllegalArgumentException("unknown event $typeName.v$version")
        return type to format.decodeFromJsonElement(Envelope.serializer(type.serializer), obj)
    }
}
