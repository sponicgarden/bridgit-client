import BridgitCore
import AVFoundation
import SwiftUI

/// The Camera tab (owner, 5 Nov 2026), like the APK's: an ordinary camera, rear or front. Each photo goes to the
/// phone (POST /v1/portal/photo), where the applets that check photos (Kitchen cleanliness, say) check it like
/// one of their own, so walking round the kitchen taking photos feeds the kitchen applet.
struct CameraView: View {
    var live: PhoneLive?
    /// Whether the Camera tab is the one showing: the iPhone's TabView builds hidden tabs too, and the camera
    /// (and its permission prompt) should only run while the tab is in front.
    var active: Bool
    var body: some View {
        if let live {
            // Built only while in front: even setting up the capture objects makes iOS ask for the camera.
            if active { CameraScreen(live: live).id(live.device.id) } else { Color.black.ignoresSafeArea() }
        } else { EmptyPhone(title: "Camera") }
    }
}

private struct CameraScreen: View {
    @ObservedObject var live: PhoneLive
    @Environment(\.scenePhase) private var phase
    @StateObject private var camera = PhotoCamera()
    @State private var targets: [PhotoTarget] = []
    /// Empty = every applet that checks photos (also ones installed later).
    @State private var chosen: [String] = []
    @State private var targetsLoaded = false
    @State private var note: String?
    @State private var result: String?
    @State private var sent = 0
    @State private var sending = 0
    @State private var flash = false

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            CameraPreview(camera: camera).ignoresSafeArea(edges: .horizontal)
            Color.white.opacity(flash ? 0.8 : 0).allowsHitTesting(false)
            VStack(spacing: 10) {
                targetMenu
                if let text = note ?? camera.problem { bubble(text) }
                Spacer()
                if let result { bubble(result).transition(.opacity) }
                CameraControls(camera: camera, canShoot: canShoot, sent: sent, sending: sending, shoot: shoot)
            }
            .padding(.horizontal, 16).padding(.top, 10).padding(.bottom, 16)
        }
        .toolbar(.hidden, for: .navigationBar)
        .onAppear { camera.start() }
        .onDisappear { camera.stop() }
        .onChange(of: phase) { _, p in p == .active ? camera.start() : camera.stop() }
        .task(id: live.device.id) { await loadTargets() }
    }

    @ViewBuilder private var targetMenu: some View {
        if !live.canSendPhotos {
            bubble("Pair on the phone to send photos")
        } else if targetsLoaded {
            PhotoTargetPicker(deviceID: live.device.id, targets: targets, chosen: $chosen)
                .font(.subheadline.weight(.medium)).foregroundStyle(.white).tint(.white)
                .padding(8).background(.black.opacity(0.65), in: RoundedRectangle(cornerRadius: 16))
        } else if note != nil {
            Button("Retry") { Task { await loadTargets() } }
        } else {
            ProgressView().tint(.white)
        }
    }

    private var canShoot: Bool { camera.ready && live.canSendPhotos && !targets.isEmpty }

    private func bubble(_ text: String) -> some View {
        Text(text).font(.subheadline).multilineTextAlignment(.center).foregroundStyle(.white)
            .padding(.horizontal, 14).padding(.vertical, 10)
            .background(.black.opacity(0.6), in: RoundedRectangle(cornerRadius: 18))
    }

    private func loadTargets() async {
        guard live.canSendPhotos else {
            note = "This phone is shown from the cloud only. To send photos, pair this \(PhoneLive.client) with the phone on its More › Linked devices."
            return
        }
        do {
            let r = try await live.photoTargets()
            targets = r.targets
            chosen = PhotoTargetPicker.selection(deviceID: live.device.id, targets: targets, fallback: r.chosen)
            targetsLoaded = true
            note = nil
        } catch RelayError.notAllowed {
            note = "Update Bridgit on the phone to send it photos from here."
        } catch {
            note = error.localizedDescription
        }
    }

    private func shoot() {
        let front = camera.front
        let ids = chosen
        withAnimation(.easeOut(duration: 0.08)) { flash = true }
        withAnimation(.easeIn(duration: 0.25).delay(0.08)) { flash = false }
        Task {
            guard let image = await camera.capture(), let jpeg = OwnerPhotos.shrink(image) else {
                result = "The photo couldn't be taken."; return
            }
            sending += 1
            withAnimation { result = "Checking…" }
            defer { sending -= 1 }
            do {
                let rs = try await live.sendPhoto(jpeg, front: front, targets: ids)
                sent += 1
                withAnimation { result = rs.map(\.text).joined(separator: "\n") }
            } catch {
                withAnimation { result = "Not sent: \(error.localizedDescription)" }
            }
        }
    }
}

