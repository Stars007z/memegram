package com.example.memegram

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.memegram.di.appModule
import com.ionspin.kotlin.crypto.LibsodiumInitializer
import kotlinx.serialization.Serializable
import org.koin.compose.KoinApplication
import org.koin.compose.viewmodel.koinViewModel
import org.koin.dsl.koinConfiguration

@Serializable object ProfileRoute
@Serializable object AuthRoute
@Serializable object ChatsRoute
@Serializable data class ChatDetailRoute(val chatName: String)
@Serializable object AppearanceRoute
@Serializable object NotificationsRoute
@Serializable object LanguageRoute
@Serializable object PrivacyRoute
@Serializable object BlackListRoute
@Composable
fun App() {
    LaunchedEffect(Unit) {
        LibsodiumInitializer.initializeWithCallback { }
    }
    KoinApplication(configuration = koinConfiguration {
        modules(appModule)
    }) {
        val themeViewModel = koinViewModel<ThemeViewModel>()
        val topBarColor by themeViewModel.topBarColor.collectAsState()

        MaterialTheme {
            Surface {
                val navController = rememberNavController()
                val navBackStackEntry by navController.currentBackStackEntryFlow.collectAsState(null)

                LaunchedEffect(navBackStackEntry) {
                    themeViewModel.refreshTheme()
                }

                NavHost(navController = navController, startDestination = AuthRoute) {
                    composable<AuthRoute> {
                        val viewModel = koinViewModel<AuthViewModel>()
                        AuthScreen(
                            onLoginSuccess = {
                                navController.navigate(ChatsRoute) {
                                    popUpTo<AuthRoute> { inclusive = true }
                                }
                            },
                            viewModel = viewModel
                        )
                    }
                    composable<ChatsRoute> {
                        val viewModel = koinViewModel<ChatsViewModel>()
                        val profileViewModel = koinViewModel<ProfileViewModel>()
                        ChatsScreen(
                            topBarColor = topBarColor,
                            onLogout = {
                                navController.navigate(AuthRoute) {
                                    popUpTo(0) { inclusive = true }
                                }
                            },
                            onChatClick = { chatName ->
                                navController.navigate(ChatDetailRoute(chatName))
                            },
                            onAppearanceClick = { navController.navigate(AppearanceRoute) },
                            onProfileClick = { navController.navigate(ProfileRoute) },
                            onNotificationsClick = { navController.navigate(NotificationsRoute) },
                            onLanguageClick = { navController.navigate(LanguageRoute) },
                            onPrivacyClick = { navController.navigate(PrivacyRoute) },
                            profileViewModel = profileViewModel,
                            viewModel = viewModel
                        )
                    }
                    composable<ChatDetailRoute> { backStackEntry ->
                        val route = backStackEntry.toRoute<ChatDetailRoute>()
                        val viewModel = koinViewModel<ChatViewModel>()
                        ChatScreen(
                            topBarColor = topBarColor,
                            chatName = route.chatName,
                            onBack = {
                                if (navController.previousBackStackEntry != null)
                                    navController.popBackStack()
                            },
                            viewModel = viewModel
                        )
                    }
                    composable<AppearanceRoute> {
                        val viewModel = koinViewModel<AppearanceViewModel>()
                        AppearanceScreen(
                            topBarColor = topBarColor,
                            onBack = {
                                if (navController.previousBackStackEntry != null)
                                    navController.popBackStack()
                            },
                            onTopBarColorChanged = { themeViewModel.refreshTheme() },
                            viewModel = viewModel
                        )
                    }
                    composable<ProfileRoute> {
                        val viewModel = koinViewModel<ProfileViewModel>()
                        ProfileScreen(
                            topBarColor = topBarColor,
                            onBack = {
                                if (navController.previousBackStackEntry != null)
                                    navController.popBackStack()
                            },
                            viewModel = viewModel
                        )
                    }
                    composable<BlackListRoute> {
                        BlackListScreen(
                            topBarColor = topBarColor,
                            onBack = {
                                if (navController.previousBackStackEntry != null)
                                    navController.popBackStack()
                            }
                        )
                    }
                    composable<NotificationsRoute> {
                        NotificationsScreen(
                            topBarColor = topBarColor,
                            onBack = {
                                if (navController.previousBackStackEntry != null)
                                    navController.popBackStack()
                            }
                        )
                    }
                    composable<LanguageRoute> {
                        val viewModel = koinViewModel<LanguageViewModel>()
                        LanguageScreen(
                            topBarColor = topBarColor,
                            onBack = {
                                if (navController.previousBackStackEntry != null)
                                    navController.popBackStack()
                            },
                            viewModel = viewModel
                        )
                    }
                    composable<PrivacyRoute> {
                        PrivacyScreen(
                            topBarColor = topBarColor,
                            onBack = {
                                if (navController.previousBackStackEntry != null)
                                    navController.popBackStack()
                            },
                            onBlackListClick = { navController.navigate(BlackListRoute) }
                        )
                    }

                    composable<BlackListRoute> {
                        BlackListScreen(
                            topBarColor = topBarColor,
                            onBack = {
                                if (navController.previousBackStackEntry != null)
                                    navController.popBackStack()
                            }
                        )
                    }
                }
            }
        }
    }
}