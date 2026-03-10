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
    runtimeOnly(libs.commons.pool2)  // Lettuce 커넥션 풀 활성화 (없으면 pool.* 설정 무시됨)
    implementation(libs.spring.boot.starter.security)

    // Test
    testImplementation(libs.bundles.test.base)
    testImplementation(libs.spring.security.test)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
}
