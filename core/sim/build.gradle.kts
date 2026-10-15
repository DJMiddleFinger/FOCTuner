plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":core:protocol"))
    api(project(":core:transport"))
    api(project(":core:config"))
    testImplementation(project(":core:vesc"))
    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
}
