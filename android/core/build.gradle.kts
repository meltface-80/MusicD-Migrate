plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // Android ships org.json in the platform, so it must NOT be packaged into
    // the APK. It is a compile-time dependency here and a real one only under
    // test, where there is no android.jar to provide it.
    compileOnly("org.json:json:20240303")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

tasks.test {
    testLogging { events("passed", "failed", "skipped") }

    // ContractTest reads files OUTSIDE this module — public/app.js, index.js,
    // lib/canon.js, lib/service.js — and compares them with the Kotlin side.
    // Gradle cannot know that, so it would report the task up to date after a
    // change to any of them and the whole class of drift these tests exist to
    // catch would go unchecked in exactly the case that matters: the JS half
    // edited on its own. Declaring them as inputs makes the task rerun.
    val repoRoot = rootProject.projectDir.parentFile
    for (name in listOf("public/app.js", "index.js", "lib/canon.js", "lib/service.js")) {
        val f = File(repoRoot, name)
        if (f.isFile) inputs.file(f).withPropertyName(name.replace('/', '_'))
    }
}
