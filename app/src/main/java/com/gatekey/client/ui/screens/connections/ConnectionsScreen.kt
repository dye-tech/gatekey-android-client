package com.gatekey.client.ui.screens.connections

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.gatekey.client.data.model.ConnectionState
import com.gatekey.client.data.model.ConnectionType
import com.gatekey.client.data.model.Gateway
import com.gatekey.client.data.model.MeshHub
import com.gatekey.client.ui.theme.GatekeyGreen
import com.gatekey.client.ui.theme.GatekeyRed
import com.gatekey.client.ui.viewmodel.ConnectionViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionsScreen(
    viewModel: ConnectionViewModel = hiltViewModel()
) {
    val gateways by viewModel.gateways.collectAsState()
    val meshHubs by viewModel.meshHubs.collectAsState()
    val activeConnections by viewModel.activeConnections.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    val context = LocalContext.current
    val activity = context as? android.app.Activity

    var selectedTab by remember { mutableIntStateOf(0) }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connections") },
                actions = {
                    IconButton(onClick = { viewModel.refreshData() }) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Tabs
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Gateways") },
                    icon = { Icon(Icons.Default.VpnKey, contentDescription = null) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Mesh") },
                    icon = { Icon(Icons.Default.Hub, contentDescription = null) }
                )
            }

            when (selectedTab) {
                0 -> GatewaysList(
                    gateways = gateways,
                    activeConnections = activeConnections,
                    onConnect = { gatewayId ->
                        activity?.let { viewModel.connectToGateway(gatewayId, it) }
                    },
                    onDisconnect = { viewModel.disconnect() }
                )
                1 -> MeshHubsList(
                    meshHubs = meshHubs,
                    activeConnections = activeConnections,
                    onConnect = { hubId ->
                        activity?.let { viewModel.connectToMeshHub(hubId, it) }
                    },
                    onDisconnect = { viewModel.disconnect() }
                )
            }
        }
    }
}

@Composable
fun GatewaysList(
    gateways: List<Gateway>,
    activeConnections: Map<String, com.gatekey.client.data.model.ActiveConnection>,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit
) {
    if (gateways.isEmpty()) {
        EmptyState(
            icon = Icons.Default.VpnKey,
            title = "No Gateways",
            message = "No gateways are available for your account."
        )
    } else {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(gateways) { gateway ->
                val connection = activeConnections[gateway.id]
                val isConnected = connection?.state == ConnectionState.CONNECTED
                val isConnecting = connection?.state == ConnectionState.CONNECTING

                GatewayDetailCard(
                    gateway = gateway,
                    connection = connection,
                    isConnected = isConnected,
                    isConnecting = isConnecting,
                    onConnect = { onConnect(gateway.id) },
                    onDisconnect = onDisconnect
                )
            }
        }
    }
}

@Composable
fun MeshHubsList(
    meshHubs: List<MeshHub>,
    activeConnections: Map<String, com.gatekey.client.data.model.ActiveConnection>,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit
) {
    if (meshHubs.isEmpty()) {
        EmptyState(
            icon = Icons.Default.Hub,
            title = "No Mesh Networks",
            message = "No mesh networks are available for your account."
        )
    } else {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(meshHubs) { hub ->
                val connection = activeConnections[hub.id]
                val isConnected = connection?.state == ConnectionState.CONNECTED
                val isConnecting = connection?.state == ConnectionState.CONNECTING

                MeshHubDetailCard(
                    hub = hub,
                    connection = connection,
                    isConnected = isConnected,
                    isConnecting = isConnecting,
                    onConnect = { onConnect(hub.id) },
                    onDisconnect = onDisconnect
                )
            }
        }
    }
}

@Composable
fun GatewayDetailCard(
    gateway: Gateway,
    connection: com.gatekey.client.data.model.ActiveConnection?,
    isConnected: Boolean,
    isConnecting: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
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
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = gateway.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        StatusBadge(
                            isActive = gateway.isActive,
                            isConnected = isConnected
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = gateway.hostname,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Details
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                DetailItem(
                    icon = Icons.Default.Dns,
                    label = "Port",
                    value = "${gateway.vpnPort ?: 1194}/${(gateway.vpnProtocol ?: "udp").uppercase()}"
                )
                if (gateway.publicIp != null) {
                    DetailItem(
                        icon = Icons.Default.Public,
                        label = "IP",
                        value = gateway.publicIp
                    )
                }
                if (gateway.location != null) {
                    DetailItem(
                        icon = Icons.Default.LocationOn,
                        label = "Location",
                        value = gateway.location
                    )
                }
            }

            // Connection info when connected
            if (isConnected && connection != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Divider()
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (connection.localIp != null) {
                        DetailItem(
                            icon = Icons.Default.Computer,
                            label = "Local IP",
                            value = connection.localIp
                        )
                    }
                    connection.connectedAt?.let { connectedAt ->
                        val duration = System.currentTimeMillis() - connectedAt
                        val minutes = (duration / 60000).toInt()
                        val hours = minutes / 60
                        val mins = minutes % 60
                        DetailItem(
                            icon = Icons.Default.Timer,
                            label = "Duration",
                            value = if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Connect/Disconnect button
            if (isConnecting) {
                Button(
                    onClick = { },
                    enabled = false,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Connecting...")
                }
            } else if (isConnected) {
                Button(
                    onClick = onDisconnect,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GatekeyRed
                    )
                ) {
                    Icon(Icons.Default.PowerSettingsNew, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Disconnect")
                }
            } else {
                Button(
                    onClick = onConnect,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = gateway.isActive
                ) {
                    Icon(Icons.Default.VpnKey, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Connect")
                }
            }
        }
    }
}

@Composable
fun MeshHubDetailCard(
    hub: MeshHub,
    connection: com.gatekey.client.data.model.ActiveConnection?,
    isConnected: Boolean,
    isConnecting: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
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
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Hub,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = hub.name ?: "Mesh Hub",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        StatusBadge(
                            isActive = hub.status == "online",
                            isConnected = isConnected
                        )
                    }
                    hub.publicEndpoint?.let { endpoint ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = endpoint,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Details
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                DetailItem(
                    icon = Icons.Default.Dns,
                    label = "Port",
                    value = "${hub.vpnPort ?: 1194}/${(hub.vpnProtocol ?: "udp").uppercase()}"
                )
            }

            // Networks
            if (!hub.networks.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Networks",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    hub.networks?.forEach { network ->
                        AssistChip(
                            onClick = { },
                            label = { Text(network, style = MaterialTheme.typography.bodySmall) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Connect/Disconnect button
            if (isConnecting) {
                Button(
                    onClick = { },
                    enabled = false,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Connecting...")
                }
            } else if (isConnected) {
                Button(
                    onClick = onDisconnect,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GatekeyRed
                    )
                ) {
                    Icon(Icons.Default.PowerSettingsNew, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Disconnect")
                }
            } else {
                Button(
                    onClick = onConnect,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = hub.status == "online"
                ) {
                    Icon(Icons.Default.Hub, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Connect to Mesh")
                }
            }
        }
    }
}

@Composable
fun StatusBadge(isActive: Boolean, isConnected: Boolean) {
    val (color, text) = when {
        isConnected -> GatekeyGreen to "Connected"
        isActive -> MaterialTheme.colorScheme.primary to "Online"
        else -> MaterialTheme.colorScheme.error to "Offline"
    }

    Surface(
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.1f)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

@Composable
fun DetailItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(4.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
fun EmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    message: String
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}
