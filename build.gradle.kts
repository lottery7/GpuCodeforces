import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    base
    kotlin("jvm") version "2.4.21" apply false
    kotlin("plugin.spring") version "2.4.21" apply false
    kotlin("plugin.jpa") version "2.4.21" apply false
    id("org.springframework.boot") version "4.1.2" apply false
}

allprojects {
    group = "dev.gpucodeforces"
    version = "0.0.1-SNAPSHOT"

    tasks.withType<KotlinCompilationTask<*>>().configureEach {
        compilerOptions {
            freeCompilerArgs.addAll(
                "-Xcollection-literals",
                "-Xexplicit-context-arguments",
                "-Xintrinsic-const-evaluation",
                "-Xcontext-sensitive-resolution",
                "-Xname-based-destructuring=only-syntax",
            )
        }
    }
}

tasks.named("check") {
    dependsOn(":backend:check")
}

tasks.named("assemble") {
    dependsOn(":backend:assemble")
}
