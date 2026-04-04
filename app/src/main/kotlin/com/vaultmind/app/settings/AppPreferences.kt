package com.vaultmind.app.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "vaultmind_prefs")

enum class AutoLockDelay {
    IMMEDIATE, THIRTY_SECONDS, ONE_MINUTE
}

data class AppSettings(
    val topK: Int = 5,
    val temperature: Float = 0.3f,
    val thinkingMode: Boolean = true,
    val autoLock: AutoLockDelay = AutoLockDelay.THIRTY_SECONDS,
    val llmModelPath: String = "",
    val embeddingModelPath: String = ""
)

@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        val TOP_K = intPreferencesKey("top_k")
        val TEMPERATURE = floatPreferencesKey("temperature")
        val THINKING_MODE = booleanPreferencesKey("thinking_mode")
        val AUTO_LOCK = stringPreferencesKey("auto_lock")
        val LLM_MODEL_PATH = stringPreferencesKey("llm_model_path")
        val EMBEDDING_MODEL_PATH = stringPreferencesKey("embedding_model_path")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            topK = prefs[TOP_K] ?: 5,
            temperature = prefs[TEMPERATURE] ?: 0.3f,
            thinkingMode = prefs[THINKING_MODE] ?: true,
            autoLock = AutoLockDelay.valueOf(prefs[AUTO_LOCK] ?: AutoLockDelay.THIRTY_SECONDS.name),
            llmModelPath = prefs[LLM_MODEL_PATH] ?: "",
            embeddingModelPath = prefs[EMBEDDING_MODEL_PATH] ?: ""
        )
    }

    suspend fun setTopK(value: Int) {
        context.dataStore.edit { it[TOP_K] = value.coerceIn(1, 15) }
    }

    suspend fun setTemperature(value: Float) {
        context.dataStore.edit { it[TEMPERATURE] = value.coerceIn(0f, 1f) }
    }

    suspend fun setThinkingMode(enabled: Boolean) {
        context.dataStore.edit { it[THINKING_MODE] = enabled }
    }

    suspend fun setAutoLock(delay: AutoLockDelay) {
        context.dataStore.edit { it[AUTO_LOCK] = delay.name }
    }

    suspend fun setLlmModelPath(path: String) {
        context.dataStore.edit { it[LLM_MODEL_PATH] = path }
    }

    suspend fun setEmbeddingModelPath(path: String) {
        context.dataStore.edit { it[EMBEDDING_MODEL_PATH] = path }
    }
}
