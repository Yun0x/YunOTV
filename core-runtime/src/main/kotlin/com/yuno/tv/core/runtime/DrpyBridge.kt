package com.yuno.tv.core.runtime

import com.google.gson.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Returns only strings/JSON. A DOM node or Java object never crosses into the JS scope. */
class HtmlParserBridge {
    fun select(html: String, selector: String, operation: String): String {
        require(html.toByteArray().size <= RuntimePolicy.HTTP_BYTES)
        var elements = listOf<Element>(Jsoup.parse(html))
        val steps = selector.split("&&")
        val attr = if (operation == "pdfh" || operation == "pd") steps.last() else ""
        val selectors = if (attr.isNotEmpty()) steps.dropLast(1) else steps
        selectors.filter { it.isNotBlank() }.forEach { css ->
            elements = elements.flatMap { it.select(css).take(500) }.take(500)
        }
        if (operation == "pdfa") return Gson().toJson(elements.map { it.outerHtml() })
        val element = elements.firstOrNull() ?: return ""
        return when (attr.lowercase()) { "text" -> element.text(); "html" -> element.html(); "" -> element.outerHtml(); else -> element.attr(attr) }
    }
}
class CryptoBridge {
    fun digest(algorithm: String, value: String) = MessageDigest.getInstance(algorithm).digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    fun base64(value: String, decode: Boolean) = if (decode) String(Base64.getDecoder().decode(value), Charsets.UTF_8) else Base64.getEncoder().encodeToString(value.toByteArray())
    fun aes(value: String, key: String, iv: String, encrypt: Boolean, mode: String = "CBC"): String {
        require(mode in listOf("CBC", "ECB"))
        val cipher = Cipher.getInstance("AES/$mode/PKCS5Padding")
        val secret = SecretKeySpec(key.toByteArray(), "AES")
        if (mode == "CBC") cipher.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, secret, IvParameterSpec(iv.toByteArray()))
        else cipher.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, secret)
        return if (encrypt) Base64.getEncoder().encodeToString(cipher.doFinal(value.toByteArray())) else String(cipher.doFinal(Base64.getDecoder().decode(value)), Charsets.UTF_8)
    }
}
class DrpyBridge(private val host: RuntimeHost) {
    private val html = HtmlParserBridge()
    private val crypto = CryptoBridge()
    fun call(name: String, arguments: List<String>): String {
        fun a(index: Int) = arguments.getOrElse(index) { "" }
        return when (name) {
            "http" -> host.http(a(0), a(1).ifBlank { "{}" })
            "get", "set", "remove" -> host.storage(name, a(0), a(1))
            "pdfh", "pdfa", "pd" -> html.select(a(0), a(1), name).let { if (name == "pd" && it.isNotBlank()) URI(a(2)).resolve(it).toString() else it }
            "MD5", "SHA-1", "SHA-256" -> crypto.digest(name, a(0))
            "base64Encode" -> crypto.base64(a(0), false)
            "base64Decode" -> crypto.base64(a(0), true)
            "encode" -> URLEncoder.encode(a(0), "UTF-8").replace("+", "%20")
            "decode" -> URLDecoder.decode(a(0), "UTF-8")
            "urljoin" -> URI(a(0)).resolve(a(1)).toString()
            "aes" -> crypto.aes(a(0), a(1), a(2), a(3) == "encrypt", a(4).ifBlank { "CBC" })
            else -> throw RuntimeFailure("UNSUPPORTED_HELPER")
        }
    }
}
