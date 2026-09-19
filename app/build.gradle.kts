plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseTag = providers.environmentVariable("RELEASE_TAG").orNull
val releaseTagPattern = Regex(
    """v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-[0-9A-Za-z]+([.-][0-9A-Za-z]+)*)?"""
)
require(releaseTag == null || releaseTagPattern.matches(releaseTag)) {
    "RELEASE_TAG must be a version tag, such as v1.0.2 or v1.0.2-beat.1"
}
val releaseVersionCode = providers.environmentVariable("BUILD_NUMBER").orNull?.let { value ->
    val number = value.toIntOrNull()
    require(number != null && number in 1..2_099_999_000 && value == number.toString()) {
        "BUILD_NUMBER must be an integer from 1 to 2099999000 without leading zeros"
    }
    1000 + number
}
val releasePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull

android {
    namespace = "cn.zgccrui.backscreen"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "cn.zgccrui.backscreen"
        minSdk = 30
        targetSdk = 36
        versionCode = releaseVersionCode ?: 3
        versionName = releaseTag?.removePrefix("v") ?: "1.0.3"
    }

    signingConfigs.getByName("debug") {
        storeFile = rootProject.file(".signing/debug.keystore")
    }
    signingConfigs.create("release") {
        storeFile = rootProject.file("signing/release.p12")
        storeType = "PKCS12"
        storePassword = releasePassword
        keyAlias = "release"
        keyPassword = releasePassword
    }
    buildTypes.getByName("release") {
        signingConfig = signingConfigs.getByName("release")
    }

    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { abortOnError = true }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.12.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
