import AVFoundation
import SwiftUI

struct DeviceCameraView: View {
    @DialogDismiss private var dismiss
    @StateObject private var camera = LocalMacCamera()
    @Environment(\.scenePhase) private var phase
    @State private var preview: Data?
    @State private var actualLens = "default"
    @State private var remaining = 0
    @State private var shutter: Task<Void, Never>?
    private let countdown: Int
    private let needsReview: Bool
    private let captured: (Data, String) -> Void
    init(captured: @escaping (Data) -> Void) {
        needsReview = false; countdown = 0; self.captured = { data, _ in captured(data) }
    }
    init(requestedLens: String, countdown: Int = 0, reviewed: @escaping (Data, String) -> Void) {
        needsReview = true; self.countdown = countdown; captured = reviewed
    }
    var body: some View {
        VStack(spacing: 16) {
            HStack { Text("Take a photo for this applet").font(.headline); Spacer(); Button("Done") { dismiss() }.keyboardShortcut(.cancelAction) }
            if let preview, let image = NSImage(data: preview) {
                Image(nsImage: image).resizable().scaledToFit().frame(maxWidth: 640, maxHeight: 480)
                HStack {
                    Button("Retake") { self.preview = nil }
                    Button("Use photo") { captured(preview, actualLens); dismiss() }.buttonStyle(.borderedProminent)
                }
            } else {
                MacCameraPreview(session: camera.session).aspectRatio(4 / 3, contentMode: .fit).frame(minWidth: 440, idealWidth: 640, maxWidth: 760)
                if needsReview { Text("Default Mac camera").font(.caption) }
                if let problem = camera.problem { Text(problem).foregroundStyle(B.error) }
                Button(remaining > 0 ? "Taking photo in \(remaining)" : "Take photo", systemImage: "camera") {
                    shutter = Task {
                        defer { remaining = 0; shutter = nil }
                        do {
                            try await PortablePhotoCountdown.run(countdown, update: { remaining = $0 })
                            guard let data = await camera.capture(), phase == .active else { return }
                            try Task.checkCancellation()
                            actualLens = camera.isFront.map { $0 ? "front" : "rear" } ?? "default"
                            if needsReview { preview = data; camera.stop() } else { captured(data, actualLens) }
                        } catch {}
                    }
                }.buttonStyle(.borderedProminent).disabled(!camera.ready || shutter != nil)
            }
        }.padding(20)
        .task(id: phase == .active && preview == nil) {
            if phase == .active && preview == nil { await camera.start() } else { camera.stop() }
        }.onChange(of: phase) { _, state in if state != .active { shutter?.cancel() } }
         .onDisappear { shutter?.cancel(); camera.stop() }
    }
}
struct MacOwnerCameraView: View {
    var pairingID: String?
    @EnvironmentObject private var model: MacModel
    @State private var remoteTargets: [PhotoTarget] = []
    @State private var chosen: [String] = []
    @State private var loaded = false
    @State private var reload = 0
    @State private var captureTask: Task<Void, Never>?
    @Environment(\.scenePhase) private var phase
    @EnvironmentObject private var local: LocalAppletModel
    @StateObject private var camera = LocalMacCamera()
    @State private var selectedID: String?
    @State private var result: String?
    @State private var saving = false

