package com.musicd.migrate

/**
 * An Http that answers from a scripted list and records what it was asked.
 *
 * The Kotlin counterpart of the fake `fetch` in test/unit/clients.test.js. It
 * is what lets the real conversion, paging, batching and rate-limit code be
 * driven on a plain JVM with no account, no phone and no internet.
 */
class FakeHttp(private val script: List<HttpResponse>) : Http {
    data class Call(val method: String, val url: String, val body: String?)

    val calls = ArrayList<Call>()
    val waits = ArrayList<Long>()
    private var index = 0

    override fun request(
        method: String, url: String, headers: Map<String, String>, body: ByteArray?,
        contentType: String?, timeoutMs: Int
    ): HttpResponse {
        calls.add(Call(method, url, body?.toString(Charsets.UTF_8)))
        // The last entry repeats, so a test that does not care how many
        // requests a batch makes does not have to script each one.
        val next = if (index < script.size - 1) script[index++] else script[script.size - 1]
        return next
    }

    companion object {
        fun res(status: Int, body: String, headers: Map<String, String> = emptyMap()) =
            HttpResponse(status, headers, body)
    }
}
