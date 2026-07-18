plugins {
    alias(libs.plugins.android.application)
    id("com.google.gms.google-services")
}

android {
    namespace = "com.vladurares.tcpclient"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.vladurares.tcpclient"
        minSdk = 24
        targetSdk = 36
        versionCode = 5
        versionName = "1.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
}

android {
    packaging {
        resources {
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

dependencies {

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(files("libs/chat-lib.jar"))
    implementation(libs.security.crypto)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)

    implementation(libs.gson)

    implementation(files("libs/bcprov-jdk18on-1.83.jar"))
    implementation(files("libs/bcpkix-jdk18on-1.83.jar"))
    implementation(files("libs/crypto-api.jar"))
    implementation(files("libs/tcpsecure-1.1.1-thin.jar"))

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
}