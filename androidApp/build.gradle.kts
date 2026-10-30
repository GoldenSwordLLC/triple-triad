plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val keystoreProps: Map<String, String> =
    rootProject.file("keystore.properties").takeIf { it.exists() }
        ?.readLines()?.filter { it.contains('=') }?.associate { line ->
            val i = line.indexOf('=')
            line.substring(0, i) to line.substring(i + 1)
        } ?: emptyMap()

android {
    namespace = "com.goldenswordllc.triad"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.goldenswordllc.triad"
        minSdk = 26
        targetSdk = 34
        versionCode = 51
        versionName = "0.9.10"
    }

    signingConfigs {
        create("release") {
            storeFile = keystoreProps["storeFile"]?.let { rootProject.file(it) }
            storePassword = keystoreProps["storePassword"]
            keyAlias = keystoreProps["keyAlias"]
            keyPassword = keystoreProps["keyPassword"]
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("com.google.zxing:core:3.5.3")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
}
