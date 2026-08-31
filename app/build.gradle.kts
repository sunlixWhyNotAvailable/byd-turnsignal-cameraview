plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.byd.extend"
    compileSdk = 35

    buildFeatures {
        buildConfig = true
        compose = true
    }

    defaultConfig {
        applicationId = "com.byd.extend"
        minSdk = 26
        targetSdk = 29
        versionCode = 99
        versionName = "1.0.0"
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
        buildConfigField(
            "String",
            "UPDATE_RELEASE_API_URL",
            "\"https://api.github.com/repos/sunlixWhyNotAvailable/byd-turnsignal-cameraview/releases/latest\""
        )
        buildConfigField("String", "UPDATE_USER_AGENT", "\"BYD-Extend-UpdateCheck\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // Sideloaded DiLink probe; target 29 preserves the known hidden-API behavior.
        disable += "ExpiredTargetSdkVersion"
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.00"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.core:core:1.13.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    androidTestImplementation("junit:junit:4.13.2")
    // Platform test APIs are optional SDK libraries, not production dependencies.
    listOf("base", "runner").forEach { library ->
        androidTestCompileOnly(files(androidComponents.sdkComponents.sdkDirectory.map { sdk ->
            sdk.file("platforms/android-${android.compileSdk}/optional/android.test.$library.jar")
        }))
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

val copyDebugApkToBuildOutput by tasks.registering(Copy::class) {
    dependsOn("packageDebug")
    from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
    into(rootProject.layout.projectDirectory.dir("build_output"))
    rename { "byd-extend-v${android.defaultConfig.versionName}.apk" }
}

tasks.matching { it.name == "assembleDebug" }.configureEach {
    finalizedBy(copyDebugApkToBuildOutput)
}
