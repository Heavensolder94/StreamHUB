package de.streamhub.tv

import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Explicit, user-triggered reachability probe, not an uptime guarantee. */
object SiteChecker {
    fun check(rawUrl: String): Reachability {
        if (!rawUrl.startsWith("https://", ignoreCase = true)) return Reachability.UNCERTAIN
        return try {
            val connection = URL(rawUrl).openConnection() as? HttpsURLConnection
                ?: return Reachability.UNCERTAIN
            val headStatus = request(connection, "HEAD")
            val status = if (headStatus == 405 || headStatus == 501) {
                val get = URL(rawUrl).openConnection() as HttpsURLConnection
                request(get, "GET")
            } else headStatus
            when (status) {
                in 200..399 -> Reachability.ONLINE
                401, 403, 429 -> Reachability.RESTRICTED
                404, 410 -> Reachability.OFFLINE
                else -> Reachability.UNCERTAIN
            }
        } catch (_: UnknownHostException) {
            Reachability.OFFLINE
        } catch (_: ConnectException) {
            Reachability.OFFLINE
        } catch (_: SocketTimeoutException) {
            Reachability.UNCERTAIN
        } catch (_: Exception) {
            Reachability.UNCERTAIN
        }
    }

    private fun request(connection: HttpURLConnection, method: String): Int {
        return try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 6500
            connection.readTimeout = 6500
            connection.setRequestProperty("User-Agent", "StreamHub/1.3 (Android TV; Site reachability check)")
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }
}
