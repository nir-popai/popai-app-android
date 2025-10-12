package com.example.popai.config

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.util.Properties

/**
 * Loads environment variables from .env file
 *
 * This class reads configuration from a .env file in the project root directory.
 * It provides a centralized way to access all environment variables.
 */
object EnvironmentConfig {

    private val properties = Properties()
    private var isLoaded = false

    /**
     * Loads the .env file from the project root
     *
     * @param context Android context
     * @return true if the file was loaded successfully, false otherwise
     */
    fun load(context: Context): Boolean {
        if (isLoaded) return true

        // Try to load from assets (packaged in APK as env.properties)
        return try {
            context.assets.open("env.properties").use { inputStream ->
                properties.load(inputStream)
                isLoaded = true
                println("EnvironmentConfig: Successfully loaded env.properties from assets")
                return true
            }
        } catch (e: Exception) {
            println("EnvironmentConfig: Failed to load from assets: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    /**
     * Loads the .env file from a specific path
     *
     * @param envFilePath Absolute path to the .env file
     * @return true if loaded successfully
     */
    fun loadFromPath(envFilePath: String): Boolean {
        return try {
            val envFile = File(envFilePath)
            if (envFile.exists()) {
                FileInputStream(envFile).use { inputStream ->
                    properties.load(inputStream)
                    isLoaded = true
                    return true
                }
            }
            false
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Gets a configuration value by key
     *
     * @param key The configuration key
     * @param defaultValue Default value if key not found
     * @return The configuration value or default
     */
    fun get(key: String, defaultValue: String = ""): String {
        // First try environment variable, then properties file, then default
        return System.getenv(key) ?: properties.getProperty(key, defaultValue)
    }

    /**
     * Gets AWS Access Key ID
     */
    val awsAccessKeyId: String
        get() = get("AWS_ACCESS_KEY_ID")

    /**
     * Gets AWS Secret Access Key
     */
    val awsSecretAccessKey: String
        get() = get("AWS_SECRET_ACCESS_KEY")

    /**
     * Gets AWS Session Token (optional, for temporary credentials)
     */
    val awsSessionToken: String?
        get() = get("AWS_SESSION_TOKEN").takeIf { it.isNotBlank() }

    /**
     * Gets AWS Region
     */
    val awsRegion: String
        get() = get("AWS_REGION", "us-east-1")

    /**
     * Gets S3 Bucket Name
     */
    val s3BucketName: String
        get() = get("S3_BUCKET_NAME", "nir-mobile-test")

    /**
     * Checks if all required AWS credentials are present
     */
    fun hasRequiredCredentials(): Boolean {
        return awsAccessKeyId.isNotBlank() &&
               awsSecretAccessKey.isNotBlank() &&
               awsRegion.isNotBlank() &&
               s3BucketName.isNotBlank()
    }

    /**
     * Clears all loaded properties (useful for testing)
     */
    fun clear() {
        properties.clear()
        isLoaded = false
    }
}
