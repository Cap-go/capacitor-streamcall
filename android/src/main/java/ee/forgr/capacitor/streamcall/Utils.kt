package ee.forgr.capacitor.streamcall

fun String.toStreamCallId(): Pair<String, String>? {
    val parts = this.split(":")
    return if (parts.size == 2) {
        Pair(parts[0], parts[1])
    } else {
        null
    }
}
fun Any.toReadableString(): String {
    return when (this) {
        is String -> this
        is Int -> this.toString()
        is Double -> this.toString()
        is Float -> this.toString()
        is Long -> this.toString()
        is Boolean -> this.toString()
        else -> this.toString()
    }
} 
