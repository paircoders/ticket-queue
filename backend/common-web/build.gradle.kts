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

    // Resilience4j CircuitBreaker — PortoneFallbackFactory(@Component)가 CallNotPermittedException 을 도메인 예외로 변환.
    // common-web 을 의존하는 모든 서비스는 com.ticketqueue.common 을 component-scan 하므로 런타임 클래스패스에서도 필요.
    // implementation 으로 선언: consumers 컴파일 노출 없이 runtime 만 보장 (도메인 예외는 BusinessException 으로 추상화됨).
    implementation(libs.resilience4j.circuitbreaker)

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
