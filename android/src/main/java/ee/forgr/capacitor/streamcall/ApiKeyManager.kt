package ee.forgr.capacitor.streamcall

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Manages dynamic API keys for Stream Video.
 * Allows runtime override of the static API key configured in resources.
 */
object ApiKeyManager {
    private const val PREFS_NAME = "stream_call_prefs"
    private const val KEY_DYNAMIC_API_KEY = "dynamic_api_key"
    
    private fun getSharedPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    /**
     * Saves a dynamic API key to override the static one
     */
    fun saveDynamicApiKey(context: Context, apiKey: String) {
        getSharedPreferences(context).edit {
            putString(KEY_DYNAMIC_API_KEY, apiKey)
        }
    }
    
    /**
     * Retrieves the currently stored dynamic API key
     */
    fun getDynamicApiKey(context: Context): String? {
        return getSharedPreferences(context).getString(KEY_DYNAMIC_API_KEY, null)
    }
    
    /**
     * Gets the effective API key - dynamic if set, otherwise static from resources
     */
    fun getEffectiveApiKey(context: Context, staticApiKey: String): String {
        return getDynamicApiKey(context) ?: staticApiKey
    }
    
    /**
     * Clears the dynamic API key, falling back to static key
     */
    fun clearDynamicApiKey(context: Context) {
        getSharedPreferences(context).edit {
            remove(KEY_DYNAMIC_API_KEY)
        }
    }
    
    /**
     * Checks if a dynamic API key is currently set
     */
    fun hasDynamicApiKey(context: Context): Boolean {
        return getDynamicApiKey(context) != null
    }
} 
