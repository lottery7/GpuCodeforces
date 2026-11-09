import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.openapitools.generator.gradle.plugin.tasks.GenerateTask
import org.openapitools.generator.gradle.plugin.tasks.ValidateTask
import org.springframework.boot.gradle.plugin.SpringBootPlugin
import java.security.MessageDigest
import java.util.HexFormat

plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    kotlin("plugin.jpa")
    id("org.springframework.boot")
    id("org.openapi.generator")
}

val apiSpec = rootProject.layout.projectDirectory.file("docs/frontend/openapi.yaml")
val generateApi = tasks.named<GenerateTask>("openApiGenerate") {
    generatorName.set("spring")
    inputSpec.set(apiSpec)
    outputDir.set(layout.buildDirectory.dir("generated/openapi"))
    apiPackage.set("dev.gpucodeforces.backend.api")
    modelPackage.set("dev.gpucodeforces.backend.api.model")
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
        "useSpringBoot4" to "true",
        "useJackson3" to "true",
        "openApiNullable" to "false",
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
val apiJavaSnapshot = rootProject.layout.projectDirectory.file("docs/frontend/openapi-java.sha256")
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
                "Run ./gradlew :backend:updateOpenApiSnapshot and commit the updated snapshot."
        }
    }
}

sourceSets.main {
    java.srcDir(generateApi.flatMap { it.outputDir.dir("src/main/java") })
}

tasks.named("compileKotlin") {
    dependsOn(generateApi)
}

kotlin {
    jvmToolchain(26)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_26)
        freeCompilerArgs.add("-Xjsr305=strict")
        javaParameters.set(true)
    }
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

dependencies {
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:2.4.21"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform {
        excludeTags("integration")
    }
}

val integrationTest = tasks.register<Test>("integrationTest") {
    description = "Runs integration tests against disposable PostgreSQL (requires Docker)."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("integration")
    }
    shouldRunAfter(tasks.test)
}

tasks.check {
    dependsOn(integrationTest)
}
