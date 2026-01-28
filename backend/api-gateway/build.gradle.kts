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
    implementation(libs.bundles.resilience4j)

    // Redis for rate limiting
    implementation(libs.spring.boot.starter.data.redis)

    // Actuator
    implementation(libs.spring.boot.starter.actuator)

    // Test
    testImplementation(libs.bundles.test.base)
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:${libs.versions.springCloud.get()}")
    }
}
