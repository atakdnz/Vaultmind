package com.vaultmind.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.vaultmind.app.auth.AuthScreen
import com.vaultmind.app.auth.AuthViewModel
import com.vaultmind.app.ingestion.ImportScreen
import com.vaultmind.app.ingestion.ImportViewModel
import com.vaultmind.app.rag.ChatScreen
import com.vaultmind.app.rag.ChatViewModel
import com.vaultmind.app.settings.SettingsScreen
import com.vaultmind.app.vault.VaultListScreen
import com.vaultmind.app.vault.VaultListViewModel
import java.net.URLDecoder

@Composable
fun VaultMindNavGraph(
    activity: FragmentActivity,
    authViewModel: AuthViewModel
) {
    val navController = rememberNavController()
    val isUnlocked by authViewModel.isUnlocked.collectAsStateWithLifecycle()

    NavHost(
        navController = navController,
        startDestination = Screen.Auth.route
    ) {
        composable(Screen.Auth.route) {
            AuthScreen(
                viewModel = authViewModel,
                activity = activity,
                onAuthenticated = {
                    navController.navigate(Screen.VaultList.route) {
                        popUpTo(Screen.Auth.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.VaultList.route) {
            val viewModel: VaultListViewModel = hiltViewModel()
            VaultListScreen(
                viewModel = viewModel,
                onOpenVault = { vault ->
                    navController.navigate(
                        Screen.Chat.createRoute(vault.id, vault.name)
                    )
                },
                onSettings = { navController.navigate(Screen.Settings.route) },
                onLock = {
                    authViewModel.lock()
                    navController.navigate(Screen.Auth.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        composable(
            route = Screen.Chat.route,
            arguments = listOf(
                navArgument("vaultId") { type = NavType.StringType },
                navArgument("vaultName") { type = NavType.StringType }
            )
        ) { backStack ->
            val vaultId = URLDecoder.decode(
                backStack.arguments?.getString("vaultId") ?: "", "UTF-8"
            )
            val vaultName = URLDecoder.decode(
                backStack.arguments?.getString("vaultName") ?: "", "UTF-8"
            )
            val viewModel: ChatViewModel = hiltViewModel()
            ChatScreen(
                viewModel = viewModel,
                vaultId = vaultId,
                vaultName = vaultName,
                onBack = { navController.popBackStack() },
                onImport = {
                    navController.navigate(Screen.Import.createRoute(vaultId, vaultName))
                }
            )
        }

        composable(
            route = Screen.Import.route,
            arguments = listOf(
                navArgument("vaultId") { type = NavType.StringType },
                navArgument("vaultName") { type = NavType.StringType }
            )
        ) { backStack ->
            val vaultId = URLDecoder.decode(
                backStack.arguments?.getString("vaultId") ?: "", "UTF-8"
            )
            val vaultName = URLDecoder.decode(
                backStack.arguments?.getString("vaultName") ?: "", "UTF-8"
            )
            val viewModel: ImportViewModel = hiltViewModel()
            ImportScreen(
                viewModel = viewModel,
                vaultId = vaultId,
                vaultName = vaultName,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
