import Foundation

/// Android 3347's local presentation rules. Suggested types never grant control.
enum LanDevicePresentation {
    enum Kind: String, Codable {
        case camera, tv, speaker, audio, computer, mobile, printer, router, smart, cast, unknown
        var label: String {
            switch self {
            case .camera: "Camera"
            case .tv: "TV"
            case .speaker: "Speaker"
            case .audio: "Audio device"
            case .computer: "Computer"
            case .mobile: "Phone or tablet"
            case .printer: "Printer"
            case .router: "Router"
            case .smart: "Smart device"
            case .cast: "Cast device"
            case .unknown: "Network device"
            }
        }
        var category: Category {
            switch self {
            case .camera: .cameras
            case .tv, .cast: .media
            case .speaker, .audio: .audio
            case .router: .network
            case .printer: .printers
            case .smart: .smart
            case .computer: .computers
            case .mobile: .mobile
            case .unknown: .other
            }
        }
    }
    enum Category: String, CaseIterable {
        case cameras, media, audio, network, printers, smart, computers, mobile, other
        var title: String {
            switch self {
            case .cameras: "Cameras"
            case .media: "TV & video"
            case .audio: "Audio"
            case .network: "Routers & switches"
            case .printers: "Printers"
            case .smart: "Smart home"
            case .computers: "Computers"
            case .mobile: "Phones & tablets"
            case .other: "Other devices"
            }
        }
        var expandedByDefault: Bool { ![.computers, .mobile, .other].contains(self) }
    }
    struct Info: Equatable { let title: String; let kind: Kind; let model: String; let identifier: String }
    struct Group { let category: Category; let devices: [LanDiscoveredDevice] }
    private static let vendors = "(?i)WiiM|Denon|Sonos|HomePod|Hikvision|Dahua|Reolink|Tapo|Eufy|Amcrest|Axis|MacBook|iMac|Chromecast|Bravia|Samsung|Bose|JBL"
    private static let cameraBrand = #"(?i)\b(Hikvision|Dahua|Reolink|Tapo|Eufy|Amcrest|Axis)\b"#

    static func describe(_ device: LanDiscoveredDevice) -> Info {
        var name = LanDiscoveryObservation.cleanName(device.name), identifiers: [String] = []
        if let match = name.range(of: vendors, options: .regularExpression) {
            let prefix = String(name[..<match.lowerBound])
            if prefix.count >= 8, prefix.allSatisfy({ $0.isASCII && $0.isHexDigit }) {
                identifiers.append(prefix); name = String(name[match.lowerBound...])
            }
        }
        let kind = classify(name, device)
        if kind == .camera, let range = name.range(of: cameraBrand, options: .regularExpression) {
            let brand = String(name[range])
            if let serial = name.range(of: #"\s[-–]\s([A-Za-z0-9]{7,})$"#, options: .regularExpression) {
                let suffix = String(name[serial]).trimmingCharacters(in: .whitespaces)
                identifiers.append(String(suffix.dropFirst()).trimmingCharacters(in: .whitespaces))
                name.removeSubrange(serial)
            }
            let model = name.replacingOccurrences(of: "(?i)\\b" + NSRegularExpression.escapedPattern(for: brand) + "\\b", with: "", options: .regularExpression)
                .trimmingCharacters(in: CharacterSet(charactersIn: " -_"))
            let title = brand.lowercased() == "tapo" && model.range(of: "^C[0-9A-Za-z-]{2,10}$", options: .regularExpression) != nil
                ? "Tapo " + model : brand.prefix(1).uppercased() + brand.dropFirst().lowercased() + " camera"
            return Info(title: title, kind: kind, model: model, identifier: identifiers.joined(separator: " · "))
        }
        let looksLikeID = name.range(of: "(?i)^[0-9a-f:_-]{12,}$", options: .regularExpression) != nil ||
            (name.count >= 16 && !name.contains(where: \.isWhitespace) && name.filter(\.isNumber).count >= name.count / 3)
        if looksLikeID { identifiers.append(name); name = "" }
        if name == device.host { name = "" }
        return Info(title: name.isEmpty ? kind.label : name, kind: kind,
                    model: device.modelHints.first ?? "", identifier: identifiers.joined(separator: " · "))
    }

