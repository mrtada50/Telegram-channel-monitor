package com.tgmonitor.app.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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

private class NavTab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    NavTab("home", "الرئيسية", Icons.Filled.Home),
    NavTab("channels", "القنوات", Icons.Filled.Menu),
    NavTab("changes", "التغييرات", Icons.Filled.Notifications),
    NavTab("settings", "الإعدادات", Icons.Filled.Settings),
)

@Composable
fun AppNav(container: AppContainer) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (TABS.any { it.route == route }) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    TABS.forEach { t ->
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
        // Short fades: the default 700ms crossfade is what made navigation feel sluggish.
        NavHost(
            nav,
            startDestination = "home",
            modifier = Modifier.padding(pad),
            enterTransition = { fadeIn(tween(140)) },
            exitTransition = { fadeOut(tween(90)) },
            popEnterTransition = { fadeIn(tween(140)) },
            popExitTransition = { fadeOut(tween(90)) },
        ) {
            composable("home") {
                HomeScreen(
                    viewModel<HomeViewModel>(factory = VmFactory { HomeViewModel(container) }),
                    onOpenMessage = { ch, msg -> nav.navigate("message/$ch/$msg") },
                )
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
                SettingsScreen(
                    viewModel<SettingsViewModel>(factory = VmFactory { SettingsViewModel(container) }),
                    viewModel<AccountViewModel>(factory = VmFactory { AccountViewModel(container) }),
                )
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
