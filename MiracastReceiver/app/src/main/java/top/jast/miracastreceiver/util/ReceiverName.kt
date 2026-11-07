package top.jast.miracastreceiver.util

/** Leave room in a DNS label for the RAOP MAC prefix and discovery suffixes. */
object ReceiverName {
    const val MAX_BYTES = 48

    fun normalize(value: String): String? {
        val name = value.trim()
        return name.takeIf {
            it.isNotEmpty() && it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES &&
                it.none { char -> Character.isISOControl(char) || char == '\u2028' || char == '\u2029' } &&
                Charsets.UTF_8.newEncoder().canEncode(it)
        }
    }
}
