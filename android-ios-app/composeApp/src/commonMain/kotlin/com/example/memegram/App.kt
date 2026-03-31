package com.example.memegram

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Box
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
@Serializable data class ChatDetailRoute(
    val chatName: String,
    val conversationId: String = ""
)
@Serializable object AppearanceRoute
@Serializable object NotificationsRoute
@Serializable object LanguageRoute
@Serializable object PrivacyRoute
@Serializable object BlackListRoute
@Serializable object ContactsRoute
@Serializable object StorageRoute

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
                            onChatClick = { chat ->
                                navController.navigate(
                                    ChatDetailRoute(
                                        chatName = chat.name,
                                        conversationId = chat.conversationId
                                    )
                                )
                            },
                            onAppearanceClick = { navController.navigate(AppearanceRoute) },
                            onProfileClick = { navController.navigate(ProfileRoute) },
                            onNotificationsClick = { navController.navigate(NotificationsRoute) },
                            onLanguageClick = { navController.navigate(LanguageRoute) },
                            onPrivacyClick = { navController.navigate(PrivacyRoute) },
                            profileViewModel = profileViewModel,
                            onContactsClick = { navController.navigate(ContactsRoute) },
                            onStorageClick = { navController.navigate(StorageRoute) },
                            viewModel = viewModel
                        )
                    }
                    composable<ChatDetailRoute> { backStackEntry ->
                        val route = backStackEntry.toRoute<ChatDetailRoute>()
                        val viewModel = koinViewModel<ChatViewModel>(
                            key = route.conversationId
                        )

                        LaunchedEffect(route.conversationId) {
                            if (route.conversationId.isNotEmpty()) {
                                viewModel.loadConversation(route.conversationId)
                            }
                        }

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
                            onLogoutDone = {
                                navController.navigate(AuthRoute) {
                                    popUpTo(0) { inclusive = true }
                                }
                            },
                            viewModel = viewModel
                        )
                    }
                    composable<BlackListRoute> {
                        val viewModel = koinViewModel<BlackListViewModel>()
                        BlackListScreen(
                            topBarColor = topBarColor,
                            onBack = { if (navController.previousBackStackEntry != null) navController.popBackStack() },
                            viewModel = viewModel
                        )
                    }
                    composable<NotificationsRoute> {
                        val viewModel = koinViewModel<NotificationsViewModel>()
                        NotificationsScreen(
                            topBarColor = topBarColor,
                            onBack = { if (navController.previousBackStackEntry != null) navController.popBackStack() },
                            viewModel = viewModel
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
                        val viewModel = koinViewModel<PrivacyViewModel>()
                        PrivacyScreen(
                            topBarColor = topBarColor,
                            onBack = { if (navController.previousBackStackEntry != null) navController.popBackStack() },
                            onBlackListClick = { navController.navigate(BlackListRoute) },
                            onAccountDeleted = {
                                navController.navigate(AuthRoute) { popUpTo(0) { inclusive = true } }
                            },
                            viewModel = viewModel
                        )
                    }
                    composable<ContactsRoute> {
                        val viewModel = koinViewModel<ContactsViewModel>()
                        val isCreatingChat by viewModel.isCreatingChat.collectAsState()

                        Box {
                            ContactsScreen(
                                topBarColor = topBarColor,
                                onBack = { if (navController.previousBackStackEntry != null) navController.popBackStack() },
                                onChatClick = { chat ->
                                    navController.navigate(
                                        ChatDetailRoute(
                                            chatName = chat.name,
                                            conversationId = chat.conversationId
                                        )
                                    )
                                },
                                viewModel = viewModel
                            )
                            if (isCreatingChat) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.3f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(color = Color.White)
                                }
                            }
                        }
                    }
                    composable<StorageRoute> {
                        val viewModel = koinViewModel<StorageViewModel>()
                        StorageScreen(
                            topBarColor = topBarColor,
                            onBack = { if (navController.previousBackStackEntry != null) navController.popBackStack() },
                            viewModel = viewModel
                        )
                    }
                }
            }
        }
    }
}