// Plugin versions are declared once, here, and applied in the modules that
// need them. Declaring a version in each subproject instead loads the Kotlin
// plugin twice — Gradle warns that this "is not supported and may break the
// build", and the remedy it names is exactly this.
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.2.21" apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
