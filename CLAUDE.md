# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Popai is a medical audio recording Android app that records healthcare conversations, encrypts them, and uploads them to S3 in manageable chunks. The app uses Room database for local storage, foreground services for reliable recording/upload, and AES-256 encryption for data security.

**Key Technologies:**
- Kotlin with Coroutines
- Room Database (SQLite)
- Android Foreground Services
- AES-256 encryption (Android Keystore)
- S3 presigned URL uploads

## Build Commands

### Set JAVA_HOME (Required on Windows)
```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
```

### Build Debug APK
```bash
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

### Build Release APK
```bash
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk
```

### Run Tests
```bash
# Unit tests (no device needed)
./gradlew test

# Integration tests (requires connected device/emulator)
./gradlew connectedAndroidTest
```

### Install to Device
```bash
# Via ADB
adb install app/build/outputs/apk/debug/app-debug.apk

# Build and install
./gradlew installDebug
```

### Check Logs
```bash
# View all app logs
adb logcat | findstr "Popai"

# View specific service logs
adb logcat | findstr "RecordingService"
adb logcat | findstr "UploadService"
```

## Architecture Overview

### Core Components

**Activities:**
- `MainActivity.kt` - Primary recording UI with start/pause/stop controls
- `RecordingsActivity.kt` - Recording history with upload status tracking
- `RecordingsAdapter.kt` - RecyclerView adapter for displaying recording list

**Foreground Services:**
- `RecordingService.kt` - Handles audio recording with 5-minute chunk rotation, pause/resume, and encryption
- `UploadService.kt` - Manages sequential chunk uploads to S3 with retry logic (max 3 attempts)

**Database (Room):**
- `AppDatabase.kt` - Singleton Room database (version 3)
- `RecordingEntity.kt` + `RecordingDao.kt` - Main recording metadata
- `ChunkEntity.kt` + `ChunkDao.kt` - Individual chunk tracking

**API Layer:**
- `PresignedUrlService.kt` - HTTP client for requesting S3 presigned URLs from backend

**Encryption:**
- `EncryptionManager.kt` - AES-256-CBC encryption using Android Keystore

**Data Models:**
- `RecordingManifest.kt` - JSON manifest structure for server validation

### Recording Flow

1. User enters patient name → `MainActivity` shows dialog
2. Press Start → Bind to `RecordingService` → Start MediaRecorder
3. Every 5 minutes → Auto-rotate chunk → Encrypt previous chunk → Create new `ChunkEntity`
4. Press Pause → Pause MediaRecorder → Track pause duration
5. Press Stop → Finalize chunk → Encrypt → Update `RecordingEntity` → Launch `UploadService`

### Upload Flow

1. `UploadService` queries all PENDING chunks for recording
2. Request presigned URLs from API by sending patient name, healthcare professional, and file count
3. Server generates human-readable `recordingId` (e.g., `john_doe_dr_sarah_smith_20251022_143000`)
4. PUT request with raw audio files to S3 using presigned URLs
5. Update `ChunkEntity` with S3 URL and status
6. After all chunks upload → Generate `manifest.json` using server's `recordingId` → Upload manifest
7. Update `RecordingEntity` status (UPLOADED/PARTIAL/FAILED)

### Database Schema

**RecordingEntity:**
- `id` (UUID - local database identifier only, NOT used for S3 uploads)
- `patientName`, `healthcareProfessional` (sent to API for generating S3 folder name)
- `startTime`, `endTime`, `totalDurationMs`, `pausedDurationMs`
- `status` (RECORDING/COMPLETED/UPLOADING/UPLOADED/FAILED/PARTIAL)
- `chunkCount`, `uploadedChunks`, `failedChunks`
- `totalBytes`, `uploadedBytes`, `currentChunkProgress`

**Note:** The local `id` (UUID) is only used for database relationships. The server generates a human-readable `recordingId` (e.g., `john_doe_dr_sarah_smith_20251022_143000`) which is used for S3 folder names and in the manifest.json file.

**ChunkEntity:**
- `id` (UUID), `recordingId` (FK), `chunkIndex`
- `localFilePath`, `encryptedFilePath`, `durationMs`, `fileSize`
- `uploadStatus` (PENDING/UPLOADING/UPLOADED/FAILED)
- `s3Key`, `s3Url`, `uploadAttempts`, `lastError`

## Configuration

### Environment Configuration
**File:** `app/src/main/assets/env.properties`
```properties
AWS_REGION=us-east-1
S3_BUCKET_NAME=nir-mobile-test
```

### Backend API
**Presigned URL Endpoint:**
```
POST https://iijdu4x4ac.execute-api.us-east-1.amazonaws.com/prod/upload/presigned-url
```

**Request Format:**
```json
{
  "patientName": "John Doe",
  "healthcareProfessional": "Dr. Sarah Smith",
  "fileCount": 2,
  "timestamp": "2025-10-22T14:30:00Z"  // Optional - defaults to current time if not provided
}
```

**Response Format:**
```json
{
  "recordingId": "john_doe_dr_sarah_smith_20251022_143000",
  "bucketName": "nir-mobile-test",
  "presignedUrls": [
    {
      "file": "manifest.json",
      "url": "https://...",
      "key": "medical-recordings/john_doe_dr_sarah_smith_20251022_143000/manifest.json"
    },
    {
      "file": "chunk_0.m4a",
      "url": "https://...",
      "key": "medical-recordings/john_doe_dr_sarah_smith_20251022_143000/chunk_0.m4a"
    }
  ],
  "expiresIn": 3600
}
```

**Key Points:**
- Server generates human-readable `recordingId` based on patient name, provider, and timestamp
- S3 folder names are human-readable (e.g., `john_doe_dr_sarah_smith_20251022_143000`)
- The server's `recordingId` is used in the `manifest.json`, not the local database UUID
- Timestamp is formatted in ISO 8601 format using the recording's start time

### Recording Settings (Hardcoded)
- **Chunk Duration:** 5 minutes (300,000 ms)
- **Audio Codec:** AAC
- **Bitrate:** 128 kbps
- **Sample Rate:** 44.1 kHz
- **Format:** MPEG-4 (.m4a)
- **Max Retry Attempts:** 3 per chunk

## Key Development Notes

### Service Architecture
- Both `RecordingService` and `UploadService` extend `LifecycleService` for coroutine support
- Services run as foreground services with persistent notifications (required for Android 8+)
- Use `ACTION_START_RECORDING`, `ACTION_PAUSE_RECORDING`, etc. for service control

### Database Operations
- All database operations are suspend functions or return Flow
- Use `lifecycleScope.launch` for coroutine-based database calls
- DAOs emit Flow for reactive UI updates
- Database queries should use `Dispatchers.IO`

### Chunk Management
- Chunks are created every 5 minutes during recording
- Each chunk is encrypted immediately after recording for local storage
- **Upload uses raw (unencrypted) files**, not encrypted versions
- Upload happens sequentially (not parallel) to manage bandwidth
- Retry logic: 3 attempts per chunk (max 3 total attempts)

### Encryption
- Uses Android Keystore for key generation (hardware-backed when available)
- AES-256-CBC with random IV per file
- IV is prepended to encrypted file (first 16 bytes)
- Encryption happens on `Dispatchers.IO` to avoid blocking UI

### Status Tracking
**Recording Status Flow:**
```
RECORDING → COMPLETED → UPLOADING → UPLOADED/PARTIAL/FAILED
```

**Chunk Status Flow:**
```
PENDING → UPLOADING → UPLOADED
       ↘ UPLOADING → PENDING (retry)
       ↘ UPLOADING → FAILED (3 retries exhausted)
