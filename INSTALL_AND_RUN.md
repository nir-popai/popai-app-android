# Install and Run the S3 Uploader App on Your Phone

## Your App is Ready!

The APK has been built successfully: **6.1 MB**

Location: `app/build/outputs/apk/debug/app-debug.apk`

## Installation Options

### Option 1: Install from Android Studio (Recommended)

1. **Connect your phone to your computer via USB**
2. **Enable USB Debugging on your phone:**
   - Go to Settings → About Phone
   - Tap "Build Number" 7 times to enable Developer Options
   - Go back to Settings → Developer Options
   - Enable "USB Debugging"
   - Allow the computer when prompted on phone

3. **Open the project in Android Studio**
4. **Click the green "Run" button (▶️)** or press Shift+F10
5. **Select your device** from the list
6. The app will install and launch automatically!

### Option 2: Install APK Directly

1. **Copy the APK to your phone:**
   - Connect phone via USB
   - Copy `app-debug.apk` to your phone's Downloads folder
   - Or use: `adb install app/build/outputs/apk/debug/app-debug.apk`

2. **Install on phone:**
   - Open Files app on your phone
   - Navigate to Downloads
   - Tap on `app-debug.apk`
   - Tap "Install" (you may need to allow installing from unknown sources)

### Option 3: Use ADB Command Line

```bash
# From the project root directory
adb install app/build/outputs/apk/debug/app-debug.apk
```

## How to Use the App

1. **Launch the app** - Look for "Popai" icon on your phone

2. **Check Configuration Status:**
   - The app will automatically load your AWS credentials from the `.env` file
   - You should see a green checkmark with configuration details
   - It will show if you're using temporary credentials (with session token)

3. **Upload a Test File:**
   - Tap the "Upload Test File" button
   - The app will create a test file and upload it to S3
   - You'll see real-time status updates showing:
     - File creation
     - Upload progress
     - Success/failure status
     - S3 URL of uploaded file

4. **View Results:**
   - Successful uploads will show the S3 URL
   - Failed uploads will show error details
   - All uploads go to the `mobile-uploads/` folder in your bucket

## What the App Does

The app demonstrates the S3 uploader module by:

1. Loading AWS credentials from your `.env` file
2. Creating a test text file with device information
3. Uploading the file to S3 bucket `nir-mobile-test`
4. Displaying the upload status and URL
5. Cleaning up the local test file

## Troubleshooting

### "Configuration Error"
- Ensure `.env` file exists at: `C:\Users\nirma\AndroidStudioProjects\popai\.env`
- Verify all credentials are filled in (not placeholders)
- Check that AWS session token is valid and not expired

### "Upload Failed - Access Denied"
- Your AWS session token may have expired (they typically last 1-12 hours)
- Generate new temporary credentials and update `.env` file
- Rebuild the app: `./gradlew assembleDebug`

### "Upload Failed - Network Error"
- Check that your phone has internet connectivity
- Verify the app has Internet permission (already added)

### App Won't Install
- Enable "Install from Unknown Sources" in phone settings
- Check that USB debugging is enabled
- Try running: `adb devices` to verify phone is connected

## Testing in S3

After uploading, you can verify the files in your S3 bucket:

1. Go to AWS S3 Console
2. Open bucket `nir-mobile-test`
3. Navigate to `mobile-uploads/` folder
4. You should see files named `test-{timestamp}.txt`

## Rebuilding the App

If you change the credentials in `.env`, rebuild the app:

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
./gradlew assembleDebug
```

Then reinstall using any of the methods above.

## Features Implemented

✓ Load AWS credentials from `.env` file
✓ Support for temporary credentials with session token
✓ Real-time upload status display
✓ Error handling and user feedback
✓ Automatic file cleanup
✓ Device information in uploaded files
✓ Material Design UI

## Next Steps

Now that the basic upload works, you can:
- Add file picker to upload photos/documents
- Add upload history
- Implement background uploads
- Add download functionality
- Implement file management (list/delete files)

The S3Uploader module is fully functional and ready to be integrated into any feature you want to build!
