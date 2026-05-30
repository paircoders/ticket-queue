import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.kotlin.jpa) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
}

allprojects {
    group = "com.ticketqueue"
    version = "0.0.1-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    tasks.withType<KotlinCompile> {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
            freeCompilerArgs.addAll(
                "-Xjsr305=strict",
                "-Xjvm-default=all"
            )
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }

    // 통합 테스트(TestContainers 기반) 전용 실행 태스크.
    // 기존 test 소스셋을 그대로 재사용하여 `*IntegrationTest` 클래스와 `*.integration.*`
    // 패키지에 속한 테스트만 선별 실행한다. test 태스크 동작은 변경하지 않으므로
    // `./gradlew test` 는 종전대로 단위+통합 테스트를 모두 수행한다. (이슈 #261)
    plugins.withType<JavaPlugin> {
        val testSourceSet = extensions.getByType<SourceSetContainer>()["test"]
        tasks.register<Test>("integrationTest") {
            description = "TestContainers 기반 통합 테스트 실행 (*IntegrationTest, *.integration.* 패키지)"
            group = "verification"
            testClassesDirs = testSourceSet.output.classesDirs
            classpath = testSourceSet.runtimeClasspath
            useJUnitPlatform()
            filter {
                includeTestsMatching("*IntegrationTest")
                includeTestsMatching("*.integration.*")
                isFailOnNoMatchingTests = false
            }
            shouldRunAfter(tasks.named("test"))
        }
    }
}
