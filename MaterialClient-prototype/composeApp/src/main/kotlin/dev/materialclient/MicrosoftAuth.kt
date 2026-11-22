
package dev.materialclient

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class MicrosoftDeviceCode(
    val userCode: String,
    val verificationUri: String,
    val message: String,
    val deviceCode: String,
    val expiresIn: Long,
    val interval: Long
)

data class MicrosoftToken(
    val accessToken: String,
    val refreshToken: String?,
    val expiresIn: Long
)

object MicrosoftAuth {
    // Вставим сюда Client ID собственной регистрации приложения.
    // Не используй ID портала Entra.
    private const val CLIENT_ID = "PASTE_YOUR_CLIENT_ID_HERE"

    private const val BASE_URL =
        "https://login.microsoftonline.com/consumers/oauth2/v2.0"

    private const val SCOPE = "XboxLive.signin offline_access"

    private fun formEncode(value: String): String =
        URLEncoder.encode(value, "UTF-8")

    private fun postForm(
        url: String,
        values: Map<String, String>
    ): String {
        val connection = URL(url).openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 20000
            connection.doOutput = true
            connection.setRequestProperty(
                "Content-Type",
                "application/x-www-form-urlencoded"
            )
            connection.setRequestProperty(
                "Accept",
                "application/json"
            )

            val body = values.entries.joinToString("&") {
                "${formEncode(it.key)}=${formEncode(it.value)}"
            }

            connection.outputStream.use {
                it.write(body.toByteArray(Charsets.UTF_8))
            }

            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val response = stream?.bufferedReader()?.use {
                it.readText()
            }.orEmpty()

            if (connection.responseCode !in 200..299) {
                val message = try {
                    val json = JsonParser.parseString(response).asJsonObject
                    json.get("error_description")?.asString
                        ?: json.get("error")?.asString
                        ?: "HTTP ${connection.responseCode}"
                } catch (_: Exception) {
                    "HTTP ${connection.responseCode}"
                }

                error("Microsoft OAuth: $message")
            }

            return response
        } finally {
            connection.disconnect()
        }
    }

    suspend fun requestDeviceCode(): MicrosoftDeviceCode =
        withContext(Dispatchers.IO) {
            require(
                CLIENT_ID != "PASTE_YOUR_CLIENT_ID_HERE" &&
                        CLIENT_ID.isNotBlank()
            ) {
                tr("Сначала укажи Client ID собственной регистрации приложения.")
            }

            val response = postForm(
                "$BASE_URL/devicecode",
                mapOf(
                    "client_id" to CLIENT_ID,
                    "scope" to SCOPE
                )
            )

            val json = JsonParser.parseString(response).asJsonObject

            MicrosoftDeviceCode(
                userCode = json.get("user_code").asString,
                verificationUri = json.get("verification_uri").asString,
                message = json.get("message")?.asString
                    ?: tr("Открой страницу Microsoft и введи код."),
                deviceCode = json.get("device_code").asString,
                expiresIn = json.get("expires_in").asLong,
                interval = json.get("interval")?.asLong ?: 5L
            )
        }

    suspend fun pollForToken(
        deviceCode: MicrosoftDeviceCode,
        onStatus: (String) -> Unit
    ): MicrosoftToken = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() +
                deviceCode.expiresIn * 1000L

        var intervalSeconds = deviceCode.interval

        while (System.currentTimeMillis() < deadline) {
            delay(intervalSeconds * 1000L)

            val response = postTokenRequest(deviceCode.deviceCode)

            val json = JsonParser.parseString(response).asJsonObject

            if (json.has("access_token")) {
                onStatus(tr("Microsoft-вход выполнен"))

                return@withContext MicrosoftToken(
                    accessToken = json.get("access_token").asString,
                    refreshToken = json.get("refresh_token")
                        ?.takeUnless { it.isJsonNull }
                        ?.asString,
                    expiresIn = json.get("expires_in").asLong
                )
            }

            when (json.get("error")?.asString) {
                "authorization_pending" -> {
                    onStatus(tr("Ожидаем подтверждения входа…"))
                }

                "slow_down" -> {
                    intervalSeconds += 5
                    onStatus(tr("Ожидаем подтверждения входа…"))
                }

                "authorization_declined" -> {
                    error(tr("Вход отменён пользователем."))
                }

                "expired_token" -> {
                    error(tr("Срок действия кода истёк. Запроси новый."))
                }

                else -> {
                    val description =
                        json.get("error_description")?.asString
                            ?: json.get("error")?.asString
                            ?: tr("Неизвестная ошибка")

                    error("Microsoft OAuth: $description")
                }
            }
        }

        error(tr("Время ожидания входа истекло. Запроси новый код."))
    }


    private fun postTokenRequest(deviceCode: String): String {
        val connection = URL("$BASE_URL/token")
            .openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 20000
            connection.doOutput = true
            connection.setRequestProperty(
                "Content-Type",
                "application/x-www-form-urlencoded"
            )
            connection.setRequestProperty("Accept", "application/json")

            val body = mapOf(
                "grant_type" to
                        "urn:ietf:params:oauth:grant-type:device_code",
                "client_id" to CLIENT_ID,
                "device_code" to deviceCode
            ).entries.joinToString("&") {
                "${formEncode(it.key)}=${formEncode(it.value)}"
            }

            connection.outputStream.use {
                it.write(body.toByteArray(Charsets.UTF_8))
            }

            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            return stream?.bufferedReader()?.use { it.readText() }
                ?: error(tr("Microsoft вернул пустой ответ."))
        } finally {
            connection.disconnect()
        }
    }
}
