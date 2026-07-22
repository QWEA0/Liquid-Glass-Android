plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

android {
    namespace = "com.example.liquidglass"
    compileSdk = 35
    // 锁定 NDK：不指定的话 CI 会用 runner 默认版本，编出的 .so 与本地不一致
    ndkVersion = "27.0.12077973"

    defaultConfig {
        minSdk = 24

        // NDK 原生模糊/色差加速（CPU 管线）
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }

    // JitPack / maven-publish：发布 release 变体并附带源码 jar
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
}

// JitPack 发布配置：
// 使用方式（consumer 项目）：
//   repositories { maven { url = uri("https://jitpack.io") } }
//   dependencies { implementation("com.github.QWEA0.Liquid-Glass-Android:liquidglass:<tag>") }
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.github.QWEA0"
                artifactId = "liquidglass"
                version = "2.0.0"
            }
        }
    }
}
