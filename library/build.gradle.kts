plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    `maven-publish`
}

android {
    namespace = "com.vanespark.googledrivesync"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // Kotlin
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.bundles.lifecycle)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)

    // Room (optional - for sync state persistence)
    implementation(libs.bundles.room)
    ksp(libs.androidx.room.compiler)

    // Hilt
    implementation(libs.hilt.android)
    implementation(libs.hilt.work)
    ksp(libs.hilt.compiler)
    ksp(libs.hilt.work.compiler)

    // Google Drive API
    implementation(libs.bundles.google.drive)

    // Unit Testing
    testImplementation(libs.bundles.testing)
    testRuntimeOnly(libs.junit5.engine)
    testRuntimeOnly(libs.junit5.platform.launcher)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    // Android Testing
    androidTestImplementation(libs.bundles.android.testing)
    androidTestImplementation(libs.androidx.room.testing)
}

// Configure JUnit 5
tasks.withType<Test> {
    useJUnitPlatform()
}

// Maven publishing configuration
publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.vanespark"
            artifactId = "google-drive-sync"
            version = "0.0.1-SNAPSHOT"

            afterEvaluate {
                from(components["release"])
            }

            pom {
                name.set("Android Google Drive Sync")
                description.set("A robust, flexible Android library for synchronizing files with Google Drive")
                url.set("https://github.com/vanespark/android-google-drive-sync")

                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }

                developers {
                    developer {
                        id.set("vanespark")
                        name.set("VaneSpark")
                    }
                }
            }
        }
    }
}
