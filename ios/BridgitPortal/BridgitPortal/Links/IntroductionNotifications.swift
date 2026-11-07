import Foundation
import UserNotifications

/// LNK-36 "Ask me": a notification "Use porch-cam? camera, microphone · Muse via kitchen-tab" with Allow and Deny.
enum IntroductionNotifications {
    static let category = "link-introduction"
    static let allow = "allow"
    static let deny = "deny"

    static func register() {
        let c = UNNotificationCategory(identifier: category, actions: [
            UNNotificationAction(identifier: allow, title: "Allow", options: [.authenticationRequired]),
            UNNotificationAction(identifier: deny, title: "Deny", options: [.destructive]),
        ], intentIdentifiers: [])
        UNUserNotificationCenter.current().setNotificationCategories([c])
    }

    static func text(_ i: Introduction) -> (title: String, body: String) {
        let by = [i.agentName, "via " + i.via].compactMap { $0 }.joined(separator: " ")
        return ("Use \(i.fromName)?", [LinkService.list(i.services), by].filter { !$0.isEmpty }.joined(separator: " · "))
    }

    static func post(_ i: Introduction) {
        let center = UNUserNotificationCenter.current()
        center.requestAuthorization(options: [.alert, .sound]) { granted, _ in
            guard granted else { return }
            let content = UNMutableNotificationContent()
            let t = text(i)
            content.title = t.title
            content.body = t.body
            content.categoryIdentifier = category
            content.userInfo = ["introduction": i.id]
            center.add(UNNotificationRequest(identifier: "intro-" + i.id, content: content, trigger: nil))
        }
    }
}

/// Routes the notification's Allow/Deny to the app model.
final class IntroductionNotificationDelegate: NSObject, UNUserNotificationCenterDelegate {
    var onAnswer: (String, Bool) -> Void = { _, _ in }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        guard let id = response.notification.request.content.userInfo["introduction"] as? String else { return }
        switch response.actionIdentifier {
        case IntroductionNotifications.allow: await MainActor.run { onAnswer(id, true) }
        case IntroductionNotifications.deny: await MainActor.run { onAnswer(id, false) }
        default: break
        }
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner, .sound]
    }
}
