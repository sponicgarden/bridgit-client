import SwiftUI

struct CardLibraryRow: View {
    let card: AppletLists.Card
    @Environment(\.dynamicTypeSize) private var textSize

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(card.title).font(.headline).foregroundStyle(B.onSurface)
                Text(card.summary).font(.subheadline).foregroundStyle(B.onSurfaceVariant)
                    .lineLimit(textSize.isAccessibilitySize ? nil : 1)
            }
            Spacer(minLength: 0)
            Image(systemName: "chevron.right")
                .font(.caption.weight(.semibold)).foregroundStyle(B.onSurfaceVariant)
                .accessibilityHidden(true)
        }
        .multilineTextAlignment(.leading)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, minHeight: 64, alignment: .leading)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}
