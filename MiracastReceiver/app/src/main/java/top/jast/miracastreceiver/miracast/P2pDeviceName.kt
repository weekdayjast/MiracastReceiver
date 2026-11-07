package top.jast.miracastreceiver.miracast

/** WPS Device Name has a 32-byte UTF-8 limit, unlike mDNS service names. */
internal object P2pDeviceName {
    fun fromDisplayName(value: String): String? {
        val name = value.trim()
        if (name.isEmpty() || value.any { Character.isISOControl(it) } ||
            !Charsets.UTF_8.newEncoder().canEncode(name)) return null
        val result = StringBuilder()
        var index = 0
        var bytes = 0
        while (index < name.length) {
            val count = Character.charCount(Character.codePointAt(name, index))
            val part = name.substring(index, index + count)
            bytes += part.toByteArray(Charsets.UTF_8).size
            if (bytes > 32) break
            result.append(part)
            index += count
        }
        return result.toString().trimEnd().takeIf { it.isNotEmpty() }
    }

    /** A shell argument, not double-quoted text: names may contain $, quotes and backticks. */
    fun quote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

    fun command(binary: String, socket: String, name: String): String? {
        val safeName = fromDisplayName(name) ?: return null
        return "${quote(binary)} ${quote(socket)} ${quote("SET device_name $safeName")}"
    }
}
