import Foundation
import ComposeApp
#if canImport(ZIPFoundation)
import ZIPFoundation
#endif

final class ZipBridge: NSObject, ZipBridgeDelegate {

    static let shared = ZipBridge()

    func unzip(zipPath: String, destinationDir: String) {
        #if canImport(ZIPFoundation)
        let fm = FileManager.default
        let zipURL = URL(fileURLWithPath: zipPath)
        guard let archive = Archive(url: zipURL, accessMode: .read) else {
            print("[ZipBridge] cannot open archive at \(zipPath)")
            return
        }
        for entry in archive {
            let baseName = (entry.path as NSString).lastPathComponent
            if entry.type == .directory || baseName.isEmpty { continue }
            let destPath = (destinationDir as NSString).appendingPathComponent(baseName)
            if fm.fileExists(atPath: destPath) {
                try? fm.removeItem(atPath: destPath)
            }
            do {
                _ = try archive.extract(entry, to: URL(fileURLWithPath: destPath))
            } catch {
                print("[ZipBridge] extract failed for \(entry.path): \(error)")
            }
        }
        #else
        print("[ZipBridge] ZIPFoundation not linked — unzip is a no-op")
        #endif
    }
}
