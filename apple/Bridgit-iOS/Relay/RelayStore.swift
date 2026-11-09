import Foundation
import Combine
import BridgitCore

/// Pairings live in the Keychain (they hold the relay token), indexed by relay device id.
@MainActor
final class RelayStore: ObservableObject {
    @Published private(set) var pairings: [Pairing] = []
    private static let key = "relay-pairings"

    init(loadStored: Bool = true) {
        guard loadStored, !AppleFixtureWorkspace.enabled else { return }
        if let s = Keychain.get(Self.key), let list = try? JSONDecoder().decode([Pairing].self, from: Data(s.utf8)) {
            pairings = list
        }
    }

    func add(_ p: Pairing) {
        pairings.removeAll { $0.deviceId == p.deviceId }
        pairings.append(p)
        save()
    }

    func remove(_ p: Pairing) {
        pairings.removeAll { $0.deviceId == p.deviceId }
        save()
    }

    func setChecked(_ next: [Pairing]) throws {
        if AppleFixtureWorkspace.enabled { pairings = next; return }
        let data = try JSONEncoder().encode(next)
        guard Keychain.set(String(decoding: data, as: UTF8.self), for: Self.key) else { throw DeviceAPIError(503, "keychain_unavailable", "The linked-device change could not be saved.") }
        pairings = next
    }
    /// The phone's friendly name changed on the phone: keep the stored pairing in step.
    func rename(_ p: Pairing, to name: String) {
        guard let i = pairings.firstIndex(where: { $0.id == p.id && $0.token == p.token && $0.base == p.base }), pairings[i].name != name else { return }
        pairings[i].name = name
        save()
    }

    func pairing(forInstall install: String) -> Pairing? { pairings.first { $0.install == install } }

    func replaceAllForSample(_ list: [Pairing]) { pairings = list }

    private func save() {
        guard !AppleFixtureWorkspace.enabled else { return }
        let data = try? JSONEncoder().encode(pairings)
        Keychain.set(data.flatMap { String(data: $0, encoding: .utf8) }, for: Self.key)
    }
}
