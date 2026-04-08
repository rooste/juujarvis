package com.juujarvis.tool

import com.anthropic.models.messages.Tool

/**
 * Interface for all Juujarvis tools that Claude can invoke.
 * Each tool defines its schema (for Claude) and its execution logic.
 */
interface JuujarvisTool {

    /** Unique tool name */
    val name: String

    /** Tool definition for the Claude API */
    fun definition(): Tool

    /**
     * Execute the tool with the given JSON arguments.
     * Returns a result string that gets fed back to Claude.
     */
    fun execute(arguments: Map<String, Any?>): String
}
