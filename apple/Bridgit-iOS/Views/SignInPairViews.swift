import BridgitCore
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
                    BridgitWordmark(height: 52)
                    Text("Unlock your device.\nUnleash agentic applications.")
                        .font(.title3.weight(.medium)).multilineTextAlignment(.center).foregroundStyle(B.onSurface)
                        .frame(maxWidth: .infinity)
                    AccentRule().padding(.top, 4)
                }
                Button("This device") { model.showThisDevice = true }.font(.callout)
                Text("View your linked devices and their applets. This iPhone or iPad acts as a view satellite, with only the access each device owner grants.")
                    .font(.subheadline).multilineTextAlignment(.center).foregroundStyle(B.onSurfaceVariant)
                VStack(spacing: 12) {
                    Button { Task { await model.signInWithBridgit() } } label: {
                        Label("Sign in with Google", systemImage: "person.crop.circle").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent).controlSize(.large).disabled(model.signingIn)
                    Button { showPair = true } label: {
                        Label("Pair with a device key", systemImage: "qrcode.viewfinder").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered).controlSize(.large)
                }
                if let e = model.error { Text(e).font(.footnote).foregroundStyle(B.error).multilineTextAlignment(.center) }
                VStack(spacing: 22) {
                    Button("Paste a sign-in or pairing link") { showPaste = true }.font(.footnote)
                    Button("Preview with sample data") { model.enterSample() }.font(.footnote).foregroundStyle(B.onSurfaceVariant)
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
        .coveringBuildStampSurface(showPair)
        .sheet(isPresented: $showPair) { PairView().buildStampSurface() }
        .sheet(isPresented: $showPaste) { PasteLinkView() }
    }
}

/// Pairing with a phone's key: scan the QR code the phone shows (More › Linked devices › Pair a device), or paste the
/// link / the relay setup text the phone copied.
struct PairView: View {
    @EnvironmentObject var model: AppModel
    var body: some View {
        DevicePairingView(save: { try model.saveConfiguredPairing($0) },
                          scanner: { code in AnyView(QRScanner(onCode: code)) })
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
                    Text("Use a Bridgit pairing link or the connector setup text shared by the device owner.")
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
            guard AVCaptureDevice.default(for: .video) != nil else {
                message("No camera here. Paste the link or choose a QR image instead."); return
            }
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
