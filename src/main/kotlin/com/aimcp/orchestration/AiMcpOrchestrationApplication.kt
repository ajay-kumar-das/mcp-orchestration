// AiMcpOrchestrationApplication.kt
package com.aimcp.orchestration

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.web.reactive.config.EnableWebFlux
import org.springframework.web.reactive.function.client.WebClient

@SpringBootApplication
@EnableWebFlux
@EnableScheduling
@ConfigurationPropertiesScan
class AiMcpOrchestrationApplication {

    @Bean
    fun webClientBuilder(): WebClient.Builder {
        return WebClient.builder()
    }
}

fun main(args: Array<String>) {
    runApplication<AiMcpOrchestrationApplication>(*args)
}