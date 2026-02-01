plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    // Common module
    implementation(project(":common"))

    // Spring Boot & Security
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.data.redis)

    // JWT
    implementation(libs.bundles.jjwt)

    // Test
    testImplementation(libs.bundles.test.base)
    testImplementation(libs.spring.security.test)
    testImplementation(libs.bundles.testcontainers)
}
