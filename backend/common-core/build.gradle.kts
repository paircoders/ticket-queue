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
    // Kotlin
    api(libs.bundles.kotlin)
    api(libs.jackson.datatype.jsr310)

    // Spring Boot Core & Web
    api(libs.spring.boot.starter)
    api(libs.spring.boot.starter.web)
    api(libs.spring.boot.starter.validation)
    api(libs.spring.boot.starter.actuator)
    api(libs.micrometer.registry.prometheus)

    // Spring Cloud
    api(platform(libs.spring.cloud.dependencies))

    // Spring Cloud AWS
    api(platform(libs.spring.cloud.aws.dependencies))
    api(libs.spring.cloud.aws.starter.secrets.manager)

    // Logging
    api(libs.kotlin.logging)
    api(libs.logstash.logback.encoder)

    // Spring Security (GlobalExceptionHandler에서 AccessDeniedException 사용)
    api(libs.spring.boot.starter.security)

    // Test
    testImplementation(libs.bundles.test.base)
    testImplementation(libs.spring.boot.starter.security)
}

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs("--add-opens", "java.base/java.time=ALL-UNNAMED")
}
