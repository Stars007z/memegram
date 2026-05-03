import Foundation
import UIKit
import ComposeApp

final class FileOpenBridge: NSObject, FileOpenBridgeDelegate, UIDocumentInteractionControllerDelegate {

    static let shared = FileOpenBridge()

    private var retainedControllers: [UIDocumentInteractionController] = []

    func open(path: String, mime: String) -> Bool {
        if !Thread.isMainThread {
            var result = false
            DispatchQueue.main.sync { result = open(path: path, mime: mime) }
            return result
        }

        guard FileManager.default.fileExists(atPath: path) else {
            print("[FileOpenBridge] file does not exist: \(path)")
            return false
        }
        guard let presenter = Self.topViewController(), let view = presenter.view else {
            print("[FileOpenBridge] no presenter - cannot open file")
            return false
        }

        let url = URL(fileURLWithPath: path)
        let controller = UIDocumentInteractionController(url: url)
        controller.delegate = self
        retainedControllers.append(controller)
        let sourceRect = CGRect(x: view.bounds.midX, y: view.bounds.midY, width: 1, height: 1)

        if controller.presentPreview(animated: true) {
            return true
        }
        if controller.presentOpenInMenu(from: sourceRect, in: view, animated: true) {
            return true
        }
        if controller.presentOptionsMenu(from: sourceRect, in: view, animated: true) {
            return true
        }
        release(controller)
        return false
    }

    func documentInteractionControllerViewControllerForPreview(
        _ controller: UIDocumentInteractionController
    ) -> UIViewController {
        Self.topViewController() ?? UIViewController()
    }

    func documentInteractionControllerDidEndPreview(_ controller: UIDocumentInteractionController) {
        release(controller)
    }

    func documentInteractionControllerDidDismissOpenInMenu(_ controller: UIDocumentInteractionController) {
        release(controller)
    }

    func documentInteractionControllerDidDismissOptionsMenu(_ controller: UIDocumentInteractionController) {
        release(controller)
    }

    private func release(_ controller: UIDocumentInteractionController) {
        retainedControllers.removeAll { $0 === controller }
    }

    private static func topViewController() -> UIViewController? {
        let scene = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive }
            ?? UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first

        let window = scene?.windows.first(where: { $0.isKeyWindow }) ?? scene?.windows.first
        var top = window?.rootViewController
        while let presented = top?.presentedViewController { top = presented }
        return top
    }
}
