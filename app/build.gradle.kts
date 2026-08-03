plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ru.franprobe.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "ru.franprobe.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 20003
        versionName = "2.0.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
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

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

configurations.configureEach {
    resolutionStrategy.force(
        "androidx.core:core:1.17.0",
        "androidx.core:core-ktx:1.17.0"
    )
}

tasks.register("verifyApi36Dependencies") {
    group = "verification"
    description = "Fails if the resolved debug classpath escapes the API 36-compatible dependency set."

    doLast {
        val modules = configurations.getByName("debugRuntimeClasspath")
            .resolvedConfiguration
            .resolvedArtifacts
            .map { it.moduleVersion.id }

        fun requireVersion(group: String, name: String, expected: String) {
            val versions = modules
                .filter { it.group == group && it.name == name }
                .map { it.version }
                .distinct()
            check(versions == listOf(expected)) {
                "Expected $group:$name:$expected, resolved $versions"
            }
        }

        requireVersion("androidx.core", "core", "1.17.0")
        requireVersion("androidx.core", "core-ktx", "1.17.0")
        requireVersion("androidx.activity", "activity-compose", "1.11.0")
        requireVersion("androidx.lifecycle", "lifecycle-runtime-ktx", "2.9.4")
        requireVersion("androidx.lifecycle", "lifecycle-viewmodel-compose", "2.9.4")

        val incompatibleCompose = modules.filter { module ->
            module.group.startsWith("androidx.compose") && run {
                val parts = module.version.split('.', '-', limit = 3)
                val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
                val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
                major > 1 || (major == 1 && minor >= 12)
            }
        }
        check(incompatibleCompose.isEmpty()) {
            "Compose 1.12+ requires compileSdk 37: $incompatibleCompose"
        }

        println("API 36 dependency set verified (${modules.size} resolved modules)")
    }
}

dependencies {
    val composeBom = enforcedPlatform("androidx.compose:compose-bom:2025.08.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:5.3.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
