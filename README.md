# Gatekey Android Client

Android client for connecting to Gatekey VPN gateways and mesh networks.

## Features

- **Web-based SSO Login**: Authenticate using your organization's identity provider (OIDC)
- **API Key Authentication**: Alternative authentication using API keys
- **Gateway Connections**: Connect to VPN gateways for secure network access
- **Mesh Network Support**: Connect to mesh hubs for multi-site networking
- **Multi-connection Management**: View and manage active VPN connections
- **Material Design 3**: Modern UI built with Jetpack Compose
- **Embedded OpenVPN3**: Built-in VPN support without requiring external apps

## Requirements

- Android 8.0 (API 26) or higher
- Network connectivity to your Gatekey server

## Building

### Prerequisites

- JDK 17 (required, JDK 25+ not supported by current Android Gradle Plugin)
- Android SDK with API 35
- Android NDK 29.0.14206865 (for native OpenVPN3 builds)
- CMake (installed via Android SDK Manager)
- SWIG 4.0+ (for OpenVPN3 Java bindings generation)

### Install Build Dependencies

#### On Fedora/RHEL:
```bash
sudo dnf install swig
```

#### On Ubuntu/Debian:
```bash
sudo apt install swig
```

#### On macOS:
```bash
brew install swig
```

### Set up JDK 17

The build requires JDK 17. Set the JAVA_HOME environment variable:

```bash
# On Linux (Homebrew)
export JAVA_HOME=/home/linuxbrew/.linuxbrew/opt/openjdk@17

# On macOS (Homebrew)
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home

# On Ubuntu/Debian
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
```

### Build Steps

1. Clone the repository with submodules:
   ```bash
   git clone --recursive https://github.com/gatekey/gatekey-android-client.git
   cd gatekey-android-client
   ```

2. If you already cloned without submodules, initialize them:
   ```bash
   git submodule update --init --recursive
   ```

3. Build the debug APK:
   ```bash
   ./gradlew assembleDebug
   ```

4. The APK will be generated at:
   ```
   app/build/outputs/apk/debug/app-debug.apk
   ```

### Install on Device

#### Via ADB:
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

#### Via Gradle:
```bash
./gradlew installDebug
```

### Local Development Deployment

For rapid development and testing on a physical device:

#### One-Command Build and Deploy
```bash
# Set JDK 17 and build+install in one step
JAVA_HOME=/path/to/jdk17 ./gradlew installDebug
```

#### Common JDK 17 Paths
```bash
# Linux (Homebrew)
export JAVA_HOME=/home/linuxbrew/.linuxbrew/Cellar/openjdk@17/17.0.17/libexec

# macOS (Homebrew)
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec

# Ubuntu/Debian
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64

# Fedora/RHEL
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
```

#### Reinstalling After Signature Change
If you switch between debug and release builds, or rebuild with different signing keys:
```bash
# Uninstall existing app first
adb uninstall com.gatekey.client

# Then install new build
adb install app/build/outputs/apk/debug/app-debug.apk
```

#### Viewing Logs
```bash
# View app logs in real-time
adb logcat --pid=$(adb shell pidof -s com.gatekey.client)

# Filter for GateKey-specific logs
adb logcat | grep -i gatekey
```

#### Wireless ADB (optional)
```bash
# Connect device via USB first, then enable wireless
adb tcpip 5555
adb connect <device-ip>:5555
# Now you can disconnect USB
```

### Release Build

1. Create a signing key (one-time):
   ```bash
   keytool -genkey -v -keystore release-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias gatekey
   ```

2. Configure signing in `app/build.gradle.kts` or via environment variables

3. Build release:
   ```bash
   ./gradlew assembleRelease
   ```

4. The signed APK will be at:
   ```
   app/build/outputs/apk/release/app-release.apk
   ```

### Creating APKs for Distribution

