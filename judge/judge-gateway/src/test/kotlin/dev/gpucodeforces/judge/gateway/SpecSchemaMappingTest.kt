package dev.gpucodeforces.judge.gateway

import kotlin.reflect.KClass
import kotlin.reflect.KVisibility
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.dataformat.yaml.YAMLMapper

/**
 * The generator uses existing Kotlin classes for the schemas listed in build.gradle.kts, so nothing generated
 * would break when a class and its schema drift apart. This test is that check.
 */
class SpecSchemaMappingTest {
    private val schemas: JsonNode = YAMLMapper().readTree(javaClass.getResource("/static/openapi.yaml")!!.readText())
        .path("components").path("schemas")

    private val mappings: Map<String, KClass<*>> = System.getProperty("judge.openapi.schemaMappings")!!
        .split(",")
        .associate { it.substringBefore("=") to Class.forName(it.substringAfter("=")).kotlin }

    @Test
    fun `mapped classes match their schemas`() {
        val mismatches = mappings.flatMap { (name, type) ->
            val schema = schemas.path(name)
            check(schema.isObject) { "schema $name is not in the spec" }
            mismatches(schema, type).map { "$name: $it" }
        }
        assertEquals(emptyList(), mismatches)
    }

    private fun mismatches(schema: JsonNode, type: KClass<*>): List<String> {
        val enumConstants = type.java.enumConstants
        if (enumConstants != null) {
            val values = schema.path("enum").values().map { it.asString() }
            val constants = enumConstants.map { (it as Enum<*>).name }
            return if (values == constants) emptyList() else listOf("enum $values, class $constants")
        }

        val properties = schema.path("properties").properties().map { it.key }.toSet()
        val required = schema.path("required").values().map { it.asString() }.toSet()
        // Computed properties are serialized too, so a schema may list them, but requests never carry them.
        val members = type.memberProperties.filter { it.visibility == KVisibility.PUBLIC }.associateBy { it.name }
        val parameters = type.primaryConstructor!!.parameters
        return buildList {
            parameters.map { it.name!! }.filter { it !in properties }.forEach { add("property '$it' is not in the schema") }
            properties.filter { it !in members }.forEach { add("property '$it' is not in the class") }
            parameters.filter { !it.isOptional && !it.type.isMarkedNullable && it.name !in required }
                .forEach { add("property '${it.name}' is always present but not required") }
            required.filter { members[it]?.returnType?.isMarkedNullable ?: false }
                .forEach { add("property '$it' is required but nullable") }
        }
    }
}
