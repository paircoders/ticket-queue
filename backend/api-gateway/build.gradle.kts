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

    // Test
    testImplementation(libs.bundles.test.base)
    testImplementation(libs.reactor.test)
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:${libs.versions.springCloud.get()}")
    }
}