#### Debug APK (for testing)
```bash
JAVA_HOME=/path/to/jdk17 ./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

#### Release APK (for distribution)
```bash
JAVA_HOME=/path/to/jdk17 ./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk
```

#### Build Both Variants
```bash
JAVA_HOME=/path/to/jdk17 ./gradlew assemble
```

#### Clean Build (if encountering issues)
```bash
JAVA_HOME=/path/to/jdk17 ./gradlew clean assembleDebug
```

#### APK Size Optimization
The release APK includes native libraries for all architectures. To create smaller APKs per architecture:
```bash
# Build separate APKs per ABI (add to app/build.gradle.kts)
# splits { abi { isEnable = true } }
./gradlew assembleRelease
# Creates: app-arm64-v8a-release.apk, app-armeabi-v7a-release.apk, etc.
```

## GitHub Actions

The project includes CI/CD workflows for automated builds and releases.

### Workflows

| Workflow | Trigger | Description |
|----------|---------|-------------|
| `ci.yml` | Push to main/develop, PRs | Build, test, and lint |
| `release.yml` | Tag push (v*) | Build signed APKs and create GitHub Release |

### Creating a Release

1. Update `versionCode` and `versionName` in `app/build.gradle.kts`
2. Commit and push changes
3. Create and push a tag:
   ```bash
   git tag v1.0.4
   git push origin v1.0.4
   ```
4. GitHub Actions will build and create a release with:
   - `gatekey-android-<version>-release.apk` (signed)
   - `gatekey-android-<version>-debug.apk`
   - `checksums.txt`

### Required Secrets

For signed release builds, configure these repository secrets:

| Secret | Description |
|--------|-------------|
| `KEYSTORE_BASE64` | Base64-encoded release keystore file |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Key alias in keystore (default: `gatekey`) |

To encode your keystore:
```bash
base64 -w 0 gatekey-release.keystore
# Copy output to KEYSTORE_BASE64 secret
```

### Local Release Build

To build a signed release locally:
```bash
KEYSTORE_PASSWORD="your-password" ./gradlew assembleRelease
```

## Testing

### Run Unit Tests
```bash
./gradlew test
```

### Run Instrumented Tests
```bash
./gradlew connectedAndroidTest
```

### Manual Testing Checklist

1. **Login Flow**:
   - [ ] SSO login redirects to browser and returns with token
   - [ ] API key authentication works
   - [ ] Invalid credentials show error message

2. **Gateway Connection**:
   - [ ] Gateway list loads from server
   - [ ] VPN permission prompt appears on first connect
   - [ ] Connection establishes successfully
   - [ ] Connection status updates in UI
   - [ ] Disconnect works properly

3. **Mesh Hub Connection**:
   - [ ] Mesh hub list loads from server
   - [ ] Mesh connection generates correct config
   - [ ] Connection establishes successfully

4. **Settings**:
   - [ ] Server URL can be changed
   - [ ] Logout clears credentials
   - [ ] Dark/light theme works

## Project Structure

```
gatekey-android-client/
├── app/                              # Main application module
│   └── src/main/java/com/gatekey/client/
│       ├── GatekeyApplication.kt     # Application class
│       ├── MainActivity.kt           # Main activity with OAuth callback
│       ├── data/
│       │   ├── api/
│       │   │   ├── GatekeyApi.kt     # Retrofit API interface
│       │   │   └── AuthInterceptor.kt # Auth header interceptor
│       │   ├── model/
│       │   │   ├── AuthModels.kt     # Authentication models
│       │   │   ├── GatewayModels.kt  # Gateway/mesh models
│       │   │   └── SettingsModels.kt # Settings models
│       │   └── repository/
│       │       ├── AuthRepository.kt  # Authentication logic
│       │       ├── GatewayRepository.kt # Gateway management
│       │       ├── SettingsRepository.kt # Settings persistence
│       │       └── TokenRepository.kt # Token storage
│       ├── di/
│       │   └── NetworkModule.kt      # Hilt DI module
│       ├── vpn/
│       │   ├── VpnManager.kt         # VPN connection orchestration
│       │   └── OpenVpnServiceManager.kt # OpenVPN library interface
│       └── ui/
│           ├── GatekeyApp.kt         # Navigation host
│           ├── theme/
│           │   └── Theme.kt          # Material 3 theme
│           ├── screens/
│           │   ├── login/LoginScreen.kt
│           │   ├── home/HomeScreen.kt
│           │   ├── connections/ConnectionsScreen.kt
│           │   └── settings/SettingsScreen.kt
│           └── viewmodel/
│               ├── AuthViewModel.kt
│               ├── ConnectionViewModel.kt
│               └── SettingsViewModel.kt
└── openvpn/                          # OpenVPN library module (ics-openvpn)
    └── main/
        └── src/main/
            ├── java/de/blinkt/openvpn/  # OpenVPN Java/Kotlin code
            └── cpp/                      # Native libraries
                ├── openvpn3/             # OpenVPN3 core
                ├── openssl/              # OpenSSL cryptography
                ├── mbedtls/              # mbedTLS cryptography
                ├── lz4/                  # LZ4 compression
                ├── asio/                 # Asio networking
                └── fmt/                  # fmt formatting library
