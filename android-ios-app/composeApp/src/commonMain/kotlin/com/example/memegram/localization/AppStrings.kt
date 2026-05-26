package com.example.memegram.localization

import androidx.compose.runtime.staticCompositionLocalOf

interface AppStrings {

    val cancel: String
    val error: String
    val delete: String
    val add: String
    val save: String
    val back: String
    val close: String
    val search: String
    val loading: String
    val copied: String
    val ok: String

    val nickname: String
    val inviteCode: String
    val autoLoginHint: String
    val login: String
    val register: String
    val noAccountRegister: String
    val hasAccountLogin: String
    val loginFromOtherDevice: String

    val settingsAppearance: String
    val settingsNotifications: String
    val settingsPrivacy: String
    val settingsDataAndStorage: String
    val settingsContacts: String
    val settingsLinkedDevices: String
    val settingsLanguage: String
    val darkTheme: String

    val searchPlaceholder: String
    val createGroup: String
    val addByKey: String
    val addByQr: String
    val createChannel: String
    val newChat: String
    val enterPublicKeyToChat: String
    val publicKey: String
    val start: String
    val nothingFound: String
    val chatResults: String
    val messageResults: String
    fun searchResultsCount(n: Int): String
    val youPrefix: String

    val gallery: String
    val file: String
    val all: String
    val smallerThumbs: String
    val largerThumbs: String
    val noGalleryAccess: String
    val openGallery: String
    fun attachNPhotos(n: Int): String
    val muteNotifications: String
    val mute1Hour: String
    val mute8Hours: String
    val mute24Hours: String
    val muteForever: String
    val deleteChatTitle: String
    val deleteChat: String
    val deleteChatMessage: String
    val deleteForAll: String
    val resendMessage: String
    val deleteFailedMessage: String
    val resendUnsupported: String
    val clearHistory: String
    val clearHistoryMessage: String
    val clearHistoryForEveryoneMessage: String
    val clearHistoryForEveryoneNotAllowed: String
    val onlyForMe: String
    val forAll: String
    val deleteMessageTitle: String
    val deleteMessageText: String
    val notifications: String
    val changeWallpaper: String
    val reply: String
    val voiceMessage: String
    val photo: String
    val swipeToCancel: String
    val messagePlaceholder: String
    val messageDeletedEmoji: String
    val messageDeleted: String
    val member: String
    val saveToGallery: String
    fun saveAllNPhotos(count: Int): String
    val copyText: String
    val you: String
    val interlocutor: String
    val showInChat: String
    val savedToGallery: String
    val saveFailed: String

    val mlsNotReady: String
    val encrypted: String
    val sentFromOtherDevice: String
    val decryptionError: String
    fun loadError(msg: String): String
    fun leftGroup(name: String): String
    fun removedFromGroup(kickerName: String, kickedName: String): String
    val admin: String
    val encryptionNotReady: String
    fun sendError(msg: String): String
    val photoSendError: String
    fun photoSendErrorDetail(msg: String): String
    fun voiceSendError(msg: String): String
    fun deleteError(msg: String): String
    fun fileSendError(msg: String): String
    val fileReadError: String
    fun fileTooLarge(actual: String, max: String): String
    val downloadFile: String
    val openFile: String
    val fileDownloadError: String
    val fileSaveError: String

    val newContact: String
    val enterPublicKey: String
    val deleteContactTitle: String
    fun deleteContactMessage(name: String): String
    val blockTitle: String
    fun blockMessage(name: String): String
    val blockAction: String
    val contactsTitle: String
    val noContacts: String
    val addContactHint: String
    val favorites: String
    val allContacts: String
    val removeFromFavorites: String
    val addToFavorites: String

    val profileTitle: String
    val username: String
    val aboutMe: String
    val copyMyPublicKey: String

    val roleOwner: String
    val roleAdmin: String
    val roleMember: String
    val chatFallback: String
    val leaveGroupTitle: String
    val leaveGroupMessage: String
    val leave: String
    val removeMemberTitle: String
    val userFallback: String
    fun removeMemberMessage(name: String): String
    val remove: String
    val sendDM: String
    val addToContacts: String
    val makeAdmin: String
    val removeAdminRole: String
    val removeFromGroup: String
    val selectMember: String
    val noAvailableContacts: String
    val noName: String
    val groupInfo: String
    fun membersCount(n: Int): String
    val addMember: String
    val leaveGroup: String
    val searchMembers: String
    val editGroupName: String
    val changeGroupPhoto: String

    val sendMessage: String
    val addToContactsButton: String

    val groupName: String
    fun membersSelected(n: Int): String
    val noContactsYet: String

    val privacyTitle: String
    val whoSeesProfile: String
    val whoSeesLastSeen: String
    val deleteAccountAfter: String
    val autoDeleteMessages: String
    val autoDeleteOff: String
    val autoDelete1Day: String
    val autoDelete1Week: String
    val autoDelete1Month: String
    val deleteAccountTitle: String
    val deleteAccountWarning: String
    val blackList: String
    val manage: String
    val privacySection: String
    val autoDeleteSection: String
    val deleteAccountAction: String
    val nsfwFilter: String
    val nsfwFilterDescription: String
    val nsfwModel: String
    val nsfwModelDescription: String

    val visEverybody: String
    val visContacts: String
    val visNobody: String
    val days1Month: String
    val days3Months: String
    val days6Months: String
    val days1Year: String
    val daysOff: String

    val notificationsTitle: String
    val privateChats: String
    val groups: String
    val channels: String
    val enableNotifications: String
    val disableForever: String
    val disableForTime: String
    val disableForHowLong: String
    val hours: String
    val disable: String
    val disabled: String
    val enabled: String
    val vibration: String
    val vibrationOff: String
    val vibrationLight: String
    val vibrationNormal: String
    val vibrationStrong: String
    val genericNotificationBody: String
    val noChatsToConfigure: String

    val appearanceTitle: String
    val preview: String
    val chatPreview: String
    val previewMessage1: String
    val previewMessage2: String
    val topBarColor: String
    val chatBgColor: String
    val myMessageColor: String
    val otherMessageColor: String
    val transparentBubblesTitle: String
    val transparentBubblesSubtitle: String
    val bubbleTransparencyLabel: String
    val previewBubbleMy: String
    val previewBubbleTheir: String
    val textColorSection: String
    val textColorTopBar: String
    val textColorChatBg: String
    val textColorMyMessage: String
    val textColorOtherMessage: String
    val textColorAuto: String
    val reset: String
    val chooseColor: String
    val colorPickerPresets: String
    val colorPickerCustom: String
    val colorPickerHex: String
    val themeSection: String
    val selectColor: String
    val cropTitle: String
    val setPhoto: String
    val colorOrPhoto: String

