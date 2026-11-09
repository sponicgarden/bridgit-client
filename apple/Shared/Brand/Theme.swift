import SwiftUI
#if os(macOS)
import AppKit
#else
import UIKit
#endif

enum BridgitAppearance: String, CaseIterable {
    case system = "System", light = "Light", dark = "Dark"

    var colorScheme: ColorScheme? {
        switch self {
        case .system: nil
        case .light: .light
        case .dark: .dark
        }
    }
}

/// The APK's colour tokens (app/src/main/res/values/colors.xml and values-night/colors.xml, spec UI-02),
/// shared by iOS and macOS, with identical light and dark values on both platforms.
enum B {
    static let primary = pair(0x1F5BD6, 0xADC6FF)
    static let onPrimary = pair(0xFFFFFF, 0x002B6B)
    static let primaryContainer = pair(0xD6E2FF, 0x1B4AAE)
    static let onPrimaryContainer = pair(0x001849, 0xDCE6FF)
    static let secondary = pair(0x7A5900, 0xFFC15E)
    static let secondaryContainer = pair(0xFFDEA6, 0x5C4200)
    static let surface = pair(0xF8F9FF, 0x0B0F17)
    static let surfaceLow = pair(0xEFF2FA, 0x121825)
    static let surfaceContainer = pair(0xE6EBF5, 0x1A2130)
    static let surfaceHigh = pair(0xD7DEEC, 0x262F42)
    static let surfaceLowest = pair(0xFFFFFF, 0x06090F)
    static let onSurface = pair(0x151B27, 0xE1E6F2)
    static let onSurfaceVariant = pair(0x3C4558, 0xBAC4D8)
    static let outline = pair(0x6B758A, 0x8892A8)
    static let outlineVariant = pair(0xB5BFD2, 0x3D475C)
    static let error = pair(0xBA1A1A, 0xFFB4AB)
    static let errorContainer = pair(0xFFDAD6, 0x93000A)
    static let success = pair(0x1E7A3C, 0x7FD99A)
    static let onSecondaryContainer = pair(0x261900, 0xFFDEA6)
    static let onErrorContainer = pair(0x410002, 0xFFDAD6)
    static let onSuccessContainer = pair(0x00210B, 0x9CF6B4)
    static let successContainer = pair(0xC3EFCD, 0x005226)
    static let beaconGold = Color(hex: 0xFFC15E)
    // design tokens (colors_design.xml): the lavender-to-amber rule and the info-panel gradient.
    static let ruleStart = Color(hex: 0xB9A3FF)
    static let ruleEnd = Color(hex: 0xF59E42)
    static let infoStart = pair(0xF1EBFF, 0x2C2142)
    static let infoEnd = pair(0xFBF4E6, 0x1B2233)
    static let screenGlow = pair(0xEFE8FF, 0x2A1E3E)
    /// The Home scene's own page background (DiagnosticsScene.kt).
    static let sceneBackground = pair(0xF8F9FF, 0x070B14)

    private static let avatarBg: [(Int, Int)] = [(0xE9D5FF, 0x4C1D95), (0xFED7AA, 0x7C2D12), (0xBFDBFE, 0x1E3A8A), (0xFBCFE8, 0x831843),
                                                 (0xD9F99D, 0x365314), (0xA5F3FC, 0x164E63), (0xFDE68A, 0x78350F), (0xE2E8F0, 0x334155)]
    private static let avatarFg: [(Int, Int)] = [(0x3B0764, 0xEDE9FE), (0x431407, 0xFFEDD5), (0x172554, 0xDBEAFE), (0x500724, 0xFCE7F3),
                                                 (0x1A2E05, 0xECFCCB), (0x083344, 0xCFFAFE), (0x451A03, 0xFEF3C7), (0x0F172A, 0xF1F5F9)]

    /// Agent avatar colours, picked from the name like the APK does.
    static func avatar(_ name: String) -> (bg: Color, fg: Color) {
        let i = abs(name.unicodeScalars.reduce(0) { ($0 &* 31) &+ Int($1.value) }) % avatarBg.count
        return (pair(avatarBg[i].0, avatarBg[i].1), pair(avatarFg[i].0, avatarFg[i].1))
    }

    static func pair(_ light: Int, _ dark: Int) -> Color {
        #if os(macOS)
        Color(nsColor: NSColor(name: nil) { appearance in
            let hex = appearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua ? dark : light
            return NSColor(srgbRed: CGFloat((hex >> 16) & 255) / 255, green: CGFloat((hex >> 8) & 255) / 255, blue: CGFloat(hex & 255) / 255, alpha: 1)
        })
        #else
        Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(hex: dark) : UIColor(hex: light) })
        #endif
    }
}

#if !os(macOS)
extension UIColor {
    convenience init(hex: Int, alpha: CGFloat = 1) {
        self.init(red: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255, blue: CGFloat(hex & 0xFF) / 255, alpha: alpha)
    }
}

