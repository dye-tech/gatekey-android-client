# GateKey Android Client - Development Notes

## Java Requirements

This project requires **Java 21**. The `gradle.properties` file is configured to use `/usr/lib/jvm/java-21-openjdk` automatically.

If the build fails with Java version errors, ensure Java 21 is installed:
```bash
# Fedora/RHEL
sudo dnf install java-21-openjdk-devel

# Ubuntu/Debian
sudo apt install openjdk-21-jdk

# Verify installation
/usr/lib/jvm/java-21-openjdk/bin/java -version
```

## Build Commands

Build release APK:
```bash
cd /home/jesse/Desktop/gatekey-android-client
./gradlew clean assembleRelease
```

Build debug APK (faster, for testing):
```bash
./gradlew assembleDebug
```

APK output location: `app/build/outputs/apk/release/app-release.apk`

## Deployment to Web UI

When deploying the APK to the GateKey web UI, you MUST copy to BOTH locations:

```bash
# Copy to public folder (source)
cp app/build/outputs/apk/release/app-release.apk /home/jesse/Desktop/GateKey/web/public/mobile/gatekey-android.apk

# Copy to dist folder (what Docker actually serves)
cp app/build/outputs/apk/release/app-release.apk /home/jesse/Desktop/GateKey/web/dist/mobile/gatekey-android.apk
```

Then rebuild and deploy Docker:
```bash
cd /home/jesse/Desktop/GateKey
docker build --no-cache -f Dockerfile.web -t harbor.dye.tech/library/gatekey-web:latest .
docker push harbor.dye.tech/library/gatekey-web:latest
kubectl rollout restart deployment gatekey-web -n gatekey
```

## ADB Testing

Install APK directly to connected device:
```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

Monitor logs for crashes:
```bash
adb logcat -c && adb logcat | grep -iE "gatekey|AndroidRuntime|FATAL"
```

Force stop and relaunch:
```bash
adb shell am force-stop com.gatekey.client
adb shell am start -n com.gatekey.client/.MainActivity
```

## Common Issues

### Gson Null Safety
Kotlin non-null types do NOT protect against Gson deserialization. If the server returns `null` for a field declared as `String` (not `String?`), Gson will set it to Java null, causing crashes when accessed.

Always make server response fields nullable with defaults:
```kotlin
@SerializedName("field_name") val fieldName: String? = null
```

And handle nulls in UI:
```kotlin
fieldName?.let { value ->
    Text(text = value)
}
```
