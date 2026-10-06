import com.android.build.api.variant.HasUnitTestBuilder
import org.gradle.api.tasks.testing.Test

plugins {
    id("com.android.application") version "9.0.1"
}

val appVersionName = "4.9.17"

val mapkitApiKey = "74d6077f-f6ce-4e0e-9fca-a2550f5e4036"

android {
    namespace = "ru.gpsantiradar.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "ru.gpsantiradar.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 52
        versionName = appVersionName
        buildConfigField("String", "MAPKIT_API_KEY", "\"$mapkitApiKey\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("projectDebug") {
            storeFile = rootProject.file("work/gps-antiradar-debug.keystore")
            storePassword = "android"
            keyAlias = "gpsantiradar"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("projectDebug")
        }
        getByName("release") {
            signingConfig = signingConfigs.getByName("projectDebug")
            isMinifyEnabled = false
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = false
        }
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("AndroidManifest.xml")
            res.directories.add("res")
            assets.directories.add("assets")
        }
        getByName("test") {
            kotlin.directories.add("src/test/kotlin")
            kotlin.directories.add("tests")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

androidComponents {
    beforeVariants(selector().withBuildType("release")) { variantBuilder ->
        (variantBuilder as HasUnitTestBuilder).enableUnitTest = true
    }
}

base {
    archivesName = "GPS-AntiRadar-$appVersionName"
}

dependencies {
    implementation("com.yandex.android:maps.mobile:4.42.0-lite")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("androidx.car.app:app:1.7.0")
    implementation("androidx.car.app:app-projected:1.7.0")
    testImplementation("androidx.car.app:app-testing:1.7.0")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
}

tasks.withType<Test>().configureEach {
    if (project.hasProperty("radarBaseFile")) {
        systemProperty("radarBaseFile", project.property("radarBaseFile"))
    }
}
