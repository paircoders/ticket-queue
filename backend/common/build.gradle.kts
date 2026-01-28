plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

// Disable bootJar for library module
tasks.bootJar { enabled = false }
tasks.jar { enabled = true }

dependencies {
    // Kotlin
    api(libs.bundles.kotlin)
    api(libs.jackson.datatype.jsr310)

    // Spring Boot
    api(libs.spring.boot.starter)
    api(libs.spring.boot.starter.web)
    api(libs.spring.boot.starter.data.jpa)
    api(libs.spring.boot.starter.validation)

    // Database
    runtimeOnly(libs.postgresql)

    // Test
    testImplementation(libs.bundles.test.base)
}
