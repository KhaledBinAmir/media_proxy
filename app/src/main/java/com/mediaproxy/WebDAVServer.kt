package com.mediaproxy

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.regex.Pattern

class WebDAVServer(
    private val port: Int,
    private val sources: List<Source>
) : NanoHTTPD(port) {

    companion object {
        const val TAG = "MediaProxy"
    }

    data class Source(val name: String, val url: String)

    data class IndexEntry(
        val displayName: String,
        val isDir: Boolean,
        val dateStr: String,
        val sizeStr: String
    )

    // ── Cache (lives until proxy restarts) ──

    private val indexCache = ConcurrentHashMap<String, List<IndexEntry>>()
    private val xmlCache = ConcurrentHashMap<String, String>()  // caches built PROPFIND XML responses
    private val prefetchExecutor = Executors.newSingleThreadExecutor()

    // ── URL encoding ──

    private fun encodeSegment(segment: String): String {
        return URLEncoder.encode(segment, "UTF-8")
            .replace("+", "%20")
            .replace("%21", "!")
            .replace("%27", "'")
            .replace("%28", "(")
            .replace("%29", ")")
            .replace("%7E", "~")
    }

    private fun encodePathSegments(decodedPath: String): String {
        return decodedPath.split("/").joinToString("/") { seg ->
            if (seg.isEmpty()) "" else encodeSegment(seg)
        }
    }

    private fun buildUpstreamUrl(decodedPath: String): String? {
        val trimmed = decodedPath.trimStart('/')
        if (trimmed.isEmpty()) return null

        val slashIdx = trimmed.indexOf('/')
        val sourceName = if (slashIdx >= 0) trimmed.substring(0, slashIdx) else trimmed
        val rest = if (slashIdx >= 0) trimmed.substring(slashIdx + 1) else ""

        val source = sources.find { it.name == sourceName } ?: return null
        val baseUrl = source.url.trimEnd('/')

        return if (rest.isEmpty()) {
            "$baseUrl/"
        } else {
            "$baseUrl/${encodePathSegments(rest)}"
        }
    }

    // ── Index parsing ──

    private val indexPattern: Pattern = Pattern.compile(
        """<a href="([^"]+)">([^<]+)</a>\s+(\d{2}-\w{3}-\d{4}\s+\d{2}:\d{2})\s+(-|\d[\d.]*\w?)"""
    )

    private fun fetchIndex(decodedPath: String): List<IndexEntry>? {
        val cacheKey = decodedPath.trim('/')

        // Check cache (permanent until restart)
        indexCache[cacheKey]?.let {
            Log.d(TAG, "fetchIndex CACHE HIT: $cacheKey (${it.size} entries)")
            return it
        }

        var url = buildUpstreamUrl(decodedPath) ?: return null
        if (!url.endsWith("/")) url += "/"

        Log.d(TAG, "fetchIndex: $url")

        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 30000
            conn.readTimeout = 180000
            conn.instanceFollowRedirects = true
            val code = conn.responseCode
            if (code != 200) {
                Log.w(TAG, "fetchIndex got $code for $url")
                conn.disconnect()
                return null
            }
            val html = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            val entries = mutableListOf<IndexEntry>()
            val matcher = indexPattern.matcher(html)
            while (matcher.find()) {
                val rawHref = matcher.group(1) ?: continue
                if (rawHref == "../") continue
                val displayName = try {
                    URLDecoder.decode(rawHref, "UTF-8")
                } catch (e: Exception) {
                    rawHref
                }
                val isDir = rawHref.endsWith("/")
                val dateStr = matcher.group(3) ?: ""
                val sizeStr = matcher.group(4) ?: "-"
                entries.add(IndexEntry(displayName, isDir, dateStr, sizeStr))
            }

            // Cache permanently (until proxy restarts)
            indexCache[cacheKey] = entries
            Log.d(TAG, "fetchIndex CACHED: $cacheKey (${entries.size} entries)")

            entries
        } catch (e: Exception) {
            Log.e(TAG, "fetchIndex error: ${e.message}")
            null
        }
    }

    /**
     * Pre-fetch source root directories in background when server starts.
     * This warms the cache so Nova's first request is instant.
     */
    fun prefetchSources() {
        prefetchExecutor.submit {
            Log.i(TAG, "Pre-fetching ${sources.size} source(s)...")
            for (source in sources) {
                try {
                    val dirPath = "${source.name}/"
                    val entries = fetchIndex(dirPath)
                    if (entries != null) {
                        Log.i(TAG, "Pre-fetched ${source.name}: ${entries.size} entries")

                        // Also pre-build the XML response so first PROPFIND is instant
                        val body = StringBuilder()
                        val hrefPath = "/${source.name}/"
                        body.append(xmlPropCollection(hrefPath, source.name))
                        for (entry in entries) {
                            val childName = entry.displayName.trimEnd('/')
                            val childHref = hrefPath + childName + (if (entry.isDir) "/" else "")
                            val lastMod = formatDate(entry.dateStr)
                            if (entry.isDir) {
                                body.append(xmlPropCollection(childHref, childName, lastMod))
                            } else {
                                val size = parseNginxSize(entry.sizeStr)
                                body.append(xmlPropFile(childHref, childName, size, guessMime(childName), lastMod))
                            }
                        }
                        val xml = wrapMultistatus(body.toString())
                        xmlCache["$dirPath|1"] = xml
                        Log.i(TAG, "Pre-built XML for ${source.name}: ${xml.length / 1024}KB")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Pre-fetch failed for ${source.name}: ${e.message}")
                }
            }
            Log.i(TAG, "Pre-fetch complete")
        }
    }

    // ── Nginx size parser ──

    /**
     * Convert nginx human-readable size to bytes.
     * nginx outputs: "5G", "250M", "1.2G", "500K", "12345" (raw bytes), or "-" (directory)
     */
    private fun parseNginxSize(sizeStr: String): String {
        if (sizeStr == "-") return ""
        val s = sizeStr.trim()
        if (s.isEmpty()) return ""

        // Pure number = already bytes
        s.toLongOrNull()?.let { return it.toString() }

        // Has suffix: K, M, G
        val numPart = s.dropLast(1).toDoubleOrNull() ?: return ""
        val bytes = when (s.last().uppercaseChar()) {
            'K' -> (numPart * 1024).toLong()
            'M' -> (numPart * 1024 * 1024).toLong()
            'G' -> (numPart * 1024 * 1024 * 1024).toLong()
            'T' -> (numPart * 1024 * 1024 * 1024 * 1024).toLong()
            else -> return ""
        }
        return bytes.toString()
    }

    // ── MIME types ──

    private val mimeMap = mapOf(
        "mp4" to "video/mp4",
        "mkv" to "video/x-matroska",
        "avi" to "video/x-msvideo",
        "mov" to "video/quicktime",
        "wmv" to "video/x-ms-wmv",
        "flv" to "video/x-flv",
        "webm" to "video/webm",
        "ts" to "video/mp2t",
        "m4v" to "video/mp4",
        "m2ts" to "video/mp2t",
        "mp3" to "audio/mpeg",
        "aac" to "audio/aac",
        "flac" to "audio/flac",
        "srt" to "text/plain",
        "ass" to "text/plain",
        "ssa" to "text/plain",
        "sub" to "text/plain",
        "idx" to "application/octet-stream",
        "nfo" to "text/plain",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png"
    )

    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return mimeMap[ext] ?: "application/octet-stream"
    }

    // ── XML helpers ──

    private fun esc(s: String): String {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;")
    }

    private fun formatDate(dateStr: String): String {
        return try {
            val parser = SimpleDateFormat("dd-MMM-yyyy HH:mm", Locale.ENGLISH)
            val date = parser.parse(dateStr)
            val formatter = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH)
            formatter.format(date!!)
        } catch (e: Exception) {
            ""
        }
    }

    // ── HEAD upstream (gets real file size) ──

    private fun headUpstream(decodedPath: String): Map<String, String>? {
        val url = buildUpstreamUrl(decodedPath) ?: return null
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "HEAD"
            conn.connectTimeout = 30000
            conn.readTimeout = 180000
            conn.instanceFollowRedirects = true
            conn.connect()

            if (conn.responseCode >= 400) {
                Log.w(TAG, "HEAD ${conn.responseCode} for $url")
                conn.disconnect()
                return null
            }

            val headers = mutableMapOf<String, String>()
            conn.getHeaderField("Content-Length")?.let { headers["Content-Length"] = it }
            conn.getHeaderField("Content-Type")?.let { headers["Content-Type"] = it }
            conn.getHeaderField("Accept-Ranges")?.let { headers["Accept-Ranges"] = it }
            conn.getHeaderField("Last-Modified")?.let { headers["Last-Modified"] = it }
            conn.disconnect()
            headers
        } catch (e: Exception) {
            Log.e(TAG, "headUpstream error for $url: ${e.message}")
            null
        }
    }

    // ── PROPFIND XML builders ──

    private fun xmlPropCollection(href: String, displayName: String, lastMod: String = ""): String {
        val sb = StringBuilder()
        sb.append("<D:response>")
        sb.append("<D:href>${esc(encodePathSegments(href))}</D:href>")
        sb.append("<D:propstat><D:prop>")
        sb.append("<D:resourcetype><D:collection/></D:resourcetype>")
        sb.append("<D:displayname>${esc(displayName)}</D:displayname>")
        if (lastMod.isNotEmpty()) {
            sb.append("<D:getlastmodified>$lastMod</D:getlastmodified>")
        }
        sb.append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>")
        sb.append("</D:response>")
        return sb.toString()
    }

    private fun xmlPropFile(href: String, displayName: String, contentLength: String, contentType: String, lastMod: String = ""): String {
        val sb = StringBuilder()
        sb.append("<D:response>")
        sb.append("<D:href>${esc(encodePathSegments(href))}</D:href>")
        sb.append("<D:propstat><D:prop>")
        sb.append("<D:resourcetype/>")
        sb.append("<D:displayname>${esc(displayName)}</D:displayname>")
        if (contentLength.isNotEmpty()) {
            sb.append("<D:getcontentlength>$contentLength</D:getcontentlength>")
        }
        sb.append("<D:getcontenttype>$contentType</D:getcontenttype>")
        if (lastMod.isNotEmpty()) {
            sb.append("<D:getlastmodified>$lastMod</D:getlastmodified>")
        }
        sb.append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>")
        sb.append("</D:response>")
        return sb.toString()
    }

    private fun wrapMultistatus(body: String): String {
        return """<?xml version="1.0" encoding="utf-8"?><D:multistatus xmlns:D="DAV:">$body</D:multistatus>"""
    }

    // ── Request handling ──

    override fun serve(session: IHTTPSession): Response {
        val method = session.method.name.uppercase()

        // NanoHTTPD QUIRK: For methods with a body (like PROPFIND),
        // NanoHTTPD requires parseBody() to be called, otherwise it
        // returns 400 Bad Request internally before we even get here.
        // We must consume the body to prevent this.
        if (method == "PROPFIND") {
            try {
                val files = HashMap<String, String>()
                session.parseBody(files)
            } catch (e: Exception) {
                // Ignore parse errors — we don't need the PROPFIND body
            }
        }

        // Decode the URI to get clean path
        val decodedPath = try {
            URLDecoder.decode(session.uri, "UTF-8")
        } catch (e: Exception) {
            session.uri
        }

        Log.d(TAG, "$method $decodedPath")

        return try {
            when (method) {
                "OPTIONS" -> handleOptions()
                "PROPFIND" -> handlePropfind(decodedPath, session)
                "GET" -> handleGet(decodedPath, session)
                "HEAD" -> handleHead(decodedPath)
                else -> newFixedLengthResponse(
                    Response.Status.METHOD_NOT_ALLOWED,
                    MIME_PLAINTEXT,
                    "Method not allowed"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling $method $decodedPath: ${e.message}", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                "Error: ${e.message}"
            )
        }
    }

    private fun handleOptions(): Response {
        val resp = newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "")
        resp.addHeader("Allow", "OPTIONS, GET, HEAD, PROPFIND")
        resp.addHeader("DAV", "1, 2")
        resp.addHeader("MS-Author-Via", "DAV")
        return resp
    }

    private fun handlePropfind(decodedPath: String, session: IHTTPSession): Response {
        val depth = session.headers["depth"] ?: "1"
        val trimmed = decodedPath.trim('/')

        // Root: show source folders
        if (trimmed.isEmpty()) {
            val body = StringBuilder()
            body.append(xmlPropCollection("/", "Media"))
            if (depth != "0") {
                for (source in sources) {
                    body.append(xmlPropCollection("/${source.name}/", source.name))
                }
            }
            return resp207(wrapMultistatus(body.toString()))
        }

        // Is this a path to a known source?
        val slashIdx = trimmed.indexOf('/')
        val sourceName = if (slashIdx >= 0) trimmed.substring(0, slashIdx) else trimmed
        if (sources.none { it.name == sourceName }) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Source not found: $sourceName")
        }

        // Determine if this looks like a file (has a media/known extension)
        val lastSegment = trimmed.substringAfterLast('/')
        val looksLikeFile = !decodedPath.endsWith("/") && lastSegment.contains('.') &&
            lastSegment.substringAfterLast('.').lowercase().let { ext ->
                ext in mimeMap || ext in setOf("txt", "nzb", "xml", "json", "log")
            }

        if (looksLikeFile) {
            // File path — try HEAD to get real info
            Log.d(TAG, "PROPFIND file: $decodedPath")
            val info = headUpstream(decodedPath)
            if (info != null) {
                val fileName = lastSegment
                val href = "/" + decodedPath.trimStart('/')
                val body = xmlPropFile(
                    href,
                    fileName,
                    info["Content-Length"] ?: "",
                    guessMime(fileName),
                    info["Last-Modified"] ?: ""
                )
                return resp207(wrapMultistatus(body))
            }
            // HEAD failed — maybe it's actually a directory with a dot in the name
            Log.d(TAG, "HEAD failed for file-like path, trying as directory")
        }

        // Directory path — fetch index listing
        val dirPath = if (decodedPath.endsWith("/")) decodedPath else "$decodedPath/"
        val entries = fetchIndex(dirPath)

        if (entries != null) {
            val xmlCacheKey = "$dirPath|$depth"

            // Check XML cache — avoids rebuilding massive XML on repeat requests
            xmlCache[xmlCacheKey]?.let {
                Log.d(TAG, "XML CACHE HIT: $xmlCacheKey")
                return resp207(it)
            }

            val body = StringBuilder(entries.size * 300)  // pre-allocate
            val hrefPath = "/" + dirPath.trim('/') + "/"
            val dirName = dirPath.trimEnd('/').substringAfterLast('/').ifEmpty { sourceName }
            body.append(xmlPropCollection(hrefPath, dirName))

            val isLargeDir = entries.size > 500

            if (depth != "0") {
                for (entry in entries) {
                    val childName = entry.displayName.trimEnd('/')
                    val childHref = hrefPath + childName + (if (entry.isDir) "/" else "")
                    // Skip date parsing for huge directories — saves CPU
                    val lastMod = if (isLargeDir) "" else formatDate(entry.dateStr)

                    if (entry.isDir) {
                        // Minimal XML for large dirs — just href + displayname + collection
                        if (isLargeDir) {
                            body.append("<D:response><D:href>${esc(encodePathSegments(childHref))}</D:href><D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype><D:displayname>${esc(childName)}</D:displayname></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>")
                        } else {
                            body.append(xmlPropCollection(childHref, childName, lastMod))
                        }
                    } else {
                        val size = parseNginxSize(entry.sizeStr)
                        body.append(xmlPropFile(childHref, childName, size, guessMime(childName), lastMod))
                    }
                }
            }

            val xml = wrapMultistatus(body.toString())
            xmlCache[xmlCacheKey] = xml
            Log.d(TAG, "XML CACHED: $xmlCacheKey (${xml.length / 1024}KB)")
            return resp207(xml)
        }

        // Last resort: try HEAD as file (for paths without known extensions)
        if (!looksLikeFile) {
            val info = headUpstream(decodedPath)
            if (info != null) {
                val fileName = lastSegment
                val href = "/" + decodedPath.trimStart('/')
                val body = xmlPropFile(
                    href,
                    fileName,
                    info["Content-Length"] ?: "",
                    guessMime(fileName),
                    info["Last-Modified"] ?: ""
                )
                return resp207(wrapMultistatus(body))
            }
        }

        return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
    }

    private fun handleGet(decodedPath: String, session: IHTTPSession): Response {
        val url = buildUpstreamUrl(decodedPath)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")

        Log.d(TAG, "GET streaming: $url")

        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 0
        conn.instanceFollowRedirects = true

        // Forward range header for seeking
        val range = session.headers["range"]
        if (range != null) {
            conn.setRequestProperty("Range", range)
            Log.d(TAG, "GET Range: $range")
        }

        conn.connect()
        val status = conn.responseCode

        Log.d(TAG, "GET upstream status: $status")

        if (status >= 400) {
            val errBody = try { conn.errorStream?.bufferedReader()?.readText() ?: "" } catch (e: Exception) { "" }
            conn.disconnect()
            return newFixedLengthResponse(
                Response.Status.lookup(status) ?: Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                "Upstream $status"
            )
        }

        val inputStream: InputStream = conn.inputStream
        val contentLength = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
        val contentType = guessMime(decodedPath)

        val nanoStatus = Response.Status.lookup(status) ?: Response.Status.OK

        val resp: Response
        if (contentLength >= 0) {
            resp = newFixedLengthResponse(nanoStatus, contentType, inputStream, contentLength)
        } else {
            resp = newChunkedResponse(nanoStatus, contentType, inputStream)
        }

        // Headers critical for video playback
        resp.addHeader("Accept-Ranges", "bytes")
        conn.getHeaderField("Content-Range")?.let { resp.addHeader("Content-Range", it) }
        conn.getHeaderField("Last-Modified")?.let { resp.addHeader("Last-Modified", it) }
        // Ensure no caching issues
        resp.addHeader("Cache-Control", "no-cache")

        return resp
    }

    private fun handleHead(decodedPath: String): Response {
        val info = headUpstream(decodedPath) ?: return newFixedLengthResponse(
            Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found"
        )

        val contentType = guessMime(decodedPath)
        // Return empty body but with correct headers
        val resp = newFixedLengthResponse(Response.Status.OK, contentType, "")
        info["Content-Length"]?.let { resp.addHeader("Content-Length", it) }
        resp.addHeader("Accept-Ranges", "bytes")
        info["Last-Modified"]?.let { resp.addHeader("Last-Modified", it) }
        return resp
    }

    private fun resp207(xml: String): Response {
        return newFixedLengthResponse(
            Response.Status.lookup(207),
            "application/xml; charset=utf-8",
            xml
        )
    }
}
