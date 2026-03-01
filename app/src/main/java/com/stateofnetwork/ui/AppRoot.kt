package com.stateofnetwork.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.stateofnetwork.ui.screens.DomainCheckScreen
import com.stateofnetwork.ui.screens.HistoryScreen
import com.stateofnetwork.ui.screens.HomeScreen
import com.stateofnetwork.ui.screens.SpeedTestScreen
import com.stateofnetwork.ui.screens.SpeedHistoryDetailScreen
import com.stateofnetwork.ui.vm.AppViewModel
import com.stateofnetwork.ui.theme.AppTheme
import com.stateofnetwork.ui.components.GlassBackground

@Composable
fun AppRoot() {
    val nav = rememberNavController()
    val vm: AppViewModel = viewModel()
    val ctx = LocalContext.current

    AppTheme {
        GlassBackground {
            NavHost(navController = nav, startDestination = "home") {
                composable("home") {
                    HomeScreen(
                        vm = vm,
                        onSpeedStart = { nav.navigate("speed_start") },
                        onDomains = { nav.navigate("domains") },
                        onHistory = { nav.navigate("history") }
                    )
                }
                composable("speed") { SpeedTestScreen(vm, onBack = { nav.popBackStack() }, autoStart = false) }
                composable("speed_start") { SpeedTestScreen(vm, onBack = { nav.popBackStack() }, autoStart = true) }
                composable("speed_history/{ts}") { backStackEntry ->
                    val ts = backStackEntry.arguments?.getString("ts")?.toLongOrNull() ?: 0L
                    SpeedHistoryDetailScreen(vm = vm, timestamp = ts, onBack = { nav.popBackStack() })
                }
                composable("domains") { DomainCheckScreen(vm, onBack = { nav.popBackStack() }) }
                composable("history") {
                    HistoryScreen(
                        vm = vm,
                        ctx = ctx,
                        onBack = { nav.popBackStack() },
                        onOpenSpeedDetails = { ts -> nav.navigate("speed_history/$ts") }
                    )
                }
            }
        }
    }
}