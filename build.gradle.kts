import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// The build JDK is newer than anything Android runs; pin the pure-JVM modules to Java 17 bytecode.
// The app module gets the same through compileOptions (AGP rejects javac's --release).
subprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        tasks.withType<KotlinCompile>().configureEach {
            compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
        }
        tasks.withType<JavaCompile>().configureEach {
            options.release.set(17)
        }
    }
}
