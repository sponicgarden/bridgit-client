import SwiftUI
#if os(macOS)
import AppKit
#endif

// A0131: on the Mac every dialog is its own window that the owner can move and resize, and whose
// content scrolls when it is taller than the window. iPhone and iPad keep system sheets.
// A dialog that must stay fixed has to opt out deliberately with a plain `.sheet`.

/// Closes the dialog this view is the root of: its Mac dialog window, or the system sheet elsewhere.
/// Views pushed inside a dialog's NavigationStack keep `@Environment(\.dismiss)`, which goes back.
@propertyWrapper struct DialogDismiss: DynamicProperty {
    @Environment(\.dismiss) private var system
    @Environment(\.bridgitDialogClose) private var close
    var wrappedValue: DialogDismissAction { DialogDismissAction(close: close, system: system) }
}

struct DialogDismissAction {
    fileprivate let close: (() -> Void)?
    fileprivate let system: DismissAction
    func callAsFunction() { if let close { close() } else { system() } }
}

private struct DialogCloseKey: EnvironmentKey { static let defaultValue: (() -> Void)? = nil }

extension EnvironmentValues {
    var bridgitDialogClose: (() -> Void)? {
        get { self[DialogCloseKey.self] }
        set { self[DialogCloseKey.self] = newValue }
    }
}

extension View {
    func bridgitDialog<Dialog: View>(isPresented: Binding<Bool>, onDismiss: (() -> Void)? = nil,
                                     @ViewBuilder content: @escaping () -> Dialog) -> some View {
        #if os(macOS)
        background(DialogPresenter(dialog: isPresented.wrappedValue ? AnyView(content()) : nil, identity: nil,
                                   close: { isPresented.wrappedValue = false }, onDismiss: onDismiss))
        #else
        sheet(isPresented: isPresented, onDismiss: onDismiss, content: content)
        #endif
    }

    func bridgitDialog<Item: Identifiable, Dialog: View>(item: Binding<Item?>, onDismiss: (() -> Void)? = nil,
                                                         @ViewBuilder content: @escaping (Item) -> Dialog) -> some View {
        #if os(macOS)
        background(DialogPresenter(dialog: item.wrappedValue.map { AnyView(content($0)) },
                                   identity: item.wrappedValue.map { AnyHashable($0.id) },
                                   close: { item.wrappedValue = nil }, onDismiss: onDismiss))
        #else
        sheet(item: item, onDismiss: onDismiss, content: content)
        #endif
    }
}

#if os(macOS)
/// The window's content: as wide as the window, at least as tall, and scrolled when taller.
private struct DialogFrame: View {
    var content: AnyView
    var body: some View {
        GeometryReader { proxy in
            ScrollView {
                content.frame(maxWidth: .infinity, minHeight: proxy.size.height, alignment: .top)
            }
        }
        .background(B.surface)
    }
}

/// Every update re-renders the open dialog with the presenter's current environment, like a sheet.
private struct DialogPresenter: NSViewRepresentable {
    var dialog: AnyView?
    var identity: AnyHashable?
    var close: () -> Void
    var onDismiss: (() -> Void)?

    func makeCoordinator() -> DialogWindowController { DialogWindowController() }
    func makeNSView(context: Context) -> NSView { NSView() }

    func updateNSView(_ view: NSView, context: Context) {
        let controller = context.coordinator
        controller.close = close
        controller.onDismiss = onDismiss
        guard let dialog else { controller.dismiss(); return }
        // The presenter's environment carries its models; this dialog's own close replaces any outer one.
        let root = AnyView(dialog.id(identity).environment(\.bridgitDialogClose, close).environment(\.self, context.environment))
        let parent = view.window
        if parent == nil && !controller.isOpen {
            // Not in a window yet: present once SwiftUI has placed the presenter, or centred after a moment.
            controller.pending = root
            Self.presentWhenPlaced(view, controller: controller, identity: identity, attempts: 10)
            return
        }
        controller.pending = nil
        controller.present(root, identity: identity, parent: parent)
    }

