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
