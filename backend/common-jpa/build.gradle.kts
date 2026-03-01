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

dependencies {
    api(project(":common-core"))

    // JPA
    api(libs.spring.boot.starter.data.jpa)

    // QueryDSL
    api(libs.querydsl.jpa)
    ksp(libs.querydsl.ksp.codegen)

    // Database
    runtimeOnly(libs.postgresql)

    // Test
    testImplementation(libs.bundles.test.base)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
