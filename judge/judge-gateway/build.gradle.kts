import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.openapitools.generator.gradle.plugin.tasks.GenerateTask
import org.openapitools.generator.gradle.plugin.tasks.ValidateTask
import org.springframework.boot.gradle.plugin.SpringBootPlugin
import org.springframework.boot.gradle.tasks.bundling.BootJar
import java.security.MessageDigest
import java.util.HexFormat

plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("org.openapi.generator")
}

val apiSpec = rootProject.layout.projectDirectory.file("judge/docs/openapi.yaml")

/**
 * Schemas that already exist as Kotlin classes. The ones from judge-api are also broker messages (a submission's
 * ProblemSpec goes to the executor as is), so the generator uses them instead of a second, generated copy.
 * SpecSchemaMappingTest checks that these classes and the spec agree.
 */
val apiSchemaMappings = listOf(
    "SubmissionRequest", "SubmissionAccepted", "SubmissionStatus", "ProblemSpec", "Signature", "ParamSpec",
    "ParamRole", "MemorySpace", "DType", "GeneratorSpec", "GeneratorLanguage", "CheckerSpec", "CheckerMode",
    "LimitsSpec", "TimingSpec", "TimingMode", "CompileSpec", "PolicyOverrides", "ExecutorHeartbeat", "GpuSlotStatus",
).associateWith { "dev.gpucodeforces.judge.api.$it" } +
    listOf("ExecutorView", "QueueView").associateWith { "dev.gpucodeforces.judge.gateway.service.$it" }

val generateApi = tasks.named<GenerateTask>("openApiGenerate") {
    generatorName.set("spring")
    inputSpec.set(apiSpec)
    outputDir.set(layout.buildDirectory.dir("generated/openapi"))
    apiPackage.set("dev.gpucodeforces.judge.gateway.api")
    modelPackage.set("dev.gpucodeforces.judge.gateway.api.model")
    // mapped to the simple name and imported, so the generated code reads `SubmissionRequest submissionRequest`
    schemaMappings.set(apiSchemaMappings.keys.associateWith { it })
    importMappings.set(apiSchemaMappings)
    cleanupOutput.set(true)
    globalProperties.set(mapOf(
        "apis" to "",
        "models" to "",
        "apiDocs" to "false",
        "modelDocs" to "false",
        "apiTests" to "false",
        "modelTests" to "false",
    ))
    configOptions.set(mapOf(
        "interfaceOnly" to "true",
        "skipDefaultInterface" to "true",
        "requestMappingMode" to "api_interface",
        "useTags" to "true",
        "useSpringBoot4" to "true",
        "useJackson3" to "true",
        "openApiNullable" to "false",
        // SpecValidator checks requests and reports every error in ErrorResponse; bean validation would answer
        // a part of them earlier and in another format.
        "useBeanValidation" to "false",
        "annotationLibrary" to "none",
        "documentationProvider" to "none",
        "hideGenerationTimestamp" to "true",
        "generateJsonIncludeAnnotations" to "true",
    ))
}

tasks.named<ValidateTask>("openApiValidate") {
    inputSpec.set(apiSpec)
}

val generatedApiJava = generateApi.flatMap { it.outputDir.dir("src/main/java") }
val apiJavaSnapshot = rootProject.layout.projectDirectory.file("judge/docs/openapi-java.sha256")
val apiJavaChecksums = providers.provider {
    val root = generatedApiJava.get().asFile
    val files = root.walkTopDown()
        .filter { it.isFile && it.extension == "java" }
        .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
        .toList()
    check(files.isNotEmpty()) { "No OpenAPI Java sources generated" }
    val digest = MessageDigest.getInstance("SHA-256")
    files.joinToString("\n", postfix = "\n") {
        val hash = HexFormat.of().formatHex(digest.digest(it.readBytes()))
        "$hash  ${it.relativeTo(root).invariantSeparatorsPath}"
    }
}

tasks.register("updateOpenApiSnapshot") {
    dependsOn(generateApi)
    inputs.dir(generatedApiJava)
    outputs.file(apiJavaSnapshot)
    doLast {
        apiJavaSnapshot.asFile.writeText(apiJavaChecksums.get())
    }
}

tasks.register("verifyOpenApiGenerated") {
    dependsOn(generateApi)
    inputs.dir(generatedApiJava)
    inputs.file(apiJavaSnapshot)
    doLast {
        val expected = apiJavaSnapshot.asFile.readLines().toSet()
        val actual = apiJavaChecksums.get().lines().filter { it.isNotEmpty() }.toSet()
        val changed = ((expected - actual) + (actual - expected))
            .map { it.substringAfter("  ") }.distinct().sorted()
        check(changed.isEmpty()) {
            "OpenAPI Java differs:\n${changed.joinToString("\n")}\n" +
                "Run ./gradlew :judge-gateway:updateOpenApiSnapshot and commit the updated snapshot."
        }
    }
}

sourceSets.main {
    java.srcDir(generatedApiJava)
}

tasks.named("compileKotlin") {
    dependsOn(generateApi)
}

// Swagger UI shows the spec itself, served as /openapi.yaml
tasks.processResources {
    from(apiSpec) {
        into("static")
    }
}

kotlin {
    jvmToolchain(26)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_26)
        freeCompilerArgs.add("-Xjsr305=strict")
        javaParameters.set(true)
    }
}

dependencies {
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:2.4.21"))
    implementation(project(":judge-api"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-amqp")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // not managed by the Spring Boot BOM
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.2.0")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")
    // generated models annotate required fields with @NotNull even with useBeanValidation=false
    compileOnly("jakarta.validation:jakarta.validation-api")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("tools.jackson.dataformat:jackson-dataformat-yaml")
    testImplementation(kotlin("test"))
    testImplementation("org.mockito.kotlin:mockito-kotlin:6.4.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("judge.openapi.schemaMappings", apiSchemaMappings.entries.joinToString(",") { "${it.key}=${it.value}" })
}

tasks.named<BootJar>("bootJar") {
    archiveFileName.set("judge-gateway.jar")
}

tasks.named<Jar>("jar") {
    enabled = false
}
