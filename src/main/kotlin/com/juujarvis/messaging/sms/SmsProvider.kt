package com.juujarvis.messaging.sms

import com.juujarvis.bridgarvis.BridgarvisClient
import com.juujarvis.bridgarvis.SmsSendResult
import com.juujarvis.messaging.ChatMessage
import com.juujarvis.messaging.MessagingProvider
import com.juujarvis.model.ChannelType
import com.juujarvis.service.ConversationStore
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/** US numbers as E.164, the form Bridgarvis uses. */
object PhoneNumbers {
    fun normalize(raw: String?): String? {
        val digits = raw?.filter { it.isDigit() } ?: return null
        return when {
            digits.length == 10 -> "+1$digits"
            digits.length == 11 && digits.startsWith("1") -> "+$digits"
            else -> null
        }
    }
}

/**
 * Text messages through Bridgarvis's toll-free number. Numbers that opted out (STOP) or never opted in
 * (JOIN) are remembered and skipped, so Juujarvis reaches those people another way.
 */
@Component
@ConditionalOnProperty("juujarvis.bridgarvis.enabled", havingValue = "true")
class SmsProvider(
    private val bridgarvis: ObjectProvider<BridgarvisClient>,
    private val conversationStore: ConversationStore
) : MessagingProvider {
    private val log = LoggerFactory.getLogger(javaClass)
    override val channelType = ChannelType.SMS

    /** Whether texting [address] is worth trying (not known to be opted out / not opted in). */
    fun canText(address: String): Boolean {
        val number = PhoneNumbers.normalize(address) ?: return false
        return conversationStore.smsConsent(number).let { it == null || it == "OPTED_IN" }
    }

    fun sendSms(to: String, message: String): SmsSendResult {
        val number = PhoneNumbers.normalize(to) ?: return SmsSendResult.Refused("invalid_number", "not a US phone number")
        val client = bridgarvis.ifAvailable ?: return SmsSendResult.Refused("unreachable", "Bridgarvis is not configured")
        val result = client.sendSms(number, message)
        when (result) {
            is SmsSendResult.Sent -> conversationStore.setSmsConsent(number, "OPTED_IN")
            is SmsSendResult.Refused -> {
                if (result.code == "opted_out") conversationStore.setSmsConsent(number, "OPTED_OUT")
                if (result.code == "not_opted_in") conversationStore.setSmsConsent(number, "NOT_OPTED_IN")
                log.info("SMS to …{} refused: {}", number.takeLast(4), result.code)
            }
        }
        return result
    }

    override fun send(to: String, message: String): Boolean = sendSms(to, message) is SmsSendResult.Sent

    override fun getMessages(withContact: String?, limit: Int): List<ChatMessage> = emptyList()
}
