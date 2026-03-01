plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    // Common modules
    implementation(project(":common-core"))
    implementation(project(":common-jpa"))
    implementation(project(":common-kafka"))
    implementation(project(":common-web"))

    // Spring Boot
    implementation(libs.spring.boot.starter.webflux)  // For WebClient

    // Resilience4j for circuit breaker
    implementation(libs.bundles.resilience4j)
    implementation(libs.spring.cloud.starter.circuitbreaker.resilience4j)

    // Test
    testImplementation(libs.bundles.test.base)
    testImplementation(libs.bundles.testcontainers)
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:${libs.versions.springCloud.get()}")
    }
}
