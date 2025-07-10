package ee.forgr.capacitor.streamcall

import android.content.Context
import androidx.core.content.edit

object ApiKeyManager {

    private const val API_KEY_PREFS_NAME = "stream_video_api_key_prefs"
    private const val DYNAMIC_API_KEY_PREF = "dynamic_api_key"

    fun saveDynamicApiKey(context: Context, apiKey: String) {
        val sharedPrefs = context.getSharedPreferences(API_KEY_PREFS_NAME, Context.MODE_PRIVATE)
        sharedPrefs.edit {
            putString(DYNAMIC_API_KEY_PREF, apiKey)
        }
    }

    fun getDynamicApiKey(context: Context): String? {
        val sharedPrefs = context.getSharedPreferences(API_KEY_PREFS_NAME, Context.MODE_PRIVATE)
        return sharedPrefs.getString(DYNAMIC_API_KEY_PREF, null)
    }

    fun getEffectiveApiKey(context: Context): String {
        val dynamicApiKey = getDynamicApiKey(context)
        return if (!dynamicApiKey.isNullOrEmpty() && dynamicApiKey.trim().isNotEmpty()) {
            dynamicApiKey
        } else {
            context.getString(R.string.CAPACITOR_STREAM_VIDEO_APIKEY)
        }
    }
} 
