plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
    alias(libs.plugins.ksp)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    // Common modules
    implementation(project(":common-core"))
    implementation(project(":common-jpa"))
    implementation(project(":common-web"))

    // QueryDSL - Q클래스 생성 (querydsl-jpa는 common-jpa api()로 전파됨)
    ksp(libs.querydsl.ksp.codegen)

    // Spring Boot & Security
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.webflux)
    implementation(libs.spring.cloud.starter.openfeign)
    implementation(libs.spring.cloud.starter.circuitbreaker.resilience4j)
    implementation(libs.spring.boot.starter.data.redis)

    // Resilience4j for circuit breaker
    implementation(libs.bundles.resilience4j)

    // JWT
    implementation(libs.bundles.jjwt)

    // Test
    testImplementation(libs.bundles.test.base)
    testImplementation(libs.spring.security.test)
    testImplementation(libs.bundles.testcontainers)
}