    val dataAndStorageTitle: String
    val autoClearCache: String
    val deleteOldByTime: String
    val deleteLeastRecent: String
    val deleteLeastRequested: String
    val deleteOldestFifo: String
    val messagesInChat: String
    val storeMessagesDays: String
    val globalLimit: String
    val clearLocalCache: String
    val clearProfilesCache: String
    fun clearProfilesCacheSize(size: String): String
    val profilesCacheInfo: String
    val fifoDescription: String
    val ttlDescription: String
    val lruDescription: String
    val lfuDescription: String

    val storageUsage: String
    fun storageUsageOnDevice(size: String): String
    fun currentCacheLimit(value: String): String
    val storagePhotos: String
    val storageVideos: String
    val storageDocuments: String
    val storageVoiceMessages: String
    val storageMusic: String
    val storageOther: String
    val storageMiscellaneous: String
    val storageTextMessages: String
    val storageOnDeviceModel: String
    fun clearSelectedSize(size: String): String
    val storageCloudInfo: String
    val autoRemoveMedia: String
    val never: String
    val autoRemoveInfo: String
    val maxCacheSize: String
    val noLimit: String
    val cacheLimitInfo: String
    val chatsTab: String
    val mediaTab: String
    val filesTab: String
    val musicTab: String
    fun clearCacheSize(size: String): String
    val advancedCleanup: String
    val stories: String

    val languageTitle: String
    val searchLanguage: String
    val aiTranslation: String
    val comingSoon: String
    val translate: String
    val showOriginal: String
    val translated: String
    val translating: String
    val showTranslation: String
    val autoTranslate: String
    val targetLanguage: String
    val dontTranslate: String
    val translationSettings: String
    val appLanguageHint: String
    val targetLanguageHint: String
    val translationNotAvailable: String

    val translationModel: String
    val translationModelDescription: String
    val downloadModel: String
    val downloadingModel: String
    val cancelDownload: String
    val deleteModel: String
    val modelReady: String
    val modelNotDownloaded: String
    val modelDownloadFailed: String

    val transcriptionModel: String
    val transcriptionModelDescription: String
    val transcribe: String
    val transcribing: String
    val showTranscription: String
    val hideTranscription: String
    val transcriptionFailed: String
    val transcriptionNoSpeech: String

    val linkedDevicesTitle: String
    val refresh: String
    val addDeviceByQr: String
    val pendingConfirmation: String
    val myDevices: String
    val thisDevice: String
    val revoked: String
    val revoke: String
    val revokeDeviceTitle: String
    fun revokeDeviceMessage(name: String): String
    val makePrimary: String
    val transferPrimaryTitle: String
    fun transferPrimaryMessage(name: String): String
    val decline: String
    val allow: String
    val addDevice: String
    val copyCodeHint: String
    val copyCode: String
    val codeCopied: String
    val scanQrInstead: String

    val scanQrHint: String
    val pasteCodeHint: String
    val codeOrQrLink: String
    val paste: String
    val connect: String
    val sendingDeviceData: String
    val awaitingConfirmation: String
    val confirmOnMainDevice: String
    val deviceAdded: String
    val errorOccurred: String
    val tryAgain: String

    val unblockTitle: String
    fun unblockMessage(name: String): String
    val unblockAction: String
    val blackListTitle: String
    val blackListEmpty: String
    val blockedUsersHint: String
    val blockUser: String
    val unblockUser: String
    fun blockConfirmTitle(name: String): String
    fun blockConfirmMessage(name: String): String
    val userBlockedBanner: String
    val userBlockedSendError: String
    val youBlockedThisUser: String
    val youAreBlockedByUser: String
    val cannotMessageTitle: String
    val cannotMessageBlockedByPeer: String
    val mlsChatBrokenBanner: String
    val userDeletedAccountBanner: String
    val deletedAccountTitle: String

    val mon: String
    val tue: String
    val wed: String
    val thu: String
    val fri: String
    val sat: String
    val sun: String

    fun monthShort(month: Int): String

    val groupDefault: String
    val create: String

    val createInvite: String
    val createInviteTitle: String
    val createInviteExpiresDays: String
    val inviteCreated: String
    val inviteCodeLabel: String
    val inviteExpiresAt: String
}

object RuStrings : AppStrings {

    override val cancel = "Отмена"
    override val error = "Ошибка"
    override val delete = "Удалить"
    override val add = "Добавить"
    override val save = "Сохранить"
    override val back = "Назад"
    override val close = "Закрыть"
    override val search = "Поиск"
    override val loading = "Загрузка..."
    override val copied = "Скопировано!"
    override val ok = "OK"

    override val nickname = "Никнейм"
    override val inviteCode = "Инвайт-код"
    override val autoLoginHint = "Вход выполнится автоматически\nс помощью ключа на устройстве"
    override val login = "Войти"
    override val register = "Зарегистрироваться"
    override val noAccountRegister = "Нет аккаунта? Зарегистрироваться"
    override val hasAccountLogin = "Уже есть аккаунт? Войти"
    override val loginFromOtherDevice = "Войти с другого устройства"

    override val settingsAppearance = "Внешний вид"
    override val settingsNotifications = "Уведомления"
    override val settingsPrivacy = "Конфиденциальность"
    override val settingsDataAndStorage = "Данные и память"
    override val settingsContacts = "Контакты"
    override val settingsLinkedDevices = "Связанные устройства"
    override val settingsLanguage = "Язык"
    override val darkTheme = "Тёмная тема"

    override val searchPlaceholder = "Поиск..."
    override val createGroup = "Создать группу"
    override val addByKey = "Добавить по ключу"
    override val addByQr = "Добавить по QR"
    override val createChannel = "Создать канал"
    override val newChat = "Новый чат"
    override val enterPublicKeyToChat =
        "Введите публичный ключ пользователя, чтобы добавить его в контакты и сразу начать чат."
    override val publicKey = "Публичный ключ"
    override val start = "Начать"
    override val nothingFound = "Ничего не найдено"
    override val chatResults = "Чаты"
    override val messageResults = "Сообщения"
    override fun searchResultsCount(n: Int) = "$n совпадений"
    override val youPrefix = "Вы: "

