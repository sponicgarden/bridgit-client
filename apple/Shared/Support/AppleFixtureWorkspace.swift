import Foundation

/// Developer-launched UI acceptance only. Shipping builds cannot activate fixtures.
enum AppleFixtureWorkspace {
    static let defaults: UserDefaults = enabled ? UserDefaults(suiteName: "cc.bridgit.fixture." + UUID().uuidString)! : .standard
    static var appletDirectoryName: String {
        #if DEBUG
        if standalone && CommandLine.arguments.contains("-fixtureFreshStore") { return "BridgitFreshStore-" + fixtureRunID }
        if CommandLine.arguments.contains("-fixtureInbox") { return "BridgitFixtureInbox-" + fixtureRunID }
        if !creatorNames.isEmpty { return "BridgitCreators-" + fixtureRunID }
        #endif
        if standalone { return "BridgitStandaloneUIFixtures" }
        return transferFixture ? "BridgitTransferUIFixtures" : "BridgitAppleUIFixtures"
    }
    #if DEBUG
    private static let fixtureRunID = UUID().uuidString
    static var creatorNames: [String] {
        guard standalone else { return [] }
        if CommandLine.arguments.contains("-fixtureDuplicateCreators") { return ["Fixture Same", "Fixture Same"] }
        if CommandLine.arguments.contains("-fixtureTwoCreators") { return ["Fixture Alpha", "Fixture Beta"] }
        if CommandLine.arguments.contains("-fixtureOneCreator") { return ["Fixture Alpha"] }
        return []
    }
    #endif
    static var transferFixture: Bool {
        #if DEBUG
        CommandLine.arguments.contains("-fixtureTransfers")
        #else
        false
        #endif
    }
    static var standalone: Bool {
        #if DEBUG
        CommandLine.arguments.contains("-fixtureStandalone")
        #else
        false
        #endif
    }
    static var deviceIdentity: Bool {
        #if DEBUG
        enabled && CommandLine.arguments.contains("-fixtureDeviceIdentity")
        #else
        false
        #endif
    }
    static var deviceOSFacts: Bool {
        #if DEBUG
        enabled && CommandLine.arguments.contains("-fixtureDeviceOSFacts")
        #else
        false
        #endif
    }
    static var enabled: Bool {
        #if DEBUG
        standalone || transferFixture || CommandLine.arguments.contains("-fixturePortableLibrary") || CommandLine.arguments.contains("-fixtureAppraiser") || CommandLine.arguments.contains("-fixtureInbox") || ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] != nil
        #else
        false
        #endif
    }
}
