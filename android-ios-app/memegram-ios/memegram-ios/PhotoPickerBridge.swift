import Foundation
import UIKit
import PhotosUI
import ComposeApp

final class PhotoPickerBridge: NSObject, PhotoPickerBridgeDelegate {

    static let shared = PhotoPickerBridge()

    private var pending: [ObjectIdentifier: (PHPickerViewController, ([KotlinByteArray]) -> Void)] = [:]
    private let lock = NSLock()

    func pick(multiple: Bool, onResult: @escaping ([KotlinByteArray]) -> Void) {
        let deliver: ([KotlinByteArray]) -> Void = { items in
            DispatchQueue.main.async {
                onResult(items)
            }
        }

        DispatchQueue.main.async { [weak self] in
            guard let self = self else { deliver([]); return }
            guard let presenter = Self.topViewController() else {
                print("[PhotoPickerBridge] no root view controller — cannot present picker")
                deliver([])
                return
            }

            var config = PHPickerConfiguration(photoLibrary: .shared())
            config.filter = .images
            config.selectionLimit = multiple ? 0 : 1
            if #available(iOS 15.0, *) {
                config.selection = .ordered
            }
            config.preferredAssetRepresentationMode = .current

            let picker = PHPickerViewController(configuration: config)
            picker.delegate = self
            picker.modalPresentationStyle = .fullScreen

            self.lock.lock()
            self.pending[ObjectIdentifier(picker)] = (picker, deliver)
            self.lock.unlock()

            presenter.present(picker, animated: true, completion: nil)
        }
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

    private static func originalBytes(from result: PHPickerResult, completion: @escaping (Data?) -> Void) {
        let provider = result.itemProvider
        let preferredTypes = ["public.heic", "public.jpeg", "public.png", "public.image"]
        let typeId = preferredTypes.first(where: { provider.hasItemConformingToTypeIdentifier($0) })
            ?? provider.registeredTypeIdentifiers.first

        if let typeId = typeId {
            provider.loadFileRepresentation(forTypeIdentifier: typeId) { url, err in
                if let err = err { print("[PhotoPickerBridge] file load error: \(err)") }
                if let url = url, let data = try? Data(contentsOf: url) {
                    completion(data)
                    return
                }
                Self.fallbackJpeg(provider: provider, completion: completion)
            }
            return
        }
        Self.fallbackJpeg(provider: provider, completion: completion)
    }

    private static func fallbackJpeg(provider: NSItemProvider, completion: @escaping (Data?) -> Void) {
        guard provider.canLoadObject(ofClass: UIImage.self) else { completion(nil); return }
        provider.loadObject(ofClass: UIImage.self) { obj, err in
            if let err = err { print("[PhotoPickerBridge] fallback load error: \(err)") }
            guard let image = obj as? UIImage else { completion(nil); return }
            completion(image.jpegData(compressionQuality: 0.95))
        }
    }
}

extension PhotoPickerBridge: PHPickerViewControllerDelegate {
    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        let key = ObjectIdentifier(picker)
        lock.lock()
        let entry = pending.removeValue(forKey: key)
        lock.unlock()

        picker.presentingViewController?.dismiss(animated: true, completion: nil)

        guard let (_, deliver) = entry else { return }
        if results.isEmpty { deliver([]); return }

        let group = DispatchGroup()
        var bytes: [Data?] = Array(repeating: nil, count: results.count)
        for (idx, r) in results.enumerated() {
            group.enter()
            Self.originalBytes(from: r) { data in
                bytes[idx] = data
                group.leave()
            }
        }
        group.notify(queue: .global(qos: .userInitiated)) {
            let kotlinArrays: [KotlinByteArray] = bytes.compactMap { $0 }.map { data in
                let arr = KotlinByteArray(size: Int32(data.count))
                data.withUnsafeBytes { (raw: UnsafeRawBufferPointer) in
                    let src = raw.bindMemory(to: Int8.self).baseAddress!
                    for i in 0..<data.count { arr.set(index: Int32(i), value: src[i]) }
                }
                return arr
            }
            deliver(kotlinArrays)
        }
    }
}