    override val gallery = "Галерея"
    override val file = "Файл"
    override val all = "Все"
    override val smallerThumbs = "Уменьшить"
    override val largerThumbs = "Увеличить"
    override val noGalleryAccess = "Нет доступа к галерее"
    override val openGallery = "Открыть галерею"
    override fun attachNPhotos(n: Int) = "Прикрепить $n фото"
    override val muteNotifications = "Отключить уведомления"
    override val mute1Hour = "1 час"
    override val mute8Hours = "8 часов"
    override val mute24Hours = "24 часа"
    override val muteForever = "Навсегда"
    override val deleteChatTitle = "Удалить чат?"
    override val deleteChat = "Удалить чат"
    override val deleteChatMessage = "Чат будет удалён для всех участников."
    override val deleteForAll = "Удалить для всех"
    override val resendMessage = "Отправить повторно"
    override val deleteFailedMessage = "Удалить"
    override val resendUnsupported = "Повторная отправка медиа недоступна. Удалите сообщение и отправьте файл заново."
    override val clearHistory = "Очистить историю"
    override val clearHistoryMessage = "Выберите, для кого очистить историю сообщений."
    override val clearHistoryForEveryoneMessage = "Вся история сообщений будет удалена для всех участников. Это действие нельзя отменить."
    override val clearHistoryForEveryoneNotAllowed = "Нельзя очистить всю историю для всех: вы можете удалять только свои сообщения, а в группе - все сообщения только если вы администратор."
    override val onlyForMe = "Только у меня"
    override val forAll = "У всех"
    override val deleteMessageTitle = "Удалить сообщение?"
    override val deleteMessageText = "Сообщение будет удалено для всех участников."
    override val notifications = "Уведомления"
    override val changeWallpaper = "Сменить обои"
    override val reply = "Ответ"
    override val voiceMessage = "Голосовое сообщение"
    override val photo = "Фото"
    override val swipeToCancel = "< Свайп для отмены"
    override val messagePlaceholder = "Сообщение..."
    override val messageDeletedEmoji = "\uD83D\uDDD1 Сообщение удалено"
    override val messageDeleted = "Сообщение удалено"
    override val member = "Участник"
    override val saveToGallery = "Сохранить в галерею"
    override fun saveAllNPhotos(count: Int) = "Сохранить все $count фото"
    override val copyText = "Копировать текст"
    override val you = "Вы"
    override val interlocutor = "Собеседник"
    override val showInChat = "Показать в чате"
    override val savedToGallery = "Сохранено в галерею"
    override val saveFailed = "Не удалось сохранить"

    override val mlsNotReady = "MLS не готов для этого чата"
    override val encrypted = "\uD83D\uDD12 [Зашифровано]"
    override val sentFromOtherDevice = "\uD83D\uDD12 [Отправлено с другого устройства]"
    override val decryptionError = "\uD83D\uDD12 [Ошибка расшифровки]"
    override fun loadError(msg: String) = "Ошибка загрузки: $msg"
    override fun leftGroup(name: String) = "$name покинул(а) группу"
    override fun removedFromGroup(kickerName: String, kickedName: String) =
        "$kickerName удалил(а) $kickedName из группы"
    override val admin = "Администратор"
    override val encryptionNotReady = "⚠\uFE0F Шифрование не готово"
    override fun sendError(msg: String) = "Ошибка отправки: $msg"
    override val photoSendError = "❌ Ошибка отправки фото"
    override fun photoSendErrorDetail(msg: String) = "Ошибка отправки фото: $msg"
    override fun voiceSendError(msg: String) = "Ошибка отправки голосового: $msg"
    override fun deleteError(msg: String) = "Ошибка удаления: $msg"
    override fun fileSendError(msg: String) = "Ошибка отправки файла: $msg"
    override val fileReadError = "Не удалось прочитать файл"
    override fun fileTooLarge(actual: String, max: String) = "Файл слишком большой ($actual). Максимум: $max"
    override val downloadFile = "Скачать"
    override val openFile = "Открыть"
    override val fileDownloadError = "Ошибка загрузки файла"
    override val fileSaveError = "Не удалось сохранить файл"

    override val newContact = "Новый контакт"
    override val enterPublicKey = "Введите публичный ключ пользователя"
    override val deleteContactTitle = "Удалить контакт?"
    override fun deleteContactMessage(name: String) = "Удалить $name из контактов?"
    override val blockTitle = "Заблокировать?"
    override fun blockMessage(name: String) = "Заблокировать $name? Он будет удалён из контактов."
    override val blockAction = "Заблокировать"
    override val contactsTitle = "Контакты"
    override val noContacts = "Нет контактов"
    override val addContactHint = "Нажмите + чтобы добавить контакт\nпо публичному ключу"
    override val favorites = "⭐ Избранные"
    override val allContacts = "Все контакты"
    override val removeFromFavorites = "Убрать из избранного"
    override val addToFavorites = "В избранное"

    override val profileTitle = "Профиль"
    override val username = "Имя пользователя"
    override val aboutMe = "О себе"
    override val copyMyPublicKey = "Скопировать мой публичный ключ"

    override val roleOwner = "Владелец"
    override val roleAdmin = "Администратор"
    override val roleMember = "Участник"
    override val chatFallback = "Чат"
    override val leaveGroupTitle = "Покинуть группу?"
    override val leaveGroupMessage = "Вы больше не будете получать сообщения из этого чата."
    override val leave = "Покинуть"
    override val removeMemberTitle = "Удалить участника?"
    override val userFallback = "Пользователь"
    override fun removeMemberMessage(name: String) = "$name будет удален из группы."
    override val remove = "Удалить"
    override val sendDM = "Написать в ЛС"
    override val addToContacts = "Добавить в контакты"
    override val makeAdmin = "Назначить администратором"
    override val removeAdminRole = "Снять администратора"
    override val removeFromGroup = "Удалить из группы"
    override val selectMember = "Выберите участника"
    override val noAvailableContacts = "Нет доступных контактов для добавления"
    override val noName = "Без имени"
    override val groupInfo = "Информация о группе"
    override fun membersCount(n: Int) = "$n участников"
    override val addMember = "Добавить участника"
    override val leaveGroup = "Покинуть группу"
    override val searchMembers = "Поиск участников..."
    override val editGroupName = "Изменить название"
    override val changeGroupPhoto = "Изменить фото группы"

    override val sendMessage = "Написать"
    override val addToContactsButton = "В контакты"

