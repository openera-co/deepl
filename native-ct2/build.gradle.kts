plugins { id("com.android.library") }

// CTranslate2 (C++, int8 with Ruy/NEON kernels): the app's translation engine. 1.75x faster than
// ONNX Runtime on ARM64 for the same models, opens them in ~0.1 s (see .github/workflows/engine.yml).
android {
    namespace = "app.carnet.nativect2"
    compileSdk = 35
    ndkVersion = "27.2.12479018"
    defaultConfig {
        minSdk = 26
        // x86_64 only for the emulator tests.
        ndk { abiFilters += listOf((project.findProperty("carnet.abi") as String?) ?: "arm64-v8a") }
        externalNativeBuild { cmake { arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DANDROID_STL=c++_static", "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,--strip-all -Wl,--gc-sections") } }
    }
    externalNativeBuild { cmake { path("src/main/cpp/CMakeLists.txt"); version = "3.31.6" } }
    buildTypes { debug { externalNativeBuild { cmake { arguments += "-DCMAKE_BUILD_TYPE=Release" } } } }
}
