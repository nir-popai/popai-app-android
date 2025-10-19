package com.example.popai.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Presigned URL data for a single file
 */
data class PresignedUrl(
    val file: String,
    val url: String,
    val key: String
)

/**
 * Response from presigned URL API
 */
data class PresignedUrlResponse(
    val recordingId: String,
    val bucketName: String,
    val expiresIn: Int,
    val presignedUrls: List<PresignedUrl>
)

/**
 * Result of presigned URL request
 */
sealed class PresignedUrlResult {
    data class Success(val response: PresignedUrlResponse) : PresignedUrlResult()
    data class Failure(val error: String) : PresignedUrlResult()
}

/**
 * Upload progress callback
 */
data class UploadProgress(
    val bytesTransferred: Long,
    val totalBytes: Long
) {
    val percentComplete: Int get() = if (totalBytes > 0) {
        ((bytesTransferred * 100) / totalBytes).toInt()
    } else 0

    val megabytesTransferred: Double get() = bytesTransferred / (1024.0 * 1024.0)
    val totalMegabytes: Double get() = totalBytes / (1024.0 * 1024.0)
}

/**
 * Result of file upload
 */
sealed class UploadResult {
    data class Success(val key: String, val url: String) : UploadResult()
    data class Failure(val error: String) : UploadResult()
}

/**
 * Service for managing presigned URL uploads to S3
 */
class PresignedUrlService {

    companion object {
        private const val API_BASE_URL = "https://iijdu4x4ac.execute-api.us-east-1.amazonaws.com/prod"
        private const val PRESIGNED_URL_ENDPOINT = "$API_BASE_URL/upload/presigned-url"
        private const val CONNECTION_TIMEOUT = 30_000 // 30 seconds
        private const val READ_TIMEOUT = 30_000 // 30 seconds
        private const val UPLOAD_TIMEOUT = 1_800_000 // 30 minutes for large uploads
    }

    /**
     * Request presigned URLs for all files in a recording
     */
    suspend fun requestPresignedUrls(
        recordingId: String,
        fileCount: Int
    ): PresignedUrlResult = withContext(Dispatchers.IO) {
        try {
            val url = URL(PRESIGNED_URL_ENDPOINT)
            val connection = url.openConnection() as HttpURLConnection

            connection.apply {
                requestMethod = "POST"
                doOutput = true
                doInput = true
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = CONNECTION_TIMEOUT
                readTimeout = READ_TIMEOUT
            }

            // Create request body
            val requestBody = JSONObject().apply {
                put("recordingId", recordingId)
                put("fileCount", fileCount)
            }.toString()

            // Send request
            connection.outputStream.use { outputStream ->
                outputStream.write(requestBody.toByteArray())
            }

            // Read response
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonResponse = JSONObject(response)

                val presignedUrls = mutableListOf<PresignedUrl>()
                val urlsArray = jsonResponse.getJSONArray("presignedUrls")
                for (i in 0 until urlsArray.length()) {
                    val urlObj = urlsArray.getJSONObject(i)
                    presignedUrls.add(
                        PresignedUrl(
                            file = urlObj.getString("file"),
                            url = urlObj.getString("url"),
                            key = urlObj.getString("key")
                        )
                    )
                }

                PresignedUrlResult.Success(
                    PresignedUrlResponse(
                        recordingId = jsonResponse.getString("recordingId"),
                        bucketName = jsonResponse.getString("bucketName"),
                        expiresIn = jsonResponse.getInt("expiresIn"),
                        presignedUrls = presignedUrls
                    )
                )
            } else {
                val errorStream = connection.errorStream
                val errorMessage = errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
                PresignedUrlResult.Failure("HTTP $responseCode: $errorMessage")
            }
        } catch (e: Exception) {
            PresignedUrlResult.Failure("Failed to request presigned URLs: ${e.message}")
        }
    }

    /**
     * Upload a file to S3 using a presigned URL
     */
    suspend fun uploadFile(
        file: File,
        presignedUrl: String,
        contentType: String = "audio/mp4",
        onProgress: ((UploadProgress) -> Unit)? = null
    ): UploadResult = withContext(Dispatchers.IO) {
        try {
            require(file.exists()) { "File does not exist: ${file.absolutePath}" }

            val fileSize = file.length()
            val url = URL(presignedUrl)
            val connection = url.openConnection() as HttpURLConnection

            connection.apply {
                requestMethod = "PUT"
                doOutput = true
                setRequestProperty("Content-Type", contentType)
                setRequestProperty("Content-Length", fileSize.toString())
                setFixedLengthStreamingMode(fileSize)
                connectTimeout = CONNECTION_TIMEOUT
                readTimeout = UPLOAD_TIMEOUT
            }

            // Upload file with progress tracking
            var bytesTransferred = 0L
            file.inputStream().use { inputStream ->
                connection.outputStream.use { outputStream ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int

                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        bytesTransferred += bytesRead

                        // Report progress
                        onProgress?.invoke(
                            UploadProgress(
                                bytesTransferred = bytesTransferred,
                                totalBytes = fileSize
                            )
                        )
                    }
                    outputStream.flush()
                }
            }

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK || responseCode == 204) {
                // Extract key from presigned URL (before the query parameters)
                val key = presignedUrl.substringBefore("?").substringAfter(".com/")
                UploadResult.Success(key = key, url = presignedUrl.substringBefore("?"))
            } else {
                val errorStream = connection.errorStream
                val errorMessage = errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
                UploadResult.Failure("Upload failed with HTTP $responseCode: $errorMessage")
            }
        } catch (e: Exception) {
            UploadResult.Failure("Upload failed: ${e.message}")
        }
    }
}
