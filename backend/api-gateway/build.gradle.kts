plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    // Kotlin
    implementation(libs.bundles.kotlin)

    // Spring Cloud Gateway (WebFlux based)
    implementation(libs.spring.cloud.starter.gateway)

    // Resilience4j
    implementation(libs.resilience4j.reactor)
    implementation(libs.spring.cloud.starter.circuitbreaker.resilience4j)

    // Actuator
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.micrometer.registry.prometheus)

    // Logging
    implementation(libs.kotlin.logging)
    implementation(libs.logstash.logback.encoder)

    // Context Propagation (Reactor Context ↔ MDC 자동 동기화)
    implementation(libs.micrometer.context.propagation)

    // JWT
    implementation(libs.bundles.jjwt)

    // Redis Reactive (토큰 블랙리스트 조회)
    implementation(libs.spring.boot.starter.data.redis.reactive)

    // Spring Cloud AWS (Secrets Manager - config property 해석용)
    implementation(libs.spring.cloud.aws.starter.secrets.manager)

    // Test
    testImplementation(libs.bundles.test.base)
    testImplementation(libs.reactor.test)
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:${libs.versions.springCloud.get()}")
        mavenBom(libs.spring.cloud.aws.dependencies.get().toString())
    }
}
