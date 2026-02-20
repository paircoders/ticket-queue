plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
    alias(libs.plugins.ksp)
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

    // Spring Cloud
    api(platform(libs.spring.cloud.dependencies))

    // Spring Cloud AWS
    api(platform(libs.spring.cloud.aws.dependencies))
    api(libs.spring.cloud.aws.starter.secrets.manager)

    // Logging
    api(libs.kotlin.logging)
    api(libs.logstash.logback.encoder)
    
    // Querydsl
    api(libs.querydsl.jpa)
    ksp(libs.querydsl.ksp.codegen)

    // Optional dependencies (Services can implement them if needed)
    compileOnly(libs.spring.boot.starter.security)
    compileOnly(libs.spring.cloud.starter.openfeign)
    api(libs.spring.kafka)

    // Database
    runtimeOnly(libs.postgresql)

    // Test
    testImplementation(libs.spring.cloud.starter.openfeign)
    testImplementation(libs.bundles.test.base)
    testImplementation(libs.spring.kafka)
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation(libs.spring.boot.starter.security)

    // TestContainers
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.bundles.test.base)
    testImplementation(libs.bundles.testcontainers)
    testImplementation("org.springframework.boot:spring-boot-testcontainers")

    // Awaitility for async testing
    testImplementation("org.awaitility:awaitility:4.2.0")
    implementation(kotlin("test"))
}