    override val groupName = "Название группы"
    override fun membersSelected(n: Int) = "Участники ($n выбрано)"
    override val noContactsYet = "У вас пока нет контактов"

    override val privacyTitle = "Конфиденциальность"
    override val whoSeesProfile = "Кто видит мой профиль"
    override val whoSeesLastSeen = "Кто видит время активности"
    override val deleteAccountAfter = "Удалить аккаунт через"
    override val autoDeleteMessages = "Авто-удаление сообщений"
    override val autoDeleteOff = "Выкл"
    override val autoDelete1Day = "1 день"
    override val autoDelete1Week = "1 неделя"
    override val autoDelete1Month = "1 месяц"
    override val deleteAccountTitle = "Удалить аккаунт?"
    override val deleteAccountWarning = "Это действие необратимо. Все данные будут удалены."
    override val blackList = "Чёрный список"
    override val manage = "Управление"
    override val privacySection = "Приватность"
    override val autoDeleteSection = "Авто-удаление"
    override val deleteAccountAction = "Удалить аккаунт"
    override val nsfwFilter = "Фильтр NSFW"
    override val nsfwFilterDescription = "Пропускает входящие фото через модель до показа на экране."
    override val nsfwModel = "Модель цензуры изображений"
    override val nsfwModelDescription = "CLIP + NudeNet + anime detector. Скачиваются один раз с серверов Memegram."

    override val visEverybody = "Все"
    override val visContacts = "Контакты"
    override val visNobody = "Никто"
    override val days1Month = "1 месяц"
    override val days3Months = "3 месяца"
    override val days6Months = "6 месяцев"
    override val days1Year = "1 год"
    override val daysOff = "Выкл"

    override val notificationsTitle = "Уведомления"
    override val privateChats = "Личные чаты"
    override val groups = "Группы"
    override val channels = "Каналы"
    override val enableNotifications = "Включить уведомления"
    override val disableForever = "Отключить навсегда"
    override val disableForTime = "Отключить на время..."
    override val disableForHowLong = "Отключить на сколько часов?"
    override val hours = "Часы"
    override val disable = "Отключить"
    override val disabled = "Отключено"
    override val enabled = "Включено"
    override val vibration = "Вибрация"
    override val vibrationOff = "Выкл."
    override val vibrationLight = "Слабая"
    override val vibrationNormal = "Обычная"
    override val vibrationStrong = "Сильная"
    override val genericNotificationBody = "Новое сообщение"
    override val noChatsToConfigure = "Нет чатов"

    override val appearanceTitle = "Внешний вид"
    override val preview = "Предпросмотр"
    override val chatPreview = "Чат"
    override val previewMessage1 = "Привет! Пойдём обедать сегодня?"
    override val previewMessage2 = "Давай! Встречаемся в 12 \uD83D\uDE0A"
    override val topBarColor = "Цвет верхней панели"
    override val chatBgColor = "Цвет фона чата"
    override val myMessageColor = "Цвет моих сообщений"
    override val otherMessageColor = "Цвет чужих сообщений"
    override val transparentBubblesTitle = "Прозрачные баблы"
    override val transparentBubblesSubtitle = "Сделать баблы сообщений полупрозрачными, чтобы был виден фон чата"
    override val bubbleTransparencyLabel = "Прозрачность"
    override val previewBubbleMy = "Моё сообщение"
    override val previewBubbleTheir = "Сообщение от собеседника"
    override val textColorSection = "Цвет текста"
    override val textColorTopBar = "Текст в шапке"
    override val textColorChatBg = "Текст системных сообщений"
    override val textColorMyMessage = "Текст моих сообщений"
    override val textColorOtherMessage = "Текст чужих сообщений"
    override val textColorAuto = "Авто (по яркости фона)"
    override val reset = "Сброс"
    override val chooseColor = "Выберите цвет"
    override val colorPickerPresets = "Готовые"
    override val colorPickerCustom = "Пользовательский"
    override val colorPickerHex = "HEX"
    override val themeSection = "Тема"
    override val selectColor = "Выбрать"
    override val cropTitle = "Обрезка фото"
    override val setPhoto = "Фото"
    override val colorOrPhoto = "Цвет / Фото"

    override val dataAndStorageTitle = "Данные и память"
    override val autoClearCache = "Автоматическая очистка кэша"
    override val deleteOldByTime = "Удалять старые по времени (1 месяц)"
    override val deleteLeastRecent = "Удалять наименее недавно использованные"
    override val deleteLeastRequested = "Удалять реже всего запрашиваемые"
    override val deleteOldestFifo = "Удалять самые старые (FIFO)"
    override val messagesInChat = "Сообщений в чате (последних)"
    override val storeMessagesDays = "Хранить сообщения (дней)"
    override val globalLimit = "Глобальный лимит (сообщений)"
    override val clearLocalCache = "Очистить локальный кэш сейчас"
    override val clearProfilesCache = "Очистить кэш профилей"
    override fun clearProfilesCacheSize(size: String) = "Очистить кэш профилей ($size)"
    override val profilesCacheInfo = "Удаляет сохранённые профили и аватары других пользователей. При следующем открытии чата они будут загружены заново."
    override val fifoDescription = "Старые сообщения сверх лимита будут удалены из этого чата"
    override val ttlDescription = "Сообщения старше указанного периода удаляются автоматически"
    override val lruDescription = "Удаляются сообщения из чатов, которые давно не открывались"
    override val lfuDescription = "Удаляются сообщения из чатов, в которые реже всего заходят"

    override val storageUsage = "Использование памяти"
    override fun storageUsageOnDevice(size: String) = "Memegram занимает $size на этом устройстве"
    override fun currentCacheLimit(value: String) = "Текущий лимит: $value"
    override val storagePhotos = "Фото"
    override val storageVideos = "Видео"
    override val storageDocuments = "Документы"
    override val storageVoiceMessages = "Голосовые сообщения"
    override val storageMusic = "Музыка"
    override val storageOther = "Другое"
    override val storageMiscellaneous = "Прочее"
    override val storageTextMessages = "Текстовые сообщения"
    override val storageOnDeviceModel = "Языковая модель (на устройстве)"
    override fun clearSelectedSize(size: String) = "Очистить выбранное $size"
    override val storageCloudInfo = "Все медиафайлы останутся в облаке Memegram и могут быть загружены повторно при необходимости."
    override val autoRemoveMedia = "Авто-удаление кэшированных медиа"
    override val never = "Никогда"
    override val autoRemoveInfo = "Фото, видео и другие файлы из облачных чатов, к которым вы не обращались в течение этого периода, будут удалены с устройства для экономии места."
    override val maxCacheSize = "Максимальный размер кэша"
    override val noLimit = "Без лимита"
    override val cacheLimitInfo = "Если размер кэша превысит этот лимит, самые старые неиспользуемые медиафайлы будут удалены с устройства."
    override val chatsTab = "Чаты"
    override val mediaTab = "Медиа"
    override val filesTab = "Файлы"
    override val musicTab = "Музыка"
    override fun clearCacheSize(size: String) = "Очистить кэш $size"
    override val advancedCleanup = "Расширенная очистка"
    override val stories = "Истории"

