plugins {
 id("com.android.application")
 id("org.jetbrains.kotlin.android")
}
android {
 buildFeatures { buildConfig = true }
 namespace = "com.imgofficial.livestudio.v2"
 compileSdk = 35
 defaultConfig {
  applicationId = "com.imgofficial.livestudio.v2"
  minSdk = 26
  targetSdk = 35
  versionCode = 1
  versionName = "0.1.0"
  val oauthOrigin = providers.gradleProperty("imgOAuthBackendUrl").orElse("").get()
  require(oauthOrigin.isEmpty() || (oauthOrigin.startsWith("https://") && !oauthOrigin.contains('"') && !oauthOrigin.contains('\\'))) { "Invalid trusted OAuth backend origin" }
  buildConfigField("String", "OAUTH_BACKEND_URL", "\\\"$oauthOrigin\\\"")
 }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
}
dependencies {
 implementation("androidx.core:core-ktx:1.15.0")
 implementation("androidx.activity:activity-ktx:1.9.3")
 implementation("androidx.webkit:webkit:1.12.1")
 // RootEncoder 2.8.1 requires newer Kotlin metadata; integration held until toolchain upgrade.
}
