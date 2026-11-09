import SwiftUI

/// The version and build every Bridgit screen shows, so any screenshot names the build it came from
/// (A0156, A0158, A0159). Self-contained: the iPhone/iPad app and the Bridgit Portal app both compile it.
enum BuildStampText {
    /// Line one is "v" + CFBundleShortVersionString; line two is CFBundleVersion, with a
    /// yymmddHHMM stamp shortened to its eight-digit mmddHHMM form (2611071810 → 11071810).
    static func lines(version: String, build: String) -> (version: String, build: String) {
        let shortBuild = build.count == 10 && build.hasPrefix("26") && build.allSatisfy(\.isNumber)
            ? String(build.dropFirst(2)) : build
        return ("v" + version, shortBuild)
    }

    /// One line for the corner fallback: "v0.1.0 · 11071810".
    static var compact: String? {
        guard let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String,
              let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String else { return nil }
        let lines = lines(version: version, build: build)
        return lines.version + " · " + lines.build
    }
}

#if os(iOS)
import UIKit

/// Every inline BuildStamp carries one of these invisible probes. The corner fallback below checks
/// whether the screen in front already shows a stamp; if not, it draws one.
final class BuildStampProbeView: UIView {
    static let all = NSHashTable<BuildStampProbeView>.weakObjects()

    override init(frame: CGRect) {
        super.init(frame: frame)
        isUserInteractionEnabled = false
        isAccessibilityElement = false
        backgroundColor = .clear
        Self.all.add(self)
    }

    required init?(coder: NSCoder) { nil }
}

struct BuildStampProbe: UIViewRepresentable {
    func makeUIView(context: Context) -> BuildStampProbeView { BuildStampProbeView() }
    func updateUIView(_ uiView: BuildStampProbeView, context: Context) {}
}

extension View {
    /// Apply once to the root view of each window. Any screen, sheet, full-screen cover, alert or
    /// pushed page that does not place its own stamp then gets one in its top-trailing corner.
    func buildStampFallback() -> some View {
        background(BuildStampFallbackInstaller().frame(width: 0, height: 0).accessibilityHidden(true))
    }
}

private struct BuildStampFallbackInstaller: UIViewRepresentable {
    func makeUIView(context: Context) -> InstallerView { InstallerView() }
    func updateUIView(_ uiView: InstallerView, context: Context) {}

    final class InstallerView: UIView {
        override init(frame: CGRect) {
            super.init(frame: frame)
            isUserInteractionEnabled = false
            isAccessibilityElement = false
        }

        required init?(coder: NSCoder) { nil }

        override func didMoveToWindow() {
            super.didMoveToWindow()
            if let window { BuildStampFallback.attach(to: window) }
        }
    }
}

/// A see-through window above the app's window that holds one small label. It never takes touches,
/// focus or VoiceOver attention, and it hides itself whenever the screen in front has its own stamp.
@MainActor
final class BuildStampFallback {
    private static var attached: [ObjectIdentifier: BuildStampFallback] = [:]

    static func attach(to appWindow: UIWindow) {
        guard !(appWindow is PassthroughWindow), let scene = appWindow.windowScene,
              let text = BuildStampText.compact else { return }
        let key = ObjectIdentifier(appWindow)
        if let existing = attached[key], existing.appWindow === appWindow { return }
        attached[key]?.teardown()
        attached[key] = BuildStampFallback(appWindow: appWindow, scene: scene, text: text)
    }

    private final class PassthroughWindow: UIWindow {
        override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? { nil }
    }

    private final class Ticker: NSObject {
        weak var owner: BuildStampFallback?
        @MainActor @objc func tick() { owner?.tick() }
    }

    private weak var appWindow: UIWindow?
    private let key: ObjectIdentifier
    private let window: UIWindow
    private let label = UILabel()
    private var link: CADisplayLink?

    private init(appWindow: UIWindow, scene: UIWindowScene, text: String) {
        self.appWindow = appWindow
        key = ObjectIdentifier(appWindow)
        window = PassthroughWindow(windowScene: scene)
        window.frame = appWindow.frame
        window.windowLevel = UIWindow.Level(rawValue: UIWindow.Level.alert.rawValue + 1)
        window.backgroundColor = .clear
        window.isUserInteractionEnabled = false
        window.accessibilityElementsHidden = true

        label.text = text
        label.font = .monospacedDigitSystemFont(ofSize: 9, weight: .regular)
        label.textColor = .secondaryLabel
        label.textAlignment = .center
        label.backgroundColor = UIColor.systemBackground.withAlphaComponent(0.6)
        label.layer.cornerRadius = 3
        label.clipsToBounds = true
        label.isAccessibilityElement = false
        label.accessibilityIdentifier = "fixture-build-stamp-fallback"
        label.isHidden = true
        window.addSubview(label)
        window.isHidden = false

        let ticker = Ticker()
        ticker.owner = self
        let link = CADisplayLink(target: ticker, selector: #selector(Ticker.tick))
        link.preferredFramesPerSecond = 10
        link.add(to: .main, forMode: .common)
        self.link = link
    }

    private func teardown() {
        link?.invalidate()
        link = nil
        window.isHidden = true
        if Self.attached[key] === self { Self.attached[key] = nil }
    }

    fileprivate func tick() {
        guard let app = appWindow, app.windowScene != nil else { teardown(); return }
        if window.frame != app.frame { window.frame = app.frame }
        window.overrideUserInterfaceStyle = app.traitCollection.userInterfaceStyle

        let surface = Self.frontController(in: app)
        let surfaceView = surface?.view ?? app
        let alreadyStamped = BuildStampProbeView.all.allObjects.contains { probe in
            probe.window === app && probe.isDescendant(of: surfaceView) && Self.isShown(probe, in: app)
        }
        label.isHidden = alreadyStamped || app.isHidden
        if label.isHidden { return }

        var area = app.bounds
        var top = app.safeAreaInsets.top
        var right = app.safeAreaInsets.right
        if surfaceView.window === app {
            let frame = surfaceView.convert(surfaceView.bounds, to: app)
            if frame.width > 0 && frame.height > 0 {
                area = frame
                top = surfaceView.safeAreaInsets.top
                right = surfaceView.safeAreaInsets.right
            }
        }
        let size = label.sizeThatFits(CGSize(width: 400, height: 40))
        let width = ceil(size.width) + 6, height = ceil(size.height) + 2
        label.frame = CGRect(x: area.maxX - right - 12 - width, y: area.minY + top + 1, width: width, height: height)
    }

    /// The screen in front: the last presented controller that is not on its way out.
    private static func frontController(in window: UIWindow) -> UIViewController? {
        var front = window.rootViewController
        while let next = front?.presentedViewController, !next.isBeingDismissed { front = next }
        return front
    }

    private static func isShown(_ probe: UIView, in window: UIWindow) -> Bool {
        var view: UIView? = probe
        while let current = view {
            if current.isHidden || current.alpha < 0.01 { return false }
            view = current.superview
        }
        let frame = probe.convert(probe.bounds, to: window)
        return window.bounds.insetBy(dx: -1, dy: -1).contains(CGPoint(x: frame.midX, y: frame.midY))
    }
}
#endif
