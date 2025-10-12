package com.example.popai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.popai.config.EnvironmentConfig
import com.example.popai.s3.S3Config
import com.example.popai.s3.S3Uploader
import com.example.popai.s3.UploadResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Integration test for S3Uploader
 *
 * This test actually uploads files to AWS S3 to verify the functionality works.
 *
 * IMPORTANT: Before running this test, you must configure your AWS credentials in the .env file
 * at the project root. See .env.template for the required format.
 *
 * Required credentials in .env:
 * - AWS_ACCESS_KEY_ID
 * - AWS_SECRET_ACCESS_KEY
 * - AWS_SESSION_TOKEN (optional, for temporary credentials)
 * - AWS_REGION (default: us-east-1)
 * - S3_BUCKET_NAME (default: nir-mobile-test)
 */
@RunWith(AndroidJUnit4::class)
class S3UploaderIntegrationTest {

    private lateinit var uploader: S3Uploader
    private lateinit var testFile: File

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        // Load configuration from .env file
        // Try multiple paths to find the .env file
        val loaded = EnvironmentConfig.load(context) ||
                     EnvironmentConfig.loadFromPath("/storage/emulated/0/Download/.env") ||
                     EnvironmentConfig.loadFromPath("C:\\Users\\nirma\\AndroidStudioProjects\\popai\\.env")

        if (!loaded) {
            throw IllegalStateException("Failed to load .env file. Please ensure .env exists with AWS credentials.")
        }

        if (!EnvironmentConfig.hasRequiredCredentials()) {
            throw IllegalStateException("Missing required AWS credentials in .env file. See .env.template for required fields.")
        }

        // Create test configuration from environment
        val config = S3Config(
            bucketName = EnvironmentConfig.s3BucketName,
            region = EnvironmentConfig.awsRegion,
            accessKey = EnvironmentConfig.awsAccessKeyId,
            secretKey = EnvironmentConfig.awsSecretAccessKey,
            sessionToken = EnvironmentConfig.awsSessionToken
        )

        uploader = S3Uploader(config)

        // Create a test file
        testFile = File(context.cacheDir, "test_upload.txt")
        testFile.writeText("This is a test file for S3 upload integration test.\nTimestamp: ${System.currentTimeMillis()}")
    }

    @Test
    fun uploadFile_shouldSuccessfullyUploadToS3() = runBlocking {
        // Generate a unique key for this test
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val key = "test-uploads/integration-test-$timestamp.txt"

        // Upload the file
        val result = uploader.uploadFile(testFile, key, "text/plain")

        // Verify the upload was successful
        assertTrue("Upload should succeed", result is UploadResult.Success)

        if (result is UploadResult.Success) {
            assertEquals("Key should match", key, result.key)
            assertTrue("URL should not be empty", result.url.isNotEmpty())
            assertTrue("URL should contain bucket name", result.url.contains("nir-mobile-test"))
            println("Upload successful! URL: ${result.url}")
        }
    }

    @Test
    fun uploadFile_andVerifyExists() = runBlocking {
        // Generate a unique key for this test
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val key = "test-uploads/existence-test-$timestamp.txt"

        // Upload the file
        val uploadResult = uploader.uploadFile(testFile, key, "text/plain")
        assertTrue("Upload should succeed", uploadResult is UploadResult.Success)

        // Verify the file exists
        val exists = uploader.fileExists(key)
        assertTrue("File should exist in S3", exists)
    }

    @Test
    fun uploadFile_andDeleteIt() = runBlocking {
        // Generate a unique key for this test
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val key = "test-uploads/delete-test-$timestamp.txt"

        // Upload the file
        val uploadResult = uploader.uploadFile(testFile, key, "text/plain")
        assertTrue("Upload should succeed", uploadResult is UploadResult.Success)

        // Verify the file exists
        assertTrue("File should exist before deletion", uploader.fileExists(key))

        // Delete the file
        val deleteResult = uploader.deleteFile(key)
        assertTrue("Delete should succeed", deleteResult)

        // Verify the file no longer exists
        assertFalse("File should not exist after deletion", uploader.fileExists(key))
    }

    @Test
    fun uploadStream_shouldSuccessfullyUploadToS3() = runBlocking {
        // Generate a unique key for this test
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val key = "test-uploads/stream-test-$timestamp.txt"

        // Upload using stream
        val inputStream = testFile.inputStream()
        val result = uploader.uploadStream(
            inputStream = inputStream,
            key = key,
            contentLength = testFile.length(),
            contentType = "text/plain"
        )

        // Verify the upload was successful
        assertTrue("Stream upload should succeed", result is UploadResult.Success)

        if (result is UploadResult.Success) {
            assertEquals("Key should match", key, result.key)
            assertTrue("URL should not be empty", result.url.isNotEmpty())
            println("Stream upload successful! URL: ${result.url}")
        }
    }

    @Test
    fun fileExists_shouldReturnFalseForNonExistentFile() = runBlocking {
        val nonExistentKey = "test-uploads/this-file-does-not-exist-${System.currentTimeMillis()}.txt"

        val exists = uploader.fileExists(nonExistentKey)

        assertFalse("Non-existent file should return false", exists)
    }
}
