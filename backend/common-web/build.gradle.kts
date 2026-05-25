plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

// Disable bootJar for library module
tasks.bootJar { enabled = false }
tasks.jar { enabled = true }

dependencies {
    api(project(":common-core"))

    // OpenFeign (FeignConfig, PortoneFeignClient에 필요)
    api(libs.spring.cloud.starter.openfeign)

    // Resilience4j CircuitBreaker — PortoneFallbackFactory가 CallNotPermittedException을 도메인 예외로 변환.
    // PortoneFeignClient 사용 서비스(payment-service, user-service)는 이미 implementation 의존성을 갖고 있어 런타임 충돌 없음.
    compileOnly(libs.resilience4j.circuitbreaker)

    // Spring Security (InternalApiKeyValidator 등에서 사용)
    compileOnly(libs.spring.boot.starter.security)

    // Test
    testImplementation(libs.bundles.test.base)
    testImplementation(libs.spring.cloud.starter.openfeign)
    testImplementation(libs.spring.boot.starter.security)
    testImplementation(libs.resilience4j.circuitbreaker)
}

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs("--add-opens", "java.base/java.time=ALL-UNNAMED")
}
