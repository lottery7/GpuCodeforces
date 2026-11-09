import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    `java-library`
    kotlin("jvm")
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
    // Contracts only: plain data classes plus Jackson annotations, no Spring. The backend can depend on this module.
    api(platform(SpringBootPlugin.BOM_COORDINATES))
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:2.4.21"))
    api("com.fasterxml.jackson.core:jackson-annotations")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
