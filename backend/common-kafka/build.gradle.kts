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
    // common-jpa 포함 (OutboxEvent entity 사용, common-core도 transitive 포함)
    api(project(":common-jpa"))

    // Kafka
    api(libs.spring.kafka)

    // Test
    testImplementation(libs.bundles.test.base)
    testImplementation(libs.spring.kafka)
    testImplementation("org.springframework.kafka:spring-kafka-test")

    // TestContainers
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.bundles.testcontainers)
    testImplementation("org.springframework.boot:spring-boot-testcontainers")

    // Awaitility for async testing
    testImplementation("org.awaitility:awaitility:4.2.0")
}

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs("--add-opens", "java.base/java.time=ALL-UNNAMED")
}
