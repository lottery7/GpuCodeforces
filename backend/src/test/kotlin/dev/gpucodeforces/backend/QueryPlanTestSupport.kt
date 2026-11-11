package dev.gpucodeforces.backend

import org.springframework.context.ConfigurableApplicationContext
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader
import org.springframework.test.context.ContextConfigurationAttributes
import org.springframework.test.context.ContextCustomizer
import org.springframework.test.context.ContextCustomizerFactory
import org.springframework.test.context.MergedContextConfiguration
import org.springframework.test.context.TestContext
import org.springframework.test.context.support.AbstractTestExecutionListener

/**
 * Exceptions apply only to this test - or, on a class, to its every test with their setup - and to matching SQL,
 * including SQL on HTTP/RabbitMQ threads.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Repeatable
annotation class AllowFullScan(val table: String, val sql: String, val reason: String, val maxTableRows: Int = 0)

/**
 * Scans that belong to no test: scheduled jobs run during any of them, and these read a whole table by design.
 */
val BACKGROUND_SCANS = listOf(
    AllowFullScan(
        "problems", "FROM gpucodeforces\\.problems p\\s+JOIN LATERAL \\(SELECT \\* FROM gpucodeforces\\.problem_versions",
        "Reference measurements and duel matching go through every published problem; each current version is a key lookup",
    ),
)

/** Registered through spring.factories so future Spring database tests are checked automatically. */
class QueryPlanContextCustomizerFactory : ContextCustomizerFactory {
    override fun createContextCustomizer(
        testClass: Class<*>, configAttributes: List<ContextConfigurationAttributes>,
    ): ContextCustomizer = ConfigureQueryPlans

    private object ConfigureQueryPlans : ContextCustomizer {
        override fun customizeContext(context: ConfigurableApplicationContext, mergedConfig: MergedContextConfiguration) {
            val registry = context.beanFactory
            check(registry is BeanDefinitionRegistry) { "Query plan checking requires a bean definition registry" }
            AnnotatedBeanDefinitionReader(registry).register(QueryPlanConfiguration::class.java)
        }
    }
}

class QueryPlanTestExecutionListener : AbstractTestExecutionListener() {
    // Includes @BeforeEach/@AfterEach and transaction setup/teardown. Context initialization (Flyway
    // migrations and Hibernate metadata discovery) happens before this application-query guard.
    override fun getOrder() = 3500

    override fun beforeTestMethod(testContext: TestContext) {
        val exceptions = testContext.testMethod.getAnnotationsByType(AllowFullScan::class.java).toList() +
            testContext.testClass.getAnnotationsByType(AllowFullScan::class.java)
        testContext.applicationContext.getBean(QueryPlans::class.java).begin(exceptions + BACKGROUND_SCANS)
    }

    override fun afterTestMethod(testContext: TestContext) {
        testContext.applicationContext.getBean(QueryPlans::class.java).finish()
    }
}
