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

    // Spring Security (SecurityConfig에서 HttpSecurity, EnableWebSecurity 사용)
    implementation(libs.spring.boot.starter.security)

    // Redis with Redisson for distributed locks
    implementation(libs.redisson.spring.boot.starter)

    // Test
    testImplementation(libs.bundles.test.base)
    testImplementation(libs.bundles.testcontainers)
}