package ug.kuuma.core.bus

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import ug.kuuma.contracts.Envelope
import ug.kuuma.contracts.EventPayload
import ug.kuuma.contracts.EventType
import ug.kuuma.contracts.Namespace

/** Thrown when a module tries to publish an event that belongs to another module (rule 3). */
class TopicOwnershipException(message: String) : SecurityException(message)

/** Cancels a subscription. */
fun interface Subscription {
    fun cancel()
}

/** The read side of the bus, which every module may use. */
interface EventSource {
    fun <P : EventPayload> subscribe(type: EventType<P>, handler: (Envelope<P>) -> Unit): Subscription
}

/**
 * Typed, in-process publish/subscribe over the generated contract types.
 *
 * Publishing goes through a [ModulePublisher], which only the module registry hands out,
 * one per namespace. A publisher refuses event types outside its namespace, so a module
 * cannot publish another module's topics even by mistake.
 *
 * Handlers run synchronously on the publishing thread, in subscription order. Keep them
 * short; hand slow work to your own executor.
 */
class EventBus(private val deviceId: () -> String, private val clock: () -> Long = System::currentTimeMillis) :
    EventSource {
    private class Sub(val type: EventType<*>?, val handler: (EventType<*>, Envelope<*>) -> Unit)

    private val subs = CopyOnWriteArrayList<Sub>()

    override fun <P : EventPayload> subscribe(type: EventType<P>, handler: (Envelope<P>) -> Unit): Subscription {
        @Suppress("UNCHECKED_CAST")
        val sub = Sub(type) { _, env -> handler(env as Envelope<P>) }
        subs.add(sub)
        return Subscription { subs.remove(sub) }
    }

    /** Every event, e.g. for the debug console. Never forward these to a network client. */
    fun subscribeAll(handler: (EventType<*>, Envelope<*>) -> Unit): Subscription {
        val sub = Sub(null, handler)
        subs.add(sub)
        return Subscription { subs.remove(sub) }
    }

    /** Only the module registry calls this. */
    internal fun publisherFor(namespace: Namespace): ModulePublisher = ModulePublisher(namespace, this)

    internal fun <P : EventPayload> dispatch(type: EventType<P>, payload: P, caseId: String?): Envelope<P> {
        val env = Envelope(
            eventId = UUID.randomUUID().toString(),
            type = type.type,
            version = type.version,
            occurredAt = iso(clock()),
            deviceId = deviceId(),
            caseId = caseId,
            payload = payload
        )
        for (s in subs) {
            if (s.type == null || s.type === type) s.handler(type, env)
        }
        return env
    }

    companion object {
        fun iso(millis: Long): String {
            val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            f.timeZone = TimeZone.getTimeZone("UTC")
            return f.format(Date(millis))
        }
    }
}

/** Publishes events for exactly one namespace. */
class ModulePublisher internal constructor(val namespace: Namespace, private val bus: EventBus) {
    fun <P : EventPayload> publish(type: EventType<P>, payload: P, caseId: String? = null): Envelope<P> {
        if (type.publisher != namespace) {
            throw TopicOwnershipException(
                "${namespace.wire} may not publish $type; it belongs to ${type.publisher.wire}"
            )
        }
        return bus.dispatch(type, payload, caseId)
    }
}