    override val languageTitle = "Язык"
    override val searchLanguage = "Поиск языка..."
    override val aiTranslation = "AI-перевод"
    override val comingSoon = "Скоро..."
    override val translate = "Перевести"
    override val showOriginal = "Показать оригинал"
    override val translated = "Переведено"
    override val translating = "Перевожу…"
    override val showTranslation = "Показать перевод"
    override val autoTranslate = "Авто-перевод"
    override val targetLanguage = "Целевой язык"
    override val dontTranslate = "Не переводить"
    override val translationSettings = "Настройки перевода"
    override val appLanguageHint = "язык приложения"
    override val targetLanguageHint = "целевой язык"
    override val translationNotAvailable = "Перевод недоступен: недостаточно памяти или модель не загружена"
    override val translationModel = "Модель перевода"
    override val translationModelDescription = "NLLB-200. Скачивается один раз с серверов Memegram."
    override val downloadModel = "Загрузить модель"
    override val downloadingModel = "Загрузка модели…"
    override val cancelDownload = "Отменить"
    override val deleteModel = "Удалить модель"
    override val modelReady = "Модель готова"
    override val modelNotDownloaded = "Модель не загружена"
    override val modelDownloadFailed = "Не удалось загрузить модель"
    override val transcriptionModel = "Модель распознавания речи"
    override val transcriptionModelDescription = "Whisper Small. Скачивается один раз с серверов Memegram."
    override val transcribe = "Распознать речь"
    override val transcribing = "Распознавание…"
    override val showTranscription = "Показать текст"
    override val hideTranscription = "Скрыть текст"
    override val transcriptionFailed = "Не удалось распознать"
    override val transcriptionNoSpeech = "В голосовом нет слов"
    override val linkedDevicesTitle = "Связанные устройства"
    override val refresh = "Обновить"
    override val addDeviceByQr = "Добавить устройство по QR"
    override val pendingConfirmation = "Ожидают подтверждения"
    override val myDevices = "Мои устройства"
    override val thisDevice = "это устройство"
    override val revoked = "Отозвано"
    override val revoke = "Отозвать"
    override val revokeDeviceTitle = "Отозвать устройство?"
    override fun revokeDeviceMessage(name: String) = "'$name' будет удалено из вашего аккаунта."
    override val makePrimary = "Сделать основным"
    override val transferPrimaryTitle = "Передать основное устройство?"
    override fun transferPrimaryMessage(name: String) = "'$name' сможет добавлять и отзывать устройства."
    override val decline = "Отклонить"
    override val allow = "Разрешить"
    override val addDevice = "Добавить устройство"
    override val copyCodeHint = "Или скопируйте код и вставьте его на новом устройстве"
    override val copyCode = "Скопировать код"
    override val codeCopied = "✓ Скопировано"
    override val scanQrInstead = "Хочу отсканировать QR вместо этого"

    override val scanQrHint = "Отсканируй QR-код\nс основного устройства"
    override val pasteCodeHint = "Или вставьте код с другого устройства"
    override val codeOrQrLink = "Код или QR-ссылка"
    override val paste = "Вставить"
    override val connect = "Подключить"
    override val sendingDeviceData = "Отправка данных устройства..."
    override val awaitingConfirmation = "Ожидаем подтверждения\nот основного устройства"
    override val confirmOnMainDevice =
        "Зайди на основном телефоне в\nНастройки → Связанные устройства\nи нажми «Разрешить»"
    override val deviceAdded = "Устройство добавлено!"
    override val errorOccurred = "Произошла ошибка"
    override val tryAgain = "Попробовать снова"

    override val unblockTitle = "Разблокировать?"
    override fun unblockMessage(name: String) = "Разблокировать $name?"
    override val unblockAction = "Разблокировать"
    override val blackListTitle = "Чёрный список"
    override val blackListEmpty = "Чёрный список пуст"
    override val blockedUsersHint = "Заблокированные пользователи появятся здесь"
    override val blockUser = "Заблокировать"
    override val unblockUser = "Разблокировать"
    override fun blockConfirmTitle(name: String) = "Заблокировать $name?"
    override fun blockConfirmMessage(name: String) = "Вы не сможете получать сообщения от $name. Вы уверены?"
    override val userBlockedBanner = "Пользователь заблокирован"
    override val userBlockedSendError = "Нельзя отправить сообщение заблокированному пользователю"
    override val youBlockedThisUser = "В чёрном списке"
    override val youAreBlockedByUser = "Вы заблокированы этим пользователем"
    override val cannotMessageTitle = "Невозможно написать"
    override val cannotMessageBlockedByPeer = "Этот пользователь заблокировал вас. Вы не можете отправлять ему сообщения."
    override val mlsChatBrokenBanner = "Чат недоступен для расшифровки. Попросите собеседника создать новый чат."
    override val userDeletedAccountBanner = "Этот пользователь удалил аккаунт. Вы не можете отправлять ему сообщения."
    override val deletedAccountTitle = "Удалённый аккаунт"

    override val mon = "Пн"
    override val tue = "Вт"
    override val wed = "Ср"
    override val thu = "Чт"
    override val fri = "Пт"
    override val sat = "Сб"
    override val sun = "Вс"

    override fun monthShort(month: Int): String = when (month) {
        1 -> "Янв"; 2 -> "Фев"; 3 -> "Мар"; 4 -> "Апр"
        5 -> "Май"; 6 -> "Июн"; 7 -> "Июл"; 8 -> "Авг"
        9 -> "Сен"; 10 -> "Окт"; 11 -> "Ноя"; else -> "Дек"
    }

    override val groupDefault = "Группа"
    override val create = "Создать"

    override val createInvite = "Создать инвайт"
    override val createInviteTitle = "Новый инвайт-код"
    override val createInviteExpiresDays = "Срок действия (дней)"
    override val inviteCreated = "Инвайт-код создан"
    override val inviteCodeLabel = "Код"
    override val inviteExpiresAt = "Действует до"
}