    /// Supported hosts must come from actual platform adapters, not simply an
    /// advertised SSH/camera service. Missing Apple adapters remain a parity gap.
    static func groups(_ devices: [LanDiscoveredDevice], added: Set<String> = [], supported: Set<String> = []) -> [Group] {
        let grouped = Dictionary(grouping: devices) { describe($0).kind.category }
        func rank(_ device: LanDiscoveredDevice) -> Int { added.contains(device.host) ? 0 : supported.contains(device.host) ? 1 : 2 }
        return Category.allCases.compactMap { category in
            guard let rows = grouped[category] else { return nil }
            return Group(category: category, devices: rows.sorted {
                if rank($0) != rank($1) { return rank($0) < rank($1) }
                if $0.present != $1.present { return $0.present }
                if $0.connected != $1.connected { return $0.connected }
                if priority($0) != priority($1) { return priority($0) < priority($1) }
                let order = describe($0).title.caseInsensitiveCompare(describe($1).title)
                return order == .orderedSame ? $0.host < $1.host : order == .orderedAscending
            })
        }
    }
    private static func priority(_ device: LanDiscoveredDevice) -> Int {
        switch describe(device).kind {
        case .camera: device.cameraCandidate ? 0 : 1
        case .tv, .cast: 2
        case .speaker, .audio: 3
        case .router: 4
        case .printer: 5
        case .smart: 6
        case .computer: 7
        case .mobile: 8
        case .unknown: 9
        }
    }
    private static func classify(_ name: String, _ device: LanDiscoveredDevice) -> Kind {
        let services = device.services.joined(separator: " ").lowercased()
        let n = (name + " " + device.modelHints.joined(separator: " ")).lowercased()
        func named(_ pattern: String) -> Bool { n.range(of: pattern, options: .regularExpression) != nil }
        func has(_ fragments: [String]) -> Bool { fragments.contains(where: services.contains) }
        if services.contains("tplink_smart_device") { return .smart }
        if services.contains("tplink_router") { return .router }
        if has(["_androidtvremote2.", "_mediaremotetv."]) { return .tv }
        if device.cameraCandidate || name.range(of: cameraBrand, options: .regularExpression) != nil || named(#"\b(camera|webcam)\b"#) { return .camera }
        if named(#"macbook|imac|mac studio|mac mini|macmini|macpro|thinkpad|laptop|desktop|windows pc|\bmac[0-9]+,[0-9]+\b"#) { return .computer }
        if named("iphone|ipad|pixel|galaxy") || named("android") && !named(#"\btv\b"#) { return .mobile }
        if has(["_ipp.", "_ipps.", "_printer.", "_pdl-datastream."]) || named(#"\b(printer|epson|brother)\b"#) { return .printer }
        if named(#"wiim|denon|sonos|homepod|\bspeaker\b|\bbose\b|\bjbl\b"#) { return .speaker }
        if named(#"\btv\b|television|chromecast|bravia|roku"#) { return .tv }
        if named(#"\brouter\b|openwrt|fritz!box|dream machine|tp-link deco"#) || services.contains("internetgatewaydevice") { return .router }
        if named(#"\bswitch\b|\busw-|\btl-sg|\bgs[0-9]{3}|access point|\beap[0-9]{3}"#) && !named("nintendo") { return .router }
        if has(["_shelly.", "_hue.", "_hap.", "_matter.", "_matterc.", "_home-assistant.", "_esphomelib.", "_wled.", "_mqtt."]) || named(#"\bshelly\b"#) { return .smart }
        if services.contains("_googlecast.") { return .cast }
        if has(["_sonos.", "_linkplay."]) { return .audio }
        if has(["_smb.", "_adisk.", "_ssh.", "_sftp-ssh.", "_rfb.", "_rdp."]) { return .computer }
        if has(["_airplay.", "_raop.", "_spotify-connect."]) { return .audio }
        return .unknown
    }
}
