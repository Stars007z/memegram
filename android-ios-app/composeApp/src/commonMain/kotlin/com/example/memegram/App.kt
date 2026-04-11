package com.example.memegram

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import com.example.memegram.localization.EnStrings
import com.example.memegram.localization.LocalStrings
import com.example.memegram.localization.RuStrings
import com.example.memegram.localization.S
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
@Serializable object LinkedDevicesRoute
@Serializable object AddDeviceRoute
@Serializable object CreateGroupRoute
@Serializable data class UserProfileRoute(val userId: String, val username: String)
@Serializable data class GroupProfileRoute(val conversationId: String, val groupName: String)

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

        val languageViewModel = koinViewModel<LanguageViewModel>()
        val currentLang by languageViewModel.currentLang.collectAsState()
        val strings = if (currentLang == "ru") RuStrings else EnStrings
        LaunchedEffect(strings) { S.current = strings }

        CompositionLocalProvider(LocalStrings provides strings) {
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
                            onAddDevice = {
                                navController.navigate(AddDeviceRoute)
                            },
                            viewModel = viewModel,
                            languageViewModel = languageViewModel
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
                            onNavigateToChat = { convId ->
                                navController.navigate(
                                    ChatDetailRoute(
                                        chatName = strings.newChat,
                                        conversationId = convId
                                    )
                                )
                            },
                            onNavigateToCreateGroup = {
                                navController.navigate(CreateGroupRoute)
                            },
                            onAppearanceClick = { navController.navigate(AppearanceRoute) },
                            onProfileClick = { navController.navigate(ProfileRoute) },
                            onNotificationsClick = { navController.navigate(NotificationsRoute) },
                            onLanguageClick = { navController.navigate(LanguageRoute) },
                            onPrivacyClick = { navController.navigate(PrivacyRoute) },
                            profileViewModel = profileViewModel,
                            onContactsClick = { navController.navigate(ContactsRoute) },
                            onStorageClick = { navController.navigate(StorageRoute) },
                            onLinkedDevicesClick = { navController.navigate(LinkedDevicesRoute) },
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
                            onProfileClick = {
                                val isGroup = viewModel.isGroupChat.value
                                if (isGroup) {
                                    navController.navigate(GroupProfileRoute(route.conversationId, route.chatName))
                                } else {
                                    val peerId = viewModel.peerUserId
                                    if (peerId != null) {
                                        navController.navigate(UserProfileRoute(peerId, route.chatName))
                                    }
                                }
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
                        LanguageScreen(
                            topBarColor = topBarColor,
                            onBack = {
                                if (navController.previousBackStackEntry != null)
                                    navController.popBackStack()
                            },
                            viewModel = languageViewModel
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
                    composable<LinkedDevicesRoute> {
                        LinkedDevicesScreen(
                            onBack = { if (navController.previousBackStackEntry != null) navController.popBackStack() },
                            onNavigateToScanQr = { navController.navigate(AddDeviceRoute) }
                        )
                    }
                    composable<AddDeviceRoute> {
                        AddDeviceScreen(
                            onBack = { navController.popBackStack() },
                            onSuccess = {
                                navController.navigate(ChatsRoute) {
                                    popUpTo(AuthRoute) { inclusive = true }
                                }
                            }
                        )
                    }
                    composable<CreateGroupRoute> {
                        CreateGroupScreen(
                            onBack = { navController.popBackStack() },
                            onGroupCreated = { chatId ->
                                navController.navigate(ChatDetailRoute(strings.groupDefault, chatId)) {
                                    popUpTo(ChatsRoute)
                                }
                            }
                        )
                    }
                    composable<GroupProfileRoute> { backStackEntry ->
                        val route = backStackEntry.toRoute<GroupProfileRoute>()
                        val viewModel = koinViewModel<GroupProfileViewModel>()
                        val contactsVm = koinViewModel<ContactsViewModel>()

                        GroupProfileScreen(
                            topBarColor = topBarColor,
                            conversationId = route.conversationId,
                            groupName = route.groupName,
                            onBack = { navController.popBackStack() },
                            onLeaveSuccess = {
                                navController.navigate(ChatsRoute) {
                                    popUpTo(ChatsRoute) { inclusive = true }
                                }
                            },
                            onNavigateToChat = { convId, chatName ->
                                navController.navigate(ChatDetailRoute(chatName, convId)) {
                                    popUpTo(ChatsRoute)
                                }
                            },
                            onNavigateToUserProfile = { userId, username ->
                                navController.navigate(UserProfileRoute(userId, username))
                            },
                            viewModel = viewModel,
                            contactsViewModel = contactsVm
                        )
                    }

                    composable<UserProfileRoute> { backStackEntry ->
                        val route = backStackEntry.toRoute<UserProfileRoute>()
                        val viewModel = koinViewModel<UserProfileViewModel>()
                        val contactsVm = koinViewModel<ContactsViewModel>()

                        val createdChatId by contactsVm.chatCreated.collectAsState()
                        LaunchedEffect(createdChatId) {
                            createdChatId?.let { id ->
                                contactsVm.clearChatCreated()
                                navController.navigate(ChatDetailRoute(route.username, id)) {
                                    popUpTo(ChatsRoute)
                                }
                            }
                        }

                        UserProfileScreen(
                            topBarColor = topBarColor,
                            userId = route.userId,
                            initialUsername = route.username,
                            onBack = { navController.popBackStack() },
                            onStartChat = { _ ->
                                contactsVm.startDirectChatByUserId(route.userId)
                            },
                            viewModel = viewModel
                        )
                    }
                }
            }
        }
        } // CompositionLocalProvider
    }
}