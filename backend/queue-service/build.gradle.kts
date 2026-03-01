plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    // Common module (core만 사용 - JPA/Kafka 불필요)
    implementation(project(":common-core"))

    // Spring Boot
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.spring.boot.starter.security)

    // Test
    testImplementation(libs.bundles.test.base)
    testImplementation(libs.spring.security.test)
}
