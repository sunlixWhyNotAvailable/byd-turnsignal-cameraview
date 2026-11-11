import com.android.build.api.artifact.SingleArtifact

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.byd.extend"
    compileSdk = 35
    ndkVersion = "30.0.15729638"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    testOptions.unitTests.isIncludeAndroidResources = true

    packaging {
        // The shell worker loads our JNI library by the installed package's nativeLibraryDir.
        jniLibs.useLegacyPackaging = true
        // BC modules duplicate this Java 9 OSGi descriptor; Android does not use it.
        resources.excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        resources.merges += "META-INF/LICENSE.md"
    }

    defaultConfig {
        applicationId = "com.byd.extend"
        minSdk = 26
        targetSdk = 29
        versionCode = 128
        versionName = "1.9.6"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_static" } }
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

    buildTypes {
        create("performance") {
            initWith(getByName("release"))
            isDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("com.android.tools.smali:smali-dexlib2:3.0.9")
    implementation(platform("androidx.compose:compose-bom:2024.10.00"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.core:core:1.13.1")
    implementation("io.coil-kt.coil3:coil-compose-core:3.0.4")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.85")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.getstream:stream-webrtc-android:1.3.10")
    implementation("androidx.media3:media3-muxer:1.8.0")
    implementation("com.google.zxing:core:3.5.3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.16.1")
}

val tunnelJniOutput = layout.buildDirectory.dir("generated/tunnelJniLibs")

// The host container cannot read the IVI APK mount. Ship only the fixed artwork writer DEX.
val artworkClasses = layout.buildDirectory.dir("artwork/classes")
val artworkAssets = layout.buildDirectory.dir("generated/artworkAssets")
val compileArtworkWorker by tasks.registering(JavaCompile::class) {
    source(fileTree("src/main/java/com/byd/extend/artwork") { include("*.java") })
    classpath = files(android.bootClasspath)
    destinationDirectory.set(artworkClasses)
    sourceCompatibility = "17"
    targetCompatibility = "17"
    options.encoding = "UTF-8"
    javaCompiler.set(javaToolchains.compilerFor { languageVersion.set(JavaLanguageVersion.of(17)) })
}
val artworkWorkerClasses by tasks.registering(Jar::class) {
    dependsOn(compileArtworkWorker)
    from(artworkClasses)
    archiveFileName.set("worker-classes.jar")
    destinationDirectory.set(layout.buildDirectory.dir("artwork"))
}
val dexArtworkWorker by tasks.registering(Exec::class) {
    dependsOn(artworkWorkerClasses)
    inputs.file(artworkWorkerClasses.flatMap { it.archiveFile })
    outputs.dir(artworkAssets)
    doFirst {
        val output = artworkAssets.get().dir("music-artwork").asFile
        output.mkdirs()
        val d8 = android.sdkDirectory.resolve("build-tools/${android.buildToolsVersion}/d8.bat")
        commandLine("cmd", "/c", d8.absolutePath, "--release", "--min-api", "31",
            "--lib", android.bootClasspath.first().absolutePath, "--output",
            output.resolve("worker.jar").absolutePath, artworkWorkerClasses.get().archiveFile.get().asFile.absolutePath)
    }
}
android.sourceSets.getByName("main").assets.srcDir(artworkAssets)
tasks.matching {
    (it.name.startsWith("merge") && it.name.endsWith("Assets")) || it.name.contains("lint", ignoreCase = true)
}.configureEach {
    dependsOn(dexArtworkWorker)
}

android.sourceSets.getByName("main").jniLibs.srcDir(tunnelJniOutput)
val buildCameraTunnels by tasks.registering(Exec::class) {
    inputs.dir("src/main/native/tunnel")
    outputs.dir(tunnelJniOutput)
    commandLine("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
        file("src/main/native/tunnel/build.ps1").absolutePath,
        "-AndroidNdk", android.sdkDirectory.resolve("ndk/${android.ndkVersion}").absolutePath,
        "-OutputRoot", tunnelJniOutput.get().asFile.absolutePath)
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach {
    dependsOn(buildCameraTunnels)
}

val copyPerformanceApkToBuildOutput by tasks.registering(Copy::class) {
    dependsOn("packagePerformance")
    from(layout.buildDirectory.file("outputs/apk/performance/app-performance.apk"))
    into(rootProject.layout.projectDirectory.dir("build_output"))
    rename { "byd-extend-v${android.defaultConfig.versionName}.apk" }
}

androidComponents.onVariants(androidComponents.selector().withBuildType("performance")) { variant ->
    val viewerAssets = variant.artifacts.get(SingleArtifact.ASSETS)
    tasks.withType<Test>().matching { it.name == "testPerformanceUnitTest" }.configureEach {
        dependsOn(viewerAssets)
        inputs.file(viewerAssets.map { it.file("camera-view/viewer.js") })
        systemProperty("cameraViewerAssets", viewerAssets.get().asFile.absolutePath)
    }
    val verifyCameraViewerRevision = tasks.register<Test>("verifyCameraViewerRevision") {
        description = "Checks the packaged viewer script against the compiled server revision."
        dependsOn("compilePerformanceUnitTestJavaWithJavac", viewerAssets)
        val unitTests = tasks.named<Test>("testPerformanceUnitTest").get()
        testClassesDirs = unitTests.testClassesDirs
        classpath = unitTests.classpath
        inputs.file(viewerAssets.map { it.file("camera-view/viewer.js") })
        systemProperty("cameraViewerAssets", viewerAssets.get().asFile.absolutePath)
        filter.includeTestsMatching(
            "com.byd.extend.remote.ViewerStopReasonTest.packagedScriptMatchesServerRevision")
    }
    tasks.matching { it.name == "packagePerformance" }.configureEach {
        dependsOn(verifyCameraViewerRevision)
    }
}

tasks.matching { it.name == "assemblePerformance" }.configureEach {
    finalizedBy(copyPerformanceApkToBuildOutput)
}
