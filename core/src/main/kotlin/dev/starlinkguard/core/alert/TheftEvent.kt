package dev.starlinkguard.core.alert

import dev.starlinkguard.core.detect.Trigger
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class EventType {
    ARMED,
    BASELINE_CAPTURED,
    DISARMED,
    ALARM,
    ALARM_STOPPED,
    DISH_UNREACHABLE,
    DISH_RECOVERED,
    WEBHOOK_FAILED,
}

/** One line in the app's history, and the thing a webhook describes. */
@Serializable
data class TheftEvent(
    val id: String = UUID.randomUUID().toString(),
    val timestampMs: Long,
    val type: EventType,
    val message: String,
    val triggers: List<Trigger> = emptyList(),
    val dishSerial: String? = null,
) {
    companion object {
        val json: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}

/**
 * The JSON body POSTed to the user's webhook.
 *
 * Deliberately plain and self-describing so it drops straight into Home Assistant, n8n,
 * Zapier or a couple of lines of server code without a schema to look up.
 */
@Serializable
data class WebhookPayload(
    val source: String = "starlink-anti-theft",
    val schemaVersion: Int = 1,
    val event: String,
    val message: String,
    val timestampMs: Long,
    val timestampIso: String,
    val dishSerial: String? = null,
    val triggers: List<WebhookTrigger> = emptyList(),
) {
    companion object {
        fun from(event: TheftEvent): WebhookPayload = WebhookPayload(
            event = event.type.name,
            message = event.message,
            timestampMs = event.timestampMs,
            timestampIso = Instant.ofEpochMilli(event.timestampMs).toString(),
            dishSerial = event.dishSerial,
            triggers = event.triggers.map(WebhookTrigger::from),
        )
    }
}

@Serializable
data class WebhookTrigger(
    val axis: String,
    val kind: String,
    val description: String,
    val referenceValue: Double,
    val currentValue: Double,
    val delta: Double,
    val threshold: Double,
    val unit: String,
) {
    companion object {
        fun from(trigger: Trigger): WebhookTrigger = WebhookTrigger(
            axis = trigger.axis.name,
            kind = trigger.kind.name,
            description = trigger.describe(),
            referenceValue = trigger.referenceValue,
            currentValue = trigger.currentValue,
            delta = trigger.delta,
            threshold = trigger.threshold,
            unit = trigger.unit,
        )
    }
}
