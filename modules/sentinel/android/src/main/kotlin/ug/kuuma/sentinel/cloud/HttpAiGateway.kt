package ug.kuuma.sentinel.cloud

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.KSerializer
import ug.kuuma.contracts.AcceptedResponse
import ug.kuuma.contracts.AnalyseRequest
import ug.kuuma.contracts.AnalyseResponse
import ug.kuuma.contracts.ArtifactManifest
import ug.kuuma.contracts.ContractJson
import ug.kuuma.contracts.FeedbackRequest

/** Signs a request: returns the X-Kuuma-* headers (DeviceSigner.requestHeaders in core-android). */
fun interface RequestSigner {
    fun headers(method: String, path: String, body: ByteArray): Map<String, String>
}

/**
 * /v1/ai over HttpURLConnection (no extra libraries, works on Android 5). Bodies are the
 * generated contract types, so only redacted, contract-shaped data can be sent.
 */
class HttpAiGateway(
    private val baseUrl: String,
    private val signer: RequestSigner,
    private val timeoutMillis: Int = 15_000
) : AiGateway {
    override fun analyse(request: AnalyseRequest): AnalyseResponse =
        call("POST", "/v1/ai/analyse", request, AnalyseRequest.serializer(), AnalyseResponse.serializer())

    override fun feedback(request: FeedbackRequest) {
        call("POST", "/v1/ai/feedback", request, FeedbackRequest.serializer(), AcceptedResponse.serializer())
    }

    override fun latestLexicon(): ArtifactManifest =
        call("GET", "/v1/ai/lexicon/latest", null, null, ArtifactManifest.serializer())

    override fun download(url: String): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMillis
        conn.readTimeout = timeoutMillis * 4
        try {
            if (conn.responseCode !in 200..299) throw IOException("download failed: ${conn.responseCode}")
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    private fun <I, O> call(method: String, path: String, body: I?, inSer: KSerializer<I>?, outSer: KSerializer<O>): O {
        val bytes = if (body != null &&
            inSer != null
        ) {
            ContractJson.json.encodeToString(inSer, body).toByteArray()
        } else {
            ByteArray(0)
        }
        val conn = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = timeoutMillis
        conn.readTimeout = timeoutMillis
        conn.setRequestProperty("Accept", "application/json")
        signer.headers(method, path, bytes).forEach(conn::setRequestProperty)
        try {
            if (method != "GET") {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(bytes) }
            }
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("$method $path -> $code") // body deliberately not read into logs
            val out = ByteArrayOutputStream()
            conn.inputStream.use { it.copyTo(out) }
            return ContractJson.json.decodeFromString(outSer, out.toString("UTF-8"))
        } finally {
            conn.disconnect()
        }
    }
}
