plugins { id("com.android.library") }

android {
    namespace = "app.carnet.nativewhisper"
    compileSdk = 35
    ndkVersion = "27.2.12479018"
    defaultConfig {
        minSdk = 26
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild { cmake { arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DANDROID_STL=c++_static", "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,--strip-all -Wl,--gc-sections") } }
    }
    externalNativeBuild { cmake { path("src/main/cpp/CMakeLists.txt"); version = "3.31.6" } }
    buildTypes { debug { externalNativeBuild { cmake { arguments += "-DCMAKE_BUILD_TYPE=Release" } } } }
}
