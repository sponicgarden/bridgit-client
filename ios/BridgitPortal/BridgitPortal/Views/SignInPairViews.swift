import AVFoundation
import SwiftUI

/// First screen: the two ways the phone's owner grants this iPhone or iPad access.
struct SignInView: View {
    @EnvironmentObject var model: AppModel
    @State private var showPair = false
    @State private var showPaste = false
    @State private var glow = false

    var body: some View {
        ScrollView {
            VStack(spacing: 22) {
                ZStack {
                    Circle().fill(B.screenGlow).frame(width: 190, height: 190).blur(radius: 40)
                        .scaleEffect(glow ? 1.08 : 0.92).opacity(glow ? 1 : 0.7)
                    Image("Mark").resizable().scaledToFit().frame(width: 96, height: 96)
                        .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
                }
                .frame(height: 130)
                // The wordmark, its tagline, then the accent line.
                VStack(spacing: 10) {
                    PortalWordmark(height: 52)
                    Text("Distributed sensorial applications,\nbuilt by your favorite agent.")
                        .frostText(.section, weight: .medium).multilineTextAlignment(.center).foregroundStyle(B.onSurface)
                        .frame(maxWidth: .infinity)
                    AccentRule().padding(.top, 4)
                }
                Text("See your Bridgit Android device screens from iOS, remotely. It's the next best thing to having Bridgit.")
                    .frostText(.supporting).multilineTextAlignment(.center).foregroundStyle(B.onSurfaceVariant)
                VStack(spacing: 12) {
                    Button { Task { await model.signInWithBridgit() } } label: {
                        Label("Sign in with Google", systemImage: "person.crop.circle").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent).controlSize(.large).disabled(model.signingIn)
                    Button { showPair = true } label: {
                        Label("Pair with a phone's key", systemImage: "qrcode.viewfinder").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered).controlSize(.large)
                }
                if let e = model.error { Text(e).frostText(.supporting).foregroundStyle(B.error).multilineTextAlignment(.center) }
                VStack(spacing: 22) {
                    Button("Paste a sign-in or pairing link") { showPaste = true }.frostText(.supporting)
                    Button("Preview with sample data") { model.enterSample() }.frostText(.supporting).foregroundStyle(B.onSurfaceVariant)
                }
                .padding(.top, 8)
            }
            .padding(24)
            .frame(maxWidth: 520)
            .frame(maxWidth: .infinity)
        }
        .background(B.surface.ignoresSafeArea())
        .onAppear {
            guard !UIAccessibility.isReduceMotionEnabled else { return }
            withAnimation(.easeInOut(duration: 2.4).repeatForever(autoreverses: true)) { glow = true }
        }
        .sheet(isPresented: $showPair) { PairView() }
        .sheet(isPresented: $showPaste) { PasteLinkView() }
    }
}

/// Pairing with a phone's key: scan the QR code the phone shows (More › Linked devices › Pair a device), or paste the
/// link / the relay setup text the phone copied.
struct PairView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var scanned: String?
    @State private var showPaste = false
    @State private var error: String?

    var body: some View {
        NavigationStack {
            VStack(spacing: 16) {
                Text("On the phone, open More › Linked devices › Pair a device and tap Show a pairing code. Then scan the code.")
                    .multilineTextAlignment(.center).foregroundStyle(B.onSurfaceVariant)
                QRScanner { code in
                    guard scanned == nil else { return }
                    scanned = code
                    if let p = Pairing.parse(code) { model.addPairing(p); dismiss() }
                    else { error = "That code isn't a Bridgit pairing code."; DispatchQueue.main.asyncAfter(deadline: .now() + 2) { scanned = nil } }
                }
                .frame(maxWidth: 420, maxHeight: 420)
                .aspectRatio(1, contentMode: .fit)
                .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(B.primary, lineWidth: 2))
                if let error { Text(error).foregroundStyle(B.error).frostText(.supporting) }
                Button("Paste a link or setup text instead") { showPaste = true }
                Spacer()
            }
            .padding(20)
            .navigationTitle("Pair with a phone")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
            .sheet(isPresented: $showPaste) { PasteLinkView(onDone: { dismiss() }) }
        }
    }
}

