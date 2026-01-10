package com.gatekey.client.util

/**
 * Utility functions for IP address formatting and handling.
 * Supports both IPv4 and IPv6 addresses.
 */
object IpUtils {

    /**
     * Formats an IP address and port into a proper endpoint string.
     * Uses bracket notation for IPv6 addresses: [2001:db8::1]:443
     * Uses standard notation for IPv4 addresses: 192.168.1.1:443
     *
     * @param ip The IP address (IPv4 or IPv6)
     * @param port The port number
     * @return Formatted endpoint string, or fallback if ip is null
     */
    fun formatEndpoint(ip: String?, port: String?, fallback: String = "—"): String {
        return when {
            ip.isNullOrEmpty() -> fallback
            port.isNullOrEmpty() -> ip
            isIPv6(ip) -> "[$ip]:$port"
            else -> "$ip:$port"
        }
    }

    /**
     * Formats an IP address and port into a proper endpoint string.
     * Overload accepting Int port.
     */
    fun formatEndpoint(ip: String?, port: Int?, fallback: String = "—"): String {
        return formatEndpoint(ip, port?.toString(), fallback)
    }

    /**
     * Checks if the given string is an IPv6 address.
     * IPv6 addresses contain colons, while IPv4 addresses use dots.
     *
     * @param ip The IP address string to check
     * @return true if the address appears to be IPv6
     */
    fun isIPv6(ip: String?): Boolean {
        if (ip.isNullOrEmpty()) return false
        // IPv6 addresses contain colons
        // IPv4-mapped IPv6 (::ffff:192.168.1.1) also contains colons
        return ip.contains(":")
    }

    /**
     * Checks if the given string is an IPv4 address.
     *
     * @param ip The IP address string to check
     * @return true if the address appears to be IPv4
     */
    fun isIPv4(ip: String?): Boolean {
        if (ip.isNullOrEmpty()) return false
        // Simple check: contains dots and no colons
        return ip.contains(".") && !ip.contains(":")
    }

    /**
     * Formats an IP address for display, wrapping IPv6 in brackets if needed.
     *
     * @param ip The IP address
     * @param wrapIPv6 Whether to wrap IPv6 addresses in brackets
     * @return Formatted IP address
     */
    fun formatIpAddress(ip: String?, wrapIPv6: Boolean = false, fallback: String = "—"): String {
        return when {
            ip.isNullOrEmpty() -> fallback
            wrapIPv6 && isIPv6(ip) -> "[$ip]"
            else -> ip
        }
    }

    /**
     * Extracts the IP address from an endpoint string.
     * Handles both IPv4 (192.168.1.1:443) and IPv6 ([2001:db8::1]:443) formats.
     *
     * @param endpoint The endpoint string
     * @return The IP address portion, or null if parsing fails
     */
    fun extractIpFromEndpoint(endpoint: String?): String? {
        if (endpoint.isNullOrEmpty()) return null

        return when {
            // IPv6 with brackets: [2001:db8::1]:443
            endpoint.startsWith("[") -> {
                val endBracket = endpoint.indexOf("]")
                if (endBracket > 1) {
                    endpoint.substring(1, endBracket)
                } else null
            }
            // IPv6 without port (no brackets needed)
            endpoint.contains(":") && endpoint.count { it == ':' } > 1 -> endpoint
            // IPv4 with port: 192.168.1.1:443
            endpoint.contains(":") -> endpoint.substringBeforeLast(":")
            // Just an IP address
            else -> endpoint
        }
    }

    /**
     * Extracts the port from an endpoint string.
     * Handles both IPv4 (192.168.1.1:443) and IPv6 ([2001:db8::1]:443) formats.
     *
     * @param endpoint The endpoint string
     * @return The port as a string, or null if no port found
     */
    fun extractPortFromEndpoint(endpoint: String?): String? {
        if (endpoint.isNullOrEmpty()) return null

        return when {
            // IPv6 with brackets: [2001:db8::1]:443
            endpoint.contains("]:") -> endpoint.substringAfterLast("]:")
            // IPv4 with port: 192.168.1.1:443 (only one colon)
            endpoint.count { it == ':' } == 1 -> endpoint.substringAfterLast(":")
            // No port found
            else -> null
        }
    }
}
