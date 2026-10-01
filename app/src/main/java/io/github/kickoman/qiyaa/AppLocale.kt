package io.github.kickoman.qiyaa

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import io.github.kickoman.qiyaa.data.AppLanguage
import io.github.kickoman.qiyaa.data.Settings
import java.util.Locale

/**
 * The app's own interface language, whatever the system's: the Application and the playback
 * service take it in attachBaseContext, the activity as an override configuration, and a new
 * choice is applied to their resources while the process runs.
 */
object AppLocale {
    fun configuration(language: AppLanguage): Configuration = Configuration().apply {
        setLocales(LocaleList(locale(language)))
    }

    /** [base] in the stored language, for Application and Service attachBaseContext. */
    fun wrap(base: Context): Context {
        val language = Settings.storedLanguage(base)
        Locale.setDefault(locale(language))
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocales(LocaleList(locale(language)))
        return base.createConfigurationContext(configuration)
    }

    /**
     * Switches the resources of [context] (the application's, the service's) to [language]: after
     * a new choice, and after a system configuration change, which resets them.
     */
    @Suppress("DEPRECATION") // the one way to change a running Application's resources
    fun apply(context: Context, language: AppLanguage) {
        Locale.setDefault(locale(language))
        val resources = context.resources
        val configuration = Configuration(resources.configuration)
        configuration.setLocales(LocaleList(locale(language)))
        resources.updateConfiguration(configuration, resources.displayMetrics)
    }

    private fun locale(language: AppLanguage): Locale = Locale.forLanguageTag(language.tag)
}
