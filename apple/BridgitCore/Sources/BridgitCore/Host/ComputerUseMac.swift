#if os(macOS)
import AppKit
import ApplicationServices
import CoreGraphics
import Foundation
import ImageIO
import ScreenCaptureKit
import UniformTypeIdentifiers

/// The Mac side of computer use: ScreenCaptureKit for the screen, the Accessibility API
/// (AXUIElement) for any app's UI tree and actions, and CGEvent for mouse and keyboard input.
/// macOS checks Accessibility and Screen Recording itself; this class only reads and asks.
public final class MacComputerUseBackend: ComputerUseBackend, @unchecked Sendable {
    public init() {}

    public static var current: ComputerUsePermissions {
        .init(accessibility: AXIsProcessTrusted(), screenRecording: CGPreflightScreenCaptureAccess())
    }

    public func permissions() async -> ComputerUsePermissions { Self.current }

    public func requestPermissions() async -> ComputerUsePermissions {
        await MainActor.run {
            if !AXIsProcessTrusted() {
                _ = AXIsProcessTrustedWithOptions(["AXTrustedCheckOptionPrompt": true] as CFDictionary)
            }
            if !CGPreflightScreenCaptureAccess() { _ = CGRequestScreenCaptureAccess() }
        }
        return await permissions()
    }

    // MARK: Screen

