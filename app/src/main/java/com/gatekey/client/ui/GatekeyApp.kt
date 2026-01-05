package com.gatekey.client.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.gatekey.client.ui.screens.connections.ConnectionsScreen
import com.gatekey.client.ui.screens.home.HomeScreen
import com.gatekey.client.ui.screens.login.LoginScreen
import com.gatekey.client.ui.screens.settings.SettingsScreen
import com.gatekey.client.ui.theme.GatekeyGreen
import com.gatekey.client.ui.viewmodel.AuthViewModel
import com.gatekey.client.ui.viewmodel.ConnectionViewModel
import com.gatekey.client.vpn.VpnManager

sealed class Screen(
    val route: String,
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    data object Home : Screen("home", "Home", Icons.Filled.Home, Icons.Outlined.Home)
    data object Connections : Screen("connections", "Connections", Icons.Filled.Shield, Icons.Outlined.Shield)
    data object Settings : Screen("settings", "Settings", Icons.Filled.Settings, Icons.Outlined.Settings)
    data object Login : Screen("login", "Login", Icons.Filled.Home, Icons.Outlined.Home)
}

val bottomNavItems = listOf(
    Screen.Home,
    Screen.Connections,
    Screen.Settings
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GatekeyApp(
    authViewModel: AuthViewModel = hiltViewModel(),
    connectionViewModel: ConnectionViewModel = hiltViewModel()
) {
    val navController = rememberNavController()
    val authState by authViewModel.authState.collectAsState()
    val vpnState by connectionViewModel.vpnState.collectAsState()

    val isLoggedIn = authState is com.gatekey.client.data.repository.AuthRepository.AuthState.LoggedIn
    val isVpnConnected = vpnState is VpnManager.VpnState.Connected

    // Start destination based on auth state
    val startDestination = if (isLoggedIn) Screen.Home.route else Screen.Login.route

    LaunchedEffect(Unit) {
        authViewModel.checkAuthState()
    }

    LaunchedEffect(isLoggedIn) {
        android.util.Log.d("GatekeyApp", "LaunchedEffect triggered - isLoggedIn: $isLoggedIn, authState: $authState")
        if (isLoggedIn) {
            android.util.Log.d("GatekeyApp", "Navigating to Home screen")
            navController.navigate(Screen.Home.route) {
                popUpTo(Screen.Login.route) { inclusive = true }
            }
        } else if (authState is com.gatekey.client.data.repository.AuthRepository.AuthState.LoggedOut) {
            android.util.Log.d("GatekeyApp", "Navigating to Login screen (logged out)")
            navController.navigate(Screen.Login.route) {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    Scaffold(
        bottomBar = {
            if (isLoggedIn) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    val navBackStackEntry by navController.currentBackStackEntryAsState()
                    val currentDestination = navBackStackEntry?.destination

                    bottomNavItems.forEach { screen ->
                        val selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true

                        // Special handling for Connections/Shield icon - green when VPN connected
                        val iconTint = when {
                            screen == Screen.Connections && isVpnConnected -> GatekeyGreen
                            selected -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }

                        NavigationBarItem(
                            icon = {
                                Icon(
                                    imageVector = if (selected || (screen == Screen.Connections && isVpnConnected)) {
                                        screen.selectedIcon
                                    } else {
                                        screen.unselectedIcon
                                    },
                                    contentDescription = screen.title,
                                    tint = iconTint
                                )
                            },
                            label = {
                                Text(
                                    text = screen.title,
                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            selected = selected,
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer
                            ),
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Login.route) {
                LoginScreen()
            }
            composable(Screen.Home.route) {
                HomeScreen()
            }
            composable(Screen.Connections.route) {
                ConnectionsScreen()
            }
            composable(Screen.Settings.route) {
                SettingsScreen()
            }
        }
    }
}
