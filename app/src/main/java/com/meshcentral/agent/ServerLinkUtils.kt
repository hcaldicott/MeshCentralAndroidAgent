package com.meshcentral.agent

fun getServerHostFromLink(link: String?): String? {
    if (link == null) return null
    val parts = link.split(',')
    if (parts.size < 3) return null
    var host = parts[0]
    if (host.startsWith("mc://")) {
        host = host.substring(5)
    }
    return if (host.contains('/')) {
        host.substring(0, host.indexOf('/'))
    } else {
        host
    }
}

fun getServerHashFromLink(link: String?): String? {
    if (link == null) return null
    val parts = link.split(',')
    if (parts.size < 3) return null
    return parts[1]
}

fun getDevGroupFromLink(link: String?): String? {
    if (link == null) return null
    val parts = link.split(',')
    if (parts.size < 3) return null
    return parts[2]
}
