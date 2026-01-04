package com.gatekey.client.ui.screens.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.gatekey.client.data.model.Gateway
import com.gatekey.client.data.model.MeshHub
import com.gatekey.client.ui.theme.GatekeyGreen
import com.gatekey.client.ui.theme.GatekeyRed
import com.gatekey.client.ui.theme.GatekeyYellow
import com.gatekey.client.ui.viewmodel.AuthViewModel
import com.gatekey.client.ui.viewmodel.ConnectionViewModel
import com.gatekey.client.vpn.VpnManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    authViewModel: AuthViewModel = hiltViewModel(),
    connectionViewModel: ConnectionViewModel = hiltViewModel()
) {
    val currentUser by authViewModel.currentUser.collectAsState()
    val gateways by connectionViewModel.gateways.collectAsState()
    val meshHubs by connectionViewModel.meshHubs.collectAsState()
    val activeConnections by connectionViewModel.activeConnections.collectAsState()
    val vpnState by connectionViewModel.vpnState.collectAsState()
    val isLoading by connectionViewModel.isLoading.collectAsState()
    val error by connectionViewModel.error.collectAsState()

    val context = LocalContext.current
    val activity = context as? android.app.Activity

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            connectionViewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Gatekey") },
                actions = {
                    IconButton(onClick = { connectionViewModel.refreshData() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // User info card
            item {
                UserInfoCard(
                    userName = currentUser?.name ?: "User",
                    userEmail = currentUser?.email ?: "",
                    groups = currentUser?.groups ?: emptyList()
                )
            }

            // Connection status
            item {
                ConnectionStatusCard(
                    vpnState = vpnState,
                    activeConnections = activeConnections,
                    onDisconnect = { connectionViewModel.disconnect() }
                )
            }

            // Quick connect section
            if (gateways.isNotEmpty()) {
                item {
                    Text(
                        text = "Gateways",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                items(gateways.take(3)) { gateway ->
                    GatewayCard(
                        gateway = gateway,
                        isConnected = activeConnections.containsKey(gateway.id),
                        isConnecting = vpnState is VpnManager.VpnState.Connecting &&
                                (vpnState as VpnManager.VpnState.Connecting).gatewayId == gateway.id,
                        onConnect = {
                            activity?.let { connectionViewModel.connectToGateway(gateway.id, it) }
                        },
                        onDisconnect = { connectionViewModel.disconnect() }
                    )
                }
            }

            // Mesh hubs section
            if (meshHubs.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Mesh Networks",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                items(meshHubs.take(3)) { hub ->
                    MeshHubCard(
                        hub = hub,
                        isConnected = activeConnections.containsKey(hub.id),
                        isConnecting = vpnState is VpnManager.VpnState.Connecting &&
                                (vpnState as VpnManager.VpnState.Connecting).gatewayId == hub.id,
                        onConnect = {
                            activity?.let { connectionViewModel.connectToMeshHub(hub.id, it) }
                        },
                        onDisconnect = { connectionViewModel.disconnect() }
                    )
                }
            }

            // Loading indicator
            if (isLoading) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }

            // Empty state
            if (!isLoading && gateways.isEmpty() && meshHubs.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudOff,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "No gateways available",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "Contact your administrator to get access.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun UserInfoCard(
    userName: String,
    userEmail: String,
    groups: List<String>
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.AccountCircle,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = userName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = userEmail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )
                if (groups.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = groups.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

@Composable
fun ConnectionStatusCard(
    vpnState: VpnManager.VpnState,
    activeConnections: Map<String, com.gatekey.client.data.model.ActiveConnection>,
    onDisconnect: () -> Unit
) {
    val (statusColor, statusIcon, statusText) = when (vpnState) {
        is VpnManager.VpnState.Connected -> Triple(
            GatekeyGreen,
            Icons.Default.CheckCircle,
            "Connected to ${vpnState.gatewayName}"
        )
        is VpnManager.VpnState.Connecting -> Triple(
            GatekeyYellow,
            Icons.Default.Sync,
            "Connecting to ${vpnState.gatewayName}..."
        )
        is VpnManager.VpnState.Disconnecting -> Triple(
            GatekeyYellow,
            Icons.Default.Sync,
            "Disconnecting..."
        )
        is VpnManager.VpnState.Error -> Triple(
            GatekeyRed,
            Icons.Default.Error,
            "Error: ${vpnState.message}"
        )
        else -> Triple(
            MaterialTheme.colorScheme.onSurfaceVariant,
            Icons.Default.Shield,
            "Not connected"
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = statusIcon,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = statusColor
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "VPN Status",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                }
                if (vpnState is VpnManager.VpnState.Connected ||
                    vpnState is VpnManager.VpnState.Connecting
                ) {
                    IconButton(onClick = onDisconnect) {
                        Icon(
                            imageVector = Icons.Default.PowerSettingsNew,
                            contentDescription = "Disconnect",
                            tint = GatekeyRed
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun GatewayCard(
    gateway: Gateway,
    isConnected: Boolean,
    isConnecting: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = gateway.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = gateway.hostname,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (gateway.location != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = gateway.location,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (isConnecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp
                )
            } else if (isConnected) {
                Button(
                    onClick = onDisconnect,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GatekeyRed
                    )
                ) {
                    Text("Disconnect")
                }
            } else {
                Button(onClick = onConnect) {
                    Text("Connect")
                }
            }
        }
    }
}

@Composable
fun MeshHubCard(
    hub: MeshHub,
    isConnected: Boolean,
    isConnecting: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Hub,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = hub.name ?: "Mesh Hub",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
                hub.publicEndpoint?.let { endpoint ->
                    Text(
                        text = endpoint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!hub.networks.isNullOrEmpty()) {
                    Text(
                        text = "Networks: ${hub.networks.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (isConnecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp
                )
            } else if (isConnected) {
                Button(
                    onClick = onDisconnect,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GatekeyRed
                    )
                ) {
                    Text("Disconnect")
                }
            } else {
                Button(onClick = onConnect) {
                    Text("Connect")
                }
            }
        }
    }
}
