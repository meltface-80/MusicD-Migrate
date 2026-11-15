package com.musicd.migrate

import com.musicd.migrate.api.MigrateApi
import com.musicd.migrate.http.Assets
import com.musicd.migrate.http.Request
import org.json.JSONObject

/** What the API test classes share: a MigrateApi over fake assets, the
 *  requests the page makes, and a store signed in to both services. */
abstract class ApiFixtures {

    protected class FakeAssets(val files: Map<String, String>) : Assets {
        override fun read(path: String): ByteArray? = files[path]?.toByteArray()
    }

    protected fun api(store: Store = MemoryStore(), http: Http = FakeHttp(emptyList())) =
        MigrateApi(store, FakeAssets(mapOf(
            "web/index.html" to "<!doctype html><title>Mandarin Migration</title>",
            "web/app.js" to "console.log(1)")), http, "0.1.0")

    protected fun get(api: MigrateApi, path: String): JSONObject {
        val q = if (path.contains("?")) path.substringAfter("?") else ""
        val res = api.handle(Request("GET", path.substringBefore("?"),
            com.musicd.migrate.http.HttpServer.parseQuery(q),
            mapOf("host" to "127.0.0.1:3380"), ByteArray(0), false, "127.0.0.1", path))
        return JSONObject(res.body.toString(Charsets.UTF_8))
    }

    protected fun post(api: MigrateApi, path: String, body: String = "{}"):
        com.musicd.migrate.http.Response =
        api.handle(Request("POST", path, emptyMap(), mapOf("host" to "127.0.0.1:3380"),
            body.toByteArray(), false, "127.0.0.1", path))

    protected fun raw(api: MigrateApi, method: String, path: String) =
        api.handle(Request(method, path.substringBefore("?"),
            com.musicd.migrate.http.HttpServer.parseQuery(
                if (path.contains("?")) path.substringAfter("?") else ""),
            mapOf("host" to "127.0.0.1:3380"), ByteArray(0), false, "127.0.0.1", path))

    protected fun signedIn(): MemoryStore {
        val store = MemoryStore()
        store.putSetting("qobuz.session", JSONObject()
            .put("token", "t").put("userId", "u").put("appId", "a")
            .put("name", "Me").toString())
        return store
    }

    /** Upload a library file the way the page does: the raw bytes as the body,
     *  the file name in the query. */
    protected fun upload(api: MigrateApi, csv: String, name: String = "library.csv") =
        api.handle(Request("POST", "/api/import/file", mapOf("name" to name),
            mapOf("host" to "127.0.0.1:3380", "content-type" to "text/csv"),
            csv.toByteArray(Charsets.UTF_8), false, "127.0.0.1",
            "/api/import/file?name=$name"))

    /** A small library: two albums, one with a barcode, one with a listing. */
    protected val SMALL_LIBRARY = """album artist,album,track,title,barcode
Metallica,Master Of Puppets,1,Battery,075992736121
Metallica,Master Of Puppets,2,Master Of Puppets,075992736121
Miles Davis,Kind Of Blue,1,So What,
Miles Davis,Kind Of Blue,2,Freddie Freeloader,
"""
}
