package gpucodeforces.judge.executor

import gpucodeforces.judge.executor.config.ExecutorProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(ExecutorProperties::class)
class ExecutorApplication

fun main(args: Array<String>) {
    runApplication<ExecutorApplication>(*args)
}
