package com.juujarvis.config

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class AnthropicConfig {

    @Bean
    fun anthropicClient(
        @Value("\${juujarvis.anthropic.api-key:}") apiKey: String
    ): AnthropicClient {
        val builder = AnthropicOkHttpClient.builder()
        if (apiKey.isNotBlank()) {
            builder.apiKey(apiKey)
        } else {
            // Falls back to ANTHROPIC_API_KEY environment variable
            builder.fromEnv()
        }
        return builder.build()
    }
}