struct PasteLinkView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    var onDone: () -> Void = {}
    @State private var text = ""
    @State private var error: String?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextEditor(text: $text).frame(minHeight: 140).font(.footnote.monospaced())
                        .autocorrectionDisabled().textInputAutocapitalization(.never)
                    Button("Paste from clipboard") { text = UIPasteboard.general.string ?? "" }
                } footer: {
                    Text("A bridgitportal:// link, or the setup text from the phone's More › Relay › Add relay control agent (connector).")
                }
                if let error { Text(error).foregroundStyle(B.error) }
                Button("Use it") {
                    let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
                    if let url = URL(string: t), let r = SignIn.parse(url) {
                        Task { await model.adopt(session: r.session, email: r.email) }; dismiss(); onDone()
                    } else if let p = Pairing.parse(t) {
                        model.addPairing(p); dismiss(); onDone()
                    } else {
                        error = "That isn't a Bridgit sign-in link, pairing link or connector setup."
                    }
                }.disabled(text.isEmpty)
            }
            .navigationTitle("Paste")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
        }
    }
}

/// A plain AVFoundation QR scanner. Without a camera (Simulator) it says so instead.
struct QRScanner: UIViewControllerRepresentable {
    var onCode: (String) -> Void

    func makeUIViewController(context: Context) -> ScannerController {
        let c = ScannerController()
        c.onCode = onCode
        return c
    }
    func updateUIViewController(_ c: ScannerController, context: Context) { c.onCode = onCode }

    final class ScannerController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
        var onCode: ((String) -> Void)?
        private let session = AVCaptureSession()
        private var preview: AVCaptureVideoPreviewLayer?

        override func viewDidLoad() {
            super.viewDidLoad()
            view.backgroundColor = .black
            AVCaptureDevice.requestAccess(for: .video) { ok in
                DispatchQueue.main.async { ok ? self.start() : self.message("Camera access is off. Allow it in Settings, or paste the link instead.") }
            }
        }

        private func start() {
            guard let cam = AVCaptureDevice.default(for: .video), let input = try? AVCaptureDeviceInput(device: cam), session.canAddInput(input) else {
                message("No camera here. Paste the link instead."); return
            }
            session.addInput(input)
            let out = AVCaptureMetadataOutput()
            guard session.canAddOutput(out) else { return }
            session.addOutput(out)
            out.setMetadataObjectsDelegate(self, queue: .main)
            out.metadataObjectTypes = [.qr]
            let p = AVCaptureVideoPreviewLayer(session: session)
            p.videoGravity = .resizeAspectFill
            p.frame = view.bounds
            view.layer.addSublayer(p)
            preview = p
            DispatchQueue.global(qos: .userInitiated).async { self.session.startRunning() }
        }

        override func viewDidLayoutSubviews() {
            super.viewDidLayoutSubviews()
            preview?.frame = view.bounds
        }

        override func viewWillDisappear(_ animated: Bool) {
            super.viewWillDisappear(animated)
            if session.isRunning { DispatchQueue.global().async { self.session.stopRunning() } }
        }

        private func message(_ text: String) {
            let l = UILabel()
            l.text = text
            l.textColor = .white
            l.numberOfLines = 0
            l.textAlignment = .center
            l.translatesAutoresizingMaskIntoConstraints = false
            view.addSubview(l)
            NSLayoutConstraint.activate([l.centerXAnchor.constraint(equalTo: view.centerXAnchor), l.centerYAnchor.constraint(equalTo: view.centerYAnchor),
                                         l.widthAnchor.constraint(equalTo: view.widthAnchor, multiplier: 0.8)])
        }

        func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput objects: [AVMetadataObject], from connection: AVCaptureConnection) {
            if let s = (objects.first as? AVMetadataMachineReadableCodeObject)?.stringValue { onCode?(s) }
        }
    }
}