struct CameraControls: View {
    @ObservedObject var camera: PhotoCamera
    var canShoot: Bool
    var sent: Int = 0
    var sending: Int = 0
    var shoot: () -> Void

    var body: some View {
        ZStack {
            HStack {
                if sent + sending > 0 {
                    Text(sending > 0 ? "\(sending)…" : "\(sent)")
                        .font(.headline).foregroundStyle(.white)
                        .frame(width: 56, height: 56).background(.black.opacity(0.6), in: RoundedRectangle(cornerRadius: 18))
                        .accessibilityLabel(sending > 0 ? "\(sending) photos sending" : "\(sent) photos sent")
                }
                Spacer()
                if camera.canFlip {
                    Button { camera.flip() } label: {
                        Image(systemName: "arrow.triangle.2.circlepath.camera").font(.title2).foregroundStyle(.white)
                            .frame(width: 56, height: 56).background(.black.opacity(0.6), in: RoundedRectangle(cornerRadius: 18))
                    }
                    .accessibilityLabel("Switch between the rear and front camera")
                }
            }
            Button(action: shoot) {
                ZStack {
                    Circle().stroke(.white, lineWidth: 4).frame(width: 76, height: 76)
                    Circle().fill(.white).frame(width: 60, height: 60)
                }
            }
            .disabled(!canShoot).opacity(canShoot ? 1 : 0.4)
            .accessibilityLabel("Take photo")
        }
        .padding(.horizontal, 16)
    }

}

@MainActor
final class PhotoCaptureGate {
    private var pending: (id: Int64, continuation: CheckedContinuation<UIImage?, Never>)?
    var pendingID: Int64? { pending?.id }

    func begin(id: Int64, continuation: CheckedContinuation<UIImage?, Never>) {
        precondition(pending == nil)
        pending = (id, continuation)
    }

    func finish(id: Int64, image: UIImage?) {
        guard let current = pending, current.id == id else { return }
        pending = nil
        current.continuation.resume(returning: image)
    }

    func cancel() {
        guard let current = pending else { return }
        pending = nil
        current.continuation.resume(returning: nil)
    }
}

/// AVFoundation photo camera: preview session, rear/front, one photo at a time. Without a camera (Simulator)
/// it says so instead.
@MainActor
final class PhotoCamera: NSObject, ObservableObject {
    @Published private(set) var ready = false
    @Published private(set) var front = false
    @Published private(set) var canFlip = false
    @Published private(set) var problem: String?
    let session = AVCaptureSession()
    private let output = AVCapturePhotoOutput()
    private var input: AVCaptureDeviceInput?
    private var configured = false
    private let pending = PhotoCaptureGate()
    private var rotation: AVCaptureDevice.RotationCoordinator?
    /// The preview layer, for the rotation of photos (set by [CameraPreview]).
    weak var previewLayer: AVCaptureVideoPreviewLayer? { didSet { updateRotation() } }
    private let queue = DispatchQueue(label: "cc.bridgit.app.camera")

    private static let frontPreference = "ownerCameraPrefersFront"
    private var generation = 0

    func start(preferFront: Bool? = nil) {
        generation += 1
        let attempt = generation
        guard device(front: false) != nil || device(front: true) != nil else {
            problem = "No camera available"
            return
        }
        Task {
            let allowed = await AVCaptureDevice.requestAccess(for: .video)
            guard generation == attempt else { return }
            problem = nil
            guard allowed else {
                problem = "Camera access is off. Allow it in Settings › Bridgit."; return
            }
            if !configured { configure(preferFront: preferFront) }
            guard configured else { return }
            let s = session
            // The shutter works once the session really runs (a photo asked for earlier fails before processing).
            queue.async {
                if !s.isRunning { s.startRunning() }
                let running = s.isRunning
                Task { @MainActor in if self.generation == attempt { self.ready = running } }
            }
        }
    }

