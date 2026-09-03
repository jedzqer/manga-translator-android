import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.manga.translate"
    compileSdk = 36
    val storeFilePath = project.findProperty("STORE_FILE") as String?
    val storePasswordProp = project.findProperty("STORE_PASSWORD") as String?
    val keyAliasProp = project.findProperty("KEY_ALIAS") as String?
    val keyPasswordProp = project.findProperty("KEY_PASSWORD") as String?
    val hasSigning = !storeFilePath.isNullOrBlank() &&
        !storePasswordProp.isNullOrBlank() &&
        !keyAliasProp.isNullOrBlank() &&
        !keyPasswordProp.isNullOrBlank()

    defaultConfig {
        applicationId = "com.manga.translate.v3"
        minSdk = 24
        targetSdk = 36
        versionCode = 93
        versionName = "3.4.8"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val releaseSigning = if (hasSigning) {
        signingConfigs.create("release") {
            storeFile = rootProject.file(storeFilePath!!)
            storePassword = storePasswordProp
            keyAlias = keyAliasProp
            keyPassword = keyPasswordProp
        }
    } else {
        null
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            if (releaseSigning != null) {
                signingConfig = releaseSigning
            } else {
                println("Release signing is not configured. Set STORE_FILE/STORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD.")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    lint {
        // The app intentionally ships arm64-only native model dependencies.
        disable += "ChromeOsAbiSupport"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        jniLibs {
            keepDebugSymbols += setOf(
                "**/libonnxruntime.so",
                "**/libonnxruntime4j_jni.so",
                // avif-coder ships these native libs pre-stripped, so we package them as-is
                // instead of letting AGP attempt another strip pass and log noisy warnings.
                "**/libaom.so",
                "**/libcoder.so",
                "**/libdav1d.so",
                "**/libde265.so",
                "**/libheif.so",
                "**/libx265.so"
            )
        }
    }

    sourceSets["main"].assets.srcDirs("src/main/assets", "../assets")
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.2")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.28.0")
    implementation("net.lingala.zip4j:zip4j:2.11.6")
    implementation("io.github.awxkee:avif-coder:2.2.1")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.28.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.4.0")
}
