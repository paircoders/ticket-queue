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
    api(project(":common-core"))

    // OpenFeign (FeignConfig, PortoneFeignClient에 필요)
    api(libs.spring.cloud.starter.openfeign)

    // Spring Security (InternalApiKeyValidator 등에서 사용)
    compileOnly(libs.spring.boot.starter.security)

    // Test
    testImplementation(libs.bundles.test.base)
    testImplementation(libs.spring.cloud.starter.openfeign)
    testImplementation(libs.spring.boot.starter.security)
}

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs("--add-opens", "java.base/java.time=ALL-UNNAMED")
}
