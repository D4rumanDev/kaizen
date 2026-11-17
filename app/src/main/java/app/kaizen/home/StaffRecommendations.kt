package app.kaizen.home

import app.kaizen.tv.ui.components.*

import app.kaizen.commons.logError
import com.google.gson.Gson
import app.kaizen.App
import app.kaizen.pojos.av1.Recommended
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object StaffRecommendations {
    suspend fun createList(count: Int = 10): List<Recommended> {
        try {
            val json = withContext(Dispatchers.IO) {
                App.context.assets.open("suggestions.json").bufferedReader().use { it.readText() }
            }
            val suggestions = Gson().fromJson(json, Array<Recommended>::class.java)
            // La nota no se pide aquí: cada tarjeta la pide al pintarse (RecommendedCard en KaizenUi.kt).
            return suggestions.toList().shuffled().take(count)
        } catch (e: Exception) {
            logError(e)
            return emptyList()
        }
    }
}