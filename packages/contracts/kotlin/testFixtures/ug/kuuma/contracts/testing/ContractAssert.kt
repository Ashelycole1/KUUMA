package ug.kuuma.contracts.testing

import com.networknt.schema.InputFormat
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SpecVersion
import com.networknt.schema.resource.InputStreamSource
import com.networknt.schema.resource.SchemaLoader
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import ug.kuuma.contracts.ContractJson
import ug.kuuma.contracts.Envelope
import ug.kuuma.contracts.EventPayload
import ug.kuuma.contracts.EventType
import ug.kuuma.contracts.Events
import ug.kuuma.contracts.Namespace

/**
 * Contract-test harness for Kotlin builds. Use from unit tests:
 *
 *     testImplementation(testFixtures(project(":contracts")))
 *
 *     ContractAssert.assertPublishes(Namespace.SENTINEL, Events.SENTINEL_MESSAGE_FLAGGED, envelope)
 */
object ContractAssert {
    private const val ID_BASE = "https://schemas.kuuma.ug/"

    private val bundle: JsonObject by lazy {
        val text = ContractAssert::class.java.classLoader!!.getResource("kuuma/schema-bundle.json")!!.readText()
        Json.parseToJsonElement(text).jsonObject
    }

    private val factory: JsonSchemaFactory by lazy {
        val loader = SchemaLoader { iri ->
            val schema = bundle[iri.toString()] ?: return@SchemaLoader null
            InputStreamSource { ByteArrayInputStream(schema.toString().toByteArray()) }
        }
        JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012) { builder ->
            builder.schemaLoaders { it.add(loader) }
        }
    }

    private val schemas = ConcurrentHashMap<String, JsonSchema>()

    private fun schema(id: String): JsonSchema = schemas.getOrPut(id) { factory.getSchema(SchemaLocation.of(id)) }

    /** Validate raw JSON against a schema id, throwing [ContractViolation] with the reasons. */
    fun validate(schemaId: String, json: String) {
        val errors = schema(schemaId).validate(json, InputFormat.JSON)
        if (errors.isNotEmpty()) {
            throw ContractViolation("$schemaId rejected the data: " + errors.take(10).joinToString("; ") { it.message })
        }
    }

    fun validateEventJson(json: String): EventType<out EventPayload> {
        val (type, _) = ContractJson.decode(json, ContractJson.strict)
        validate("${ID_BASE}events/${type.type}.v${type.version}.json", json)
        return type
    }

    /** Assert [namespace] may publish [envelope] and that its JSON matches the schema. */
    fun <P : EventPayload> assertPublishes(
        namespace: Namespace,
        type: EventType<P>,
        envelope: Envelope<P>,
        leavingPhone: Boolean = false
    ): String {
        if (type.publisher != namespace) {
            throw ContractViolation("$namespace may not publish $type; it belongs to ${type.publisher}")
        }
        if (leavingPhone &&
            type.phoneOnly
        ) {
            throw ContractViolation("$type is phone only and must never leave the phone")
        }
        val json = ContractJson.encode(type, envelope, ContractJson.strict)
        validateEventJson(json)
        return json
    }

    /** Assert an incoming event is valid and of the expected type; returns it parsed. */
    @Suppress("UNCHECKED_CAST")
    fun <P : EventPayload> assertConsumes(type: EventType<P>, json: String): Envelope<P> {
        val actual = validateEventJson(json)
        if (actual !== type) throw ContractViolation("expected $type, got $actual")
        return ContractJson.decode(json, ContractJson.strict).second as Envelope<P>
    }

    /** Validate an API body against a schema file, e.g. "api/alert-list.json". */
    fun validateBody(schemaFile: String, json: String) = validate(ID_BASE + schemaFile, json)

    /** Every event type, for "all events" parametrised tests. */
    val allEvents get() = Events.all
}

class ContractViolation(message: String) : AssertionError(message)
