package dev.ozcan.stress.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dev.ozcan.stress.engine.SceneQuality
import dev.ozcan.stress.run.StressDuration
import dev.ozcan.stress.run.StressMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the user chose on the settings screen, and the last test they set up. */
data class Settings(
    /**
     * Device safety: the app stops a test before the phone gets dangerously
     * hot or flat ([dev.ozcan.stress.safety.SafetyPolicy]). On by default;
     * turning it off asks first.
     */
    val deviceSafety: Boolean = true,
    /** Quality of the cinematic scenes. Medium is the scene as tuned on a mid-range phone. */
    val quality: SceneQuality = SceneQuality.Medium,
    /** Full brightness and the highest refresh rate while a test runs: the same display load every time. */
    val maxDisplay: Boolean = true,
    /** Vibrate on start, finish and safety stops. */
    val haptics: Boolean = true,
    /** The use-with-care notice each time the app opens; its "don't show again" turns this off. */
    val startupNotice: Boolean = true,
    /** The test the home screen offers: the last one started. */
    val lastMode: StressMode = StressMode.Full,
    val lastDuration: StressDuration = StressDuration.Fifteen,
)

/** [Settings] kept in shared preferences, published as a flow. */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    val current: Settings get() = _settings.value

    @Synchronized
    fun update(transform: (Settings) -> Settings) {
        val next = transform(_settings.value)
        if (next == _settings.value) return
        prefs.edit {
            putBoolean(KEY_SAFETY, next.deviceSafety)
            putString(KEY_QUALITY, next.quality.name)
            putBoolean(KEY_DISPLAY, next.maxDisplay)
            putBoolean(KEY_HAPTICS, next.haptics)
            putBoolean(KEY_NOTICE, next.startupNotice)
            putString(KEY_MODE, next.lastMode.name)
            putString(KEY_DURATION, next.lastDuration.name)
        }
        _settings.value = next
    }

    private fun read(): Settings {
        val defaults = Settings()
        return Settings(
            deviceSafety = prefs.getBoolean(KEY_SAFETY, defaults.deviceSafety),
            quality = enumOr(prefs.getString(KEY_QUALITY, null), defaults.quality),
            maxDisplay = prefs.getBoolean(KEY_DISPLAY, defaults.maxDisplay),
            haptics = prefs.getBoolean(KEY_HAPTICS, defaults.haptics),
            startupNotice = prefs.getBoolean(KEY_NOTICE, defaults.startupNotice),
            lastMode = enumOr(prefs.getString(KEY_MODE, null), defaults.lastMode),
            lastDuration = enumOr(prefs.getString(KEY_DURATION, null), defaults.lastDuration),
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: fallback

    private companion object {
        const val FILE = "settings"
        const val KEY_SAFETY = "device_safety"
        const val KEY_QUALITY = "scene_quality"
        const val KEY_DISPLAY = "max_display"
        const val KEY_HAPTICS = "haptics"
        const val KEY_NOTICE = "startup_notice"
        const val KEY_MODE = "last_mode"
        const val KEY_DURATION = "last_duration"
    }
}
