package li.mof.kamigura

import android.util.Log

object KamiguraLog {
    private const val Tag = "Kamigura"

    fun w(message: String, throwable: Throwable? = null) {
        if (throwable == null) {
            Log.w(Tag, message)
        } else {
            Log.w(Tag, message, throwable)
        }
    }
}
