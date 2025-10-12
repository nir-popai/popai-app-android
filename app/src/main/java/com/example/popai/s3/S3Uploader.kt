package com.example.popai.s3

import com.amazonaws.auth.AWSCredentials
import com.amazonaws.auth.AWSSessionCredentials
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.auth.BasicSessionCredentials
import com.amazonaws.regions.Region
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.ObjectMetadata
import com.amazonaws.services.s3.model.PutObjectRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

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
        val client = AmazonS3Client(credentials)
        client.setRegion(Region.getRegion(config.region))
        client
    }

    /**
     * Uploads a file to S3 bucket
     *
     * @param file The file to upload
     * @param key The S3 object key (path) where the file will be stored
     * @param contentType Optional content type of the file (e.g., "image/jpeg")
     * @return UploadResult indicating success or failure
     */
    suspend fun uploadFile(
        file: File,
        key: String,
        contentType: String? = null
    ): UploadResult = withContext(Dispatchers.IO) {
        try {
            require(file.exists()) { "File does not exist: ${file.absolutePath}" }
            require(key.isNotBlank()) { "Key cannot be blank" }

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

            s3Client.putObject(putRequest)

            val url = s3Client.getUrl(config.bucketName, key).toString()
            UploadResult.Success(key, url)
        } catch (e: Exception) {
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
     * @return UploadResult indicating success or failure
     */
    suspend fun uploadStream(
        inputStream: InputStream,
        key: String,
        contentLength: Long,
        contentType: String? = null
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
