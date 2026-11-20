plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.pengom"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.pengom"
        minSdk = 26
        targetSdk = 34
        versionCode = 23
        versionName = "0.16.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // 웹 프로토타입(scroll-melt/index.html)을 그대로 앱 화면으로 쓴다. 빌드할 때마다 복사.
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("webassets"))
}

val copyWeb by tasks.registering(Copy::class) {
    from(rootProject.file("../scroll-melt/index.html"))
    // 앱에 넣은 글꼴 (인터넷 없이도 같은 모양)
    from(rootProject.file("../scroll-melt/fonts")) {
        include("*.woff2", "*.txt")
        into("fonts")
    }
    into(layout.buildDirectory.dir("webassets/web"))
}
tasks.named("preBuild") { dependsOn(copyWeb) }
