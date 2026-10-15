plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":core:protocol"))
    testImplementation(kotlin("test"))
}
