package ug.kuuma.core.bus

import ug.kuuma.contracts.CloudSendablePayload
import ug.kuuma.contracts.ContractJson
import ug.kuuma.contracts.Envelope
import ug.kuuma.contracts.EventPayload
import ug.kuuma.contracts.EventType

/** Thrown when anything tries to send a phone-only event off the phone. */
class PhoneOnlyException(message: String) : SecurityException(message)

/**
 * The only way an event may reach a network client.
 *
 * Compile time: [CloudEventSender.send] accepts only [CloudSendablePayload] events, so
 * phone-only payloads (which implement PhoneOnlyPayload) do not type-check.
 * Run time: [CloudGate.toWire] re-checks the event type, for code that holds an erased type.
 */
interface CloudEventSender {
    fun <P : CloudSendablePayload> send(type: EventType<P>, envelope: Envelope<P>)
}

object CloudGate {
    /** Serialise an event for the network, refusing phone-only events. */
    fun <P : EventPayload> toWire(type: EventType<P>, envelope: Envelope<P>): String {
        if (type.phoneOnly || envelope.payload !is CloudSendablePayload) {
            throw PhoneOnlyException("$type is phone only and must never leave the phone")
        }
        return ContractJson.encode(type, envelope)
    }
}
