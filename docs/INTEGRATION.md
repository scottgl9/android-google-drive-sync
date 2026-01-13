# Integration Guide

> How to integrate the Android Google Drive Sync library into your project

## Prerequisites

1. Android SDK 26+ (Android 8.0 Oreo)
2. Kotlin 1.9+
3. Google Cloud Project with Drive API enabled
4. OAuth 2.0 credentials configured

## Setup Google Cloud Project

### 1. Create a Google Cloud Project

1. Go to [Google Cloud Console](https://console.cloud.google.com/)
2. Create a new project or select an existing one
3. Note your project ID

### 2. Enable Google Drive API

1. Navigate to APIs & Services > Library
2. Search for "Google Drive API"
3. Click Enable

### 3. Configure OAuth Consent Screen

1. Navigate to APIs & Services > OAuth consent screen
2. Select External user type (or Internal for organization)
3. Fill in required fields:
   - App name
   - User support email
   - Developer contact information
4. Add scopes:
   - `https://www.googleapis.com/auth/drive.file`
   - `https://www.googleapis.com/auth/drive.appdata` (optional)

### 4. Create OAuth 2.0 Credentials

1. Navigate to APIs & Services > Credentials
2. Click Create Credentials > OAuth client ID
3. Select Android as application type
4. Enter your app's package name
5. Enter your app's SHA-1 certificate fingerprint
   ```bash
   # Debug keystore
   keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android

   # Release keystore
   keytool -list -v -keystore your-release-key.keystore -alias your-alias
   ```
6. Download the credentials JSON file

## Add Library Dependency

### Gradle (Kotlin DSL)

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

// app/build.gradle.kts
dependencies {
    implementation("com.vanespark:google-drive-sync:1.0.0")
}
```

### Gradle (Groovy)

```groovy
// settings.gradle
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

// app/build.gradle
dependencies {
    implementation 'com.vanespark:google-drive-sync:1.0.0'
}
```

## Initialize the Library

### With Hilt (Recommended)

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object SyncModule {

    @Provides
    @Singleton
    fun provideSyncConfiguration(): SyncConfiguration {
        return SyncConfiguration.builder()
            .appFolderName("MyApp")
            .addSyncDirectory(SyncDirectory("documents"))
            .conflictPolicy(ConflictPolicy.NEWER_WINS)
            .networkPolicy(NetworkPolicy.UNMETERED_ONLY)
            .build()
    }

    @Provides
    @Singleton
    fun provideGoogleSyncClient(
        @ApplicationContext context: Context,
        configuration: SyncConfiguration
    ): GoogleSyncClient {
        return GoogleSyncClient.create(context, configuration)
    }
}
```

### Without Dependency Injection

```kotlin
class MyApplication : Application() {

    lateinit var syncClient: GoogleSyncClient
        private set

    override fun onCreate() {
        super.onCreate()

        val configuration = SyncConfiguration.builder()
            .appFolderName("MyApp")
            .build()

        syncClient = GoogleSyncClient.create(this, configuration)
    }
}
```

## Basic Usage

See [README.md](../README.md) for basic usage examples.

## Advanced Configuration

See [CONFIGURATION.md](CONFIGURATION.md) for detailed configuration options.

## Troubleshooting

See [TROUBLESHOOTING.md](TROUBLESHOOTING.md) for common issues and solutions.
