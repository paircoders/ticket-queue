plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    // Common module (for DTOs and exceptions only, no JPA)
    implementation(project(":common"))

    // Spring Boot
    implementation(libs.spring.boot.starter.data.redis)

    // Test
    testImplementation(libs.bundles.test.base)
}
