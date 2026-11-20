package app.kaizen.database

import android.net.Uri
import androidx.room.TypeConverter
import app.kaizen.commons.SharedGson
import com.google.gson.reflect.TypeToken
import app.kaizen.videoservers.Headers

class BaseConverter {
    @TypeConverter
    fun booleanToInt(b: Boolean): Int {
        return if (b) 1 else 0
    }

    @TypeConverter
    fun intToBoolean(i: Int): Boolean {
        return i == 1
    }

    @TypeConverter
    fun uriToString(uri: Uri): String {
        return uri.toString()
    }

    @TypeConverter
    fun stringToUri(s: String): Uri {
        return Uri.parse(s)
    }

    @TypeConverter
    fun headersToString(headers: Headers?): String {
        return SharedGson.gson.toJson(headers, HEADERS_TYPE)
    }

    @TypeConverter
    fun stringToHeader(json: String?): Headers? {
        return SharedGson.gson.fromJson<Headers>(json, HEADERS_TYPE)
    }

    private companion object {
        val HEADERS_TYPE: java.lang.reflect.Type = object : TypeToken<Headers>() {}.type
    }
}