object EnStrings : AppStrings {

    override val cancel = "Cancel"
    override val error = "Error"
    override val delete = "Delete"
    override val add = "Add"
    override val save = "Save"
    override val back = "Back"
    override val close = "Close"
    override val search = "Search"
    override val loading = "Loading..."
    override val copied = "Copied!"
    override val ok = "OK"

    override val nickname = "Nickname"
    override val inviteCode = "Invite code"
    override val autoLoginHint = "Login will happen automatically\nusing the key on this device"
    override val login = "Log in"
    override val register = "Sign up"
    override val noAccountRegister = "No account? Sign up"
    override val hasAccountLogin = "Already have an account? Log in"
    override val loginFromOtherDevice = "Log in from another device"

    override val settingsAppearance = "Appearance"
    override val settingsNotifications = "Notifications"
    override val settingsPrivacy = "Privacy"
    override val settingsDataAndStorage = "Data and Storage"
    override val settingsContacts = "Contacts"
    override val settingsLinkedDevices = "Linked Devices"
    override val settingsLanguage = "Language"
    override val darkTheme = "Dark theme"

    override val searchPlaceholder = "Search..."
    override val createGroup = "Create group"
    override val addByKey = "Add by key"
    override val addByQr = "Add by QR"
    override val createChannel = "Create channel"
    override val newChat = "New chat"
    override val enterPublicKeyToChat =
        "Enter the user's public key to add them to contacts and start a chat."
    override val publicKey = "Public key"
    override val start = "Start"
    override val nothingFound = "Nothing found"
    override val chatResults = "Chats"
    override val messageResults = "Messages"
    override fun searchResultsCount(n: Int) = "$n result${if (n == 1) "" else "s"}"
    override val youPrefix = "You: "

    override val gallery = "Gallery"
    override val file = "File"
    override val all = "All"
    override val smallerThumbs = "Smaller"
    override val largerThumbs = "Larger"
    override val noGalleryAccess = "No gallery access"
    override val openGallery = "Open gallery"
    override fun attachNPhotos(n: Int) = "Attach $n photo${if (n == 1) "" else "s"}"
    override val muteNotifications = "Mute notifications"
    override val mute1Hour = "1 hour"
    override val mute8Hours = "8 hours"
    override val mute24Hours = "24 hours"
    override val muteForever = "Forever"
    override val deleteChatTitle = "Delete chat?"
    override val deleteChat = "Delete chat"
    override val deleteChatMessage = "The chat will be deleted for all participants."
    override val deleteForAll = "Delete for everyone"
    override val resendMessage = "Resend"
    override val deleteFailedMessage = "Delete"
    override val resendUnsupported = "Resending media is not supported. Delete the message and send the file again."
    override val clearHistory = "Clear history"
    override val clearHistoryMessage = "Choose who to clear message history for."
    override val clearHistoryForEveryoneMessage = "The entire message history will be deleted for everyone. This cannot be undone."
    override val clearHistoryForEveryoneNotAllowed = "Cannot clear the whole history for everyone: you can only delete your own messages, or all group messages if you are an admin."
    override val onlyForMe = "Only for me"
    override val forAll = "For everyone"
    override val deleteMessageTitle = "Delete message?"
    override val deleteMessageText = "The message will be deleted for all participants."
    override val notifications = "Notifications"
    override val changeWallpaper = "Change wallpaper"
    override val reply = "Reply"
    override val voiceMessage = "Voice message"
    override val photo = "Photo"
    override val swipeToCancel = "< Swipe to cancel"
    override val messagePlaceholder = "Message..."
    override val messageDeletedEmoji = "\uD83D\uDDD1 Message deleted"
    override val messageDeleted = "Message deleted"
    override val member = "Member"
    override val saveToGallery = "Save to gallery"
    override fun saveAllNPhotos(count: Int) = "Save all $count photos"
    override val copyText = "Copy text"
    override val you = "You"
    override val interlocutor = "Contact"
    override val showInChat = "Show in chat"
    override val savedToGallery = "Saved to gallery"
    override val saveFailed = "Save failed"

    override val mlsNotReady = "MLS is not ready for this chat"
    override val encrypted = "\uD83D\uDD12 [Encrypted]"
    override val sentFromOtherDevice = "\uD83D\uDD12 [Sent from another device]"
    override val decryptionError = "\uD83D\uDD12 [Decryption error]"
    override fun loadError(msg: String) = "Loading error: $msg"
    override fun leftGroup(name: String) = "$name left the group"
    override fun removedFromGroup(kickerName: String, kickedName: String) =
        "$kickerName removed $kickedName from the group"
    override val admin = "Admin"
    override val encryptionNotReady = "⚠\uFE0F Encryption not ready"
    override fun sendError(msg: String) = "Send error: $msg"
    override val photoSendError = "❌ Photo send error"
    override fun photoSendErrorDetail(msg: String) = "Photo send error: $msg"
    override fun voiceSendError(msg: String) = "Voice send error: $msg"
    override fun deleteError(msg: String) = "Delete error: $msg"
    override fun fileSendError(msg: String) = "File send error: $msg"
    override val fileReadError = "Failed to read file"
    override fun fileTooLarge(actual: String, max: String) = "File too large ($actual). Maximum: $max"
    override val downloadFile = "Download"
    override val openFile = "Open"
    override val fileDownloadError = "File download error"
    override val fileSaveError = "Failed to save file"

    override val newContact = "New contact"
    override val enterPublicKey = "Enter the user's public key"
    override val deleteContactTitle = "Delete contact?"
    override fun deleteContactMessage(name: String) = "Delete $name from contacts?"
    override val blockTitle = "Block?"
    override fun blockMessage(name: String) = "Block $name? They will be removed from contacts."
    override val blockAction = "Block"
    override val contactsTitle = "Contacts"
    override val noContacts = "No contacts"
    override val addContactHint = "Tap + to add a contact\nby public key"
    override val favorites = "⭐ Favorites"
    override val allContacts = "All contacts"
    override val removeFromFavorites = "Remove from favorites"
    override val addToFavorites = "Add to favorites"

    override val profileTitle = "Profile"
    override val username = "Username"
    override val aboutMe = "About"
    override val copyMyPublicKey = "Copy my public key"