    public func screenshot(maxEdge: Int, format: String, quality: Int) async throws -> [String: Any] {
        let content: SCShareableContent
        do { content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: true) }
        catch { throw DeviceAPIError(409, "os_permission_required", "macOS refused screen capture. Allow Screen & System Audio Recording for Bridgit.", reason: "screen_recording") }
        let mainID = CGMainDisplayID()
        guard let display = content.displays.first(where: { $0.displayID == mainID }) ?? content.displays.first else {
            throw DeviceAPIError(503, "display_unavailable", "No display is available to capture.")
        }
        let points = CGDisplayBounds(display.displayID).size
        let scale = max(1, Double(CGDisplayPixelsWide(display.displayID)) / max(1, Double(points.width)))
        let native = CGSize(width: points.width * scale, height: points.height * scale)
        let fit = min(1, Double(maxEdge) / max(native.width, native.height))
        let configuration = SCStreamConfiguration()
        configuration.width = max(1, Int((native.width * fit).rounded()))
        configuration.height = max(1, Int((native.height * fit).rounded()))
        configuration.showsCursor = true
        let filter = SCContentFilter(display: display, excludingWindows: [])
        let image = try await SCScreenshotManager.captureImage(contentFilter: filter, configuration: configuration)
        let type = format == "png" ? UTType.png : UTType.jpeg
        let data = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(data, type.identifier as CFString, 1, nil) else {
            throw DeviceAPIError(500, "encoding_failed", "Could not encode the screenshot.")
        }
        CGImageDestinationAddImage(destination, image, format == "png" ? nil : [kCGImageDestinationLossyCompressionQuality: Double(quality) / 100] as CFDictionary)
        guard CGImageDestinationFinalize(destination) else { throw DeviceAPIError(500, "encoding_failed", "Could not encode the screenshot.") }
        return ["mime": format == "png" ? "image/png" : "image/jpeg", "data_base64": (data as Data).base64EncodedString(),
                "width_px": image.width, "height_px": image.height,
                "width_points": Double(points.width), "height_points": Double(points.height),
                "display_id": Int(display.displayID), "displays": content.displays.count]
    }

    // MARK: Apps

    public func apps() async throws -> [[String: Any]] {
        await MainActor.run {
            let front = NSWorkspace.shared.frontmostApplication?.processIdentifier
            return NSWorkspace.shared.runningApplications.filter { $0.activationPolicy == .regular }.map { app in
                ["pid": Int(app.processIdentifier), "bundle_id": app.bundleIdentifier as Any? ?? NSNull(),
                 "name": app.localizedName as Any? ?? NSNull(), "frontmost": app.processIdentifier == front,
                 "hidden": app.isHidden]
            }
        }
    }

    private func application(pid: Int32?, bundleID: String?) async throws -> NSRunningApplication {
        let found: NSRunningApplication? = await MainActor.run {
            if let pid { return NSRunningApplication(processIdentifier: pid) }
            if let bundleID { return NSRunningApplication.runningApplications(withBundleIdentifier: bundleID).first }
            return NSWorkspace.shared.frontmostApplication
        }
        guard let found, !found.isTerminated else { throw DeviceAPIError(404, "app_not_found", "That app is not running. GET /v1/computer-use/apps lists running apps.") }
        return found
    }

    // MARK: Accessibility tree

    private static func attribute(_ element: AXUIElement, _ name: String) -> CFTypeRef? {
        var value: CFTypeRef?
        guard AXUIElementCopyAttributeValue(element, name as CFString, &value) == .success else { return nil }
        return value
    }
    private static func string(_ element: AXUIElement, _ name: String, max: Int = 300) -> String? {
        guard let value = attribute(element, name) else { return nil }
        let text: String?
        if let string = value as? String { text = string }
        else if let number = value as? NSNumber { text = number.stringValue }
        else { text = nil }
        guard let text, !text.isEmpty else { return nil }
        return text.count > max ? String(text.prefix(max)) + "…" : text
    }
    private static func children(_ element: AXUIElement) -> [AXUIElement] {
        guard let value = attribute(element, "AXChildren"), CFGetTypeID(value) == CFArrayGetTypeID() else { return [] }
        return (value as! [AnyObject]).compactMap { item in
            CFGetTypeID(item) == AXUIElementGetTypeID() ? (item as! AXUIElement) : nil
        }
    }
    private static func frame(_ element: AXUIElement) -> [String: Double]? {
        var point = CGPoint.zero, size = CGSize.zero
        guard let position = attribute(element, "AXPosition"), CFGetTypeID(position) == AXValueGetTypeID(),
              AXValueGetValue(position as! AXValue, .cgPoint, &point),
              let extent = attribute(element, "AXSize"), CFGetTypeID(extent) == AXValueGetTypeID(),
              AXValueGetValue(extent as! AXValue, .cgSize, &size) else { return nil }
        return ["x": Double(point.x), "y": Double(point.y), "width": Double(size.width), "height": Double(size.height)]
    }
    private static func actions(_ element: AXUIElement) -> [String] {
        var names: CFArray?
        guard AXUIElementCopyActionNames(element, &names) == .success, let names = names as? [String] else { return [] }
        return names
    }

    public func tree(pid: Int32?, bundleID: String?, maxDepth: Int, maxNodes: Int) async throws -> [String: Any] {
        let app = try await application(pid: pid, bundleID: bundleID)
        let root = AXUIElementCreateApplication(app.processIdentifier)
        AXUIElementSetMessagingTimeout(root, 2)
        var count = 0, truncated = false
        func node(_ element: AXUIElement, id: String, depth: Int) -> [String: Any] {
            count += 1
            var entry: [String: Any] = ["id": id]
            for (key, attribute) in [("role", "AXRole"), ("subrole", "AXSubrole"), ("title", "AXTitle"), ("value", "AXValue"),
                                     ("description", "AXDescription"), ("help", "AXHelp"), ("identifier", "AXIdentifier")] {
                if let text = Self.string(element, attribute) { entry[key] = text }
            }
            if let frame = Self.frame(element) { entry["frame"] = frame }
            if let enabled = Self.attribute(element, "AXEnabled") as? Bool, !enabled { entry["enabled"] = false }
            if let focused = Self.attribute(element, "AXFocused") as? Bool, focused { entry["focused"] = true }
            let actions = Self.actions(element)
            if !actions.isEmpty { entry["actions"] = actions }
            let kids = Self.children(element)
            if !kids.isEmpty {
                if depth >= maxDepth { entry["children_omitted"] = kids.count; truncated = true }
                else {
                    var list: [[String: Any]] = []
                    for (index, child) in kids.enumerated() {
                        guard count < maxNodes else { truncated = true; entry["children_omitted"] = kids.count - index; break }
                        list.append(node(child, id: id + "." + String(index), depth: depth + 1))
                    }
                    entry["children"] = list
                }
            }
            return entry
        }
        let tree = node(root, id: "0", depth: 0)
        guard count > 1 || Self.attribute(root, "AXRole") != nil else {
            throw DeviceAPIError(409, "os_permission_required", "macOS did not share this app's interface. Check that Accessibility is on for Bridgit.", reason: "accessibility")
        }
        return ["app": ["pid": Int(app.processIdentifier), "bundle_id": app.bundleIdentifier as Any? ?? NSNull(), "name": app.localizedName as Any? ?? NSNull()],
                "tree": tree, "nodes": count, "truncated": truncated,
                "element_ids": "Pass an id as element with pid to press, focus or set_value. Ids change when the app's interface changes; read the tree again first."]
    }

    private func element(_ action: ComputerUseAction) async throws -> AXUIElement {
        let app = try await application(pid: action.pid, bundleID: action.bundleID)
        var current = AXUIElementCreateApplication(app.processIdentifier)
        AXUIElementSetMessagingTimeout(current, 2)
        let path = action.elementPath
        guard path.first == 0 else { throw DeviceAPIError(422, "invalid_parameter", "Element ids start at 0, the app itself.") }
        for index in path.dropFirst() {
            let kids = Self.children(current)
            guard kids.indices.contains(index) else { throw DeviceAPIError(409, "element_not_found", "That element is gone. Read the ui tree again.") }
            current = kids[index]
        }
        return current
    }

    // MARK: Actions

    private static func flags(_ modifiers: [String]) -> CGEventFlags {
        var flags = CGEventFlags()
        for name in modifiers {
            switch name {
            case "cmd": flags.insert(.maskCommand)
            case "shift": flags.insert(.maskShift)
            case "option": flags.insert(.maskAlternate)
            case "control": flags.insert(.maskControl)
            case "fn": flags.insert(.maskSecondaryFn)
            default: break
            }
        }
        return flags
    }

    private static func post(_ event: CGEvent?) throws {
        guard let event else { throw DeviceAPIError(500, "input_failed", "macOS could not create that input event.") }
        event.post(tap: .cghidEventTap)
    }

    private static func mouse(_ type: CGEventType, _ point: CGPoint, _ button: CGMouseButton, flags: CGEventFlags, clicks: Int64 = 1) throws {
        let event = CGEvent(mouseEventSource: nil, mouseType: type, mouseCursorPosition: point, mouseButton: button)
        event?.flags = flags
        if clicks > 1 { event?.setIntegerValueField(.mouseEventClickState, value: clicks) }
        try post(event)
    }

    private static func pause() async { try? await Task.sleep(nanoseconds: 30_000_000) }

    public func perform(_ action: ComputerUseAction) async throws -> [String: Any] {
        let flags = Self.flags(action.modifiers)
        let point = CGPoint(x: action.x ?? 0, y: action.y ?? 0)
        switch action.kind {
        case .move:
            try Self.mouse(.mouseMoved, point, .left, flags: flags)
        case .click:
            try Self.mouse(.leftMouseDown, point, .left, flags: flags); await Self.pause()
            try Self.mouse(.leftMouseUp, point, .left, flags: flags)
        case .doubleClick:
            for clicks in 1...2 {
                try Self.mouse(.leftMouseDown, point, .left, flags: flags, clicks: Int64(clicks))
                try Self.mouse(.leftMouseUp, point, .left, flags: flags, clicks: Int64(clicks))
                await Self.pause()
            }
        case .rightClick:
            try Self.mouse(.rightMouseDown, point, .right, flags: flags); await Self.pause()
            try Self.mouse(.rightMouseUp, point, .right, flags: flags)
        case .drag:
            let end = CGPoint(x: action.toX ?? 0, y: action.toY ?? 0)
            try Self.mouse(.leftMouseDown, point, .left, flags: flags)
            for step in 1...12 {
                let t = CGFloat(step) / 12
                try Self.mouse(.leftMouseDragged, CGPoint(x: point.x + (end.x - point.x) * t, y: point.y + (end.y - point.y) * t), .left, flags: flags)
                await Self.pause()
            }
            try Self.mouse(.leftMouseUp, end, .left, flags: flags)
        case .scroll:
            if action.x != nil, action.y != nil { try Self.mouse(.mouseMoved, point, .left, flags: []) }
            let event = CGEvent(scrollWheelEvent2Source: nil, units: .pixel, wheelCount: 2,
                                wheel1: Int32(action.dy ?? 0), wheel2: Int32(action.dx ?? 0), wheel3: 0)
            event?.flags = flags
            try Self.post(event)
        case .type:
            let units = Array((action.text ?? "").utf16)
            var index = 0
            while index < units.count {
                var chunk = Array(units[index..<min(index + 16, units.count)])
                // Keep surrogate pairs together.
                if let last = chunk.last, UTF16.isLeadSurrogate(last), index + chunk.count < units.count { chunk.append(units[index + chunk.count]) }
                for down in [true, false] {
                    let event = CGEvent(keyboardEventSource: nil, virtualKey: 0, keyDown: down)
                    event?.keyboardSetUnicodeString(stringLength: chunk.count, unicodeString: chunk)
                    try Self.post(event)
                }
                index += chunk.count
                await Self.pause()
            }
        case .key:
            guard let key = action.key, let code = ComputerUseKeys.code(key) else { throw DeviceAPIError(422, "invalid_parameter", "Unknown key.") }
            for down in [true, false] {
                let event = CGEvent(keyboardEventSource: nil, virtualKey: CGKeyCode(code), keyDown: down)
                event?.flags = flags
                try Self.post(event)
            }
        case .press:
            let target = try await element(action)
            let names = Self.actions(target)
            let name = names.contains("AXPress") ? "AXPress" : names.first
            guard let name else { throw DeviceAPIError(409, "no_action", "That element has no Accessibility action. Click its frame instead.") }
            let status = AXUIElementPerformAction(target, name as CFString)
            guard status == .success else { throw DeviceAPIError(409, "action_failed", "macOS refused the action (\(status.rawValue)).") }
            return ["done": true, "performed": name]
        case .focus:
            let target = try await element(action)
            let status = AXUIElementSetAttributeValue(target, "AXFocused" as CFString, kCFBooleanTrue)
            guard status == .success else { throw DeviceAPIError(409, "action_failed", "macOS refused to focus that element (\(status.rawValue)).") }
        case .setValue:
            let target = try await element(action)
            let status = AXUIElementSetAttributeValue(target, "AXValue" as CFString, (action.text ?? "") as CFString)
            guard status == .success else { throw DeviceAPIError(409, "action_failed", "macOS refused to set that value (\(status.rawValue)). Focus it and use type instead.") }
        case .activate:
            let app = try await application(pid: action.pid, bundleID: action.bundleID)
            let activated = await MainActor.run { app.activate() }
            guard activated else { throw DeviceAPIError(409, "action_failed", "macOS did not bring that app to the front.") }
            return ["done": true, "pid": Int(app.processIdentifier)]
        }
        return ["done": true]
    }
}
#endif
