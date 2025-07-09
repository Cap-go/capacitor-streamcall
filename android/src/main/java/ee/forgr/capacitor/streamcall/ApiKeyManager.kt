package ee.forgr.capacitor.streamcall

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

object ApiKeyManager {

    private const val API_KEY_PREFS_NAME = "stream_video_api_key_prefs"
    private const val DYNAMIC_API_KEY_PREF = "dynamic_api_key"

    private lateinit var context: Context
    private val sharedPrefs: SharedPreferences by lazy {
        context.getSharedPreferences(API_KEY_PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun initialize(context: Context) {
        this.context = context.applicationContext
    }

    fun saveDynamicApiKey(apiKey: String) {
        sharedPrefs.edit {
            putString(DYNAMIC_API_KEY_PREF, apiKey)
        }
    }

    fun getDynamicApiKey(): String? {
        return sharedPrefs.getString(DYNAMIC_API_KEY_PREF, null)
    }

    fun getEffectiveApiKey(): String {
        val dynamicApiKey = getDynamicApiKey()
        return if (!dynamicApiKey.isNullOrEmpty() && dynamicApiKey.trim().isNotEmpty()) {
            dynamicApiKey
        } else {
            context.getString(R.string.CAPACITOR_STREAM_VIDEO_APIKEY)
        }
    }
} 
