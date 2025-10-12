package com.example.popai.s3

/**
 * Configuration for S3 upload operations
 *
 * @property bucketName The name of the S3 bucket
 * @property region AWS region where the bucket is located (e.g., "us-east-1")
 * @property accessKey AWS access key ID for authentication
 * @property secretKey AWS secret access key for authentication
 * @property sessionToken AWS session token for temporary credentials (optional)
 */
data class S3Config(
    val bucketName: String,
    val region: String,
    val accessKey: String,
    val secretKey: String,
    val sessionToken: String? = null
) {
    init {
        require(bucketName.isNotBlank()) { "Bucket name cannot be blank" }
        require(region.isNotBlank()) { "Region cannot be blank" }
        require(accessKey.isNotBlank()) { "Access key cannot be blank" }
        require(secretKey.isNotBlank()) { "Secret key cannot be blank" }
    }
}
