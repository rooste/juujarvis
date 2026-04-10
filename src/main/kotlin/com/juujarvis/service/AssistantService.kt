package com.juujarvis.service

import com.anthropic.client.AnthropicClient
import com.anthropic.core.JsonValue
import com.anthropic.helpers.MessageAccumulator
import com.anthropic.models.messages.*
import com.juujarvis.messaging.MessagingService
import com.juujarvis.model.ChannelType
import com.juujarvis.model.IncomingMessage
import com.juujarvis.tool.ToolRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class AssistantService(
    private val anthropicClient: AnthropicClient,
    private val toolRegistry: ToolRegistry,
    private val webSocketService: WebSocketService,
    private val messagingService: MessagingService,
    @Value("\${juujarvis.anthropic.model:claude-sonnet-4-20250514}")
    private val modelId: String
) {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val MAX_TOOL_LOOPS = 10
        private const val SYSTEM_PROMPT = """You are Juujarvis, an AI family assistant. You help the family stay organized by managing their calendar, sending messages between family members, and providing helpful reminders.

You have access to tools for managing the calendar and sending messages. Use them when the user asks you to create events, check the schedule, remind family members, or contact someone.

Be warm, helpful, and concise. You're part of the family — think of yourself as a helpful household assistant.

Family members:
- Dad (Heikki) - the primary user
- Mom - wife
- Children - the kids

When asked to notify or remind family members, use the send_message tool."""
    }

    fun processStreaming(message: IncomingMessage) {
        log.info("Processing message from '{}': {}", message.userId, message.text)

        val conversationMessages = mutableListOf<MessageParam>(
            MessageParam.builder()
                .role(MessageParam.Role.USER)
                .content(MessageParam.Content.ofString(message.text))
                .build()
        )

        var loopCount = 0
        val textBuffer = StringBuilder()

        while (loopCount < MAX_TOOL_LOOPS) {
            loopCount++
            log.debug("Claude call loop {}", loopCount)

            val paramsBuilder = MessageCreateParams.builder()
                .model(Model.of(modelId))
                .maxTokens(2048L)
                .system(SYSTEM_PROMPT)

            // Add all tools
            toolRegistry.definitions().forEach { tool ->
                paramsBuilder.addTool(tool)
            }

            // Add all conversation messages
            conversationMessages.forEach { msg ->
                paramsBuilder.addMessage(msg)
            }

            val params = paramsBuilder.build()

            val accumulator = MessageAccumulator.create()
            textBuffer.clear()

            try {
                val streamResponse = anthropicClient.messages().createStreaming(params)
                streamResponse.use { sr ->
                    sr.stream().forEach { event: RawMessageStreamEvent ->
                        accumulator.accumulate(event)
                        event.contentBlockDelta().ifPresent { delta ->
                            delta.delta().text().ifPresent { textDelta ->
                                val chunk: String = textDelta.text()
                                textBuffer.append(chunk)
                                // Only stream to WebSocket for web UI messages
                                if (message.channel != ChannelType.IMESSAGE) {
                                    webSocketService.sendStreamChunk(message.userId, chunk)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                log.error("Error streaming from Claude", e)
                deliverResponse(message, "Sorry, I encountered an error: ${e.message}")
                return
            }

            val accumulatedMessage = accumulator.message()

            // Check if Claude wants to use tools
            val toolUseBlocks = accumulatedMessage.content().filter { block ->
                block.toolUse().isPresent
            }

            if (toolUseBlocks.isEmpty()) {
                log.info("Response complete (no tool use): {} chars", textBuffer.length)
                deliverResponse(message, textBuffer.toString())
                return
            }

            // Claude wants to use tools
            log.info("Claude requested {} tool call(s)", toolUseBlocks.size)

            // Add Claude's response as assistant message
            val assistantContentBlocks = mutableListOf<ContentBlockParam>()
            accumulatedMessage.content().forEach { block ->
                block.text().ifPresent { textBlock ->
                    assistantContentBlocks.add(
                        ContentBlockParam.ofText(
                            TextBlockParam.builder().text(textBlock.text()).build()
                        )
                    )
                }
                block.toolUse().ifPresent { toolUse ->
                    assistantContentBlocks.add(
                        ContentBlockParam.ofToolUse(
                            ToolUseBlockParam.builder()
                                .id(toolUse.id())
                                .name(toolUse.name())
                                .input(toolUse._input())
                                .build()
                        )
                    )
                }
            }
            conversationMessages.add(
                MessageParam.builder()
                    .role(MessageParam.Role.ASSISTANT)
                    .content(MessageParam.Content.ofBlockParams(assistantContentBlocks))
                    .build()
            )

            // Execute each tool and collect results
            val toolResults = mutableListOf<ContentBlockParam>()
            toolUseBlocks.forEach { block ->
                val toolUse = block.toolUse().get()
                val toolName = toolUse.name()
                val toolId = toolUse.id()

                val arguments = parseToolArguments(toolUse._input())

                log.info("Executing tool '{}' with args: {}", toolName, arguments)
                webSocketService.sendToolStatus(message.userId, toolName, "executing")

                val result = try {
                    toolRegistry.execute(toolName, arguments)
                } catch (e: Exception) {
                    log.error("Tool '{}' failed", toolName, e)
                    "Error executing tool: ${e.message}"
                }

                log.info("Tool '{}' result: {}", toolName, result)
                webSocketService.sendToolStatus(message.userId, toolName, "completed")

                toolResults.add(
                    ContentBlockParam.ofToolResult(
                        ToolResultBlockParam.builder()
                            .toolUseId(toolId)
                            .content(ToolResultBlockParam.Content.ofString(result))
                            .build()
                    )
                )
            }

            // Add tool results as user message
            conversationMessages.add(
                MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .content(MessageParam.Content.ofBlockParams(toolResults))
                    .build()
            )
        }

        log.warn("Max tool loops ({}) reached", MAX_TOOL_LOOPS)
        deliverResponse(message, textBuffer.toString())
    }

    private fun deliverResponse(message: IncomingMessage, text: String) {
        if (message.channel == ChannelType.IMESSAGE) {
            if (text.isNotBlank()) {
                val sent = messagingService.sendDirect(ChannelType.IMESSAGE, message.userId, text)
                log.info("iMessage reply to {}: {} (sent={})", message.userId, text.take(80), sent)
                webSocketService.broadcastIMessage("out", message.userId, text)
            }
        } else {
            webSocketService.sendStreamChunk(message.userId, text)
            webSocketService.sendStreamEnd(message.userId)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseToolArguments(input: JsonValue): Map<String, Any?> {
        return try {
            val raw = input.convert(Map::class.java) as? Map<String, Any?> ?: emptyMap()
            raw
        } catch (e: Exception) {
            log.warn("Failed to parse tool arguments: {}", e.message)
            emptyMap()
        }
    }
}
