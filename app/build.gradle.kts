plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.studycloud.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.studycloud.tv"
        // EncryptedSharedPreferences 要求 API 23+（见开发文档 5.4 的兼容性风险）
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // 模拟器访问宿主机用 10.0.2.2；真机改成局域网 IP
            buildConfigField("String", "API_BASE_URL", "\"http://10.0.2.2:5080\"")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("String", "API_BASE_URL", "\"https://api.example.com\"")
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
        viewBinding = true
        buildConfig = true
    }

    lint {
        // 依赖升级提示与 targetSdk 提示留给人工决策，不阻断构建；
        // 真正会在设备上出问题的（NewApi、WorkManager 初始化、TV banner）保持为错误。
        warningsAsErrors = false
        disable += setOf("GradleDependency", "OldTargetApi", "UnusedAttribute", "DataExtractionRules")
        abortOnError = true
    }
}

dependencies {
    implementation(project(":core-gate"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
