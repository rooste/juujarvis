package com.juujarvis.service

import com.juujarvis.messaging.MessagingService
import com.juujarvis.model.ChannelType
import com.juujarvis.model.Conversation
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

@Service
class ReminderService(
    private val conversationStore: ConversationStore,
    private val messagingService: MessagingService
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 60000)
    fun dispatchDueReminders() {
        val due = conversationStore.loadDueReminders()
        if (due.isEmpty()) return

        log.info("Dispatching {} due reminder(s)", due.size)
        due.forEach { reminder ->
            val result = when (reminder.recipientType) {
                "group" -> {
                    val conversation = Conversation(
                        chatId = reminder.recipient,
                        chatGuid = reminder.recipient,
                        isGroup = true,
                        displayName = null,
                        participants = emptyList()
                    )
                    val sent = messagingService.sendToConversation(conversation, reminder.message)
                    if (sent) "Sent to group ${reminder.recipient}" else "Failed to send to group"
                }
                else -> messagingService.sendToUser(reminder.recipient, reminder.message)
            }
            log.info("Reminder [{}] to '{}': {} ({})", reminder.recipientType, reminder.recipient, reminder.message.take(80), result)
            conversationStore.markReminderSent(reminder.id)
        }
    }
}
