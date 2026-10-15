plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":core:protocol"))
    api(project(":core:transport"))
    testImplementation(project(":core:vesc"))
    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
}
