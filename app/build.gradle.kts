plugins {
    id("com.android.application")
}

android {
    namespace = "com.byd.turnsignalguard.capture"
    compileSdk = 35

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.byd.turnsignalguard.capture"
        minSdk = 26
        targetSdk = 29
        versionCode = 43
        versionName = "0.41.0"
        buildConfigField(
            "String",
            "UPDATE_RELEASE_API_URL",
            "\"https://api.github.com/repos/sunlixWhyNotAvailable/byd-turnsignal-cameraview/releases/latest\""
        )
        buildConfigField("String", "UPDATE_USER_AGENT", "\"BYD-TurnSignal-Camera-UpdateCheck\"")
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

dependencies {
    implementation("androidx.core:core:1.13.1")
    testImplementation("junit:junit:4.13.2")
}

val copyDebugApkToBuildOutput by tasks.registering(Copy::class) {
    dependsOn("packageDebug")
    from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
    into(rootProject.layout.projectDirectory.dir("build_output"))
    rename { "byd-turnsignal-camera-v${android.defaultConfig.versionName}.apk" }
}

tasks.matching { it.name == "assembleDebug" }.configureEach {
    finalizedBy(copyDebugApkToBuildOutput)
}
