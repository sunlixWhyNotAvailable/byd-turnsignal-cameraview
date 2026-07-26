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
        versionCode = 34
        versionName = "0.34.0"
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