```

### File Paths

**Local Storage:**
- Raw audio chunks: `{externalCacheDir}/recordings/{localUUID}/chunk_{index}.m4a`
- Encrypted chunks: `{externalCacheDir}/recordings/{localUUID}/chunk_{index}.m4a.enc`
- Manifest (temporary): `{cacheDir}/manifest_{localUUID}.json`

**S3 Storage:**
- S3 folder: `medical-recordings/{patientName}_{providerName}_{timestamp}/`
- Example: `medical-recordings/john_doe_dr_sarah_smith_20251022_143000/`
- Files in S3: `chunk_0.m4a`, `chunk_1.m4a`, ..., `manifest.json`

### Permissions Required
- `RECORD_AUDIO` - Microphone access
- `FOREGROUND_SERVICE_MICROPHONE` - Recording service
- `FOREGROUND_SERVICE_DATA_SYNC` - Upload service
- `INTERNET` - API and S3 uploads
- `POST_NOTIFICATIONS` - Service notifications

## Common Development Tasks

### Adding New Recording Parameters
1. Update `RecordingEntity` with new field
2. Increment database version in `AppDatabase`
3. Add migration or use `fallbackToDestructiveMigration()`
4. Update `RecordingService` to capture/store new data
5. Update UI layouts to display new field

### Modifying Chunk Duration
1. Change `CHUNK_DURATION_MS` in `RecordingService`
2. Test upload performance with new chunk size
3. Consider S3 presigned URL timeout limits

### Changing Upload Retry Logic
1. Modify `MAX_RETRY_ATTEMPTS` in `UploadService`
2. Adjust retry delay calculation in upload loop
3. Update error handling for max retries exceeded

### Adding New Upload Providers (Beyond S3)
1. Create new service class similar to `UploadService`
2. Implement provider-specific upload logic
3. Update `ChunkEntity` to track provider-specific metadata
4. Add provider selection in UI

### Debugging Recording Issues
1. Check `adb logcat | findstr RecordingService` for service logs
2. Verify microphone permissions granted
3. Check `externalCacheDir` for chunk files
4. Query database for ChunkEntity records: `adb shell run-as com.example.popai`

### Debugging Upload Issues
1. Check presigned URL validity (URLs expire)
2. Verify network connectivity
3. Inspect `ChunkEntity.lastError` for failure reasons
4. Test S3 bucket permissions manually with curl

## Testing Strategy

### Unit Tests
- Test configuration validation
- Test encryption/decryption logic
- Mock database operations
- Run with: `./gradlew test`

### Integration Tests
- Test actual S3 uploads (requires `.env` configuration)
- Test database migrations
- Test service lifecycle
- Run with: `./gradlew connectedAndroidTest --tests "com.example.popai.*IntegrationTest"`

### Manual Testing Checklist
- [ ] Start recording → Verify chunk creation every 5 min
- [ ] Pause/Resume → Verify pause duration tracking
- [ ] Stop recording → Verify encryption of all chunks
- [ ] Upload → Check S3 bucket for files
- [ ] Retry failed upload → Verify attempt counter increments
- [ ] View recordings list → Verify status updates in real-time

## API Changes and Migration Notes

### Presigned URL API Update (October 2025)

The `/upload/presigned-url` endpoint was updated to generate human-readable folder names instead of UUIDs.

**Changes Made:**
1. **Request format changed** from:
   ```json
   {
     "recordingId": "550e8400-e29b-41d4-a716-446655440000",
     "fileCount": 2
   }
   ```
   To:
   ```json
   {
     "patientName": "John Doe",
     "healthcareProfessional": "Dr. Sarah Smith",
     "fileCount": 2,
     "timestamp": "2025-10-22T14:30:00Z"
   }
   ```

2. **Updated files:**
   - `PresignedUrlService.kt` - Changed method signature to accept patient/provider info
   - `UploadService.kt` - Updated to send new request format and use server's recordingId
   - `uploadManifest()` function now accepts and uses server-generated recordingId

3. **Benefits:**
   - S3 folders now have human-readable names (e.g., `john_doe_dr_sarah_smith_20251022_143000`)
   - Easier to locate specific recordings in S3 console
   - Manifest uses meaningful recordingId for server processing

## Troubleshooting

### "JAVA_HOME is not set"
Set the path to Android Studio's bundled JDK:
```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
```

### "Build failed: Unresolved reference"
Clean and rebuild:
```bash
./gradlew clean
./gradlew build
```

### "Recording Service crashes"
- Check microphone permission granted
- Verify `FOREGROUND_SERVICE_MICROPHONE` in manifest
- Check device API level >= 24

### "Upload fails with 403 Forbidden"
- Presigned URL may have expired
- Verify backend API is generating valid URLs
- Check S3 bucket CORS configuration

### "Chunks not appearing in S3"
- Check upload service logs: `adb logcat | findstr UploadService`
- Verify network connectivity on device
- Inspect `ChunkEntity.lastError` in database
- Test presigned URL manually with curl

### Database Migration Errors
Option 1: Add proper migration
```kotlin
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE recordings ADD COLUMN newField TEXT")
    }
}
```

Option 2: Clear app data (dev only):
```bash
adb shell pm clear com.example.popai
```
