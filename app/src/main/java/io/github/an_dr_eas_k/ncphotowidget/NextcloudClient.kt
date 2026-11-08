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
import okhttp3.Response
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.InputStream
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.concurrent.TimeUnit

class HttpException(val code: Int) : IOException("HTTP $code")

private val http = OkHttpClient.Builder()
    .callTimeout(60, TimeUnit.SECONDS)
    .build()

// Scans of large libraries can take long; only a server that sends nothing for two minutes is given up on.
private val scanHttp = http.newBuilder()
    .callTimeout(0, TimeUnit.SECONDS)
    .readTimeout(120, TimeUnit.SECONDS)
    .build()

// A picture is a single small download; a slow server should not hold the widget up for long.
private val previewHttp = http.newBuilder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .callTimeout(25, TimeUnit.SECONDS)
    .build()

private const val USER_AGENT = "NextcloudPhotoWidget"

private fun <T> call(request: Request.Builder, client: OkHttpClient = http, tag: Any? = null, read: (Response) -> T): T =
    client.newCall(request.header("User-Agent", USER_AGENT).tag(tag).build()).execute().use { response ->
        if (!response.isSuccessful) throw HttpException(response.code)
        read(response)
    }

class NextcloudClient(private val settings: Settings) {
    /** Aborts this client's requests that are still running, e.g. when the job using them is stopped. */
    fun cancel() {
        val dispatcher = http.dispatcher
        (dispatcher.runningCalls() + dispatcher.queuedCalls()).filter { it.request().tag() === this }.forEach { it.cancel() }
    }

    private val auth = Credentials.basic(settings.username, settings.appPassword)
    private val base = settings.serverUrl.toHttpUrl()

    private class Entry(val href: String, val isDir: Boolean, val contentType: String, val fileId: Long, val time: LocalDateTime?) {
        val day: Int get() = time?.toLocalDate()?.toEpochDay()?.toInt() ?: NO_DAY
    }

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
    fun listImageIds(
        folders: List<String>,
        tags: List<Tag> = emptyList(),
        wanted: (day: Int) -> Boolean = { true },
        onFirstImage: ((Long) -> Unit)? = null,
        onProgress: ((folders: Int, ids: Collection<Long>) -> Unit)? = null,
        isStopped: () -> Boolean = { false },
        preFiltered: MutableSet<Long>? = null,
    ): LongArray {
        // A set, so overlapping folders do not count a photo twice. Only photos that pass [wanted] get in.
        val ids = LinkedHashSet<Long>()
        var notified = false
        var scanned = 0
        if (tags.isNotEmpty()) {
            listTaggedImageIds(folders, tags).filter { wanted(it.day) }.forEach {
                ids.add(it.fileId)
                if (isSnapseed(it.href)) preFiltered?.add(it.fileId)
            }
            onProgress?.invoke(1, ids)
            if (ids.isNotEmpty()) onFirstImage?.invoke(ids.random())
            return ids.toLongArray()
        }
        val queue = ArrayDeque<HttpUrl>()
        folders.forEach { queue.add(folderUrl(it)) }
        while (queue.isNotEmpty()) {
            if (isStopped()) throw IOException("Cancelled")
            val url = queue.removeFirst()
            for (e in propfind(url)) {
                if (e.isDir) {
                    url.resolve(e.href)?.let { queue.add(it) }
                } else if (e.contentType.startsWith("image/") && e.fileId > 0) {
                    if (wanted(e.day)) {
                        ids.add(e.fileId)
                        if (isSnapseed(e.href)) preFiltered?.add(e.fileId)
                    }
                    if (!notified && ids.size >= EARLY_POOL) {
                        notified = true
                        onFirstImage?.invoke(ids.random())
                    }
                }
            }
            onProgress?.invoke(++scanned, ids)
        }
        // Fewer photos than the pool size: pick from all of them.
        if (!notified && ids.isNotEmpty()) onFirstImage?.invoke(ids.random())
        return ids.toLongArray()
    }

