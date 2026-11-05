package ug.kuuma.fakes

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A scripted chat, bundled from fakes/scenarios/<name>.yaml by `make gen`. Invented text only. */
@Serializable
data class Scenario(
    val name: String,
    val description: String,
    val language: String,
    val appSource: String,
    val conversation: String,
    val participants: Map<String, Participant>,
    val messages: List<ScriptedMessage>
)

@Serializable
data class Participant(val displayName: String)

@Serializable
data class Expect(val category: String, val severity: String)

@Serializable
data class ScriptedMessage(
    val day: Int,
    val time: String,
    @SerialName("from") val sender: String,
    val text: String,
    val expect: Expect? = null,
    val stage: String? = null
) {
    /** Millis since the scenario's start (day 1, 00:00). */
    fun offsetMillis(): Long {
        val (h, m) = time.split(":").map { it.toLong() }
        return ((day - 1) * 24L * 60 + h * 60 + m) * 60_000L
    }
}

object ScenarioLibrary {
    private val json = Json { ignoreUnknownKeys = true }

    private fun resource(name: String): String =
        ScenarioLibrary::class.java.classLoader!!.getResourceAsStream("kuuma/scenarios/$name")
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: throw IllegalArgumentException("no bundled scenario file $name (run `make gen`)")

    fun names(): List<String> = json.decodeFromString<List<String>>(resource("index.json"))

    fun load(name: String): Scenario {
        require(name in names()) { "unknown scenario $name; available: ${names()}" }
        return json.decodeFromString(Scenario.serializer(), resource("$name.json"))
    }
}
