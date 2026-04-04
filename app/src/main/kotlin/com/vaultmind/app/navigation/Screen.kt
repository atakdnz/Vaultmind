package com.vaultmind.app.navigation

/** All navigation destinations in the app. */
sealed class Screen(val route: String) {
    data object Auth : Screen("auth")
    data object VaultList : Screen("vault_list")
    data object Chat : Screen("chat/{vaultId}/{vaultName}") {
        fun createRoute(vaultId: String, vaultName: String) =
            "chat/${encode(vaultId)}/${encode(vaultName)}"
        private fun encode(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
    }
    data object Import : Screen("import/{vaultId}/{vaultName}") {
        fun createRoute(vaultId: String, vaultName: String) =
            "import/${encode(vaultId)}/${encode(vaultName)}"
        private fun encode(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
    }
    data object Settings : Screen("settings")
}
