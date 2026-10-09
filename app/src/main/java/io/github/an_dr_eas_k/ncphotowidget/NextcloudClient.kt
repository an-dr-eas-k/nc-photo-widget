package io.github.an_dr_eas_k.ncphotowidget

import android.net.Uri
import android.util.Xml
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

class HttpException(val code: Int) : IOException("HTTP $code")

private val http = OkHttpClient.Builder()
    .callTimeout(60, TimeUnit.SECONDS)
    .build()

private const val USER_AGENT = "NextcloudPhotoWidget"

class NextcloudClient(private val settings: Settings) {
    private val auth = Credentials.basic(settings.username, settings.appPassword)
    private val base = settings.serverUrl.toHttpUrl()

    private class Entry(val href: String, val isDir: Boolean, val contentType: String, val fileId: Long)

    private fun folderUrl(folder: String): HttpUrl {
        val url = base.newBuilder()
            .addPathSegments("remote.php/dav/files")
            .addPathSegment(settings.username)
        folder.split('/').filter { it.isNotEmpty() }.forEach { url.addPathSegment(it) }
        return url.build()
    }

    /** Names of the direct subfolders of [folder], sorted case-insensitively. */
    fun listSubfolders(folder: String): List<String> =
        propfind(folderUrl(folder))
            .filter { it.isDir }
            .map { Uri.decode(it.href.trimEnd('/').substringAfterLast('/')) }
            .sortedBy { it.lowercase() }

    /** Walks [folders] recursively (Depth: 1 per folder, since Depth: infinity is usually disabled). */
    fun listImageIds(folders: List<String>): LongArray {
        // A set, so overlapping folders do not count a photo twice.
        val ids = LinkedHashSet<Long>()
        val queue = ArrayDeque<HttpUrl>()
        folders.forEach { queue.add(folderUrl(it)) }
        while (queue.isNotEmpty()) {
            val url = queue.removeFirst()
            for (e in propfind(url)) {
                if (e.isDir) {
                    url.resolve(e.href)?.let { queue.add(it) }
                } else if (e.contentType.startsWith("image/") && e.fileId > 0) {
                    ids.add(e.fileId)
                }
            }
        }
        return ids.toLongArray()
    }

    private fun propfind(url: HttpUrl): List<Entry> {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", auth)
            .header("User-Agent", USER_AGENT)
            .header("Depth", "1")
            .method("PROPFIND", PROPFIND_BODY.toRequestBody("application/xml".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HttpException(response.code)
            // The first response element is the requested folder itself.
            return parseMultiStatus(response.body!!.byteStream()).drop(1)
        }
    }

    private fun parseMultiStatus(stream: InputStream): List<Entry> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(stream, null)

        val entries = ArrayList<Entry>()
        var href = ""
        var isDir = false
        var type = ""
        var fileId = -1L
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "response" -> { href = ""; isDir = false; type = ""; fileId = -1L }
                    "href" -> href = parser.nextText()
                    "getcontenttype" -> type = parser.nextText()
                    "fileid" -> fileId = parser.nextText().toLongOrNull() ?: -1L
                    "collection" -> isDir = true
                }
            } else if (event == XmlPullParser.END_TAG && parser.name == "response") {
                entries.add(Entry(href, isDir, type, fileId))
            }
            event = parser.next()
        }
        return entries
    }

    /** Returns a downscaled preview so the bitmap stays small enough for RemoteViews. */
    fun fetchPreview(fileId: Long, size: Int = 1024): ByteArray {
        val url = base.newBuilder()
            .addPathSegments("index.php/core/preview")
            .addQueryParameter("fileId", fileId.toString())
            .addQueryParameter("x", size.toString())
            .addQueryParameter("y", size.toString())
            .addQueryParameter("a", "true")
            .addQueryParameter("forceIcon", "0")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", auth)
            .header("User-Agent", USER_AGENT)
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HttpException(response.code)
            return response.body!!.bytes()
        }
    }

    private companion object {
        const val PROPFIND_BODY = """<?xml version="1.0"?>
<d:propfind xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
  <d:prop><oc:fileid/><d:getcontenttype/><d:resourcetype/></d:prop>
</d:propfind>"""
    }
}

/** Nextcloud Login Flow v2: yields a revocable app password, so the real password is never stored. */
object LoginFlow {
    class Session(val loginUrl: String, val pollUrl: String, val token: String)
    class Result(val server: String, val username: String, val appPassword: String)

    fun start(serverUrl: String): Session {
        val url = serverUrl.toHttpUrl().newBuilder().addPathSegments("index.php/login/v2").build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .post(FormBody.Builder().build())
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HttpException(response.code)
            val json = JSONObject(response.body!!.string())
            val poll = json.getJSONObject("poll")
            // Poll on the host the user typed: the server may report an internal host name.
            val base = serverUrl.toHttpUrl()
            val pollUrl = poll.getString("endpoint").toHttpUrl().newBuilder()
                .scheme(base.scheme).host(base.host).port(base.port).build()
            return Session(json.getString("login"), pollUrl.toString(), poll.getString("token"))
        }
    }

    /** Returns null until the user has finished logging in. */
    fun poll(session: Session): Result? {
        val request = Request.Builder()
            .url(session.pollUrl)
            .header("User-Agent", USER_AGENT)
            .post(FormBody.Builder().add("token", session.token).build())
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) throw HttpException(response.code)
            val json = JSONObject(response.body!!.string())
            return Result(json.getString("server"), json.getString("loginName"), json.getString("appPassword"))
        }
    }
}