    /** All system tags the user can see, sorted case-insensitively. */
    fun listTags(): List<Tag> {
        val url = base.newBuilder().addPathSegments("remote.php/dav/systemtags/").build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", auth)
            .header("Depth", "1")
            .method("PROPFIND", TAGS_BODY.toRequestBody("application/xml".toMediaType()))
        val tags = call(request, scanHttp, this) { response ->
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setInput(response.body!!.byteStream(), null)
            val found = ArrayList<Tag>()
            var id = -1L
            var name = ""
            var visible = true
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "response" -> { id = -1L; name = ""; visible = true }
                        "id" -> id = parser.nextText().toLongOrNull() ?: -1L
                        "display-name" -> name = parser.nextText()
                        "user-visible" -> visible = parser.nextText() != "false"
                    }
                } else if (event == XmlPullParser.END_TAG && parser.name == "response" && id >= 0 && visible) {
                    found.add(Tag(id, name))
                }
                event = parser.next()
            }
            found
        }
        return tags.sortedBy { it.name.lowercase() }
    }

    /**
     * Images that have every required tag and, when there are alternatives, at least one of them.
     * The server only ANDs tags, so each alternative is a search of its own and the results are joined.
     */
    private fun listTaggedImageIds(folders: List<String>, tags: List<Tag>): List<Entry> {
        val required = tags.filter { it.required }
        val alternatives = tags.filter { !it.required }
        if (alternatives.isEmpty()) return searchTagged(folders, required)
        return alternatives.flatMap { searchTagged(folders, required + it) }.distinctBy { it.fileId }
    }

    /** Images carrying all [tags] (a server-side search over all files) that lie inside one of [folders]. */
    private fun searchTagged(folders: List<String>, tags: List<Tag>): List<Entry> {
        val rules = tags.joinToString("") { "<oc:systemtag>${it.id}</oc:systemtag>" }
        val body = """<?xml version="1.0"?>
<oc:filter-files xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns">
  <d:prop><oc:fileid/><d:getcontenttype/><d:resourcetype/><d:getlastmodified/><nc:metadata-photos-original_date_time/></d:prop>
  <oc:filter-rules>$rules</oc:filter-rules>
</oc:filter-files>"""
        val url = base.newBuilder()
            .addPathSegments("remote.php/dav/files")
            .addPathSegment(settings.username)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", auth)
            .method("REPORT", body.toRequestBody("application/xml".toMediaType()))
        val prefixes = folders.map { Uri.decode(folderUrl(it).encodedPath).trimEnd('/') + "/" }
        return call(request, scanHttp, this) { parseMultiStatus(it.body!!.byteStream()) }
            .filter { e ->
                !e.isDir && e.contentType.startsWith("image/") && e.fileId > 0 &&
                    Uri.decode(e.href).let { href -> prefixes.any { href.startsWith(it) } }
            }
    }

    private fun propfind(url: HttpUrl): List<Entry> {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", auth)
            .header("Depth", "1")
            .method("PROPFIND", PROPFIND_BODY.toRequestBody("application/xml".toMediaType()))
        // The first response element is the requested folder itself.
        return call(request, scanHttp, this) { parseMultiStatus(it.body!!.byteStream()).drop(1) }
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
        var taken: LocalDateTime? = null
        var modified: LocalDateTime? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "response" -> { href = ""; isDir = false; type = ""; fileId = -1L; taken = null; modified = null }
                    "href" -> href = parser.nextText()
                    "getcontenttype" -> type = parser.nextText()
                    "fileid" -> fileId = parser.nextText().toLongOrNull() ?: -1L
                    "collection" -> isDir = true
                    "getlastmodified" -> modified = httpTime(parser.nextText())
                    "metadata-photos-original_date_time" -> taken = unixTime(parser.nextText())
                }
            } else if (event == XmlPullParser.END_TAG && parser.name == "response") {
                // The date taken is what the Photos app records; the file date is the fallback.
                entries.add(Entry(href, isDir, type, fileId, taken ?: modified))
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
        return call(Request.Builder().url(url).header("Authorization", auth), previewHttp, this) { it.body!!.bytes() }
    }

    /** Pictures saved by Snapseed already carry a look of their own, so they are shown without our filter. */
    private fun isSnapseed(href: String) =
        Uri.decode(href.trimEnd('/').substringAfterLast('/')).contains("snapseed", ignoreCase = true)

    /** Local time of an HTTP date such as "Mon, 02 Jan 2023 10:00:00 GMT". */
    private fun httpTime(text: String): LocalDateTime? = try {
        ZonedDateTime.parse(text.trim(), DateTimeFormatter.RFC_1123_DATE_TIME)
            .withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()
    } catch (e: DateTimeParseException) {
        null
    }

    /** Time of a Unix time in seconds. The Photos app stores the clock time of the picture as if it were UTC. */
    private fun unixTime(text: String): LocalDateTime? =
        text.trim().toLongOrNull()?.let { LocalDateTime.ofEpochSecond(it, 0, ZoneOffset.UTC) }

    /** When the photo was taken, or else last modified; null if the server does not know the file. */
    fun fetchDateTime(fileId: Long): LocalDateTime? {
        val body = """<?xml version="1.0"?>
<d:searchrequest xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns">
  <d:basicsearch>
    <d:select><d:prop><d:getlastmodified/><nc:metadata-photos-original_date_time/></d:prop></d:select>
    <d:from><d:scope><d:href>/files/${Uri.encode(settings.username)}</d:href><d:depth>infinity</d:depth></d:scope></d:from>
    <d:where><d:eq><d:prop><oc:fileid/></d:prop><d:literal>$fileId</d:literal></d:eq></d:where>
  </d:basicsearch>
</d:searchrequest>"""
        val request = Request.Builder()
            .url(base.newBuilder().addPathSegments("remote.php/dav/").build())
            .header("Authorization", auth)
            .method("SEARCH", body.toRequestBody("text/xml".toMediaType()))
        return call(request, http, this) { parseMultiStatus(it.body!!.byteStream()) }.firstOrNull { !it.isDir }?.time
    }

    private companion object {
        const val EARLY_POOL = 50
        const val TAGS_BODY = """<?xml version="1.0"?>
<d:propfind xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
  <d:prop><oc:id/><oc:display-name/><oc:user-visible/></d:prop>
</d:propfind>"""
        const val PROPFIND_BODY = """<?xml version="1.0"?>
<d:propfind xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns">
  <d:prop><oc:fileid/><d:getcontenttype/><d:resourcetype/><d:getlastmodified/><nc:metadata-photos-original_date_time/></d:prop>
</d:propfind>"""
    }
}