    var body: some View {
        let targets = local.cameraTargets
        let selected = targets.first { $0.id == selectedID } ?? (targets.count == 1 ? targets.first : nil)
        VStack(alignment: .leading, spacing: 12) {
            if let pairingID {
                if loaded {
                    PhotoTargetPicker(deviceID: pairingID, targets: remoteTargets, chosen: $chosen).disabled(saving)
                } else if result == nil { ProgressView("Loading photo applets") }
                else { Button("Retry") { reload += 1 } }
            } else if targets.isEmpty {
                Text("Photos are not connected to any Bridgit applet. No immediate action will be taken.")
            } else {
                Menu(selected.map { "Save to: \($0.name) · \($0.label)" } ?? "Choose an applet") {
                    ForEach(targets) { target in
                        Button("\(target.name) · \(target.label)") { selectedID = target.id; result = nil }
                    }
                }
            }
            MacCameraPreview(session: camera.session)
                .aspectRatio(4 / 3, contentMode: .fit).frame(maxWidth: 640)
                .background(.black).accessibilityHidden(true)
            if let problem = camera.problem { Text(problem) }
            Button("Take photo", systemImage: "camera") {
                guard !saving else { return }
                let ids = chosen
                let front = camera.isFront
                saving = true
                captureTask = Task {
                    defer { saving = false }
                    guard let data = await camera.capture() else {
                        result = "The photo couldn't be taken."
                        return
                    }
                    guard !Task.isCancelled else { return }
                    do {
                        if let pairingID {
                            let checks = try await model.sendPhoto(data, front: front, targets: ids, to: pairingID)
                            result = checks.isEmpty ? "The device returned no checks." : checks.map(\.text).joined(separator: "\n")
                            return
                        }
                        guard let selected else { return }
                        _ = try await local.importPhoto(applet: selected.applet, data: data, action: selected.action)
                        result = "Saved to \(selected.name)"
                    } catch { result = error.localizedDescription }
                }
            }.buttonStyle(.borderedProminent).disabled(!camera.ready || saving || (pairingID == nil ? selected == nil : !loaded || remoteTargets.isEmpty))
            if let result { Text(result) }
        }
        .task(id: reload) {
            guard let pairingID else { return }
            loaded = false
            remoteTargets = []
            chosen = []
            result = nil
            do {
                let response = try await model.photoTargets(for: pairingID)
                try Task.checkCancellation()
                remoteTargets = response.targets
                chosen = PhotoTargetPicker.selection(deviceID: pairingID, targets: response.targets, fallback: response.chosen)
                loaded = true
            } catch { if !Task.isCancelled { result = error.localizedDescription } }
        }
        .task(id: phase) {
            if phase == .active { await camera.start() }
            else { camera.stop() }
        }
        .onDisappear { captureTask?.cancel(); camera.stop() }
    }
}

@MainActor private final class LocalMacCamera: NSObject, ObservableObject, AVCapturePhotoCaptureDelegate {
    let session = AVCaptureSession()
    @Published var ready = false
    @Published var problem: String?
    var isFront: Bool? {
        switch (session.inputs.first as? AVCaptureDeviceInput)?.device.position {
        case .front: true
        case .back: false
        default: nil
        }
    }
    private let output = AVCapturePhotoOutput()
    private let queue = DispatchQueue(label: "cc.bridgit.local.camera")
    private var active = false
    private var generation = UUID()
    private var pending: (id: Int64, continuation: CheckedContinuation<Data?, Never>)?
    func start() async {
        let run = UUID(); generation = run; active = true
        guard AVCaptureDevice.default(for: .video) != nil else { problem = "No camera is connected."; return }
        let allowed = await AVCaptureDevice.requestAccess(for: .video)
        guard active, generation == run, !Task.isCancelled else { return }
        guard allowed else { problem = "Camera access is off. Allow Bridgit in System Settings to take photos."; return }
        problem = nil
        if session.inputs.isEmpty {
            guard let device = AVCaptureDevice.default(for: .video), let input = try? AVCaptureDeviceInput(device: device) else { problem = "No camera is connected."; return }
            session.beginConfiguration()
            guard session.canAddInput(input), session.canAddOutput(output) else { session.commitConfiguration(); problem = "The camera is unavailable."; return }
            session.addInput(input); session.addOutput(output); session.sessionPreset = .photo; session.commitConfiguration()
        }
        let session = self.session
        await withCheckedContinuation { continuation in queue.async { session.startRunning(); continuation.resume() } }
        guard active, generation == run, !Task.isCancelled else { return }
        ready = true
    }
    func stop() {
        active = false; generation = UUID(); ready = false
        pending?.continuation.resume(returning: nil); pending = nil
        let session = self.session
        queue.async { session.stopRunning() }
    }

    func capture() async -> Data? {
        guard ready, pending == nil else { return nil }
        let settings = AVCapturePhotoSettings()
        return await withCheckedContinuation { continuation in
            pending = (settings.uniqueID, continuation)
            output.capturePhoto(with: settings, delegate: self)
        }
    }

    nonisolated func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        let id = photo.resolvedSettings.uniqueID
        let data = error == nil ? photo.fileDataRepresentation() : nil
        Task { @MainActor in
            guard let request = pending, request.id == id else { return }
            pending = nil
            request.continuation.resume(returning: data)
        }
    }
}
private struct MacCameraPreview: NSViewRepresentable {
    var session: AVCaptureSession
    func makeNSView(context: Context) -> NSView { let view = NSView(); view.wantsLayer = true; let layer = AVCaptureVideoPreviewLayer(session: session); layer.videoGravity = .resizeAspect; view.layer = layer; return view }
    func updateNSView(_ view: NSView, context: Context) { view.layer?.frame = view.bounds }
}