    private static func presentWhenPlaced(_ view: NSView, controller: DialogWindowController, identity: AnyHashable?, attempts: Int) {
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) {
            guard let root = controller.pending, !controller.isOpen, controller.close != nil else { return }
            if view.window == nil && attempts > 0 {
                presentWhenPlaced(view, controller: controller, identity: identity, attempts: attempts - 1)
                return
            }
            controller.pending = nil
            controller.present(root, identity: identity, parent: view.window)
        }
    }

    static func dismantleNSView(_ view: NSView, coordinator: DialogWindowController) {
        let close = coordinator.close
        coordinator.close = nil
        if coordinator.dismiss() { DispatchQueue.main.async { close?() } }
    }
}

/// Escape closes a dialog window, as it closes a sheet.
private final class DialogWindow: NSWindow {
    override func cancelOperation(_ sender: Any?) { performClose(sender) }
}

@MainActor final class DialogWindowController: NSObject, NSWindowDelegate {
    var close: (() -> Void)?
    var onDismiss: (() -> Void)?
    /// A dialog waiting for its presenter to be placed in a window.
    var pending: AnyView?
    private var window: NSWindow?
    private var hosting: NSHostingController<AnyView>?
    private var closingFromModel = false
    var isOpen: Bool { window != nil }

    /// The size each time a dialog opens: its content's own size, within most of the screen.
    nonisolated static func openingSize(content: NSSize, screen: NSRect) -> NSSize {
        let limit = NSSize(width: max(360, screen.width * 0.9), height: max(240, screen.height * 0.85))
        func clamp(_ value: CGFloat, _ low: CGFloat, _ high: CGFloat) -> CGFloat { value.isFinite ? min(max(value, low), high) : high }
        return NSSize(width: clamp(content.width, 360, limit.width), height: clamp(content.height, 240, limit.height))
    }

    func present(_ root: AnyView, identity: AnyHashable?, parent: NSWindow?) {
        if let hosting, window != nil {
            hosting.rootView = AnyView(DialogFrame(content: root))
            return
        }
        let visible = (parent?.screen ?? NSScreen.main)?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1280, height: 800)
        // Measured before it is in a window, so the dialog's tasks start only once.
        let fitted = NSHostingController(rootView: root)
            .sizeThatFits(in: NSSize(width: min(720, visible.width * 0.9), height: min(640, visible.height * 0.85)))
        let size = Self.openingSize(content: fitted, screen: visible)
        let hosting = NSHostingController(rootView: AnyView(DialogFrame(content: root)))
        hosting.sizingOptions = []
        let window = DialogWindow(contentRect: NSRect(origin: .zero, size: size),
                                  styleMask: [.titled, .closable, .resizable], backing: .buffered, defer: false)
        window.contentViewController = hosting
        window.setContentSize(size)
        window.contentMinSize = NSSize(width: 320, height: 200)
        window.titleVisibility = .hidden
        window.isReleasedWhenClosed = false
        window.tabbingMode = .disallowed
        window.appearance = parent?.effectiveAppearance
        window.delegate = self
        if let parent {
            let frame = window.frame
            var origin = NSPoint(x: parent.frame.midX - frame.width / 2, y: parent.frame.maxY - frame.height - 60)
            origin.x = min(max(origin.x, visible.minX), visible.maxX - frame.width)
            origin.y = min(max(origin.y, visible.minY), visible.maxY - frame.height)
            window.setFrameOrigin(origin)
            // Stays above Bridgit's window and follows it, but moves on its own when dragged.
            parent.addChildWindow(window, ordered: .above)
        } else {
            window.center()
        }
        self.hosting = hosting
        self.window = window
        window.makeKeyAndOrderFront(nil)
    }

    /// Closes the window because the model no longer presents it. Returns whether one was open.
    @discardableResult func dismiss() -> Bool {
        pending = nil
        guard let window else { return false }
        closingFromModel = true
        window.close()
        closingFromModel = false
        return true
    }

    func windowWillClose(_ notification: Notification) {
        guard let window = notification.object as? NSWindow, window === self.window else { return }
        window.parent?.removeChildWindow(window)
        window.delegate = nil
        self.window = nil
        hosting = nil
        let close = closingFromModel ? nil : close
        let onDismiss = onDismiss
        // Never during a SwiftUI update: the window may be closing inside one.
        DispatchQueue.main.async {
            close?()
            onDismiss?()
        }
    }
}
#endif
