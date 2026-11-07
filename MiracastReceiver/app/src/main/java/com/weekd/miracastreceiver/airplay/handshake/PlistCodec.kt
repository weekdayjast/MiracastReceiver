package com.weekd.miracastreceiver.airplay.handshake

import com.dd.plist.BinaryPropertyListWriter
import com.dd.plist.NSArray
import com.dd.plist.NSData
import com.dd.plist.NSDictionary
import com.dd.plist.NSNumber
import com.dd.plist.NSObject
import com.dd.plist.NSString
import com.dd.plist.PropertyListParser

/**
 * PlistCodec — encodes/decodes Apple binary property lists ("bplist00") used throughout
 * the AirPlay 2 handshake (GET /info, SETUP request/response bodies).
 *
 * WHY: macOS exchanges capability and stream-setup data as binary plists. This bridges
 * dd-plist's NSObject tree to plain Kotlin maps/lists/primitives so handlers work with
 * ordinary types instead of NSObject everywhere.
 */
object PlistCodec {

    /** Encodes a map to a binary plist. The result begins with the ASCII magic "bplist00". */
    fun encode(map: Map<String, Any?>): ByteArray =
        BinaryPropertyListWriter.writeToArray(toNs(map))

    /**
     * Encodes a map to an XML property list (`text/x-apple-plist+xml`). AirPlay video clients expect
     * the `GET /playback-info` body in this legacy XML form rather than binary.
     */
    fun encodeXml(map: Map<String, Any?>): ByteArray =
        toNs(map).toXMLPropertyList().toByteArray(Charsets.UTF_8)

    /**
     * Decodes a binary (or XML) plist into a String-keyed map.
     * @throws IllegalArgumentException if the root element is not a dictionary.
     */
    fun decode(bytes: ByteArray): Map<String, Any?> {
        val root = PropertyListParser.parse(bytes)
        @Suppress("UNCHECKED_CAST")
        return (fromNs(root) as? Map<String, Any?>)
            ?: throw IllegalArgumentException("plist root is not a dictionary")
    }

    private fun toNs(value: Any?): NSObject = when (value) {
        null -> NSString("")
        is NSObject -> value
        is String -> NSString(value)
        is Boolean -> NSNumber(value)
        is Int -> NSNumber(value.toLong())
        is Long -> NSNumber(value)
        is Double -> NSNumber(value)
        is ByteArray -> NSData(value)
        is Map<*, *> -> NSDictionary().apply {
            value.forEach { (k, v) -> put(k.toString(), toNs(v)) }
        }
        is List<*> -> NSArray(*value.map { toNs(it) }.toTypedArray())
        else -> throw IllegalArgumentException("Unsupported plist value type: ${value::class.java}")
    }

    private fun fromNs(obj: NSObject?): Any? = when (obj) {
        null -> null
        is NSDictionary -> obj.allKeys().associateWith { fromNs(obj.objectForKey(it)) }
        is NSArray -> obj.array.map { fromNs(it) }
        is NSData -> obj.bytes()
        is NSNumber -> when {
            obj.isBoolean -> obj.boolValue()
            obj.isInteger -> obj.longValue()
            else -> obj.doubleValue()
        }
        is NSString -> obj.content
        else -> obj.toString()
    }
}

