package com.juujarvis.controller

import com.juujarvis.model.ChannelType
import com.juujarvis.model.IncomingMessage
import com.juujarvis.service.MessageRouter
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

data class MessageRequest(
    val userId: String = "dad",
    val channel: ChannelType = ChannelType.WEB_UI,
    val text: String
)

@RestController
@RequestMapping("/api/messages")
class MessageController(
    private val messageRouter: MessageRouter
) {

    @PostMapping
    fun receiveMessage(@RequestBody request: MessageRequest): ResponseEntity<Map<String, String>> {
        val incoming = IncomingMessage(
            userId = request.userId,
            channel = request.channel,
            text = request.text
        )
        // Fire and forget — response streams back via WebSocket
        messageRouter.handleIncoming(incoming)
        return ResponseEntity.ok(mapOf("status" to "processing"))
    }
}
