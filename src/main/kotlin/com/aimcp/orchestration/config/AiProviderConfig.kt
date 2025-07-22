package com.aimcp.orchestration.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "ai.providers")
data class AiProviderConfig(
    var claude: ClaudeConfig = ClaudeConfig(),
    var openai: OpenAiConfig = OpenAiConfig(),
    var gemini: GeminiConfig = GeminiConfig(),
    var defaultProvider: String = "claude"
)

data class ClaudeConfig(
    var enabled: Boolean = true,
    var apiKey: String = "",
    var model: String = "claude-3-5-sonnet-20241022",
    var maxTokens: Int = 4000,
    var temperature: Double = 0.3
)

data class OpenAiConfig(
    var enabled: Boolean = false,
    var apiKey: String = "",
    var model: String = "gpt-4-turbo",
    var maxTokens: Int = 4000,
    var temperature: Double = 0.3
)

data class GeminiConfig(
    var enabled: Boolean = false,
    var apiKey: String = "",
    var model: String = "gemini-pro",
    var maxTokens: Int = 4000,
    var temperature: Double = 0.3
)
