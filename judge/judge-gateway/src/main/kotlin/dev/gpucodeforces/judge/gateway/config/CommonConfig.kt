package dev.gpucodeforces.judge.gateway.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.amqp.rabbit.connection.ConnectionFactory
import org.springframework.amqp.rabbit.core.RabbitAdmin
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import java.time.Clock

@Configuration
@EnableScheduling
class CommonConfig {
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun rabbitAdmin(connectionFactory: ConnectionFactory) = RabbitAdmin(connectionFactory)

    @Bean
    fun openApi(): OpenAPI = OpenAPI().info(
        Info()
            .title("GPU Codeforces judge gateway")
            .version("v1")
            .description(
                "Entry point of the testing system. The backend POSTs submissions here and consumes results " +
                    "from the RabbitMQ queue `judge.events` (JudgeEvent JSON).",
            ),
    )
}
