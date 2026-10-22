import org.jetbrains.kotlin.gradle.dsl.JvmTarget
plugins {
 id("com.android.application")
 id("org.jetbrains.kotlin.android")
}
val sideBySideTest = providers.gradleProperty("imgSideBySideTest").orElse("false").get() == "true"
android {
 buildFeatures { buildConfig = true }
 namespace = "com.imgofficial.livestudio.v2"
 compileSdk = 35
 defaultConfig {
  applicationId = if (sideBySideTest) "com.imgofficial.livestudio.v2.test" else "com.imgofficial.livestudio.v2"
  minSdk = 26
  targetSdk = 35
  versionCode = if (sideBySideTest) 8 else 7
  versionName = if (sideBySideTest) "0.8.0-v2-graphics-motion" else "0.7.0-v2-graphics-motion"
  manifestPlaceholders["imgAppLabel"] = if (sideBySideTest) "IMG LIVE Studio V2 Test" else "IMG LIVE Studio"
  val oauthOrigin = providers.gradleProperty("imgOAuthBackendUrl").orElse("").get()
  require(oauthOrigin.isEmpty() || (oauthOrigin.startsWith("https://") && !oauthOrigin.contains('"') && !oauthOrigin.contains('\\'))) { "Invalid trusted OAuth backend origin" }
  require(!sideBySideTest || oauthOrigin.isNotEmpty()) { "V2 device-test build requires an explicit trusted OAuth backend URL" }
  buildConfigField("String", "OAUTH_BACKEND_URL", "\"$oauthOrigin\"")
 }
 // Stable update packages require a private keystore supplied from CI secrets.
 // Never commit signing materials or use the runner's ephemeral debug certificate
 // for an APK described as update-compatible.
 val signingFile = System.getenv("IMG_V2_KEYSTORE_PATH").orEmpty()
 if(signingFile.isNotBlank()){
  signingConfigs.create("v2Stable"){
   storeFile = file(signingFile)
   storePassword = System.getenv("IMG_V2_STORE_PASSWORD")
   keyAlias = System.getenv("IMG_V2_KEY_ALIAS")
   keyPassword = System.getenv("IMG_V2_KEY_PASSWORD")
  }
  buildTypes.getByName("release").signingConfig = signingConfigs.getByName("v2Stable")
 }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }

}
kotlin {
 compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}
dependencies {
 implementation("androidx.core:core-ktx:1.15.0")
 implementation("androidx.activity:activity-ktx:1.9.3")
 implementation("androidx.webkit:webkit:1.12.1")
 implementation("androidx.browser:browser:1.8.0")
 implementation("com.github.pedroSG94.RootEncoder:library:2.8.1")
}
