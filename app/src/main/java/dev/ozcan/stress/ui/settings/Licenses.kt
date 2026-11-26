package dev.ozcan.stress.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ozcan.stress.R
import dev.ozcan.stress.ui.components.EngineeringPage
import dev.ozcan.stress.ui.components.logSection
import dev.ozcan.stress.ui.theme.NumberStyles
import dev.ozcan.stress.ui.theme.StressColors
import dev.ozcan.stress.ui.theme.StressType

/**
 * One item the app ships or is built on: its [name], [licence] and copyright [holder] as the licence states them, and
 * what the app uses it for. Holders and licences are taken from the font files' name table (copyright and licence
 * entries) and the libraries' Maven POMs; change them only from those sources.
 */
private class Credit(val name: String, val licence: String, val holder: String, @StringRes val use: Int)

private const val MIT = "MIT"
private const val OFL = "SIL OFL 1.1"
private const val APACHE = "Apache 2.0"
private const val AOSP = "The Android Open Source Project"
private const val JETBRAINS = "JetBrains"

private val App = Credit("Stress Test", MIT, "© 2026 Özcan Orhan Demirci", R.string.licenses_use_app)

private val Fonts = listOf(
    Credit("Instrument Sans", OFL, "© 2022 The Instrument Sans Project Authors", R.string.licenses_use_instrument),
    Credit("Space Grotesk", OFL, "© 2020 The Space Grotesk Project Authors", R.string.licenses_use_grotesk),
    Credit("Space Mono", OFL, "© 2016 The Space Mono Project Authors", R.string.licenses_use_mono),
    Credit("JetBrains Mono", OFL, "© 2020 The JetBrains Mono Project Authors", R.string.licenses_use_jetbrains),
)

private val Libraries = listOf(
    Credit("Jetpack Compose", APACHE, AOSP, R.string.licenses_use_compose),
    Credit("Material 3", APACHE, AOSP, R.string.licenses_use_material),
    Credit("Material Icons", APACHE, AOSP, R.string.licenses_use_icons),
    Credit("AndroidX Activity, Core, Lifecycle", APACHE, AOSP, R.string.licenses_use_androidx),
    Credit("Kotlin", APACHE, JETBRAINS, R.string.licenses_use_kotlin),
    Credit("kotlinx.coroutines", APACHE, JETBRAINS, R.string.licenses_use_coroutines),
    Credit("kotlinx.serialization", APACHE, JETBRAINS, R.string.licenses_use_serialization),
)

/** The licences page behind About: the app's own licence, then the fonts and the libraries, as a log. */
@Composable
internal fun LicensesPage(onBack: () -> Unit) {
    val appTitle = stringResource(R.string.licenses_section_app)
    val fontsTitle = stringResource(R.string.licenses_section_fonts)
    val librariesTitle = stringResource(R.string.licenses_section_libraries)
    val oflNote = stringResource(R.string.licenses_full_text, "openfontlicense.org")
    val apacheNote = stringResource(R.string.licenses_full_text, "apache.org/licenses/LICENSE-2.0")
    EngineeringPage(stringResource(R.string.licenses_title), onBack) {
        logSection(appTitle) { credits(listOf(App)) }
        logSection(fontsTitle, summary = Fonts.size.toString(), note = oflNote) { credits(Fonts) }
        logSection(librariesTitle, summary = Libraries.size.toString(), note = apacheNote) { credits(Libraries) }
    }
}

private fun LazyListScope.credits(list: List<Credit>) {
    list.forEach { credit -> item(key = "credit:${credit.name}") { CreditLine(credit) } }
}

/** Name as the key and the licence as the value, the holder and the use as two faint lines under them. */
@Composable
private fun CreditLine(credit: Credit) {
    Column(Modifier.fillMaxWidth().heightIn(min = 32.dp).padding(vertical = 6.dp), Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(12.dp), Alignment.CenterVertically) {
            Text(credit.name, Modifier.weight(1f), style = StressType.Code, color = StressColors.TextDim)
            Text(credit.licence, style = NumberStyles.Small, color = StressColors.Text, textAlign = TextAlign.End, maxLines = 1)
        }
        Text(credit.holder, style = StressType.Code.copy(fontSize = 10.sp, lineHeight = 14.sp), color = StressColors.TextFaint)
        Text(stringResource(credit.use), style = StressType.BodySmall, color = StressColors.TextDim)
    }
}
