package com.gatekey.client.ui.screens.connections

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.gatekey.client.data.model.ConnectionState
import com.gatekey.client.data.model.Gateway
import com.gatekey.client.data.model.MeshHub
import com.gatekey.client.ui.theme.GatekeyGreen
import com.gatekey.client.ui.theme.GatekeyRed
import com.gatekey.client.ui.theme.GatekeyYellow
import com.gatekey.client.ui.viewmodel.ConnectionViewModel
import com.gatekey.client.ui.viewmodel.TrafficDataPoint
import com.gatekey.client.vpn.VpnManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionsScreen(
    viewModel: ConnectionViewModel = hiltViewModel()
) {
    val gateways by viewModel.gateways.collectAsState()
    val meshHubs by viewModel.meshHubs.collectAsState()
    val activeConnections by viewModel.activeConnections.collectAsState()
    val vpnState by viewModel.vpnState.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val localIp by viewModel.localIp.collectAsState()
    val remoteIp by viewModel.remoteIp.collectAsState()
    val remotePort by viewModel.remotePort.collectAsState()
    val bytesIn by viewModel.bytesIn.collectAsState()
    val bytesOut by viewModel.bytesOut.collectAsState()
    val trafficHistory by viewModel.trafficHistory.collectAsState()

    val context = LocalContext.current
    val activity = context as? android.app.Activity

    val isConnected = vpnState is VpnManager.VpnState.Connected
    val isConnecting = vpnState is VpnManager.VpnState.Connecting

    var statsExpanded by remember { mutableStateOf(false) }

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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Active Connection Card - shown when connected or connecting
            if (isConnected || isConnecting) {
                item {
                    ActiveConnectionCard(
                        vpnState = vpnState,
                        localIp = localIp,
                        remoteIp = remoteIp,
                        remotePort = remotePort,
                        bytesIn = bytesIn,
                        bytesOut = bytesOut,
                        trafficHistory = trafficHistory,
                        activeConnections = activeConnections,
                        expanded = statsExpanded,
                        onToggleExpand = { statsExpanded = !statsExpanded },
                        onDisconnect = { viewModel.disconnect() }
                    )
                }
            }

            // Gateways section
            if (gateways.isNotEmpty()) {
                item {
                    Text(
                        text = "Gateways",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                items(gateways) { gateway ->
                    val connection = activeConnections[gateway.id]
                    val isGatewayConnected = connection?.state == ConnectionState.CONNECTED
                    val isGatewayConnecting = connection?.state == ConnectionState.CONNECTING

                    GatewayDetailCard(
                        gateway = gateway,
                        connection = connection,
                        isConnected = isGatewayConnected,
                        isConnecting = isGatewayConnecting,
                        onConnect = { activity?.let { viewModel.connectToGateway(gateway.id, it) } },
                        onDisconnect = { viewModel.disconnect() }
                    )
                }
            }

            // Mesh Hubs section
            if (meshHubs.isNotEmpty()) {
                item {
                    Text(
                        text = "Mesh Networks",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                items(meshHubs) { hub ->
                    val connection = activeConnections[hub.id]
                    val isHubConnected = connection?.state == ConnectionState.CONNECTED
                    val isHubConnecting = connection?.state == ConnectionState.CONNECTING

                    MeshHubDetailCard(
                        hub = hub,
                        connection = connection,
                        isConnected = isHubConnected,
                        isConnecting = isHubConnecting,
                        connectedServerIp = if (isHubConnected) remoteIp else null,
                        onConnect = { activity?.let { viewModel.connectToMeshHub(hub.id, it) } },
                        onDisconnect = { viewModel.disconnect() }
                    )
                }
            }

            // Empty state
            if (gateways.isEmpty() && meshHubs.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Default.CloudOff,
                        title = "No Connections Available",
                        message = "No gateways or mesh networks are available for your account."
                    )
                }
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
fun ActiveConnectionCard(
    vpnState: VpnManager.VpnState,
    localIp: String?,
    remoteIp: String?,
    remotePort: String?,
    bytesIn: Long,
    bytesOut: Long,
    trafficHistory: List<TrafficDataPoint>,
    activeConnections: Map<String, com.gatekey.client.data.model.ActiveConnection>,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onDisconnect: () -> Unit
) {
    val isConnected = vpnState is VpnManager.VpnState.Connected
    val isConnecting = vpnState is VpnManager.VpnState.Connecting

    // Get the connected gateway/hub name
    val connectionName = activeConnections.values.firstOrNull()?.name ?: "VPN Connection"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = isConnected) { onToggleExpand() },
        colors = CardDefaults.cardColors(
            containerColor = if (isConnected) GatekeyGreen.copy(alpha = 0.1f)
                            else GatekeyYellow.copy(alpha = 0.1f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Compact Header - always visible
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = if (isConnected) GatekeyGreen else GatekeyYellow
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = connectionName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isConnected) "Connected" else "Connecting...",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isConnected) GatekeyGreen else GatekeyYellow
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isConnecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = GatekeyYellow
                        )
                    } else if (isConnected) {
                        // Compact traffic display
                        Column(horizontalAlignment = Alignment.End) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.ArrowDownward,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = GatekeyGreen
                                )
                                Text(
                                    text = formatBytes(bytesIn),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = GatekeyGreen
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.ArrowUpward,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = formatBytes(bytesOut),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (expanded) "Collapse" else "Expand",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Expanded content
            AnimatedVisibility(
                visible = expanded && isConnected,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(16.dp))
                    Divider()
                    Spacer(modifier = Modifier.height(16.dp))

                    // Connection Details
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.Computer,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Local IP",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = localIp ?: "-",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.Public,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Server IP",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = remoteIp ?: "-",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.Dns,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Port",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = remotePort ?: "-",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    // Traffic Graph
                    if (trafficHistory.size >= 2) {
                        Spacer(modifier = Modifier.height(16.dp))
                        TrafficGraph(
                            trafficHistory = trafficHistory,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(60.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Disconnect button
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
                }
            }
        }
    }
}

@Composable
fun TrafficGraph(
    trafficHistory: List<TrafficDataPoint>,
    modifier: Modifier = Modifier
) {
    val downloadColor = GatekeyGreen
    val uploadColor = MaterialTheme.colorScheme.primary

    Canvas(modifier = modifier) {
        if (trafficHistory.size < 2) return@Canvas

        val width = size.width
        val height = size.height
        val padding = 4.dp.toPx()

        // Calculate deltas (bytes per second)
        val deltas = trafficHistory.zipWithNext { a, b ->
            Pair(
                (b.bytesIn - a.bytesIn).coerceAtLeast(0),
                (b.bytesOut - a.bytesOut).coerceAtLeast(0)
            )
        }

        if (deltas.isEmpty()) return@Canvas

        val maxValue = deltas.maxOf { maxOf(it.first, it.second) }.coerceAtLeast(1024).toFloat()
        val stepX = (width - 2 * padding) / (deltas.size - 1).coerceAtLeast(1)

        // Draw download line
        val downloadPath = Path()
        deltas.forEachIndexed { index, delta ->
            val x = padding + index * stepX
            val y = height - padding - (delta.first / maxValue) * (height - 2 * padding)
            if (index == 0) {
                downloadPath.moveTo(x, y)
            } else {
                downloadPath.lineTo(x, y)
            }
        }
        drawPath(downloadPath, downloadColor, style = Stroke(width = 2.dp.toPx()))

        // Draw upload line
        val uploadPath = Path()
        deltas.forEachIndexed { index, delta ->
            val x = padding + index * stepX
            val y = height - padding - (delta.second / maxValue) * (height - 2 * padding)
            if (index == 0) {
                uploadPath.moveTo(x, y)
            } else {
                uploadPath.lineTo(x, y)
            }
        }
        drawPath(uploadPath, uploadColor, style = Stroke(width = 2.dp.toPx()))
    }
}

fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1_073_741_824 -> String.format("%.2f GB", bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> String.format("%.1f MB", bytes / 1_048_576.0)
        bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}

@Composable
fun MeshHubDetailCard(
    hub: MeshHub,
    connection: com.gatekey.client.data.model.ActiveConnection?,
    isConnected: Boolean,
    isConnecting: Boolean,
    connectedServerIp: String? = null,
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
                // Show connected IP if connected, otherwise show publicEndpoint
                val displayIp = if (isConnected && connectedServerIp != null) {
                    connectedServerIp
                } else {
                    hub.publicEndpoint
                }
                displayIp?.let { ip ->
                    DetailItem(
                        icon = Icons.Default.Public,
                        label = "IP",
                        value = ip
                    )
                }
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
