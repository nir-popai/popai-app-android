# How to Test the S3 Uploader Module

## Prerequisites

Your `.env` file should be configured with valid AWS credentials including the session token:

```
AWS_ACCESS_KEY_ID=ASIA5FMNUFQMD7IZFRGO
AWS_SECRET_ACCESS_KEY=Hb7TIIMrNJIk4ki9VlpD/JULzjlCCgSwky8xejoz
AWS_SESSION_TOKEN=IQoJb3JpZ2luX2VjEIP//////////wEaC...
AWS_REGION=us-east-1
S3_BUCKET_NAME=nir-mobile-test
```

## Option 1: Run Tests from Android Studio

1. Open the project in Android Studio
2. Navigate to the test file you want to run:
   - Unit tests: `app/src/test/java/com/example/popai/S3UploaderTest.kt`
   - Integration tests: `app/src/androidTest/java/com/example/popai/S3UploaderIntegrationTest.kt`
3. Right-click on the test class or test method
4. Select "Run 'S3UploaderTest'" or "Run 'S3UploaderIntegrationTest'"

## Option 2: Run Tests from Command Line

### Unit Tests (no device needed)
```bash
./gradlew test --tests com.example.popai.S3UploaderTest
```

### Integration Tests (requires connected Android device or emulator)
```bash
./gradlew connectedAndroidTest --tests com.example.popai.S3UploaderIntegrationTest
```

## Option 3: Manual Verification

If you can't run automated tests, create a simple Activity to test manually:

```kotlin
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.popai.config.EnvironmentConfig
import com.example.popai.s3.S3Config
import com.example.popai.s3.S3Uploader
import com.example.popai.s3.UploadResult
import kotlinx.coroutines.launch
import java.io.File

class TestS3Activity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Load environment config
        val envPath = "C:\\Users\\nirma\\AndroidStudioProjects\\popai\\.env"
        if (!EnvironmentConfig.loadFromPath(envPath)) {
            println("ERROR: Failed to load .env file")
            return
        }

        // Create S3 config
        val config = S3Config(
            bucketName = EnvironmentConfig.s3BucketName,
            region = EnvironmentConfig.awsRegion,
            accessKey = EnvironmentConfig.awsAccessKeyId,
            secretKey = EnvironmentConfig.awsSecretAccessKey,
            sessionToken = EnvironmentConfig.awsSessionToken
        )

        println("S3 Config created successfully!")
        println("- Bucket: ${config.bucketName}")
        println("- Region: ${config.region}")
        println("- Has session token: ${config.sessionToken != null}")

        // Create uploader
        val uploader = S3Uploader(config)
        println("S3 Uploader created successfully!")

        // Test upload
        lifecycleScope.launch {
            // Create a test file
            val testFile = File(cacheDir, "test_upload.txt")
            testFile.writeText("Test content: ${System.currentTimeMillis()}")

            println("Uploading test file...")
            val result = uploader.uploadFile(
                file = testFile,
                key = "manual-test/test-${System.currentTimeMillis()}.txt",
                contentType = "text/plain"
            )

            when (result) {
                is UploadResult.Success -> {
                    println("✓ Upload SUCCESS!")
                    println("  Key: ${result.key}")
                    println("  URL: ${result.url}")
                }
                is UploadResult.Failure -> {
                    println("✗ Upload FAILED!")
                    println("  Error: ${result.error.message}")
                    result.error.printStackTrace()
                }
            }
        }
    }
}
```

## What Each Test Validates

### Unit Tests (S3UploaderTest.kt)
- ✓ S3Config validates required fields (bucket, region, keys)
- ✓ S3Config rejects blank values
- ✓ S3Uploader rejects non-existent files
- ✓ S3Uploader rejects blank keys
- ✓ S3Uploader can be instantiated with valid config

### Integration Tests (S3UploaderIntegrationTest.kt)
- ✓ Upload file to S3 successfully
- ✓ Verify uploaded file exists in S3
- ✓ Upload via InputStream
- ✓ Delete file from S3
- ✓ Check non-existent file returns false

## Troubleshooting

### "JAVA_HOME is not set"
- Install JDK 11 or higher
- Set JAVA_HOME environment variable to your JDK installation path

### "Failed to load .env file"
- Verify `.env` exists in project root: `C:\Users\nirma\AndroidStudioProjects\popai\.env`
- Check file contains all required credentials
- Ensure no extra spaces or quotes around values

### "Authentication failed" or "Access Denied"
- Verify your AWS credentials are correct and not expired
- Session tokens expire quickly (usually 1-12 hours)
- Regenerate temporary credentials if expired
- Check IAM permissions allow s3:PutObject, s3:GetObject, s3:DeleteObject

### "Bucket not found"
- Verify bucket name is exactly: `nir-mobile-test`
- Check AWS region matches bucket region
- Verify your IAM user/role has access to the bucket

## Expected Test Results

All tests should pass if:
1. `.env` file is properly configured
2. AWS credentials are valid and not expired
3. IAM permissions are correct
4. Bucket `nir-mobile-test` exists and is accessible
5. Network connectivity is available

The integration tests will create test files in the `test-uploads/` folder in your S3 bucket and clean them up after testing.
