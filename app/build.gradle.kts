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
        versionCode = 57
        versionName = "3.1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1+"
        }
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    packaging {
        jniLibs {
            keepDebugSymbols += setOf(
                "**/libtensorflowlite_jni.so",
                "**/libonnxruntime.so",
                "**/libonnxruntime4j_jni.so"
            )
        }
    }

    sourceSets["main"].assets.srcDirs("src/main/assets", "../assets")
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.fragment:fragment-ktx:1.7.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")
    implementation("org.tensorflow:tensorflow-lite:2.17.0")
    implementation("net.lingala.zip4j:zip4j:2.11.5")

}
