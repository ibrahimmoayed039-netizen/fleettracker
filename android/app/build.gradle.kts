plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
// يُفعَّل تلقائياً عند وجود الملف app/google-services.json
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "com.fleet.tracker"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.fleet.tracker"
        minSdk = 24
        targetSdk = 34
        // يزيد رقم الإصدار تلقائياً مع كل بناء على GitHub، فيُثبَّت التحديث فوق القديم
        val run = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0
        versionCode = 100 + run
        versionName = "1.1.$run"
    }
    // مفتاح توقيع ثابت: بدونه كل بناء يُوقَّع بمفتاح عشوائي جديد ويرفض أندرويد التحديث
    signingConfigs {
        getByName("debug") {
            storeFile = file("fleet-sign.jks")
            storePassword = "fleet2026"
            keyAlias = "fleet"
            keyPassword = "fleet2026"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
