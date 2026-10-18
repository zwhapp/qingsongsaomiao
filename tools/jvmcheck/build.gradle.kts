// 桌面 JVM 冒烟工程：真实运行 app 端的 OpenCV Kotlin 代码。
// 背景：v1.5.3 的 Core.reduce 参数错位在 Python 验证中不可见，直到真机才暴露。
// 从此凡是打包涉及端正/增强核心算法，先在这里真跑一遍（sync_skew.py 保证
// 被测函数与 app 源文件逐字一致）。
plugins {
    kotlin("jvm") version "2.0.21"   // 与 app 的 kotlin = "2.0.21" 保持一致
    application
}

repositories {
    mavenCentral()
}

dependencies {
    // org.openpnp:opencv = OpenCV Java 绑定 + 内嵌 Windows/Linux/Mac 原生库，
    // nu.pattern.OpenCV.loadLocally() 自动解压加载，无需安装。
    implementation("org.openpnp:opencv:4.9.0-0")
}

application {
    mainClass.set("inkcheck.MainKt")   // Main.kt 带 package inkcheck
}

kotlin {
    jvmToolchain(17)
}
