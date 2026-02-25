plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val versionCodeOverride = (project.findProperty("versionCode") as String?)?.toIntOrNull()
val versionNameOverride = project.findProperty("versionName") as String?
val keystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
val keystorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
val keyAlias = System.getenv("ANDROID_KEY_ALIAS")
val keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
val hasKeystore = !keystorePath.isNullOrBlank() &&
    !keystorePassword.isNullOrBlank() &&
    !keyAlias.isNullOrBlank() &&
    !keyPassword.isNullOrBlank()

android {
    namespace = "com.sushi.sshclient"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sushi.sshclient"
        minSdk = 24
        targetSdk = 36
        versionCode = versionCodeOverride ?: 1
        versionName = versionNameOverride ?: "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        if (hasKeystore) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = keystorePassword
                keyAlias = keyAlias
                keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        release {
            if (hasKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
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

    buildFeatures {
        viewBinding = true
    }

    packaging {
        resources {
            excludes += "META-INF/DEPENDENCIES"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.jcraft:jsch:0.1.55")
    implementation("androidx.security:security-crypto:1.0.0")
    implementation("com.google.android.gms:play-services-auth:20.7.0")
    implementation("com.google.api-client:google-api-client-android:2.2.0")
    implementation("com.google.api-client:google-api-client-gson:2.2.0")
    implementation("com.google.http-client:google-http-client-android:1.43.3")
    implementation("com.google.apis:google-api-services-drive:v3-rev20230815-2.0.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}
