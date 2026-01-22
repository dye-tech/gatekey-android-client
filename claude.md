# Claude Code Instructions for Gatekey Android Client

## Project Overview

This is the Gatekey Android VPN client built with Kotlin, Jetpack Compose, and embedded OpenVPN3. The app connects to Gatekey server infrastructure to provide secure VPN access to corporate networks and mesh hubs.

## Architecture

### Technology Stack
- **Language**: Kotlin
- **UI Framework**: Jetpack Compose with Material Design 3
- **DI**: Hilt (Dagger)
- **Networking**: Retrofit + OkHttp
- **State Management**: Kotlin StateFlow/MutableStateFlow
- **VPN**: Embedded OpenVPN3 from ics-openvpn (converted to library module)
- **Build System**: Gradle with Kotlin DSL

### Module Structure
- `app/` - Main application module
- `openvpn/` - OpenVPN library module (ics-openvpn/main)

### Key Directories
```
app/src/main/java/com/gatekey/client/
├── data/api/          # Retrofit API interfaces
├── data/model/        # Data classes (use @SerializedName for JSON)
├── data/repository/   # Business logic and data access
├── di/                # Hilt modules
├── vpn/               # VPN connection management
├── ui/screens/        # Compose screens
├── ui/viewmodel/      # ViewModels with Hilt injection
└── ui/theme/          # Material 3 theming
```

## Building

### Prerequisites Check
Before building, ensure:
- JDK 21 (NOT JDK 25+)
- Android NDK 29.0.14206865
- SWIG installed (for OpenVPN3 bindings)

### Build Command
```bash
export JAVA_HOME=/home/linuxbrew/.linuxbrew/opt/openjdk@21  # Or your JDK 21 path
./gradlew assembleDebug
```

### Common Build Errors

1. **"Java version not supported"**: Use JDK 21, not a newer version
2. **"SWIG not found"**: Install SWIG (`dnf install swig` / `apt install swig`)
3. **"NDK not found"**: Check `ndkVersion = "29.0.14206865"` in build.gradle.kts

## Code Patterns

### Adding a New API Endpoint

1. Add to `GatekeyApi.kt`:
```kotlin
@GET("api/v1/endpoint")
suspend fun getEndpoint(): Response<EndpointResponse>
```

2. Create data model in `data/model/`:
```kotlin
data class EndpointResponse(
    @SerializedName("field_name")  // Use @SerializedName for snake_case API fields
    val fieldName: String
)
```

3. Add repository method in `data/repository/`:
```kotlin
suspend fun getEndpoint(): Result<EndpointData> {
    return try {
        val response = api.getEndpoint()
        if (response.isSuccessful) {
            Result.Success(response.body()!!)
        } else {
            Result.Error("Failed: ${response.message()}")
        }
    } catch (e: Exception) {
        Result.Error(e.message ?: "Unknown error")
    }
}
```

### Adding a New Screen

1. Create screen in `ui/screens/newscreen/`:
```kotlin
@Composable
fun NewScreen(
    viewModel: NewViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    // UI implementation
}
```

2. Create ViewModel in `ui/viewmodel/`:
```kotlin
@HiltViewModel
class NewViewModel @Inject constructor(
    private val repository: SomeRepository
) : ViewModel() {
    private val _state = MutableStateFlow(NewState())
    val state: StateFlow<NewState> = _state.asStateFlow()
}
```

3. Add navigation in `GatekeyApp.kt`:
```kotlin
composable("new_screen") {
    NewScreen()
}
```

### VPN Connection Flow

The VPN system uses:
- `VpnManager` - High-level connection orchestration
- `OpenVpnServiceManager` - Interface to embedded OpenVPN
- `OpenVPNService` - Android VpnService implementation (from openvpn module)

To initiate a connection:
```kotlin
viewModelScope.launch {
    vpnManager.connectToGateway(gatewayId)  // Handles config fetch + connection
}
```

## Important Files to Know

| File | Purpose |
|------|---------|
| `GatekeyApi.kt` | All API endpoint definitions |
| `VpnManager.kt` | VPN connection state machine |
| `OpenVpnServiceManager.kt` | Bridge to OpenVPN library |
| `GatewayModels.kt` | Gateway/mesh data classes |
| `AuthModels.kt` | Authentication data classes |
| `NetworkModule.kt` | Retrofit/OkHttp configuration |
| `settings.gradle.kts` | Module configuration |
| `app/build.gradle.kts` | App dependencies and build config |
| `openvpn/main/build.gradle.kts` | OpenVPN library build config |

## API Field Naming

The Gatekey server uses **snake_case** for JSON field names. Always use `@SerializedName`:

```kotlin
data class Gateway(
    @SerializedName("id")
    val id: String,
    @SerializedName("public_key")  // Server sends public_key
    val publicKey: String,         // Kotlin uses camelCase
    @SerializedName("api_url")
    val apiUrl: String?
)
```

## Testing on Device

### Install via ADB
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### View Logs
```bash
adb logcat | grep -E "(GateKey|OpenVPN|VpnManager)"
```

### Relevant Log Tags
- `VpnManager` - Connection state changes
- `OpenVpnServiceManager` - OpenVPN library interactions
- `GatewayRepository` - API calls for configs
- `AuthRepository` - Authentication flow

## OpenVPN Module Notes

The `openvpn/` module is a converted version of ics-openvpn:

- Configured as a **library module** (not application)
- Uses flavor dimensions: `implementation` (skeleton) + `ovpnimpl` (ovpn23)
- Native code in `openvpn/main/src/main/cpp/`
- Java/Kotlin code in `openvpn/main/src/main/java/de/blinkt/openvpn/`

### Key OpenVPN Classes
- `VPNLaunchHelper.startOpenVpn(profile, context, reason, replace)` - Start VPN
- `VpnStatus.addStateListener(listener)` - Listen for status changes
- `ConfigParser` - Parse .ovpn config files
- `ProfileManager` - Manage VPN profiles
- `OpenVPNService` - The actual VpnService implementation

### OpenVPN Actions (private in OpenVPNService)
```kotlin
const val PAUSE_VPN = "de.blinkt.openvpn.PAUSE_VPN"
const val RESUME_VPN = "de.blinkt.openvpn.RESUME_VPN"
const val DISCONNECT_VPN = "de.blinkt.openvpn.DISCONNECT_VPN"  // This one is public
```

## Common Tasks

### Update API Models for New Server Response
1. Check server response format (snake_case)
2. Update data class with `@SerializedName` annotations
3. Handle nullable fields appropriately

### Debug VPN Connection Issues
1. Check `VpnManager` state flow
2. Look at OpenVPN logs via `VpnStatus`
3. Verify config format from server

### Add New Settings Option
1. Add to `SettingsModels.kt`
2. Update `SettingsRepository.kt` persistence
3. Add UI in `SettingsScreen.kt`
4. Update `SettingsViewModel.kt`

## Don't Forget

- Always use `@SerializedName` for API response fields
- Run `./gradlew assembleDebug` to verify changes compile
- The OpenVPN module has its own manifest that merges with the app
- VPN permissions must be requested at runtime (Android system dialog)
- Use JDK 21 for builds (set JAVA_HOME)