/** Nextcloud Login Flow v2: yields a revocable app password, so the real password is never stored. */
object LoginFlow {
    class Session(val loginUrl: String, val pollUrl: String, val token: String)
    class Result(val username: String, val appPassword: String)

    fun start(serverUrl: String): Session {
        val base = serverUrl.toHttpUrl()
        val url = base.newBuilder().addPathSegments("index.php/login/v2").build()
        val json = call(Request.Builder().url(url).post(FormBody.Builder().build())) { JSONObject(it.body!!.string()) }
        val poll = json.getJSONObject("poll")
        // Poll on the host the user typed: the server may report an internal host name.
        val pollUrl = poll.getString("endpoint").toHttpUrl().newBuilder()
            .scheme(base.scheme).host(base.host).port(base.port).build()
        return Session(json.getString("login"), pollUrl.toString(), poll.getString("token"))
    }

    /** Returns null until the user has finished logging in. */
    fun poll(session: Session): Result? {
        val request = Request.Builder()
            .url(session.pollUrl)
            .post(FormBody.Builder().add("token", session.token).build())
        val json = try {
            call(request) { JSONObject(it.body!!.string()) }
        } catch (e: HttpException) {
            if (e.code == 404) return null
            throw e
        }
        return Result(json.getString("loginName"), json.getString("appPassword"))
    }
}
