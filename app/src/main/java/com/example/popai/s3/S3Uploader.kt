package com.example.popai.s3

import com.amazonaws.ClientConfiguration
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.auth.AWSSessionCredentials
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.auth.BasicSessionCredentials
import com.amazonaws.regions.Region
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.ObjectMetadata
import com.amazonaws.services.s3.model.ProgressEvent
import com.amazonaws.services.s3.model.ProgressListener
import com.amazonaws.services.s3.model.PutObjectRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * Upload progress information
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
 * Result of an S3 upload operation
 */
sealed class UploadResult {
    data class Success(val key: String, val url: String) : UploadResult()
    data class Failure(val error: Throwable) : UploadResult()
}

/**
 * S3 Uploader class that handles file uploads to Amazon S3 with authentication
 *
 * This class is designed to be testable and can be easily mocked for unit testing.
 * Supports both permanent credentials and temporary session credentials.
 *
 * @property config S3 configuration containing bucket name, region, and credentials
 */
class S3Uploader(private val config: S3Config) {

    private val s3Client: AmazonS3Client by lazy {
        val credentials: AWSCredentials = if (config.sessionToken != null) {
            // Use session credentials for temporary access
            BasicSessionCredentials(config.accessKey, config.secretKey, config.sessionToken)
        } else {
            // Use basic credentials for permanent access
            BasicAWSCredentials(config.accessKey, config.secretKey)
        }

        // Configure client with extended timeouts for large file uploads
        val clientConfig = ClientConfiguration().apply {
            // Set connection timeout to 60 seconds
            connectionTimeout = 60_000
            // Set socket timeout to 10 minutes (600 seconds) for large uploads
            socketTimeout = 600_000
            // Enable retry on timeout with exponential backoff
            maxErrorRetry = 3
        }

        val client = AmazonS3Client(credentials, clientConfig)
        client.setRegion(Region.getRegion(config.region))
        client
    }

    /**
     * Uploads a file to S3 bucket
     *
     * @param file The file to upload
     * @param key The S3 object key (path) where the file will be stored
     * @param contentType Optional content type of the file (e.g., "image/jpeg")
     * @param onProgress Optional callback to track upload progress
     * @return UploadResult indicating success or failure
     */
    suspend fun uploadFile(
        file: File,
        key: String,
        contentType: String? = null,
        onProgress: ((UploadProgress) -> Unit)? = null
    ): UploadResult = withContext(Dispatchers.IO) {
        try {
            require(file.exists()) { "File does not exist: ${file.absolutePath}" }
            require(key.isNotBlank()) { "Key cannot be blank" }

            android.util.Log.d("S3Uploader", "Uploading file: ${file.name} (${file.length()} bytes) to bucket: ${config.bucketName}, key: $key")

            val metadata = ObjectMetadata().apply {
                contentLength = file.length()
                contentType?.let { this.contentType = it }
            }

            val putRequest = PutObjectRequest(
                config.bucketName,
                key,
                file.inputStream(),
                metadata
            )

            // Add progress listener if callback is provided
            onProgress?.let {
                putRequest.progressListener = ProgressListener { progressEvent ->
                    val progress = UploadProgress(
                        bytesTransferred = progressEvent.bytesTransferred,
                        totalBytes = file.length()
                    )
                    it(progress)
                }
            }

            s3Client.putObject(putRequest)

            val url = s3Client.getUrl(config.bucketName, key).toString()
            android.util.Log.d("S3Uploader", "Upload successful: $url")
            UploadResult.Success(key, url)
        } catch (e: Exception) {
            android.util.Log.e("S3Uploader", "Upload failed: ${e.javaClass.simpleName} - ${e.message}", e)
            // Log more specific error information
            when {
                e.message?.contains("credentials", ignoreCase = true) == true ->
                    android.util.Log.e("S3Uploader", "CREDENTIALS ERROR: Check AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY, and AWS_SESSION_TOKEN in env.properties")
                e.message?.contains("access denied", ignoreCase = true) == true ->
                    android.util.Log.e("S3Uploader", "ACCESS DENIED: Check S3 bucket permissions for the provided credentials")
                e.message?.contains("expired", ignoreCase = true) == true ->
                    android.util.Log.e("S3Uploader", "SESSION EXPIRED: AWS session token has expired - please generate new credentials")
                e.message?.contains("network", ignoreCase = true) == true ->
                    android.util.Log.e("S3Uploader", "NETWORK ERROR: Check internet connection")
                e.message?.contains("bucket", ignoreCase = true) == true ->
                    android.util.Log.e("S3Uploader", "BUCKET ERROR: Check S3_BUCKET_NAME in env.properties")
            }
            UploadResult.Failure(e)
        }
    }

    /**
     * Uploads data from an InputStream to S3 bucket
     *
     * @param inputStream The input stream containing the data to upload
     * @param key The S3 object key (path) where the data will be stored
     * @param contentLength The size of the data in bytes
     * @param contentType Optional content type of the data
     * @param onProgress Optional callback to track upload progress
     * @return UploadResult indicating success or failure
     */
    suspend fun uploadStream(
        inputStream: InputStream,
        key: String,
        contentLength: Long,
        contentType: String? = null,
        onProgress: ((UploadProgress) -> Unit)? = null
    ): UploadResult = withContext(Dispatchers.IO) {
        try {
            require(key.isNotBlank()) { "Key cannot be blank" }
            require(contentLength > 0) { "Content length must be greater than 0" }

            val metadata = ObjectMetadata().apply {
                this.contentLength = contentLength
                contentType?.let { this.contentType = it }
            }

            val putRequest = PutObjectRequest(
                config.bucketName,
                key,
                inputStream,
                metadata
            )

            // Add progress listener if callback is provided
            onProgress?.let {
                putRequest.progressListener = ProgressListener { progressEvent ->
                    val progress = UploadProgress(
                        bytesTransferred = progressEvent.bytesTransferred,
                        totalBytes = contentLength
                    )
                    it(progress)
                }
            }

            s3Client.putObject(putRequest)

            val url = s3Client.getUrl(config.bucketName, key).toString()
            UploadResult.Success(key, url)
        } catch (e: Exception) {
            UploadResult.Failure(e)
        }
    }

    /**
     * Checks if a file exists in the S3 bucket
     *
     * @param key The S3 object key to check
     * @return true if the file exists, false otherwise
     */
    suspend fun fileExists(key: String): Boolean = withContext(Dispatchers.IO) {
        try {
            s3Client.getObjectMetadata(config.bucketName, key)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Deletes a file from the S3 bucket
     *
     * @param key The S3 object key to delete
     * @return true if deletion was successful, false otherwise
     */
    suspend fun deleteFile(key: String): Boolean = withContext(Dispatchers.IO) {
        try {
            s3Client.deleteObject(config.bucketName, key)
            true
        } catch (e: Exception) {
            false
        }
    }
}
