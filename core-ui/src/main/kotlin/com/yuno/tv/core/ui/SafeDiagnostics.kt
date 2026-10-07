package com.yuno.tv.core.ui

/** Diagnostics omit credentials, fragments and all query values including signed URLs. */
fun redactedSourceUrl(value: String): String = runCatching {
    val uri = java.net.URI(value)
    if (uri.scheme == "local") value.substringBefore('?') else
        java.net.URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toString()
}.getOrDefault(value.substringBefore('?').substringBefore('#').replace(Regex("//[^/]*@"), "//"))
