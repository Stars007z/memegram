import SwiftUI
import UIKit
import UserNotifications
import ComposeApp
#if canImport(FirebaseCore)
import FirebaseCore
import FirebaseMessaging
#endif

class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        #if canImport(FirebaseCore)
        FirebaseApp.configure()
        Messaging.messaging().delegate = self as? MessagingDelegate
        #endif

        IosOnnxBridge.shared.register(delegate: OnnxBridge.shared)
        IosZipBridge.shared.register(delegate: ZipBridge.shared)
        IosLanguageIdBridge.shared.register(delegate: LanguageIdBridge.shared)
        IosPhotoPickerBridge.shared.register(delegate: PhotoPickerBridge.shared)
        IosFileOpenBridge.shared.register(delegate: FileOpenBridge.shared)

        UNUserNotificationCenter.current().delegate = self
        let authOptions: UNAuthorizationOptions = [.alert, .badge, .sound]
        UNUserNotificationCenter.current().requestAuthorization(options: authOptions) { _, _ in }
        application.registerForRemoteNotifications()

        return true
    }

    func application(
        _ application: UIApplication,
        didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data
    ) {
        #if canImport(FirebaseMessaging)
        Messaging.messaging().apnsToken = deviceToken
        #endif
    }

    func application(
        _ application: UIApplication,
        didFailToRegisterForRemoteNotificationsWithError error: Error
    ) {
        print("APNs registration failed: \(error)")
    }

    func application(
        _ application: UIApplication,
        didReceiveRemoteNotification userInfo: [AnyHashable: Any],
        fetchCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void
    ) {
        handleRemotePayload(userInfo)
        completionHandler(.newData)
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        let userInfo = notification.request.content.userInfo
        let eventType = (userInfo["event_type"] as? String) ?? ""
        handleRemotePayload(userInfo)
        if eventType == "conversation_deleted" {
            completionHandler([])
        } else {
            if #available(iOS 14.0, *) {
                completionHandler([.banner, .list, .sound, .badge])
            } else {
                completionHandler([.alert, .sound, .badge])
            }
        }
    }

    private func handleRemotePayload(_ userInfo: [AnyHashable: Any]) {
        let eventType = (userInfo["event_type"] as? String) ?? ""
        let conversationId = (userInfo["conversation_id"] as? String) ?? ""

        switch eventType {
        case "conversation_deleted":
            let reason = (userInfo["reason"] as? String) ?? ""
            guard reason != "account_deleted" else { return }
            guard !conversationId.isEmpty else { return }
            IosConversationCleanerBridge.shared.purge(conversationId: conversationId)
        default:
            break
        }
    }

    func applicationDidReceiveMemoryWarning(_ application: UIApplication) {
        IosMlModelGateBridge.shared.onMemoryPressure()
    }

    func applicationDidEnterBackground(_ application: UIApplication) {
        IosMlModelGateBridge.shared.onAppBackgrounded()
    }
}

#if canImport(FirebaseMessaging)
extension AppDelegate: MessagingDelegate {
    func messaging(_ messaging: Messaging, didReceiveRegistrationToken fcmToken: String?) {
        IosPushTokenBridge.shared.setToken(token: fcmToken)
    }
}
#endif

@main
struct iOSApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var appDelegate

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
