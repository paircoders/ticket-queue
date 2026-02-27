plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
    alias(libs.plugins.ksp)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    // Common module
    implementation(project(":common"))

    // QueryDSL - Q클래스 생성 (querydsl-jpa는 common api()로 전파됨)
    ksp(libs.querydsl.ksp.codegen)

    // Spring Boot
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.spring.boot.starter.security)

    // Kafka
    implementation(libs.spring.kafka)

    // Test
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.bundles.test.base)
    testImplementation(libs.bundles.testcontainers)
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation(libs.spring.security.test)
}
