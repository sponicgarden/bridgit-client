import Foundation
import UserNotifications
import BridgitCore
#if os(iOS)
import UIKit
#else
import AppKit
#endif

/// Notification authorization is distinct from permission to read other apps' notifications.
@MainActor final class CaptureNotices: NSObject, ObservableObject, UNUserNotificationCenterDelegate {
    enum Availability: String { case unknown, notDetermined = "not_determined", unavailable, allowed }
    struct Active: Identifiable, Equatable {
        let id: UUID
        let title: String
    }
    static let shared = CaptureNotices()
    @Published private(set) var availability: Availability = .unknown
    @Published private(set) var active: [Active] = []
    private let center: UNUserNotificationCenter
    private let readOverride: (() async -> Availability)?
    private let sendOverride: ((UUID, String) async throws -> Void)?
    private let promptOverride: (() async throws -> Void)?
    private let isActive: () -> Bool

    init(center: UNUserNotificationCenter = .current(),
         read: (() async -> Availability)? = nil,
         send: ((UUID, String) async throws -> Void)? = nil,
         prompt: (() async throws -> Void)? = nil,
         isActive: (() -> Bool)? = nil) {
        self.center = center
        self.readOverride = read
        self.sendOverride = send
        self.promptOverride = prompt
        self.isActive = isActive ?? {
            #if os(iOS)
            UIApplication.shared.applicationState == .active
            #else
            NSApp.isActive
            #endif
        }
        super.init()
    }

    func refresh() async {
        if let readOverride { availability = await readOverride(); return }
        let settings = await center.notificationSettings()
        if settings.authorizationStatus == .notDetermined { availability = .notDetermined }
        else if settings.authorizationStatus == .authorized && settings.alertSetting == .enabled { availability = .allowed }
        else { availability = .unavailable }
    }

    /// Called only from the owner's explicit permission action.
    func request() async throws {
        guard isActive() else {
            throw DeviceAPIError(409, "foreground_required", "Open Bridgit to respond to the notification permission prompt.")
        }
        if let promptOverride { try await promptOverride() }
        else { _ = try await center.requestAuthorization(options: [.alert]) }
        await refresh()
        guard isActive() else {
            throw DeviceAPIError(409, "foreground_required", "Open Bridgit to finish notification permission setup.")
        }
    }

    func requireAvailable() async throws {
        await refresh()
        guard availability == .allowed else {
            throw DeviceAPIError(403, "notifications_required", "Allow Bridgit capture notifications before this capture.")
        }
    }

    /// A successful return means the OS accepted the notice request, not that a person saw it.
    func begin(_ title: String) async throws -> UUID {
        try await requireAvailable()
        try Task.checkCancellation()
        let id = UUID()
        let identifier = "capture." + id.uuidString
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = "Bridgit capture"
        if let sendOverride { try await sendOverride(id, title) }
        else {
            center.delegate = self
            try await center.add(UNNotificationRequest(identifier: identifier, content: content, trigger: nil))
        }
        do {
            try Task.checkCancellation()
            try await requireAvailable()
            try Task.checkCancellation()
        } catch {
            center.removePendingNotificationRequests(withIdentifiers: [identifier])
            center.removeDeliveredNotifications(withIdentifiers: [identifier])
            throw error
        }
        active.append(Active(id: id, title: title))
        return id
    }

    func end(_ id: UUID) { active.removeAll { $0.id == id } }

    func monitor(interval: UInt64 = 1_000_000_000,
                 onUnavailable: @escaping @MainActor () -> Void) -> Task<Void, Never> {
        Task { @MainActor in
            while !Task.isCancelled {
                do {
                    try await Task.sleep(nanoseconds: interval)
                    try await requireAvailable()
                } catch {
                    if !Task.isCancelled { onUnavailable() }
                    break
                }
            }
        }
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        // Preserve visibility while the host app itself is in the foreground.
        [.banner, .list]
    }
}

import SwiftUI

/// A named in-app indication complements the OS privacy indicator while a capture is active.
struct CaptureNoticeBanner: View {
    @ObservedObject private var notices = CaptureNotices.shared
    var body: some View {
        if let current = notices.active.last {
            Text(current.title)
                .font(.callout.weight(.semibold))
                .padding(.horizontal, 16).padding(.vertical, 10)
                .background(.regularMaterial, in: Capsule())
                .accessibilityIdentifier("active-capture-notice")
                .padding(.vertical, 8)
        }
    }
}
