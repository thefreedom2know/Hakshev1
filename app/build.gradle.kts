plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.hakshev"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.hakshev"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.room.runtime)
    annotationProcessor(libs.androidx.room.compiler)
    implementation("net.zetetic:sqlcipher-android:${libs.versions.sqlcipher.get()}@aar")
    testImplementation(libs.junit)
}
