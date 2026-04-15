package com.example.memegram

import com.example.memegram.di.platformModule
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Box
import com.example.memegram.utils.LocalScreenWidthDp
import com.example.memegram.utils.LocalScreenHeightDp
import com.example.memegram.utils.LocalTopBarImage
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

// ── Light color scheme ───────────────────────────────────────────────
private val LightColors = lightColorScheme(
    primary = Color(0xFF6075F2),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDEE0FF),
    onPrimaryContainer = Color(0xFF001258),
    secondary = Color(0xFF5B5D72),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0E1F9),
    onSecondaryContainer = Color(0xFF181A2C),
    tertiary = Color(0xFF77536D),
    onTertiary = Color.White,
    background = Color(0xFFFFFBFF),
    onBackground = Color(0xFF1B1B1F),
    surface = Color(0xFFFFFBFF),
    onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFE3E1EC),
    onSurfaceVariant = Color(0xFF46464F),
    outline = Color(0xFF767680),
    outlineVariant = Color(0xFFC7C5D0),
)

// ── Dark color scheme ────────────────────────────────────────────────
private val DarkColors = darkColorScheme(
    primary = Color(0xFFBBC3FF),
    onPrimary = Color(0xFF08218A),
    primaryContainer = Color(0xFF4559D9),
    onPrimaryContainer = Color(0xFFDEE0FF),
    secondary = Color(0xFFC4C5DD),
    onSecondary = Color(0xFF2D2F42),
    secondaryContainer = Color(0xFF434559),
    onSecondaryContainer = Color(0xFFE0E1F9),
    tertiary = Color(0xFFE6B9D8),
    onTertiary = Color(0xFF44263D),
    background = Color(0xFF1B1B1F),
    onBackground = Color(0xFFE4E1E6),
    surface = Color(0xFF1B1B1F),
    onSurface = Color(0xFFE4E1E6),
    surfaceVariant = Color(0xFF46464F),
    onSurfaceVariant = Color(0xFFC7C5D0),
    outline = Color(0xFF90909A),
    outlineVariant = Color(0xFF46464F),
)

@Composable
fun App() {
    LaunchedEffect(Unit) {
        LibsodiumInitializer.initializeWithCallback { }
    }
    KoinApplication(configuration = koinConfiguration {
        modules(appModule, platformModule())
    }) {
        val themeViewModel = koinViewModel<ThemeViewModel>()
        val topBarColor by themeViewModel.topBarColor.collectAsState()
        val topBarImageBytes by themeViewModel.topBarImage.collectAsState()
        val isDarkMode by themeViewModel.isDarkMode.collectAsState()

        val topBarBitmap = remember(topBarImageBytes) {
            topBarImageBytes?.let { runCatching { it.decodeToImageBitmap() }.getOrNull() }
        }

        val languageViewModel = koinViewModel<LanguageViewModel>()
        val currentLang by languageViewModel.currentLang.collectAsState()
        val strings = if (currentLang == "ru") RuStrings else EnStrings
        LaunchedEffect(strings) { S.current = strings }

        val colorScheme = if (isDarkMode) DarkColors else LightColors

        CompositionLocalProvider(LocalStrings provides strings) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val screenWidthDp = with(density) { constraints.maxWidth.toDp() }.value
            val screenHeightDp = with(density) { constraints.maxHeight.toDp() }.value
            CompositionLocalProvider(
                LocalScreenWidthDp provides screenWidthDp,
                LocalScreenHeightDp provides screenHeightDp,
                LocalTopBarImage provides topBarBitmap
            ) {
        MaterialTheme(colorScheme = colorScheme) {
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
                            languageViewModel = languageViewModel,
                            themeViewModel = themeViewModel
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
                                ) { launchSingleTop = true }
                            },
                            onNavigateToChat = { convId ->
                                navController.navigate(
                                    ChatDetailRoute(
                                        chatName = strings.newChat,
                                        conversationId = convId
                                    )
                                ) { launchSingleTop = true }
                            },
                            onNavigateToCreateGroup = {
                                navController.navigate(CreateGroupRoute) { launchSingleTop = true }
                            },
                            onAppearanceClick = { navController.navigate(AppearanceRoute) { launchSingleTop = true } },
                            onProfileClick = { navController.navigate(ProfileRoute) { launchSingleTop = true } },
                            onNotificationsClick = { navController.navigate(NotificationsRoute) { launchSingleTop = true } },
                            onLanguageClick = { navController.navigate(LanguageRoute) { launchSingleTop = true } },
                            onPrivacyClick = { navController.navigate(PrivacyRoute) { launchSingleTop = true } },
                            profileViewModel = profileViewModel,
                            onContactsClick = { navController.navigate(ContactsRoute) { launchSingleTop = true } },
                            onStorageClick = { navController.navigate(StorageRoute) { launchSingleTop = true } },
                            onLinkedDevicesClick = { navController.navigate(LinkedDevicesRoute) { launchSingleTop = true } },
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
                                    navController.navigate(GroupProfileRoute(route.conversationId, route.chatName)) { launchSingleTop = true }
                                } else {
                                    val peerId = viewModel.peerUserId
                                    if (peerId != null) {
                                        navController.navigate(UserProfileRoute(peerId, route.chatName)) { launchSingleTop = true }
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
                            viewModel = viewModel,
                            themeViewModel = themeViewModel
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
                            onGroupCreated = { chatId, name ->
                                navController.navigate(ChatDetailRoute(name, chatId)) {
                                    popUpTo(ChatsRoute)
                                    launchSingleTop = true
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
        } // CompositionLocalProvider (LocalScreenWidthDp)
        } // BoxWithConstraints
        } // CompositionLocalProvider (LocalStrings)
    }
}