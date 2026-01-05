package com.gatekey.client.ui.screens.settings

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.Divider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.gatekey.client.data.model.LogLevel
import com.gatekey.client.ui.viewmodel.AuthViewModel
import com.gatekey.client.ui.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    authViewModel: AuthViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val currentUser by authViewModel.currentUser.collectAsState()
    val settings by settingsViewModel.settings.collectAsState()
    val logFileSize by settingsViewModel.logFileSize.collectAsState()

    var showLogoutDialog by remember { mutableStateOf(false) }
    var showLogLevelDialog by remember { mutableStateOf(false) }
    var showClearLogsDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // Account section
            SettingsSection(title = "Account") {
                SettingsItem(
                    icon = Icons.Default.AccountCircle,
                    title = currentUser?.name ?: "User",
                    subtitle = currentUser?.email ?: ""
                )

                SettingsItem(
                    icon = Icons.Default.Dns,
                    title = "Server",
                    subtitle = settings.serverUrl.ifEmpty { "Not configured" }
                )

                if (currentUser?.groups?.isNotEmpty() == true) {
                    SettingsItem(
                        icon = Icons.Default.Group,
                        title = "Groups",
                        subtitle = currentUser?.groups?.joinToString(", ") ?: ""
                    )
                }
            }

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            // VPN Settings section
            SettingsSection(title = "VPN Settings") {
                SettingsSwitch(
                    icon = Icons.Default.PlayArrow,
                    title = "Auto-connect",
                    subtitle = "Connect automatically on app start",
                    checked = settings.autoConnect,
                    onCheckedChange = { settingsViewModel.updateAutoConnect(it) }
                )

                SettingsSwitch(
                    icon = Icons.Default.Notifications,
                    title = "Notifications",
                    subtitle = "Show VPN status notifications",
                    checked = settings.showNotifications,
                    onCheckedChange = { settingsViewModel.updateShowNotifications(it) }
                )

                SettingsSwitch(
                    icon = Icons.Default.Refresh,
                    title = "Keep alive",
                    subtitle = "Maintain connection in background",
                    checked = settings.keepAlive,
                    onCheckedChange = { settingsViewModel.updateKeepAlive(it) }
                )
            }

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            // Advanced section
            SettingsSection(title = "Advanced") {
                SettingsClickableItem(
                    icon = Icons.Default.BugReport,
                    title = "Log level",
                    subtitle = settings.logLevel.name,
                    onClick = { showLogLevelDialog = true }
                )

                SettingsClickableItem(
                    icon = Icons.Default.Share,
                    title = "Export logs",
                    subtitle = "Share log file ($logFileSize)",
                    onClick = {
                        val intent = settingsViewModel.getLogShareIntent()
                        if (intent != null) {
                            context.startActivity(Intent.createChooser(intent, "Share GateKey Logs"))
                        } else {
                            Toast.makeText(context, "No logs to export", Toast.LENGTH_SHORT).show()
                        }
                    }
                )

                SettingsClickableItem(
                    icon = Icons.Default.Delete,
                    title = "Clear logs",
                    subtitle = "Delete all log data",
                    onClick = { showClearLogsDialog = true }
                )
            }

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            // About section
            SettingsSection(title = "About") {
                SettingsItem(
                    icon = Icons.Default.Info,
                    title = "Version",
                    subtitle = "1.0.0"
                )

                SettingsClickableItem(
                    icon = Icons.Default.Description,
                    title = "Licenses",
                    subtitle = "Open source licenses",
                    onClick = { /* TODO: Show licenses */ }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Logout button
            Button(
                onClick = { showLogoutDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(Icons.Default.Logout, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Logout")
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // Logout confirmation dialog
    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            icon = { Icon(Icons.Default.Logout, contentDescription = null) },
            title = { Text("Logout") },
            text = { Text("Are you sure you want to logout? Any active VPN connections will be disconnected.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogoutDialog = false
                        authViewModel.logout()
                    }
                ) {
                    Text("Logout")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Log level dialog
    if (showLogLevelDialog) {
        AlertDialog(
            onDismissRequest = { showLogLevelDialog = false },
            title = { Text("Log Level") },
            text = {
                Column {
                    LogLevel.entries.forEach { level ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = settings.logLevel == level,
                                onClick = {
                                    settingsViewModel.updateLogLevel(level)
                                    showLogLevelDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(level.name)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLogLevelDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Clear logs confirmation dialog
    if (showClearLogsDialog) {
        AlertDialog(
            onDismissRequest = { showClearLogsDialog = false },
            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
            title = { Text("Clear Logs") },
            text = { Text("Are you sure you want to delete all log data? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearLogsDialog = false
                        settingsViewModel.clearLogs()
                        Toast.makeText(context, "Logs cleared", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearLogsDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        content()
    }
}

@Composable
fun SettingsItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun SettingsClickableItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun SettingsSwitch(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
