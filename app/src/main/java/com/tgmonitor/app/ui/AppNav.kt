package com.tgmonitor.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.tgmonitor.app.AppContainer

private class Tab(val route: String, val label: String, val icon: ImageVector)

@Composable
fun AppNav(container: AppContainer) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val tabs = listOf(
        Tab("home", "الرئيسية", Icons.Default.Home),
        Tab("channels", "القنوات", Icons.Default.Menu),
        Tab("changes", "التغييرات", Icons.Default.Notifications),
        Tab("settings", "الإعدادات", Icons.Default.Settings),
    )
    Scaffold(
        bottomBar = {
            if (tabs.any { it.route == route }) {
                NavigationBar {
                    tabs.forEach { t ->
                        NavigationBarItem(
                            selected = route == t.route,
                            onClick = {
                                nav.navigate(t.route) {
                                    popUpTo("home") { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
    ) { pad ->
        NavHost(nav, startDestination = "home", modifier = Modifier.padding(pad)) {
            composable("home") {
                HomeScreen(viewModel<HomeViewModel>(factory = VmFactory { HomeViewModel(container) }))
            }
            composable("channels") {
                ChannelsScreen(
                    viewModel<ChannelsViewModel>(factory = VmFactory { ChannelsViewModel(container) }),
                    onOpen = { nav.navigate("channel/$it") },
                )
            }
            composable("changes") {
                ChangesScreen(
                    viewModel<ChangesViewModel>(factory = VmFactory { ChangesViewModel(container) }),
                    onOpenMessage = { ch, msg -> nav.navigate("message/$ch/$msg") },
                )
            }
            composable("settings") {
                SettingsScreen(viewModel<SettingsViewModel>(factory = VmFactory { SettingsViewModel(container) }))
            }
            composable("channel/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                val id = entry.arguments?.getLong("id") ?: 0L
                ChannelDetailsScreen(
                    viewModel<ChannelDetailsViewModel>(key = "details-$id", factory = VmFactory { ChannelDetailsViewModel(container, id) }),
                    onBack = { nav.popBackStack() },
                    onOpenMessage = { ch, msg -> nav.navigate("message/$ch/$msg") },
                )
            }
            composable(
                "message/{channelId}/{messageId}",
                arguments = listOf(
                    navArgument("channelId") { type = NavType.LongType },
                    navArgument("messageId") { type = NavType.LongType },
                ),
            ) { entry ->
                val ch = entry.arguments?.getLong("channelId") ?: 0L
                val msg = entry.arguments?.getLong("messageId") ?: 0L
                MessageHistoryScreen(
                    viewModel<MessageHistoryViewModel>(key = "msg-$ch-$msg", factory = VmFactory { MessageHistoryViewModel(container, ch, msg) }),
                    onBack = { nav.popBackStack() },
                )
            }
        }
    }
}