    override val roleOwner = "Owner"
    override val roleAdmin = "Admin"
    override val roleMember = "Member"
    override val chatFallback = "Chat"
    override val leaveGroupTitle = "Leave group?"
    override val leaveGroupMessage = "You will no longer receive messages from this chat."
    override val leave = "Leave"
    override val removeMemberTitle = "Remove member?"
    override val userFallback = "User"
    override fun removeMemberMessage(name: String) = "$name will be removed from the group."
    override val remove = "Remove"
    override val sendDM = "Send direct message"
    override val addToContacts = "Add to contacts"
    override val makeAdmin = "Make admin"
    override val removeAdminRole = "Remove admin"
    override val removeFromGroup = "Remove from group"
    override val selectMember = "Select member"
    override val noAvailableContacts = "No contacts available to add"
    override val noName = "No name"
    override val groupInfo = "Group info"
    override fun membersCount(n: Int) = "$n member${if (n == 1) "" else "s"}"
    override val addMember = "Add member"
    override val leaveGroup = "Leave group"
    override val searchMembers = "Search members..."
    override val editGroupName = "Edit name"
    override val changeGroupPhoto = "Change group photo"

    override val sendMessage = "Message"
    override val addToContactsButton = "Add to contacts"

    override val groupName = "Group name"
    override fun membersSelected(n: Int) = "Members ($n selected)"
    override val noContactsYet = "You have no contacts yet"

    override val privacyTitle = "Privacy"
    override val whoSeesProfile = "Who can see my profile"
    override val whoSeesLastSeen = "Who can see my last seen"
    override val deleteAccountAfter = "Delete account after"
    override val autoDeleteMessages = "Auto-delete messages"
    override val autoDeleteOff = "Off"
    override val autoDelete1Day = "1 day"
    override val autoDelete1Week = "1 week"
    override val autoDelete1Month = "1 month"
    override val deleteAccountTitle = "Delete account?"
    override val deleteAccountWarning = "This action is irreversible. All data will be deleted."
    override val blackList = "Blacklist"
    override val manage = "Manage"
    override val privacySection = "Privacy"
    override val autoDeleteSection = "Auto-delete"
    override val deleteAccountAction = "Delete account"
    override val nsfwFilter = "NSFW filter"
    override val nsfwFilterDescription = "Runs incoming photos through the model before showing them."
    override val nsfwModel = "Image censorship model"
    override val nsfwModelDescription = "CLIP + NudeNet + anime detector. Downloaded once from Memegram servers."

    override val visEverybody = "Everybody"
    override val visContacts = "Contacts"
    override val visNobody = "Nobody"
    override val days1Month = "1 month"
    override val days3Months = "3 months"
    override val days6Months = "6 months"
    override val days1Year = "1 year"
    override val daysOff = "Off"

    override val notificationsTitle = "Notifications"
    override val privateChats = "Private chats"
    override val groups = "Groups"
    override val channels = "Channels"
    override val enableNotifications = "Enable notifications"
    override val disableForever = "Disable permanently"
    override val disableForTime = "Disable for a while..."
    override val disableForHowLong = "Disable for how many hours?"
    override val hours = "Hours"
    override val disable = "Disable"
    override val disabled = "Disabled"
    override val enabled = "Enabled"
    override val vibration = "Vibration"
    override val vibrationOff = "Off"
    override val vibrationLight = "Light"
    override val vibrationNormal = "Normal"
    override val vibrationStrong = "Strong"
    override val genericNotificationBody = "New message"
    override val noChatsToConfigure = "No chats"

    override val appearanceTitle = "Appearance"
    override val preview = "Preview"
    override val chatPreview = "Chat"
    override val previewMessage1 = "Hey! Want to grab lunch today?"
    override val previewMessage2 = "Sure! See you at noon \uD83D\uDE0A"
    override val topBarColor = "Top bar color"
    override val chatBgColor = "Chat background color"
    override val myMessageColor = "My message color"
    override val otherMessageColor = "Other message color"
    override val transparentBubblesTitle = "Transparent bubbles"
    override val transparentBubblesSubtitle = "Make message bubbles semi-transparent so the chat background shows through"
    override val bubbleTransparencyLabel = "Transparency"
    override val previewBubbleMy = "My message"
    override val previewBubbleTheir = "Message from contact"
    override val textColorSection = "Text color"
    override val textColorTopBar = "Top bar text"
    override val textColorChatBg = "System messages text"
    override val textColorMyMessage = "My messages text"
    override val textColorOtherMessage = "Other messages text"
    override val textColorAuto = "Auto (based on background luminance)"
    override val reset = "Reset"
    override val chooseColor = "Choose color"
    override val colorPickerPresets = "Presets"
    override val colorPickerCustom = "Custom"
    override val colorPickerHex = "HEX"
    override val themeSection = "Theme"
    override val selectColor = "Select"
    override val cropTitle = "Crop photo"
    override val setPhoto = "Photo"
    override val colorOrPhoto = "Color / Photo"

    override val dataAndStorageTitle = "Data and Storage"
    override val autoClearCache = "Automatic cache cleanup"
    override val deleteOldByTime = "Delete old by time (1 month)"
    override val deleteLeastRecent = "Delete least recently used"
    override val deleteLeastRequested = "Delete least frequently requested"
    override val deleteOldestFifo = "Delete oldest (FIFO)"
    override val messagesInChat = "Messages in chat (recent)"
    override val storeMessagesDays = "Store messages (days)"
    override val globalLimit = "Global limit (messages)"
    override val clearLocalCache = "Clear local cache now"
    override val clearProfilesCache = "Clear profile cache"
    override fun clearProfilesCacheSize(size: String) = "Clear profile cache ($size)"
    override val profilesCacheInfo = "Removes cached profiles and avatars of other users. They will be re-downloaded the next time you open a chat."
    override val fifoDescription = "Old messages beyond the limit will be removed from this chat"
    override val ttlDescription = "Messages older than the specified period are deleted automatically"
    override val lruDescription = "Messages are deleted from chats that haven't been opened for a long time"
    override val lfuDescription = "Messages are deleted from the least frequently accessed chats"

