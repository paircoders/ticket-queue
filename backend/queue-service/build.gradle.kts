plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    // Common module (web 모듈 사용 - GatewayAuthFilter, SecurityErrorHandlers 포함)
    implementation(project(":common-web"))

    // Spring Boot
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.spring.boot.starter.security)

    // Test
    testImplementation(libs.bundles.test.base)
    testImplementation(libs.spring.security.test)
}
