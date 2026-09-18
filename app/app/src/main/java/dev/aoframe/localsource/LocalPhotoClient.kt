package dev.aoframe.localsource

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HTTP client for the AOFrame reference server's CRUD photo API
 * (frameo/AOFrame/server/routes/photos.js) - the storage backend for
 * `photoSource: local` mode (see ImmichSecrets.photoSource). Mirrors
 * ImmichClient's narrow list+download surface, just against a much
 * simpler API with no auth/pagination/face-query concepts.
 */
class LocalPhotoClient(private val baseUrl: String) {
    data class LocalPhoto(val id: String, val mimeType: String)

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun fetchPhotos(): List<LocalPhoto> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$baseUrl/api/photos").get().build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Local server ${response.code}: $text")
            }
            val array = JSONArray(text)
            (0 until array.length()).map { index ->
                val obj = array.getJSONObject(index)
                LocalPhoto(id = obj.getString("id"), mimeType = obj.optString("mimeType", "image/jpeg"))
            }
        }
    }

    suspend fun downloadToFile(id: String, destination: File) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$baseUrl/api/photos/$id").get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Local server ${response.code} downloading photo $id")
            }
            val body = response.body ?: throw IOException("Empty body downloading photo $id")
            destination.parentFile?.mkdirs()
            body.byteStream().use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }
}