    override val storageUsage = "Storage Usage"
    override fun storageUsageOnDevice(size: String) = "Memegram is using $size on this device"
    override fun currentCacheLimit(value: String) = "Current limit: $value"
    override val storagePhotos = "Photos"
    override val storageVideos = "Videos"
    override val storageDocuments = "Documents"
    override val storageVoiceMessages = "Voice messages"
    override val storageMusic = "Music"
    override val storageOther = "Other"
    override val storageMiscellaneous = "Miscellaneous"
    override val storageTextMessages = "Text messages"
    override val storageOnDeviceModel = "Language model (on-device)"
    override fun clearSelectedSize(size: String) = "Clear Selected $size"
    override val storageCloudInfo = "All media will stay in the Memegram cloud and can be re-downloaded if you need them again."
    override val autoRemoveMedia = "Auto-remove cached media"
    override val never = "Never"
    override val autoRemoveInfo = "Photos, videos and other files from cloud chats that you have not accessed during this period will be removed from this device to save disk space."
    override val maxCacheSize = "Maximum cache size"
    override val noLimit = "No limit"
    override val cacheLimitInfo = "If your cache size exceeds this limit, the oldest unused media will be removed from the device."
    override val chatsTab = "Chats"
    override val mediaTab = "Media"
    override val filesTab = "Files"
    override val musicTab = "Music"
    override fun clearCacheSize(size: String) = "Clear Cache $size"
    override val advancedCleanup = "Advanced cleanup"
    override val stories = "Stories"

    override val languageTitle = "Language"
    override val searchLanguage = "Search language..."
    override val aiTranslation = "AI Translation"
    override val comingSoon = "Coming soon..."
    override val translate = "Translate"
    override val showOriginal = "Show original"
    override val translated = "Translated"
    override val translating = "Translating…"
    override val showTranslation = "Show translation"
    override val autoTranslate = "Auto-translate"
    override val targetLanguage = "Target language"
    override val dontTranslate = "Don't translate"
    override val translationSettings = "Translation settings"
    override val appLanguageHint = "app language"
    override val targetLanguageHint = "target language"
    override val translationNotAvailable = "Translation unavailable: not enough memory or model not loaded"
    override val translationModel = "Translation model"
    override val translationModelDescription = "NLLB-200. Downloaded once from Memegram servers."
    override val downloadModel = "Download model"
    override val downloadingModel = "Downloading model…"
    override val cancelDownload = "Cancel"
    override val deleteModel = "Delete model"
    override val modelReady = "Model ready"
    override val modelNotDownloaded = "Model not downloaded"
    override val modelDownloadFailed = "Model download failed"
    override val transcriptionModel = "Voice recognition model"
    override val transcriptionModelDescription = "Whisper Small. Downloaded once from Memegram servers."
    override val transcribe = "Transcribe"
    override val transcribing = "Transcribing…"
    override val showTranscription = "Show transcription"
    override val hideTranscription = "Hide transcription"
    override val transcriptionFailed = "Transcription failed"
    override val transcriptionNoSpeech = "No speech in this voice message"
    override val linkedDevicesTitle = "Linked Devices"
    override val refresh = "Refresh"
    override val addDeviceByQr = "Add device via QR"
    override val pendingConfirmation = "Pending confirmation"
    override val myDevices = "My devices"
    override val thisDevice = "this device"
    override val revoked = "Revoked"
    override val revoke = "Revoke"
    override val revokeDeviceTitle = "Revoke device?"
    override fun revokeDeviceMessage(name: String) = "'$name' will be removed from your account."
    override val makePrimary = "Make primary"
    override val transferPrimaryTitle = "Transfer primary device?"
    override fun transferPrimaryMessage(name: String) = "'$name' will be able to add and revoke devices."
    override val decline = "Decline"
    override val allow = "Allow"
    override val addDevice = "Add device"
    override val copyCodeHint = "Or copy the code and paste it on the new device"
    override val copyCode = "Copy code"
    override val codeCopied = "✓ Copied"
    override val scanQrInstead = "I want to scan a QR instead"

    override val scanQrHint = "Scan the QR code\nfrom your main device"
    override val pasteCodeHint = "Or paste a code from another device"
    override val codeOrQrLink = "Code or QR link"
    override val paste = "Paste"
    override val connect = "Connect"
    override val sendingDeviceData = "Sending device data..."
    override val awaitingConfirmation = "Awaiting confirmation\nfrom the main device"
    override val confirmOnMainDevice =
        "On your main phone go to\nSettings \u2192 Linked Devices\nand tap \"Allow\""
    override val deviceAdded = "Device added!"
    override val errorOccurred = "An error occurred"
    override val tryAgain = "Try again"

    override val unblockTitle = "Unblock?"
    override fun unblockMessage(name: String) = "Unblock $name?"
    override val unblockAction = "Unblock"
    override val blackListTitle = "Blacklist"
    override val blackListEmpty = "Blacklist is empty"
    override val blockedUsersHint = "Blocked users will appear here"
    override val blockUser = "Block"
    override val unblockUser = "Unblock"
    override fun blockConfirmTitle(name: String) = "Block $name?"
    override fun blockConfirmMessage(name: String) = "You won't receive messages from $name. Are you sure?"
    override val userBlockedBanner = "User is blocked"
    override val userBlockedSendError = "Cannot send message to a blocked user"
    override val youBlockedThisUser = "Blocked"
    override val youAreBlockedByUser = "You have been blocked by this user"
    override val cannotMessageTitle = "Cannot send message"
    override val cannotMessageBlockedByPeer = "This user has blocked you. You cannot send them messages."
    override val mlsChatBrokenBanner = "This chat can't be decrypted. Ask the other person to create a new chat."
    override val userDeletedAccountBanner = "This user deleted their account. You can't send them messages."
    override val deletedAccountTitle = "Deleted Account"

    override val mon = "Mon"
    override val tue = "Tue"
    override val wed = "Wed"
    override val thu = "Thu"
    override val fri = "Fri"
    override val sat = "Sat"
    override val sun = "Sun"

    override fun monthShort(month: Int): String = when (month) {
        1 -> "Jan"; 2 -> "Feb"; 3 -> "Mar"; 4 -> "Apr"
        5 -> "May"; 6 -> "Jun"; 7 -> "Jul"; 8 -> "Aug"
        9 -> "Sep"; 10 -> "Oct"; 11 -> "Nov"; else -> "Dec"
    }

    override val groupDefault = "Group"
    override val create = "Create"

    override val createInvite = "Create Invite"
    override val createInviteTitle = "New Invite Code"
    override val createInviteExpiresDays = "Expires in (days)"
    override val inviteCreated = "Invite code created"
    override val inviteCodeLabel = "Code"
    override val inviteExpiresAt = "Valid until"
}

val LocalStrings = staticCompositionLocalOf<AppStrings> { EnStrings }

object S {
    var current: AppStrings = EnStrings
}
