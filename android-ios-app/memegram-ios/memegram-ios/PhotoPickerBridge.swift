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

    private static func jpegBytes(from result: PHPickerResult, completion: @escaping (Data?) -> Void) {
        let provider = result.itemProvider
        guard provider.canLoadObject(ofClass: UIImage.self) else { completion(nil); return }
        provider.loadObject(ofClass: UIImage.self) { obj, err in
            if let err = err { print("[PhotoPickerBridge] load error: \(err)") }
            guard let image = obj as? UIImage else { completion(nil); return }
            let resized = downscale(image, maxEdge: 4096)
            completion(resized.jpegData(compressionQuality: 0.9))
        }
    }

    private static func downscale(_ image: UIImage, maxEdge: CGFloat) -> UIImage {
        let w = image.size.width, h = image.size.height
        let longest = max(w, h)
        guard longest > maxEdge else { return image }
        let scale = maxEdge / longest
        let newSize = CGSize(width: floor(w * scale), height: floor(h * scale))
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        return UIGraphicsImageRenderer(size: newSize, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: newSize))
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
            Self.jpegBytes(from: r) { data in
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
