plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":core:protocol"))
    api(project(":core:transport"))
    api(project(":core:config"))
    api(project(":core:logs"))
    testImplementation(project(":core:sim"))
    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
    testImplementation(libs.lzo.core)
}
