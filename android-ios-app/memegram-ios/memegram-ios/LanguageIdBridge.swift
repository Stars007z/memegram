import Foundation
import NaturalLanguage
import ComposeApp

final class LanguageIdBridge: NSObject, LanguageIdBridgeDelegate {
    static let shared = LanguageIdBridge()

    func identify(text: String) -> String? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return nil }
        let recognizer = NLLanguageRecognizer()
        recognizer.processString(trimmed)
        guard let lang = recognizer.dominantLanguage else { return nil }
        let raw = lang.rawValue
        if raw == "und" { return nil }
        return raw
    }
}
