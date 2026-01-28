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

    // Kotlin
    implementation(libs.bundles.kotlin)

    // Spring Boot
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)

    // Redis with Redisson for distributed locks
    implementation(libs.redisson.spring.boot.starter)

    // Kafka
    implementation(libs.spring.kafka)

    // OpenFeign for service-to-service calls
    implementation(libs.spring.cloud.starter.openfeign)

    // Database
    runtimeOnly(libs.postgresql)

    // Test
    testImplementation(libs.bundles.test.base)
    testImplementation(libs.bundles.testcontainers)
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:${libs.versions.springCloud.get()}")
    }
}
