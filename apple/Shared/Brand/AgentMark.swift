import SwiftUI

/// Resolve the existing official mark from model, then agent/harness name.
/// Server data that does not identify a known brand gets a neutral symbol.
struct AgentMark: View {
    var name: String
    var model: String? = nil
    private var mark: String? { Self.resolve(model) ?? Self.resolve(name) }
    private static func resolve(_ value: String?) -> String? {
        guard let text = value?.lowercased() else { return nil }
        if text.contains("claude") || text.contains("anthropic") { return "claude" }
        if ["openai", "chatgpt", "codex", "gpt-", "o3", "o4"].contains(where: text.contains) { return "openai" }
        if text.contains("gemini") || text.contains("google") { return "gemini" }
        if text.contains("grok") || text.contains("xai") { return "grok" }
        if text.contains("cursor") { return "cursor" }
        return nil
    }
    var body: some View {
        let colors = B.avatar(name)
        let tile = RoundedRectangle(cornerRadius: 9)
        Group {
            if let mark { Image("agent_brand_" + mark).resizable().scaledToFit().padding(8) }
            else { Image(systemName: "person.2.fill").font(.title2) }
        }
        .foregroundStyle(mark == nil ? B.onSurfaceVariant : colors.fg).frame(width: 40, height: 40)
        .background(mark == nil ? B.surfaceHigh : colors.bg, in: tile)
        .overlay { tile.strokeBorder(B.outlineVariant, lineWidth: 1) }
        .accessibilityHidden(true)
    }
}
