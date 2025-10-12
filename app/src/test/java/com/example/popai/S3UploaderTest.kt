package com.example.popai

import com.example.popai.s3.S3Config
import com.example.popai.s3.S3Uploader
import com.example.popai.s3.UploadResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Unit tests for S3Uploader class
 *
 * These tests verify the behavior of the S3Uploader class without actually
 * connecting to AWS S3. For integration tests that verify actual uploads,
 * see S3UploaderIntegrationTest.
 */
class S3UploaderTest {

    private lateinit var config: S3Config
    private lateinit var uploader: S3Uploader

    @Before
    fun setup() {
        // Create a test configuration
        config = S3Config(
            bucketName = "test-bucket",
            region = "us-east-1",
            accessKey = "test-access-key",
            secretKey = "test-secret-key"
        )
        uploader = S3Uploader(config)
    }

    @Test
    fun `config should validate bucket name is not blank`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            S3Config(
                bucketName = "",
                region = "us-east-1",
                accessKey = "test-access-key",
                secretKey = "test-secret-key"
            )
        }
        assertEquals("Bucket name cannot be blank", exception.message)
    }

    @Test
    fun `config should validate region is not blank`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            S3Config(
                bucketName = "test-bucket",
                region = "",
                accessKey = "test-access-key",
                secretKey = "test-secret-key"
            )
        }
        assertEquals("Region cannot be blank", exception.message)
    }

    @Test
    fun `config should validate access key is not blank`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            S3Config(
                bucketName = "test-bucket",
                region = "us-east-1",
                accessKey = "",
                secretKey = "test-secret-key"
            )
        }
        assertEquals("Access key cannot be blank", exception.message)
    }

    @Test
    fun `config should validate secret key is not blank`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            S3Config(
                bucketName = "test-bucket",
                region = "us-east-1",
                accessKey = "test-access-key",
                secretKey = ""
            )
        }
        assertEquals("Secret key cannot be blank", exception.message)
    }

    @Test
    fun `uploadFile should fail when file does not exist`() = runBlocking {
        val nonExistentFile = File("/path/to/nonexistent/file.txt")

        val result = uploader.uploadFile(nonExistentFile, "test-key")

        assertTrue(result is UploadResult.Failure)
        val failure = result as UploadResult.Failure
        assertTrue(failure.error.message?.contains("File does not exist") == true
                   || failure.error is IllegalArgumentException)
    }

    @Test
    fun `uploadFile should fail when key is blank`() = runBlocking {
        // Create a temporary file for testing
        val tempFile = File.createTempFile("test", ".txt")
        tempFile.writeText("test content")
        tempFile.deleteOnExit()

        val result = uploader.uploadFile(tempFile, "")

        assertTrue(result is UploadResult.Failure)
        val failure = result as UploadResult.Failure
        assertTrue(failure.error is IllegalArgumentException)
        assertTrue(failure.error.message?.contains("Key cannot be blank") == true)
    }

    @Test
    fun `config should be created with valid parameters`() {
        val validConfig = S3Config(
            bucketName = "my-bucket",
            region = "us-west-2",
            accessKey = "AKIAIOSFODNN7EXAMPLE",
            secretKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
        )

        assertEquals("my-bucket", validConfig.bucketName)
        assertEquals("us-west-2", validConfig.region)
        assertEquals("AKIAIOSFODNN7EXAMPLE", validConfig.accessKey)
        assertEquals("wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", validConfig.secretKey)
    }

    @Test
    fun `S3Uploader should be instantiated with valid config`() {
        val testUploader = S3Uploader(config)
        assertNotNull(testUploader)
    }
}
