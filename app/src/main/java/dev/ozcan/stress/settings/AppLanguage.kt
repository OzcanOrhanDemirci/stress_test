package dev.ozcan.stress.settings

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.core.content.edit
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The app's language: the phone's own, or Turkish or English whatever the phone uses. */
enum class AppLanguage(val tag: String?) {
    System(null),
    Turkish("tr"),
    English("en"),
    ;

    companion object {
        /** The language for a language tag; anything the app has no strings for follows the phone. */
        fun of(tag: String?): AppLanguage = entries.firstOrNull { it.tag != null && it.tag == tag } ?: System
    }
}

/**
 * Sets and reads the app's language. From Android 13 the system keeps a
 * language per app (also offered in the phone's app settings) and applies it
 * to the whole process; before that the choice is kept in the app's
 * preferences and laid over each activity's configuration as it is created.
 * Either way the activity stays: [AppLanguageContent] redraws it in the new language.
 */
object AppLanguages {
    private const val FILE = "language"
    private const val KEY = "tag"

    private val perAppLocales: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    private val legacy = MutableStateFlow<AppLanguage?>(null)

    /** Before Android 13, the language chosen while the app runs, for [AppLanguageContent]; null from Android 13. */
    val legacyChoice: StateFlow<AppLanguage?> = legacy.asStateFlow()

    fun current(context: Context): AppLanguage =
        if (perAppLocales) {
            val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
            if (locales.isEmpty) AppLanguage.System else AppLanguage.of(locales[0].language)
        } else {
            AppLanguage.of(prefs(context).getString(KEY, null))
        }

    /** Switches the app to [language], in place: the activity is not recreated. */
    fun set(context: Context, language: AppLanguage) {
        if (language == current(context)) return
        if (perAppLocales) {
            // Reaches the activity as a configuration change it handles itself (the manifest's `locale`).
            context.getSystemService(LocaleManager::class.java).applicationLocales =
                language.tag?.let { LocaleList.forLanguageTags(it) } ?: LocaleList.getEmptyLocaleList()
        } else {
            // Written for the next start and for text written outside the activity (a notification).
            prefs(context).edit(commit = true) { putString(KEY, language.tag) }
            // Number and date formats follow the default locale; it is set before the screen redraws.
            Locale.setDefault(language.tag?.let(Locale::forLanguageTag) ?: systemLocale())
            legacy.value = language
        }
    }

    /** The phone's own language, whatever the app uses. */
    fun systemLocale(): Locale = Resources.getSystem().configuration.locales[0]

    /**
     * Before Android 13, for an activity being created: sets the process's
     * default locale, which number and date formats follow, to the chosen
     * language, and returns the configuration to lay over the activity; null
     * when the app follows the phone, or when Android does all this itself.
     */
    fun attach(context: Context): Configuration? {
        if (perAppLocales) return null
        val locale = legacyLocale(context)
        Locale.setDefault(locale ?: systemLocale())
        legacy.value = AppLanguage.of(prefs(context).getString(KEY, null))
        return locale?.let { Configuration().apply { setLocale(it) } }
    }

    /** Before Android 13: a configuration change resets the default locale to the phone's; puts the chosen one back. */
    fun reapply(context: Context) {
        if (perAppLocales) return
        Locale.setDefault(legacyLocale(context) ?: systemLocale())
    }

    /**
     * [context] in the app's language, for text written outside an activity (a notification). From Android 13 the
     * process already uses it; before that it is laid over here as over each activity.
     */
    fun localized(context: Context): Context {
        if (perAppLocales) return context
        val locale = legacyLocale(context) ?: return context
        return context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(locale) })
    }

    private fun legacyLocale(context: Context): Locale? =
        AppLanguage.of(prefs(context).getString(KEY, null)).tag?.let(Locale::forLanguageTag)

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
