package com.juujarvis.tool

import com.anthropic.models.messages.Tool
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class ToolRegistry(tools: List<JuujarvisTool>) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val toolMap: Map<String, JuujarvisTool> = tools.associateBy { it.name }

    init {
        log.info("Registered {} tools: {}", toolMap.size, toolMap.keys)
    }

    fun definitions(): List<Tool> = toolMap.values.map { it.definition() }

    fun execute(toolName: String, arguments: Map<String, Any?>): String {
        val tool = toolMap[toolName]
            ?: return "Error: unknown tool '$toolName'"
        return tool.execute(arguments)
    }
}
