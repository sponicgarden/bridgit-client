import BridgitCore
import Foundation

struct ViewerDeviceDescription {
    var id: String
    var name: String
    var installID: String?
    var relayDeviceID: String?
    var reportedMetadata = ReportedDeviceMetadata()
    var json: [String: Any] {
        var result: [String: Any] = [
            "id": id, "name": name,
            "platform": reportedMetadata.platform as Any? ?? NSNull(),
            "device_type": reportedMetadata.deviceType as Any? ?? NSNull(),
            "install_id": installID as Any? ?? NSNull(),
            "relay_device_id": relayDeviceID as Any? ?? NSNull(),
            "access": relayDeviceID == nil ? "account_snapshot" : "relay_view"
        ]
        if relayDeviceID != nil {
            result["local_role"] = "satellite"
            result["remote_role"] = "hub"
        }
        return result
    }
}

/// The app's existing owner UI and authenticated APIs use the same platform model.
/// Account credentials stay inside the platform model and its signed-in service client.
@MainActor protocol ViewerConfiguration: AnyObject {
    var configuredPairings: [Pairing] { get }
    var configuredDevices: [ViewerDeviceDescription] { get }
    var configuredAttachment: String? { get }
    var configuredAccountEmail: String? { get }
    var configuredSignedIn: Bool { get }
    var configuredSigningIn: Bool { get }
    var configuredViewsPaused: Bool? { get }
    var configuredCloud: WatchClient { get }
    func refreshConfiguredAccount() async
    func beginConfiguredSignIn()
    func cancelConfiguredSignIn()
    func configuredSignOut() async
    func saveConfiguredPairing(_ pairing: Pairing) throws
    func removeConfiguredPairing(_ pairing: Pairing) throws
    func renameConfiguredPairing(_ pairing: Pairing, name: String?) throws
    func selectConfiguredDevice(_ id: String?) throws
    func setConfiguredViewsPaused(_ paused: Bool) throws
}

@MainActor enum ViewerConfigurationRouter {
    static func execute(_ action: ViewerConfigurationAction, viewer: ViewerConfiguration, authorize: () async throws -> Void) async throws -> HostResponse {
        switch action {
        case .account:
            return try .json(["signed_in": viewer.configuredSignedIn, "signing_in": viewer.configuredSigningIn, "email": viewer.configuredAccountEmail as Any? ?? NSNull(), "credentials": "write_only", "sign_in_url": "/v1/account/sign-in", "human_step": "Complete account sign-in in the system web sheet"])
        case .accountPhones, .linkPhone, .unlinkPhone:
            let cloud = viewer.configuredCloud
            guard viewer.configuredSignedIn, let session = cloud.session else { throw DeviceAPIError(409, "account_sign_in_required", "Sign in to manage account phones.") }
            try await authorize()
            guard viewer.configuredSignedIn, cloud.session == session else { throw DeviceAPIError(409, "account_changed", "The signed-in account changed.") }
            var result: [String: Any] = ["updated": true]
            do {
                switch action {
                case .accountPhones:
                    let phones = try await cloud.phones().phones
                    guard viewer.configuredSignedIn, cloud.session == session else { throw DeviceAPIError(409, "account_changed", "The signed-in account changed.") }
                    return try .json(["phones": phones.map { ["id": $0.phone, "name": $0.displayName, "platform": $0.reportedMetadata.platform as Any? ?? NSNull(), "device_type": $0.reportedMetadata.deviceType as Any? ?? NSNull()] }])
                case .linkPhone(let code):
                    try await cloud.link(code: code)
                case .unlinkPhone(let id):
                    let pending = try await cloud.unlink(phone: id)
                    result["deletion_pending"] = pending as Any? ?? NSNull()
                default: preconditionFailure("Expected an account phone action")
                }
            } catch let error as APIError {
                throw DeviceAPIError(error.status, "account_request_failed", "The account service rejected the phone request.")
            }
            if viewer.configuredSignedIn, cloud.session == session { await viewer.refreshConfiguredAccount() }
            return try .json(result)
        case .linkedDevices:
            let pairings = viewer.configuredPairings.map { pairing -> [String: Any] in
                ["id": pairing.id, "name": pairing.displayName, "local_alias": pairing.localAlias as Any? ?? NSNull(), "reported_name": pairing.name, "local_role": "satellite", "remote_role": "hub", "flavors": ["view"], "identity_evidence": "pairing_metadata", "install_id": pairing.install as Any? ?? NSNull(), "relay_device_id": pairing.deviceId, "services": ["view"], "credentials": "write_only"]
            }
            return try .json([
                "pairings": pairings, "devices": viewer.configuredDevices.map(\.json),
                "attachment": viewer.configuredAttachment as Any? ?? NSNull(),
                "views_paused": viewer.configuredViewsPaused as Any? ?? NSNull(),
                "supports_views_pause": viewer.configuredViewsPaused != nil,
                "supports_hub_connections_pause": false, "supports_satellite_connections_pause": false,
                "local_host_authority": "separate"
            ])
        case .signIn:
            try await authorize()
            if viewer.configuredSignedIn { return try .json(["status": "signed_in"]) }
            viewer.beginConfiguredSignIn()
            return try .json(["status": "needs_sign_in", "status_url": "/v1/account", "human_step": "Complete sign-in in the system web sheet"], status: 202)
        case .cancelSignIn:
            try await authorize(); viewer.cancelConfiguredSignIn()
            return try .json(["cancelled": true])
        case .signOut:
            try await authorize(); await viewer.configuredSignOut()
            return try .json(["signed_in": viewer.configuredSignedIn])
        case .add(let pairing):
            try await authorize()
            let claimed = try await PairingClaim.claim(pairing, outboundAccess: DeviceOutboundPolicy.access)
            try await authorize()
            try viewer.saveConfiguredPairing(claimed)
            return try .json(["id": claimed.id, "name": claimed.displayName, "credentials": "write_only", "local_host_grants_created": false], status: 201)
        case .remove(let id):
            guard let pairing = viewer.configuredPairings.first(where: { $0.id == id }) else { throw missing() }
            try await authorize(); try viewer.removeConfiguredPairing(pairing)
            return try .json(["removed": true, "id": id])
        case .rename(let id, let name):
            guard let pairing = viewer.configuredPairings.first(where: { $0.id == id }) else { throw missing() }
            try await authorize(); try viewer.renameConfiguredPairing(pairing, name: name)
            return try .json(["id": id, "local_alias": name as Any? ?? NSNull()])
        case .attach(let id):
            guard id == nil || id == "local" || viewer.configuredDevices.contains(where: { $0.id == id }) else { throw missing() }
            try await authorize(); try viewer.selectConfiguredDevice(id)
            return try .json(["attachment": viewer.configuredAttachment as Any? ?? NSNull()])
        case .pauseViews(let paused):
            guard viewer.configuredViewsPaused != nil else { throw DeviceAPIError(422, "unsupported_setting", "This build has no global linked-view pause control.") }
            try await authorize(); try viewer.setConfiguredViewsPaused(paused)
            return try .json(["views_paused": viewer.configuredViewsPaused as Any? ?? NSNull()])
        }
    }
    private static func missing() -> DeviceAPIError { .init(404, "linked_device_not_found", "Choose a known linked device.") }
}
