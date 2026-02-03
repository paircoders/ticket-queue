plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

// Disable bootJar for library module
tasks.bootJar { enabled = false }
tasks.jar { enabled = true }

dependencyManagement {
    imports {
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:${libs.versions.springCloud.get()}")
    }
}

dependencies {
    // Kotlin
    api(libs.bundles.kotlin)
    api(libs.jackson.datatype.jsr310)

    // Spring Boot Core & Web
    api(libs.spring.boot.starter)
    api(libs.spring.boot.starter.web)
    api(libs.spring.boot.starter.data.jpa)
    api(libs.spring.boot.starter.validation)
    api(libs.spring.boot.starter.actuator)

    // Spring Cloud AWS
    api(platform(libs.spring.cloud.aws.dependencies))
    api(libs.spring.cloud.aws.starter.secrets.manager)

    // Optional dependencies (Services can implement them if needed)
    compileOnly(libs.spring.boot.starter.security)
    compileOnly(libs.spring.cloud.starter.openfeign)
    compileOnly(libs.spring.kafka)

    // Database
    runtimeOnly(libs.postgresql)

    // Test
    testImplementation(libs.bundles.test.base)
    testImplementation(libs.spring.kafka)
}
