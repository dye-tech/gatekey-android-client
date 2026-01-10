package com.gatekey.client.ui.screens.home

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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.gatekey.client.data.model.Gateway
import com.gatekey.client.data.model.MeshHub
import com.gatekey.client.ui.theme.GatekeyGreen
import com.gatekey.client.util.IpUtils
import com.gatekey.client.ui.theme.GatekeyRed
import com.gatekey.client.ui.theme.GatekeyYellow
import com.gatekey.client.ui.viewmodel.AuthViewModel
import com.gatekey.client.ui.viewmodel.ConnectionViewModel
import com.gatekey.client.ui.viewmodel.TrafficDataPoint
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
    val serverUrl by connectionViewModel.serverUrl.collectAsState(initial = "")
    val localIp by connectionViewModel.localIp.collectAsState()
    val remoteIp by connectionViewModel.remoteIp.collectAsState()
    val remotePort by connectionViewModel.remotePort.collectAsState()
    val bytesIn by connectionViewModel.bytesIn.collectAsState()
    val bytesOut by connectionViewModel.bytesOut.collectAsState()
    val trafficHistory by connectionViewModel.trafficHistory.collectAsState()
    val darkMode by connectionViewModel.darkMode.collectAsState(initial = false)

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
                    IconButton(onClick = { connectionViewModel.setDarkMode(!darkMode) }) {
                        Icon(
                            imageVector = if (darkMode) Icons.Default.LightMode else Icons.Default.DarkMode,
                            contentDescription = if (darkMode) "Switch to light mode" else "Switch to dark mode"
                        )
                    }
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
                    groups = currentUser?.groups ?: emptyList(),
                    serverUrl = serverUrl
                )
            }

            // Connection status
            item {
                ConnectionStatusCard(
                    vpnState = vpnState,
                    activeConnections = activeConnections,
                    localIp = localIp,
                    remoteIp = remoteIp,
                    remotePort = remotePort,
                    bytesIn = bytesIn,
                    bytesOut = bytesOut,
                    trafficHistory = trafficHistory,
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
                    val hubConnected = activeConnections.containsKey(hub.id)
                    MeshHubCard(
                        hub = hub,
                        isConnected = hubConnected,
                        isConnecting = vpnState is VpnManager.VpnState.Connecting &&
                                (vpnState as VpnManager.VpnState.Connecting).gatewayId == hub.id,
                        connectedServerIp = if (hubConnected) remoteIp else null,
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
    groups: List<String>,
    serverUrl: String
) {
    var groupsExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // User info row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.AccountCircle,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = userName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = userEmail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                }
            }

            // Server URL
            if (serverUrl.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Dns,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = serverUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }
            }

            // Expandable groups section
            if (groups.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Divider(
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f)
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { groupsExpanded = !groupsExpanded }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Group,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Groups (${groups.size})",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = if (groupsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (groupsExpanded) "Collapse" else "Expand",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }

                AnimatedVisibility(
                    visible = groupsExpanded,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    Column(
                        modifier = Modifier.padding(start = 26.dp, top = 4.dp)
                    ) {
                        groups.forEach { group ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 2.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Label,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = group,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ConnectionStatusCard(
    vpnState: VpnManager.VpnState,
    activeConnections: Map<String, com.gatekey.client.data.model.ActiveConnection>,
    localIp: String?,
    remoteIp: String?,
    remotePort: String?,
    bytesIn: Long,
    bytesOut: Long,
    trafficHistory: List<TrafficDataPoint>,
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
                    modifier = Modifier.size(40.dp),
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
                            modifier = Modifier.size(28.dp),
                            tint = GatekeyRed
                        )
                    }
                }
            }

            // Connection details when connected
            if (vpnState is VpnManager.VpnState.Connected) {
                Spacer(modifier = Modifier.height(12.dp))
                Divider(
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                // IP addresses row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Local IP
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.PhoneAndroid,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Local IP",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = localIp ?: "—",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    // Remote IP
                    Column(horizontalAlignment = Alignment.End) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Cloud,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Server",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = IpUtils.formatEndpoint(remoteIp, remotePort),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Data transfer row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Download
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.ArrowDownward,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = GatekeyGreen
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = formatBytes(bytesIn),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Upload
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.ArrowUpward,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = formatBytes(bytesOut),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Traffic graph
                if (trafficHistory.size >= 2) {
                    Spacer(modifier = Modifier.height(16.dp))
                    TrafficGraph(
                        trafficHistory = trafficHistory,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp)
                    )
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}

@Composable
fun TrafficGraph(
    trafficHistory: List<TrafficDataPoint>,
    modifier: Modifier = Modifier
) {
    if (trafficHistory.size < 2) return

    // Calculate rate of change (bytes per second)
    val rateData = trafficHistory.zipWithNext { a, b ->
        val timeDiff = (b.timestamp - a.timestamp).coerceAtLeast(1) / 1000.0
        val bytesInRate = ((b.bytesIn - a.bytesIn) / timeDiff).coerceAtLeast(0.0)
        val bytesOutRate = ((b.bytesOut - a.bytesOut) / timeDiff).coerceAtLeast(0.0)
        Pair(bytesInRate, bytesOutRate)
    }

    if (rateData.isEmpty()) return

    val maxRate = rateData.maxOf { maxOf(it.first, it.second) }.coerceAtLeast(1024.0)

    val downloadColor = GatekeyGreen
    val uploadColor = Color(0xFF2196F3) // Blue

    Column(modifier = modifier) {
        // Graph label
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .padding(end = 4.dp)
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawCircle(color = downloadColor)
                    }
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Download",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .padding(end = 4.dp)
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawCircle(color = uploadColor)
                    }
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Upload",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = formatBytes(maxRate.toLong()) + "/s",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // The graph canvas
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val width = size.width
            val height = size.height
            val stepX = width / (rateData.size - 1).coerceAtLeast(1)

            // Draw download line
            val downloadPath = Path()
            rateData.forEachIndexed { index, (downloadRate, _) ->
                val x = index * stepX
                val y = height - (downloadRate / maxRate * height).toFloat()
                if (index == 0) {
                    downloadPath.moveTo(x, y)
                } else {
                    downloadPath.lineTo(x, y)
                }
            }
            drawPath(
                path = downloadPath,
                color = downloadColor,
                style = Stroke(width = 2.dp.toPx())
            )

            // Draw upload line
            val uploadPath = Path()
            rateData.forEachIndexed { index, (_, uploadRate) ->
                val x = index * stepX
                val y = height - (uploadRate / maxRate * height).toFloat()
                if (index == 0) {
                    uploadPath.moveTo(x, y)
                } else {
                    uploadPath.lineTo(x, y)
                }
            }
            drawPath(
                path = uploadPath,
                color = uploadColor,
                style = Stroke(width = 2.dp.toPx())
            )

            // Draw baseline
            drawLine(
                color = Color.Gray.copy(alpha = 0.3f),
                start = Offset(0f, height),
                end = Offset(width, height),
                strokeWidth = 1.dp.toPx()
            )
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
        modifier = Modifier.fillMaxWidth(),
        colors = if (isConnected) {
            CardDefaults.cardColors(
                containerColor = GatekeyGreen.copy(alpha = 0.1f)
            )
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Gateway icon
            Icon(
                imageVector = Icons.Default.VpnKey,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = if (isConnected) GatekeyGreen else MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = gateway.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Language,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = gateway.hostname,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (gateway.location != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
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
                    modifier = Modifier.size(32.dp),
                    strokeWidth = 3.dp
                )
            } else if (isConnected) {
                FilledTonalButton(
                    onClick = onDisconnect,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = GatekeyRed.copy(alpha = 0.2f),
                        contentColor = GatekeyRed
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.PowerSettingsNew,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Disconnect")
                }
            } else {
                Button(onClick = onConnect) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
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
    connectedServerIp: String?,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (isConnected) {
            CardDefaults.cardColors(
                containerColor = GatekeyGreen.copy(alpha = 0.1f)
            )
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Mesh hub icon
            Icon(
                imageVector = Icons.Default.Hub,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = if (isConnected) GatekeyGreen else MaterialTheme.colorScheme.secondary
            )
            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = hub.name ?: "Mesh Hub",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                hub.publicEndpoint?.let { endpoint ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Language,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = endpoint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (!hub.networks.isNullOrEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.DeviceHub,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = hub.networks.joinToString(", "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                // Show connected server IP
                if (isConnected && connectedServerIp != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Cloud,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = GatekeyGreen
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Server: $connectedServerIp",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = GatekeyGreen
                        )
                    }
                }
            }

            if (isConnecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    strokeWidth = 3.dp
                )
            } else if (isConnected) {
                FilledTonalButton(
                    onClick = onDisconnect,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = GatekeyRed.copy(alpha = 0.2f),
                        contentColor = GatekeyRed
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.PowerSettingsNew,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Disconnect")
                }
            } else {
                Button(onClick = onConnect) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Connect")
                }
            }
        }
    }
}
