# S3 Uploader Module

A testable, modular S3 upload implementation for Android with authentication support.

## Overview

This module provides a clean, testable way to upload files to Amazon S3. It includes:
- **S3Config**: Configuration data class for S3 credentials and settings
- **S3Uploader**: Main upload class with coroutine support
- **Unit tests**: Test configuration validation and error handling
- **Integration tests**: Validate actual uploads to your S3 bucket

## Features

- Upload files and streams to S3
- AWS authentication with access keys
- Check if files exist in S3
- Delete files from S3
- Full coroutine support for async operations
- Comprehensive error handling
- Easy to test and mock

## Setup

### 1. Dependencies

The following dependencies have been added to `app/build.gradle.kts`:

```kotlin
// AWS S3 SDK
implementation("com.amazonaws:aws-android-sdk-s3:2.77.0")
implementation("com.amazonaws:aws-android-sdk-core:2.77.0")

// Coroutines
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
```

### 2. Configure AWS Credentials

**IMPORTANT: Set up your .env file first!**

1. Open the `.env` file in the project root directory
2. Replace the placeholder values with your actual AWS credentials:

```bash
AWS_ACCESS_KEY_ID=your_actual_access_key_here
AWS_SECRET_ACCESS_KEY=your_actual_secret_key_here
AWS_SESSION_TOKEN=your_session_token_here
AWS_REGION=us-east-1
S3_BUCKET_NAME=nir-mobile-test
```

**Note:** `AWS_SESSION_TOKEN` is optional and only needed for temporary credentials (e.g., from AWS STS or assumed roles).

The `.env` file is already added to `.gitignore` to prevent accidental credential commits.

You need AWS credentials with appropriate S3 permissions:
- Access Key ID
- Secret Access Key
- Bucket name: `nir-mobile-test`
- Region: e.g., `us-east-1`

### 3. Permissions

Add the following permissions to your `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

## Usage

### Basic File Upload

```kotlin
import com.example.popai.config.EnvironmentConfig
import com.example.popai.s3.S3Config
import com.example.popai.s3.S3Uploader
import com.example.popai.s3.UploadResult
import kotlinx.coroutines.launch

// Load configuration from .env file
EnvironmentConfig.loadFromPath("C:\\Users\\nirma\\AndroidStudioProjects\\popai\\.env")

// Create configuration from environment variables
val config = S3Config(
    bucketName = EnvironmentConfig.s3BucketName,
    region = EnvironmentConfig.awsRegion,
    accessKey = EnvironmentConfig.awsAccessKeyId,
    secretKey = EnvironmentConfig.awsSecretAccessKey,
    sessionToken = EnvironmentConfig.awsSessionToken  // Optional, for temporary credentials
)

// Create uploader
val uploader = S3Uploader(config)

// Upload a file
lifecycleScope.launch {
    val file = File("/path/to/your/file.jpg")
    val result = uploader.uploadFile(
        file = file,
        key = "uploads/my-image.jpg",
        contentType = "image/jpeg"
    )

    when (result) {
        is UploadResult.Success -> {
            println("Upload successful!")
            println("Key: ${result.key}")
            println("URL: ${result.url}")
        }
        is UploadResult.Failure -> {
            println("Upload failed: ${result.error.message}")
        }
    }
}
```

### Upload from InputStream

```kotlin
lifecycleScope.launch {
    val inputStream = contentResolver.openInputStream(uri)!!
    val contentLength = getFileSize(uri)

    val result = uploader.uploadStream(
        inputStream = inputStream,
        key = "uploads/my-file.pdf",
        contentLength = contentLength,
        contentType = "application/pdf"
    )

    when (result) {
        is UploadResult.Success -> println("Uploaded: ${result.url}")
        is UploadResult.Failure -> println("Error: ${result.error}")
    }
}
```

### Check if File Exists

```kotlin
lifecycleScope.launch {
    val exists = uploader.fileExists("uploads/my-image.jpg")
    if (exists) {
        println("File exists in S3")
    }
}
```

### Delete a File

```kotlin
lifecycleScope.launch {
    val success = uploader.deleteFile("uploads/my-image.jpg")
    if (success) {
        println("File deleted successfully")
    }
}
```

## Testing

### Running Unit Tests

Unit tests verify configuration validation and error handling without connecting to AWS:

```bash
./gradlew test --tests "com.example.popai.S3UploaderTest"
```

Located in: `app/src/test/java/com/example/popai/S3UploaderTest.kt`

### Running Integration Tests

Integration tests actually upload to your S3 bucket. Before running:

1. Ensure your `.env` file is configured with valid AWS credentials
2. The integration tests will automatically load credentials from the `.env` file
3. Run the tests:

```bash
./gradlew connectedAndroidTest --tests "com.example.popai.S3UploaderIntegrationTest"
```

Located in: `app/src/androidTest/java/com/example/popai/S3UploaderIntegrationTest.kt`

The integration tests will:
- Upload test files to S3
- Verify files exist
- Delete test files after verification
- Test both file and stream uploads

## Security Notes

1. **Never commit credentials** to version control
   - The `.env` file is automatically ignored by git
   - Use `.env.template` as a reference for required fields
   - Never hardcode credentials in your source code

2. Consider using for production:
   - Android Keystore for secure credential storage
   - AWS Cognito for mobile authentication
   - AWS STS for temporary credentials
   - Cognito Identity Pools for mobile identity management

3. The `EnvironmentConfig` class checks for credentials in this order:
   - System environment variables (highest priority)
   - `.env` file in project root
   - Default values (if provided)

## Architecture

The module is designed to be:
- **Testable**: Easy to mock and test without AWS connection
- **Modular**: Self-contained with clear interfaces
- **Async**: Uses coroutines for non-blocking operations
- **Type-safe**: Uses sealed classes for results
- **Validated**: Configuration validation at creation time

## Files Structure

```
# Project root
├── .env                  # Your AWS credentials (gitignored)
├── .env.template         # Template for required credentials
└── S3_UPLOADER_README.md # This documentation

# Source files
app/src/main/java/com/example/popai/
├── config/
│   └── EnvironmentConfig.kt      # Loads .env file
└── s3/
    ├── S3Config.kt               # S3 configuration data class
    └── S3Uploader.kt             # Main uploader class

# Tests
app/src/test/java/com/example/popai/
└── S3UploaderTest.kt             # Unit tests

app/src/androidTest/java/com/example/popai/
└── S3UploaderIntegrationTest.kt  # Integration tests
```

## Next Steps

1. **Configure your .env file**
   - Open `.env` in the project root
   - Replace placeholder values with your actual AWS credentials
   - Save the file (it's already gitignored for security)

2. **Run the unit tests**
   ```bash
   ./gradlew test --tests "com.example.popai.S3UploaderTest"
   ```

3. **Run the integration tests** (requires valid credentials in .env)
   ```bash
   ./gradlew connectedAndroidTest --tests "com.example.popai.S3UploaderIntegrationTest"
   ```

4. **Integrate the S3Uploader into your app**
   - Use `EnvironmentConfig` to load credentials
   - Create `S3Config` instance
   - Use `S3Uploader` for file operations

## Troubleshooting

### Authentication Errors
- Verify your access key and secret key are correct
- Check that your IAM user has S3 permissions
- Ensure the bucket name matches exactly

### Network Errors
- Verify internet connectivity
- Check that INTERNET permission is in AndroidManifest.xml
- Confirm the bucket region matches your configuration

### Upload Failures
- Verify the file exists and is readable
- Check that the bucket allows uploads (not public-access-blocked for your IAM user)
- Ensure content type is appropriate for your file