#endif
extension Color {
    init(hex: Int) { self.init(.sRGB, red: Double((hex >> 16) & 255) / 255, green: Double((hex >> 8) & 255) / 255, blue: Double(hex & 255) / 255, opacity: 1) }
}

/// The short lavender-to-amber accent line under a screen's lead text (design_rule_*).
struct AccentRule: View {
    var body: some View {
        LinearGradient(colors: [B.ruleStart, B.ruleEnd], startPoint: .leading, endPoint: .trailing)
            .frame(width: 56, height: 3).clipShape(Capsule())
    }
}

/// The gradient panel behind purely informational text (design_info_*).
struct InfoPanel<Content: View>: View {
    @ViewBuilder var content: Content
    var body: some View {
        content
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(LinearGradient(colors: [B.infoStart, B.infoEnd], startPoint: .topLeading, endPoint: .bottomTrailing))
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}

/// A screen title in the APK style: large title, accent rule, optional lead text.
struct ScreenHeader: View {
    var title: String
    var lead: String?
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(.largeTitle).foregroundStyle(B.onSurface)
            if let lead { Text(lead).font(.subheadline).foregroundStyle(B.onSurfaceVariant) }
            AccentRule()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// The section label used in the APK's More list ("Device and setup").
struct SectionLabel: View {
    var text: String
    var body: some View {
        Text(text).font(.subheadline.weight(.medium)).foregroundStyle(B.primary)
            .frame(maxWidth: .infinity, alignment: .leading).padding(.top, 18).padding(.bottom, 6)
    }
}

/// A status pill: a dot, a word, never colour alone (brand guide).
struct StatePill: View {
    enum Tone { case good, warn, bad, idle }
    var text: String
    var tone: Tone
    @State private var pulse = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var color: Color {
        switch tone { case .good: B.success; case .warn: B.secondary; case .bad: B.error; case .idle: B.outline }
    }
    var body: some View {
        HStack(spacing: 6) {
            Circle().fill(color).frame(width: 8, height: 8)
                .scaleEffect(tone == .good && pulse ? 1.35 : 1)
                .opacity(tone == .good && pulse ? 0.55 : 1)
            Text(text).font(.caption.weight(.medium)).foregroundStyle(color)
        }
        .padding(.horizontal, 10).padding(.vertical, 4)
        .overlay(Capsule().stroke(color, lineWidth: 1))
        .onAppear {
            guard tone == .good, !reduceMotion else { return }
            withAnimation(.easeInOut(duration: 1.2).repeatForever(autoreverses: true)) { pulse = true }
        }
    }
}

/// Card surface used throughout.
struct Card<Content: View>: View {
    @ViewBuilder var content: Content
    var body: some View {
        content
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(B.surfaceLow)
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(B.outlineVariant.opacity(0.6), lineWidth: 1))
    }
}

/// "3 min ago" style ages, always shown with live or stored data so stale data is visible.
enum Age {
    static func text(ms: Double?) -> String {
        guard let ms, ms > 0 else { return "never" }
        return text(Date(timeIntervalSince1970: ms / 1000))
    }
    static func text(_ d: Date) -> String {
        let s = Date().timeIntervalSince(d)
        if s < 45 { return "just now" }
        let f = RelativeDateTimeFormatter()
        f.unitsStyle = .short
        return f.localizedString(for: d, relativeTo: Date())
    }
}

/// The top bar on every main screen: the app icon, then the Bridgit wordmark.
struct BrandBar: View {
    var compact = false
    /// Measured on wordmark.png: letters' baseline at 74.3% of the image height, tops of b/d at 12.7%.
    static let baseline: CGFloat = 0.743
    static let letterTop: CGFloat = 0.127

    var body: some View {
        let mark: CGFloat = compact ? 36 : 44
        let word: CGFloat = compact ? 32 : 40
        HStack(alignment: .lastTextBaseline, spacing: compact ? 8 : 10) {
            Image("Mark").resizable().scaledToFit()
                .frame(width: mark, height: mark)
                .clipShape(RoundedRectangle(cornerRadius: mark * 0.23, style: .continuous))
                // Centre the icon on the letters (not on the g's tail).
                .alignmentGuide(.lastTextBaseline) { d in d.height / 2 + word * (Self.baseline - Self.letterTop) / 2 }
            BridgitWordmark(height: word)
        }
        .fixedSize()
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Bridgit")
    }
}

/// The existing Bridgit wordmark, without the former device-app suffix.
struct BridgitWordmark: View {
    var height: CGFloat
    var body: some View {
        Image("Wordmark").resizable().scaledToFit().frame(height: height)
            .alignmentGuide(.lastTextBaseline) { d in d.height * BrandBar.baseline }
            .accessibilityLabel("Bridgit")
    }
}

#if !os(macOS)
extension View {
    /// Puts the Bridgit brand bar in the navigation bar of a main screen.
    func brandBar() -> some View {
        toolbar { ToolbarItem(placement: .principal) { BrandBar(compact: true) } }
            .navigationBarTitleDisplayMode(.inline)
    }
}

#endif
