plugins {
    // 注意：AGP 9.0 起 Kotlin 支持已内置，不要再声明 org.jetbrains.kotlin.android，
    // 否则会直接报 "no longer required for Kotlin support since AGP 9.0"。
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
