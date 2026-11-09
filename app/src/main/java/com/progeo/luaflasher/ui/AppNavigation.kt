package com.progeo.luaflasher.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.progeo.luaflasher.ui.bundles.BundlesScreen
import com.progeo.luaflasher.ui.flash.FlashScreen
import com.progeo.luaflasher.ui.home.HomeScreen
import com.progeo.luaflasher.ui.monitor.MonitorScreen
import com.progeo.luaflasher.ui.profiles.FetchConfigScreen
import com.progeo.luaflasher.ui.profiles.ProfileEditorScreen
import com.progeo.luaflasher.ui.profiles.ProfilesScreen
import com.progeo.luaflasher.ui.settings.SettingsScreen

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    HOME("home", "Home", Icons.Default.Home),
    BUNDLES("bundles", "Bundles", Icons.Default.Inventory2),
    PROFILES("profiles", "Profiles", Icons.Default.Badge),
    MONITOR("monitor", "Monitor", Icons.Default.Terminal),
    SETTINGS("settings", "Settings", Icons.Default.Settings),
}

@Composable
fun AppNavigation() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val showBar = Tab.entries.any { it.route == route }

    Scaffold(
        bottomBar = {
            if (showBar) {
                NavigationBar {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Tab.HOME.route, modifier = Modifier.padding(padding)) {
            composable(Tab.HOME.route) {
                HomeScreen(onFlash = { nav.navigate("flash") }, onBundles = { nav.navigate(Tab.BUNDLES.route) })
            }
            composable(Tab.BUNDLES.route) { BundlesScreen() }
            composable(Tab.PROFILES.route) {
                ProfilesScreen(
                    onEdit = { id -> nav.navigate("profile/${id ?: "new"}") },
                    onFetch = { nav.navigate("fetch") },
                    onFlash = { id -> nav.navigate("flash?profile=$id") },
                )
            }
            composable("profile/{id}") { e ->
                ProfileEditorScreen(
                    id = e.arguments?.getString("id")?.takeIf { it != "new" },
                    onDone = { nav.popBackStack() },
                    onFlash = { id -> nav.navigate("flash?profile=$id") { popUpTo(Tab.PROFILES.route) } },
                )
            }
            composable("fetch") {
                FetchConfigScreen(
                    onBack = { nav.popBackStack() },
                    onSaved = { id -> nav.navigate("profile/$id") { popUpTo(Tab.PROFILES.route) } },
                )
            }
            composable(Tab.MONITOR.route) { MonitorScreen() }
            composable(Tab.SETTINGS.route) { SettingsScreen() }
            composable(
                "flash?profile={profile}",
                arguments = listOf(navArgument("profile") { type = NavType.StringType; nullable = true; defaultValue = null }),
            ) { e ->
                FlashScreen(profileId = e.arguments?.getString("profile"), onClose = { nav.popBackStack() })
            }
        }
    }
}
