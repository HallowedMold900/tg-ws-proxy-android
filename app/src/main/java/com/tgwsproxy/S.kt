package com.tgwsproxy

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale

/**
 * Строки приложения с собственным выбором языка (системный / русский / English).
 * Работает и в интерфейсе, и в сервисе, уведомлениях, виджете и журнале.
 * Чтение [revision] внутри [get] заставляет Compose перерисовать экран при смене языка.
 */
object S {
    private var app: Context? = null
    private var prefs: SharedPreferences? = null
    @Volatile private var localized: Context? = null

    var revision: Int by mutableIntStateOf(0)
        private set

    val language = MutableStateFlow("system")

    fun init(context: Context) {
        app = context.applicationContext
        prefs = app?.getSharedPreferences("language", Context.MODE_PRIVATE)
        language.value = prefs?.getString("language", "system") ?: "system"
        rebuild()
    }

    fun setLanguage(code: String) {
        language.value = code
        prefs?.edit()?.putString("language", code)?.apply()
        rebuild()
        revision++
    }

    private fun rebuild() {
        val base = app ?: return
        localized = if (language.value == "system") {
            base
        } else {
            val cfg = Configuration(base.resources.configuration)
            cfg.setLocale(Locale.forLanguageTag(language.value))
            base.createConfigurationContext(cfg)
        }
    }

    fun get(@StringRes id: Int, vararg args: Any?): String {
        @Suppress("UNUSED_VARIABLE")
        val rev = revision
        val c = localized ?: return ""
        return if (args.isEmpty()) c.getString(id) else c.getString(id, *args)
    }
}
