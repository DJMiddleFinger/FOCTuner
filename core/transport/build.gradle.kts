plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(libs.coroutines.core)
    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
}
