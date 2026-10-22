plugins {
 id("com.android.application")
 id("org.jetbrains.kotlin.android")
}
android {
 namespace = "com.imgofficial.livestudio.v2"
 compileSdk = 35
 defaultConfig {
  applicationId = "com.imgofficial.livestudio.v2"
  minSdk = 26
  targetSdk = 35
  versionCode = 1
  versionName = "0.1.0"
 }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
}
dependencies {
 implementation("androidx.core:core-ktx:1.15.0")
 implementation("androidx.activity:activity-ktx:1.9.3")
 implementation("androidx.webkit:webkit:1.12.1")
 implementation("com.github.pedroSG94.RootEncoder:library:2.8.1")
}
