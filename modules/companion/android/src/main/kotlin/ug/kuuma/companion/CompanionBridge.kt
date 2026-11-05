package ug.kuuma.companion

import ug.kuuma.companion.content.CrisisPart
import ug.kuuma.contracts.FeedbackVerdict
import ug.kuuma.contracts.Language

/**
 * The Kotlin end of the kuuma_companion Flutter screens (`ChannelCompanionBridge` in Dart).
 * It has no Flutter dependency: the host app connects it to two channels, e.g. in MainActivity:
 *
 *     val bridge = CompanionBridge(module, dialer = { n -> startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$n"))) })
 *     MethodChannel(messenger, CompanionBridge.METHOD_CHANNEL).setMethodCallHandler { call, result ->
 *         try { result.success(bridge.handle(call.method, call.arguments as? Map<*, *> ?: emptyMap<Any, Any>())) }
 *         catch (e: CompanionBridge.UnknownMethod) { result.notImplemented() }
 *         catch (e: IllegalArgumentException) { result.error("bad_args", null, null) }
 *     }
 *     EventChannel(messenger, CompanionBridge.EVENT_CHANNEL).setStreamHandler(object : EventChannel.StreamHandler {
 *         var stop: (() -> Unit)? = null
 *         override fun onListen(a: Any?, sink: EventChannel.EventSink) { stop = bridge.listen { m -> main.post { sink.success(m) } } }
 *         override fun onCancel(a: Any?) { stop?.invoke(); stop = null }
 *     })
 *
 * Values are plain maps and lists of strings, booleans and numbers (what the channel codec
 * carries). They hold IDs, enums and advice text only, never message content.
 */
class CompanionBridge(private val module: CompanionModule, private val dialer: (String) -> Unit = {}) {
    class UnknownMethod(method: String) : IllegalStateException("unknown method $method")

    fun handle(method: String, args: Map<*, *>): Any? = when (method) {
        "recent" -> module.recentFlags().mapNotNull { module.planFor(it.flagId) }.map(::planToMap)
        "crisis" -> crisisMap()
        "feedback" -> {
            module.submitFeedback(
                flagId = args.string("flagId"),
                verdict = FeedbackVerdict.fromWire(args.string("verdict")),
                consentToTrain = args["consentToTrain"] as? Boolean ?: false
            )
            null
        }
        "adviceShown" -> {
            module.adviceShown(args.string("flagId"), args.string("adviceKey"))
            null
        }
        "capture" -> module.requestCapture()
        "sos" -> module.triggerSos()
        "dial" -> {
            val number = args.string("number")
            // Only numbers from the content pack's helplines: the screens can't dial anything else.
            require(helplines().any { it.number == number }) { "not a helpline" }
            dialer(number)
            null
        }
        else -> throw UnknownMethod(method)
    }

    /** Streams every new or revised advice plan as a map. Returns the function that stops it. */
    fun listen(sink: (Map<String, Any?>) -> Unit): () -> Unit = module.addListener(
        object : CompanionListener {
            override fun onAdvice(plan: AdvicePlan) = sink(planToMap(plan))
        }
    )

    private fun language() = module.preferredLanguage ?: Language.EN

    private fun helplines() = module.advice.pack.helplines(language(), module.ageBracket)

    private fun crisisMap(): Map<String, Any?> {
        val pack = module.advice.pack
        val lang = language()
        val bracket = module.ageBracket
        return mapOf(
            "title" to pack.crisisText(CrisisPart.TITLE, lang, bracket),
            "body" to pack.crisisText(CrisisPart.BODY, lang, bracket),
            "breathe" to pack.crisisText(CrisisPart.BREATHE, lang, bracket),
            "callPrompt" to pack.crisisText(CrisisPart.CALL_PROMPT, lang, bracket),
            "helplines" to helplines().map {
                mapOf("id" to it.id, "number" to it.number, "name" to it.name, "hours" to it.hours, "free" to it.free)
            }
        )
    }

    companion object {
        const val METHOD_CHANNEL = "ug.kuuma.companion/bridge"
        const val EVENT_CHANNEL = "ug.kuuma.companion/advice"

        fun planToMap(plan: AdvicePlan): Map<String, Any?> = mapOf(
            "flagId" to plan.flagId,
            "category" to plan.category.wire,
            "severity" to plan.severity.wire,
            "appSource" to plan.appSource.wire,
            "language" to plan.language.wire,
            "crisis" to plan.crisis,
            "cards" to plan.cards.map { mapOf("key" to it.key, "text" to it.text, "steps" to it.steps) },
            "blockSteps" to plan.blockSteps,
            "reportSteps" to plan.reportSteps
        )

        private fun Map<*, *>.string(key: String): String =
            this[key] as? String ?: throw IllegalArgumentException("missing $key")
    }
}
