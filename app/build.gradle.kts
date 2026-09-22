import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    // AGP 9 起 Kotlin 支持内置，这里只需要 Compose 编译器插件
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.station1921.pixelcam"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.station1921.pixelcam"
        minSdk = 26
        targetSdk = 36
        versionCode = 74
        versionName = "1.3.49"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }

    buildFeatures {
        compose = true
    }

    // 正式签名：用工程根目录 keystore/release.keystore（已在 .gitignore 排除）。
    // 若 keystore 不存在（例如 CI 未配置密钥），则降级为未签名构建，不卡流程。
    // 密钥口令为开发用默认值，上线前请替换为自己的密钥并妥善保管。
    signingConfigs {
        create("release") {
            val ks = rootProject.file("keystore/release.keystore")
            if (ks.exists()) {
                storeFile = ks
                storePassword = "pixelcam123"
                keyAlias = "pixelcam"
                keyPassword = "pixelcam123"
            }
        }
    }

    // OpenCV 的 AAR 内含全部 ABI 的 .so，打通用包会有 100MB+。
    // 只输出 arm64-v8a 分包：2016 年后主流芯片均为 64 位 ARM，不再适配 32 位老设备。
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = false
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt"
            )
        }
    }

    buildTypes {
        release {
            // 首次交付默认关闭混淆，确保 OpenCV 的 JNI 绑定不被裁掉。
            // 需要瘦身时把这里改成 true，proguard-rules.pro 里已有保留规则。
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val ks = rootProject.file("keystore/release.keystore")
            if (ks.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // 人脸检测：本地离线，不联网、不计费
    implementation(libs.mlkit.face.detection)
    // 相机 WiFi / FTP 图传
    implementation(libs.commons.net)
    // 二维码解析：不依赖 Google Play 服务，从拍照或相册图片中解析 WiFi 二维码
    implementation("com.google.zxing:core:3.5.3")

    // 内嵌实时扫码：CameraX 取预览帧，交给上面的 ZXing 解码（仍不依赖 Google Play 服务）
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // OpenCV：注意这里用的是 @aar（artifact-only 写法）。
    // 原因：org.opencv:opencv 发布到 Maven Central 的 Gradle Module Metadata 是坏的——
    // 它声明的 AAR 约 3.5MB、sha256 为 55306ce7…，而服务器上真实文件约 120MB（含 .so）、
    // sha256 为 6d11b40f…。Gradle 优先信任 GMM，会校验失败导致构建中断。
    // 加上 @aar 可完全跳过模块元数据解析，直接下载真实产物。
    implementation("org.opencv:opencv:4.13.0@aar")
}