    func stop() {
        generation += 1
        ready = false
        pending.cancel()
        let s = session
        queue.async { if s.isRunning { s.stopRunning() } }
    }

    func flip(persistPreference: Bool = true) {
        guard let cam = device(front: !front), let next = try? AVCaptureDeviceInput(device: cam) else { return }
        session.beginConfiguration()
        if let input { session.removeInput(input) }
        if session.canAddInput(next) {
            session.addInput(next)
            input = next
            front = cam.position == .front
            if persistPreference { UserDefaults.standard.set(front, forKey: Self.frontPreference) }
        }
        else if let input { session.addInput(input) }
        session.commitConfiguration()
        updateRotation()
    }

    func capture() async -> UIImage? {
        guard ready, pending.pendingID == nil else { return nil }
        if let angle = rotation?.videoRotationAngleForHorizonLevelCapture, let c = output.connection(with: .video), c.isVideoRotationAngleSupported(angle) {
            c.videoRotationAngle = angle
        }
        let settings = AVCapturePhotoSettings(format: [AVVideoCodecKey: AVVideoCodecType.jpeg])
        return await withCheckedContinuation { cont in
            pending.begin(id: settings.uniqueID, continuation: cont)
            output.capturePhoto(with: settings, delegate: self)
        }
    }

    private func device(front: Bool) -> AVCaptureDevice? {
        AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: front ? .front : .back)
    }

    private func configure(preferFront: Bool?) {
        canFlip = device(front: true) != nil && device(front: false) != nil
        let preferredFront = preferFront ?? UserDefaults.standard.bool(forKey: Self.frontPreference)
        guard let cam = device(front: preferredFront) ?? device(front: !preferredFront), let i = try? AVCaptureDeviceInput(device: cam) else {
            problem = "No camera on this device."; return
        }
        session.beginConfiguration()
        session.sessionPreset = .photo
        if session.canAddInput(i) { session.addInput(i); input = i }
        if session.canAddOutput(output) { session.addOutput(output) }
        session.commitConfiguration()
        front = cam.position == .front
        configured = input != nil
        if !configured { problem = "The camera couldn't be opened." }
        updateRotation()
    }

    private func updateRotation() {
        guard let cam = input?.device else { return }
        rotation = AVCaptureDevice.RotationCoordinator(device: cam, previewLayer: previewLayer)
        if let layer = previewLayer, let angle = rotation?.videoRotationAngleForHorizonLevelPreview,
           let c = layer.connection, c.isVideoRotationAngleSupported(angle) { c.videoRotationAngle = angle }
    }
}

extension PhotoCamera: AVCapturePhotoCaptureDelegate {
    nonisolated func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        let image = error == nil ? photo.fileDataRepresentation().flatMap(UIImage.init(data:)) : nil
        let id = photo.resolvedSettings.uniqueID
        Task { @MainActor in pending.finish(id: id, image: image) }
    }

    /// Always called last, also when the capture failed before any photo was processed: never leave the shutter stuck.
    nonisolated func photoOutput(_ output: AVCapturePhotoOutput, didFinishCaptureFor resolvedSettings: AVCaptureResolvedPhotoSettings, error: Error?) {
        let id = resolvedSettings.uniqueID
        Task { @MainActor in pending.finish(id: id, image: nil) }
    }


}

/// The live viewfinder: an AVCaptureVideoPreviewLayer filling the view.
struct CameraPreview: UIViewRepresentable {
    let camera: PhotoCamera

    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var preview: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    }

    func makeUIView(context: Context) -> PreviewView {
        let v = PreviewView()
        v.backgroundColor = .black
        v.preview.session = camera.session
        v.preview.videoGravity = .resizeAspectFill
        camera.previewLayer = v.preview
        return v
    }

    func updateUIView(_ v: PreviewView, context: Context) {}
}
