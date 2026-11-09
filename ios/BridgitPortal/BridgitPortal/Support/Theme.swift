import SwiftUI
import UIKit

/// The APK's colour tokens (app/src/main/res/values/colors.xml and values-night/colors.xml, spec UI-02),
/// with the same light and dark values, so the portal looks like the phone it mirrors.
enum B {
    // Shared Lilac frost roles. Keep semantic status and avatar identity distinct.
    static let primary = pair(0x6D587E, 0xC4AED8)
    static let onPrimary = pair(0xFFFFFF, 0x2E2339)
    static let primaryContainer = pair(0xE9E2F0, 0x463650)
    static let onPrimaryContainer = pair(0x332C3D, 0xF0EBF5)
    static let secondary = pair(0x83501E, 0xF0C58E)
    static let onSecondary = pair(0xFFFFFF, 0x403326)
    static let secondaryContainer = pair(0xFBEFDF, 0x403326)
    static let onSecondaryContainer = pair(0x5E3715, 0xFFE1BA)
    static let surface = pair(0xFDFCFE, 0x24212A)
    static let surfaceLow = pair(0xF7F4FA, 0x292530)
    static let surfaceContainer = pair(0xF1EEF6, 0x302B38)
    static let surfaceHigh = pair(0xEAE5F0, 0x393240)
    static let surfaceHighest = pair(0xE2DCE9, 0x433B4B)
    static let surfaceLowest = pair(0xFFFFFF, 0x201D25)
    static let onSurface = pair(0x332C3D, 0xF0EBF5)
    static let onSurfaceVariant = pair(0x675D71, 0xBDB3C9)
    static let outline = pair(0x897E94, 0xA89BB5)
    static let outlineVariant = pair(0xE2DCE9, 0x484051)
    static let error = pair(0xA52D48, 0xF1A5B7)
    static let onError = pair(0xFFFFFF, 0x452933)
    static let errorContainer = pair(0xF9E9ED, 0x452933)
    static let onErrorContainer = pair(0x5F1631, 0xFFD7DF)
    static let warning = pair(0x83501E, 0xF0C58E)
    static let success = pair(0x16834A, 0x8DD9AD)
    static let successContainer = pair(0xE6F3EB, 0x203B2D)
    static let onSuccessContainer = pair(0x164D31, 0xB7ECCB)
    static let beaconGold = Color(hex: 0xFFC15E)
    static let ruleStart = primary
    static let ruleEnd = primary
    static let infoStart = surfaceContainer
    static let infoEnd = surfaceContainer
    static let screenGlow = surfaceLow
    static let sceneBackground = surface

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
        Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(hex: dark) : UIColor(hex: light) })
    }
}

extension UIColor {
    convenience init(hex: Int, alpha: CGFloat = 1) {
        self.init(red: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255, blue: CGFloat(hex & 0xFF) / 255, alpha: alpha)
    }
}

extension Color {
    init(hex: Int) { self.init(UIColor(hex: hex)) }
}

/// The short lavender-to-amber accent line under a screen's lead text (design_rule_*).
struct AccentRule: View {
    var body: some View {
        LinearGradient(colors: [B.ruleStart, B.ruleEnd], startPoint: .leading, endPoint: .trailing)
            .frame(width: 48, height: 3).clipShape(Capsule())
    }
}

/// The gradient panel behind purely informational text (design_info_*).
struct InfoPanel<Content: View>: View {
    @ViewBuilder var content: Content
    var body: some View {
        content
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(B.surfaceContainer)
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }
}

/// A screen title in the APK style: large title, accent rule, optional lead text.
struct ScreenHeader: View {
    var title: String
    var lead: String?
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).frostText(.title, weight: .semibold).foregroundStyle(B.onSurface)
            if let lead { Text(lead).frostText(.supporting).foregroundStyle(B.onSurfaceVariant) }
            AccentRule()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// The section label used in the APK's More list ("Device and setup").
struct SectionLabel: View {
    var text: String
    var body: some View {
        Text(text).frostText(.supporting, weight: .medium).foregroundStyle(B.primary)
            .frame(maxWidth: .infinity, alignment: .leading).padding(.top, 18).padding(.bottom, 6)
    }
}

