package ee.forgr.capacitor.streamcall

import com.getcapacitor.JSObject
import org.json.JSONArray
import org.json.JSONObject

fun JSONObject.toMap(): Map<String, Any> {
    val map = mutableMapOf<String, Any>()
    val keys = this.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        var value = this.get(key)

        // Handle nested JSONObjects and JSONArrays
        value = when (value) {
            is JSONObject -> value.toMap()
            is JSONArray -> value.toList()
            else -> value
        }

        map[key] = value
    }
    return map
}

// Helper for JSONArray
fun JSONArray.toList(): List<Any> {
    val list = mutableListOf<Any>()
    for (i in 0 until this.length()) {
        var value = this.get(i)
        value = when (value) {
            is JSONObject -> value.toMap()
            is JSONArray -> value.toList()
            else -> value
        }
        list.add(value)
    }
    return list
}

// Extension for Capacitor JSObject
fun JSObject.toMap(): Map<String, Any> {
    val map = mutableMapOf<String, Any>()
    val keys = this.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        var value = this.get(key)

        // Handle nested objects and arrays
        value = when (value) {
            is JSObject -> value.toMap()
            is JSONObject -> value.toMap()
            is JSONArray -> value.toList()
            else -> value
        }

        map[key] = value
    }
    return map
}
