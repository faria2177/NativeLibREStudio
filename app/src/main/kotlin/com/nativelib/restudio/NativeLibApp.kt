package com.nativelib.restudio

import android.app.Application
import android.content.Context
import com.nativelib.restudio.data.AnalysisRepository
import com.nativelib.restudio.data.AppDatabase
import com.nativelib.restudio.data.Workspace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ThemeMode { SYSTEM, DARK, LIGHT }

/** Small preference store backed by SharedPreferences, exposed as StateFlows for Compose. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _theme = MutableStateFlow(runCatching { ThemeMode.valueOf(prefs.getString("theme", ThemeMode.DARK.name)!!) }.getOrDefault(ThemeMode.DARK))
    val theme: StateFlow<ThemeMode> = _theme
    fun setTheme(mode: ThemeMode) { prefs.edit().putString("theme", mode.name).apply(); _theme.value = mode }

    private val _acceptedNotice = MutableStateFlow(prefs.getBoolean("accepted_notice", false))
    val acceptedNotice: StateFlow<Boolean> = _acceptedNotice
    fun acceptNotice() { prefs.edit().putBoolean("accepted_notice", true).apply(); _acceptedNotice.value = true }
}

class NativeLibApp : Application() {
    lateinit var workspace: Workspace private set
    lateinit var repository: AnalysisRepository private set
    lateinit var settings: SettingsStore private set

    override fun onCreate() {
        super.onCreate()
        workspace = Workspace(this)
        repository = AnalysisRepository(AppDatabase.create(this).analysisDao(), workspace)
        settings = SettingsStore(this)
    }
}