/// A status pill: a dot, a word, never colour alone (brand guide).
struct StatePill: View {
    enum Tone { case good, warn, bad, idle }
    var text: String
    var tone: Tone
    @State private var pulse = false

    var color: Color {
        switch tone { case .good: B.success; case .warn: B.secondary; case .bad: B.error; case .idle: B.outline }
    }
    var body: some View {
        HStack(spacing: 6) {
            Circle().fill(color).frame(width: 8, height: 8)
                .scaleEffect(tone == .good && pulse ? 1.35 : 1)
                .opacity(tone == .good && pulse ? 0.55 : 1)
            Text(text).frostText(.supporting, weight: .medium).foregroundStyle(color)
        }
        .padding(.horizontal, 10).padding(.vertical, 4)
        .overlay(Capsule().stroke(color, lineWidth: 1))
        .onAppear {
            guard tone == .good, !UIAccessibility.isReduceMotionEnabled else { return }
            withAnimation(.easeInOut(duration: 1.2).repeatForever(autoreverses: true)) { pulse = true }
        }
    }
}

/// Card surface used throughout.
struct Card<Content: View>: View {
    @ViewBuilder var content: Content
    var body: some View {
        content
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(B.surfaceLow)
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).stroke(B.outlineVariant, lineWidth: 1))
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

/// The top bar on every main screen: the app icon, then the "bridgit" wordmark. The brand is Bridgit, never
/// "Bridgit Portal" (owner, A0160, 9 Nov 2026).
struct BrandBar: View {
    var compact = false
    /// Measured on wordmark.png: letters' baseline at 74.3% of the image height, tops of b/d at 12.7%.
    static let baseline: CGFloat = 0.743
    static let letterTop: CGFloat = 0.127
    /// The brand's accessible name.
    static let label = "Bridgit"

    var body: some View {
        let mark: CGFloat = compact ? 36 : 44
        let word: CGFloat = compact ? 32 : 40
        HStack(alignment: .lastTextBaseline, spacing: compact ? 8 : 10) {
            Image("Mark").resizable().scaledToFit()
                .frame(width: mark, height: mark)
                .clipShape(RoundedRectangle(cornerRadius: mark * 0.23, style: .continuous))
                // Centre the icon on the letters (not on the g's tail).
                .alignmentGuide(.lastTextBaseline) { d in d.height / 2 + word * (Self.baseline - Self.letterTop) / 2 }
            PortalWordmark(height: word)
        }
        .fixedSize()
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Self.label)
    }
}

/// The "bridgit" wordmark image, on the text baseline.
struct PortalWordmark: View {
    var height: CGFloat
    var body: some View {
        HStack(alignment: .lastTextBaseline, spacing: 0) {
            Image("Wordmark").resizable().scaledToFit().frame(height: height)
                .alignmentGuide(.lastTextBaseline) { d in d.height * BrandBar.baseline }
        }
        .fixedSize()
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(BrandBar.label)
    }
}

extension View {
    /// Puts the Bridgit brand bar in the navigation bar of a main screen.
    func brandBar() -> some View {
        toolbar { ToolbarItem(placement: .principal) { BrandBar(compact: true) } }
            .navigationBarTitleDisplayMode(.inline)
    }
}

/// Shared UI typography scales with the user's accessibility text size.
enum FrostTextRole {
    case body, supporting, label, section, title
    var size: CGFloat { switch self { case .body: 15; case .supporting, .label: 14; case .section: 18; case .title: 24 } }
    var relativeTo: Font.TextStyle { switch self { case .title: .title2; case .section: .headline; default: .body } }
}
private struct FrostTextModifier: ViewModifier {
    @ScaledMetric private var size: CGFloat
    let weight: Font.Weight
    init(_ role: FrostTextRole, weight: Font.Weight) {
        _size = ScaledMetric(wrappedValue: role.size, relativeTo: role.relativeTo)
        self.weight = weight
    }
    func body(content: Content) -> some View { content.font(.system(size: size, weight: weight)) }
}
extension View {
    func frostText(_ role: FrostTextRole, weight: Font.Weight = .regular) -> some View {
        modifier(FrostTextModifier(role, weight: weight))
    }
}