```

## Configuration

### Server Setup

1. Launch the app
2. Enter your Gatekey server URL (e.g., `vpn.example.com`)
3. Tap "Login with SSO" to authenticate via your identity provider

### API Key Authentication

1. Enter your server URL
2. Tap "Use API Key"
3. Enter your API key (format: `gk_...`)
4. Tap "Login with API Key"

## API Endpoints Used

| Endpoint | Description |
|----------|-------------|
| `GET /api/v1/auth/providers` | List available auth providers |
| `GET /api/v1/auth/cli/login` | Initiate CLI/mobile login flow |
| `GET /api/v1/auth/cli/complete` | Complete login and get token |
| `GET /api/v1/auth/api-key/validate` | Validate API key |
| `GET /api/v1/auth/session` | Get current session info |
| `GET /api/v1/gateways` | List available gateways |
| `GET /api/v1/mesh/hubs` | List available mesh hubs |
| `POST /api/v1/configs/generate` | Generate VPN config |
| `POST /api/v1/mesh/generate-config` | Generate mesh config |

## VPN Integration

The app uses the embedded OpenVPN3 library (from ics-openvpn) for VPN connections:

1. User initiates connection to a gateway or mesh hub
2. App requests VPN permission (Android system dialog)
3. VPN config is fetched from Gatekey server API
4. OpenVPN3 parses config and establishes tunnel
5. Status updates shown via VpnStatus callbacks

### Architecture

```
┌────────────────────┐     ┌────────────────────┐
│   ConnectionVM     │────▶│     VpnManager     │
└────────────────────┘     └────────────────────┘
                                    │
                                    ▼
                           ┌────────────────────┐
                           │ OpenVpnServiceMgr  │
                           └────────────────────┘
                                    │
                                    ▼
                           ┌────────────────────┐
                           │  OpenVPNService    │
                           │   (VpnService)     │
                           └────────────────────┘
                                    │
                                    ▼
                           ┌────────────────────┐
                           │ OpenVPN3 Native    │
                           │ (C++ via JNI)      │
                           └────────────────────┘
```

## Security

- Tokens stored using Android DataStore with encryption
- HTTPS required for all server communication
- VPN configs include short-lived certificates (24-hour default)
- OAuth state parameter validation prevents CSRF attacks
- OpenVPN3 provides industry-standard encryption (AES-256-GCM)

## Permissions

| Permission | Purpose |
|------------|---------|
| `INTERNET` | Network communication |
| `ACCESS_NETWORK_STATE` | Check network connectivity |
| `ACCESS_WIFI_STATE` | Detect WiFi connection |
| `CHANGE_NETWORK_STATE` | VPN tunnel configuration |
| `FOREGROUND_SERVICE` | VPN service notification |
| `FOREGROUND_SERVICE_SPECIAL_USE` | VPN special use case |
| `POST_NOTIFICATIONS` | Connection status notifications |

## Troubleshooting

### Build Fails with JDK Version Error
Ensure you're using JDK 17. The Android Gradle Plugin doesn't support JDK 25+.

Set JAVA_HOME explicitly:
```bash
JAVA_HOME=/path/to/jdk17 ./gradlew assembleDebug
```

### INSTALL_FAILED_UPDATE_INCOMPATIBLE
The installed app was signed with a different key. Uninstall first:
```bash
adb uninstall com.gatekey.client
adb install app/build/outputs/apk/debug/app-debug.apk
```

### NDK Not Found
Install NDK via Android Studio: SDK Manager > SDK Tools > NDK (Side by side) > 29.0.14206865

### CMake Not Found
Install via Android Studio: SDK Manager > SDK Tools > CMake

### SWIG Not Found
Install SWIG for your platform (see Build Dependencies section above).

### VPN Permission Denied
The user must grant VPN permission when prompted. If denied, the connection will fail.

### Connection Timeout
Check that the Gatekey server is accessible and the server URL is correct.

## License

Proprietary - Gatekey
