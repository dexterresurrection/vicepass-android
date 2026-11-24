// Тестовое приложение для проверки автозаполнения Cloud Pass в эмуляторе (в релиз не входит).
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "uk.zjd.cloudpass.autofilltest"
    compileSdk = 36
    defaultConfig {
        applicationId = "uk.zjd.aftest" // не «uk.zjd.cloudpass.*»: KeePassDX не заполняет приложения со своим id внутри
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.androidx.autofill)
}
