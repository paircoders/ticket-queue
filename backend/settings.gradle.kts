plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "ticket-queue"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(
    "common",
    "api-gateway",
    "user-service",
    "event-service",
    "queue-service",
    "reservation-service",
    "payment-service"
)
