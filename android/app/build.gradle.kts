import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.musicd.migrate.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.musicd.migrate"
        minSdk = 26
        targetSdk = 36
        // versionCode must rise with versionName or Android refuses to install
        // over the previous build. The workflow publishes dist/ from these.
        versionCode = 2
        versionName = "0.1.1"
    }

    sourceSets["main"].java.srcDirs("src/main/kotlin")

    /**
     * THE FRONT-END IS NOT A COPY THAT HAS TO BE KEPT IN STEP.
     *
     * The APK's page is the repository's own public/ — the identical HTML,
     * CSS and JavaScript the Docker build serves — staged into assets/web/ by
     * the stageWebAssets task below and packaged from there.
     *
     * MusicD Remote Lite carries MusicD-Remote's public/ across a repository
     * boundary and pays for it with a sync tool and a CI check that fails when
     * somebody edits a synced file. Here both halves live in one repository,
     * so a Copy task is the whole mechanism and the two cannot drift.
     */
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/webAssets"))

    buildFeatures {
        buildConfig = true
    }

    /**
     * The release key, and why it cannot be the debug one.
     *
     * Android refuses to install an APK over one signed with a different key,
     * and the debug keystore is generated per machine — a CI runner is fresh
     * every time, so every published build would carry a NEW certificate and
     * updating over the installed app would stop working. So the key comes
     * from the environment (a CI secret), and there is no fallback that
     * silently signs with something else: a release build with no key
     * configured is unsigned, which fails loudly at install time rather than
     * producing an APK that looks fine and cannot be an update.
     */
    val keystorePath = System.getenv("MUSICD_KEYSTORE")
    if (!keystorePath.isNullOrBlank()) {
        signingConfigs.create("release") {
            storeFile = file(keystorePath)
            storePassword = System.getenv("MUSICD_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("MUSICD_KEY_ALIAS") ?: "musicd"
            keyPassword = System.getenv("MUSICD_KEY_PASSWORD")
                ?: System.getenv("MUSICD_KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/*.kotlin_module",
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*"
        )
    }
}

/**
 * Stage the repository's public/ into assets/web/.
 *
 * A Copy task rather than an assets srcDir pointing straight at ../../public,
 * because an asset source directory is packaged at its own root — every file
 * would land at assets/index.html rather than assets/web/index.html, and the
 * server would have to know that the Docker build and the APK disagree about
 * where the page lives. One copy, and both are identical.
 */
val stageWeb = tasks.register<Copy>("stageWebAssets") {
    from(rootProject.layout.projectDirectory.dir("../public"))
    into(layout.buildDirectory.dir("generated/webAssets/web"))
}

tasks.named("preBuild") { dependsOn(stageWeb) }

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Everything that is not Android lives in :core, where it is unit-tested
    // on a plain JVM. This module is the shell: a WebView, a foreground
    // service, and SQLite.
    implementation(project(":core"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
