package dev.ozcan.stress.settings

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources

/**
 * Gives [content] the app's language without recreating the activity, so a language change
 * redraws the screen in place, with no black frame and with every screen's state kept.
 *
 * From Android 13 the activity takes the change as a configuration change (the manifest
 * handles `locale`) and its resources already speak the new language. Before Android 13 the
 * chosen language is laid over the activity's resources here. Either way [content] gets a new
 * [Context] for each language, so every composable that reads text through the context, not
 * only through `stringResource`, draws again in it.
 */
@Composable
fun AppLanguageContent(content: @Composable () -> Unit) {
    val activity = LocalContext.current
    val configuration = LocalConfiguration.current
    val legacy by AppLanguages.legacyChoice.collectAsStateWithLifecycle()
    val localized = remember(activity, configuration, legacy) { LanguageContext.of(activity, configuration, legacy) }
    CompositionLocalProvider(
        LocalContext provides localized,
        LocalConfiguration provides localized.configuration,
        LocalResources provides localized.resources,
        content = content,
    )
}

/** The activity, with [resources] in [configuration]'s language. */
private class LanguageContext(base: Context, val configuration: Configuration, private val localized: Resources?) : ContextWrapper(base) {
    override fun getResources(): Resources = localized ?: super.getResources()

    companion object {
        /** [legacy]: the language chosen before Android 13, laid over [configuration] when it differs; null from Android 13. */
        fun of(activity: Context, configuration: Configuration, legacy: AppLanguage?): LanguageContext {
            val locales = when {
                legacy == null -> null
                legacy.tag == null -> Resources.getSystem().configuration.locales
                else -> LocaleList.forLanguageTags(legacy.tag)
            }
            if (locales == null || locales == configuration.locales) return LanguageContext(activity, configuration, null)
            val changed = Configuration(configuration).apply { setLocales(locales) }
            return LanguageContext(activity, changed, activity.createConfigurationContext(changed).resources)
        }
    }
}
