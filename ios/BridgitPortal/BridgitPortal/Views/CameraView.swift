import AVFoundation
import AVKit
import SwiftUI
import BridgitAppletRuntime

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
            if active { CameraScreen(live: live) } else { Color.black.ignoresSafeArea() }
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
                controls
            }
            .padding(.horizontal, 16).padding(.top, 10).padding(.bottom, 16)
        }
        .toolbar(.hidden, for: .navigationBar)
        .onAppear { camera.start() }
        .onDisappear { camera.stop() }
        .onChange(of: phase) { _, p in p == .active ? camera.start() : camera.stop() }
        .task(id: live.device.id) { await loadTargets() }
    }

    private var sendToText: String {
        if !live.canSendPhotos { return "Pair on the phone to send photos" }
        if !targetsLoaded { return "Checked by: …" }
        if targets.isEmpty { return "Photos are not connected to any Bridgit applet. No immediate action will be taken." }
        let picked = targets.filter { chosen.contains($0.id) }
        if picked.isEmpty { return targets.count == 1 ? "Checked by: \(targets[0].name)" : "Checked by: all applets (\(targets.count))" }
        return "Checked by: " + picked.map(\.name).joined(separator: ", ")
    }

    private var targetMenu: some View {
        Menu {
            Button { chosen = [] } label: { Label("All applets", systemImage: chosen.isEmpty ? "checkmark" : "") }
            ForEach(targets) { t in
                Button {
                    var c = Set(chosen.isEmpty ? targets.map(\.id) : chosen)
                    if c.contains(t.id) { c.remove(t.id) } else { c.insert(t.id) }
                    // None or every one ticked both mean "all".
                    chosen = c.isEmpty || c.count == targets.count ? [] : targets.map(\.id).filter(c.contains)
                } label: { Label(t.name, systemImage: chosen.isEmpty || chosen.contains(t.id) ? "checkmark" : "") }
            }
            Button("Reload from the phone") { Task { await loadTargets() } }
        } label: {
            Text(sendToText).frostText(.supporting, weight: .medium).lineLimit(3).multilineTextAlignment(.center)
                .padding(.horizontal, 16).padding(.vertical, 8).frame(minHeight: 40)
                .background(.black.opacity(0.6), in: RoundedRectangle(cornerRadius: 20)).foregroundStyle(.white)
        }
        .disabled(!live.canSendPhotos)
        .accessibilityHint("Choose which applets check the photos")
    }

    private var controls: some View {
        HStack {
            Text(sending > 0 ? "\(sending)…" : "\(sent)")
                .frostText(.label, weight: .semibold).foregroundStyle(.white)
                .frame(width: 56, height: 56).background(.black.opacity(0.6), in: RoundedRectangle(cornerRadius: 18))
                .opacity(sent + sending > 0 ? 1 : 0)
                .accessibilityLabel(sending > 0 ? "\(sending) photos sending" : "\(sent) photos sent")
            Spacer()
            Button(action: shoot) {
                ZStack {
                    Circle().stroke(.white, lineWidth: 4).frame(width: 76, height: 76)
                    Circle().fill(.white).frame(width: 60, height: 60)
                }
            }
            .disabled(!canShoot).opacity(canShoot ? 1 : 0.4)
            .accessibilityLabel("Take photo")
            Spacer()
            Button { camera.flip() } label: {
                Image(systemName: "arrow.triangle.2.circlepath.camera").frostText(.section, weight: .semibold).foregroundStyle(.white)
                    .frame(width: 56, height: 56).background(.black.opacity(0.6), in: RoundedRectangle(cornerRadius: 18))
            }
            .opacity(camera.canFlip ? 1 : 0).disabled(!camera.canFlip)
            .accessibilityLabel("Switch between the rear and front camera")
        }
        .padding(.horizontal, 16)
    }

    private var canShoot: Bool { camera.ready && live.canSendPhotos && !targets.isEmpty }

    private func bubble(_ text: String) -> some View {
        Text(text).frostText(.supporting).multilineTextAlignment(.center).foregroundStyle(.white)
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
            chosen = r.chosen.filter { id in r.targets.contains { $0.id == id } }
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
    private let movieOutput: AVCaptureMovieFileOutput?
    private var recording: CheckedContinuation<Void,Error>?
    init(video: Bool = false) {
        movieOutput = video ? AVCaptureMovieFileOutput() : nil
        super.init()
    }
    private var input: AVCaptureDeviceInput?
    private var configured = false
    private var pending: CheckedContinuation<UIImage?, Never>?
    private var rotation: AVCaptureDevice.RotationCoordinator?
    /// The preview layer, for the rotation of photos (set by [CameraPreview]).
    weak var previewLayer: AVCaptureVideoPreviewLayer? { didSet { updateRotation() } }
    private let queue = DispatchQueue(label: "cc.bridgit.portal.camera")

    private var generation = 0
    func start(preferFront: Bool = false) {
        generation += 1
        let requested = generation
        Task {
            guard await AVCaptureDevice.requestAccess(for: .video) else {
                problem = "Camera access is off. Allow it in Settings › Bridgit Portal."; return
            }
            guard requested == generation else { return }
            if !configured { configure(preferFront: preferFront) }
            guard configured else { return }
            let s = session
            // The shutter works once the session really runs (a photo asked for earlier fails before processing).
            queue.async {
                if !s.isRunning { s.startRunning() }
                let running = s.isRunning
                Task { @MainActor in if requested == self.generation { self.ready = running } }
            }
        }
    }

    func stop() {
        endRecording()
        generation += 1
        ready = false
        let s = session
        queue.async { if s.isRunning { s.stopRunning() } }
    }

    func flip() {
        guard let cam = device(front: !front), let next = try? AVCaptureDeviceInput(device: cam) else { return }
        session.beginConfiguration()
        if let input { session.removeInput(input) }
        if session.canAddInput(next) { session.addInput(next); input = next; front.toggle() }
        else if let input { session.addInput(input) }
        session.commitConfiguration()
        updateRotation()
    }

    func capture() async -> UIImage? {
        guard ready, pending == nil else { return nil }
        if let angle = rotation?.videoRotationAngleForHorizonLevelCapture, let c = output.connection(with: .video), c.isVideoRotationAngleSupported(angle) {
            c.videoRotationAngle = angle
        }
        return await withCheckedContinuation { cont in
            pending = cont
            output.capturePhoto(with: AVCapturePhotoSettings(format: [AVVideoCodecKey: AVVideoCodecType.jpeg]), delegate: self)
        }
    }

    private func device(front: Bool) -> AVCaptureDevice? {
        AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: front ? .front : .back)
    }

    private func configure(preferFront: Bool) {
        canFlip = device(front: true) != nil && device(front: false) != nil
        guard let cam = device(front: preferFront) ?? device(front: !preferFront) ?? AVCaptureDevice.default(for: .video), let i = try? AVCaptureDeviceInput(device: cam) else {
            problem = "No camera on this device."; return
        }
        session.beginConfiguration()
        let preset: AVCaptureSession.Preset = movieOutput == nil ? .photo : .hd1280x720
        guard session.canSetSessionPreset(preset) else {
            session.commitConfiguration()
            problem = "This camera does not support the requested resolution."
            return
        }
        session.sessionPreset = preset
        if session.canAddInput(i) { session.addInput(i); input = i }
        let selected: AVCaptureOutput = movieOutput.map { $0 as AVCaptureOutput } ?? output
        if session.canAddOutput(selected) { session.addOutput(selected) }
        session.commitConfiguration()
        front = cam.position == .front
        configured = input != nil && session.outputs.contains(selected)
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

extension PhotoCamera: AVCaptureFileOutputRecordingDelegate {
    func record(to file: CardMovieFile, seconds: Int) async throws {
        guard ready, recording == nil, let movieOutput, let connection = movieOutput.connection(with:.video) else {
            throw AppletRuntimeError.invalid("Video camera unavailable")
        }
        if let angle = rotation?.videoRotationAngleForHorizonLevelCapture, connection.isVideoRotationAngleSupported(angle) {
            connection.videoRotationAngle = angle
        }
        movieOutput.maxRecordedDuration = CMTime(value:Int64(seconds),timescale:1)
        movieOutput.maxRecordedFileSize = 32 * 1024 * 1024
        try await withCheckedThrowingContinuation { result in
            recording = result
            movieOutput.startRecording(to:file.url,recordingDelegate:self)
        }
    }

    func endRecording() {
        if movieOutput?.isRecording == true { movieOutput?.stopRecording() }
    }

    nonisolated func fileOutput(_ output: AVCaptureFileOutput, didFinishRecordingTo url: URL,
                                from connections: [AVCaptureConnection], error: Error?) {
        Task { @MainActor in
            guard let recording else { return }
            self.recording = nil
            if let error, (error as NSError).userInfo[AVErrorRecordingSuccessfullyFinishedKey] as? Bool != true {
                recording.resume(throwing:error)
            } else { recording.resume() }
        }
    }
}

/// Retained by the reviewing sheet or the card's capture store until the owner closes or deletes it.
final class CardMovieFile {
    static let directory = FileManager.default.temporaryDirectory.appendingPathComponent("AppletCameraMovies",isDirectory:true)
    let url: URL
    init() throws {
        try FileManager.default.createDirectory(at:Self.directory,withIntermediateDirectories:true)
        url = Self.directory.appendingPathComponent(UUID().uuidString).appendingPathExtension("mov")
    }
    deinit { try? FileManager.default.removeItem(at:url) }

    func frames(count: Int) async throws -> [Data] {
        let asset = AVURLAsset(url:url)
        let duration = try await asset.load(.duration).seconds
        guard (1...8).contains(count), duration.isFinite, duration > 0 else {
            throw AppletRuntimeError.invalid("No video frames available")
        }
        let generator = AVAssetImageGenerator(asset:asset)
        generator.appliesPreferredTrackTransform = true
        generator.requestedTimeToleranceBefore = CMTime(value:1,timescale:30)
        generator.requestedTimeToleranceAfter = CMTime(value:1,timescale:30)
        generator.maximumSize = CGSize(width:960,height:960)
        var frames = [Data]()
        for index in 0..<count {
            try Task.checkCancellation()
            let time = CMTime(seconds:duration * (Double(index) + 0.5) / Double(count),preferredTimescale:600)
            let frame = try await generator.image(at:time)
            guard let jpeg = UIImage(cgImage:frame.image).jpegData(compressionQuality:0.8) else {
                throw AppletRuntimeError.invalid("Could not read the recorded frame")
            }
            frames.append(jpeg)
        }
        return frames
    }
}

struct AppletVideoSheet: View {
    let lens: String
    let seconds: Int
    let countdown: Int
    let accept: (CardMovieFile,String) -> Void
    let cancel: () -> Void
    @StateObject private var camera = PhotoCamera(video:true)
    @Environment(\.scenePhase) private var phase
    @State private var task: Task<Void,Never>?
    @State private var remaining = 0
    @State private var recording = false
    @State private var recorded: CardMovieFile?
    @State private var player: AVPlayer?
    @State private var message: String?

    var body: some View {
        VStack {
            if let recorded {
                VideoPlayer(player:player)
                Button("Use video") { accept(recorded,camera.front ? "front" : "rear") }
                Button("Retake") {
                    player?.pause(); player = nil; self.recorded = nil
                    camera.start(preferFront:lens == "front")
                }
            } else {
                CameraPreview(camera:camera)
                Text(camera.front ? "Front camera" : "Rear camera")
                if recording {
                    Button("Finish recording") { camera.endRecording() }
                } else {
                    Button("Flip camera") { camera.flip() }.disabled(!camera.canFlip || task != nil)
                    Button(remaining > 0 ? "Recording in " + String(remaining) : "Record up to " + String(seconds) + " seconds") {
                        task = Task { await record() }
                    }.disabled(!camera.ready || task != nil)
                }
            }
            if let error = message ?? camera.problem { Text(error) }
            Button("Cancel",role:.cancel) { stop(); cancel() }
        }
        .padding()
        .onAppear { camera.start(preferFront:lens == "front") }
        .onDisappear { stop() }
        .onChange(of:phase) { _, state in
            if state != .active { stop() }
            else if recorded == nil { camera.start(preferFront:lens == "front") }
        }
    }

    private func stop() { task?.cancel(); camera.stop(); player?.pause() }

    @MainActor private func record() async {
        defer { remaining = 0; recording = false; task = nil }
        do {
            message = nil
            for second in (0..<countdown).reversed() {
                remaining = second + 1
                try await Task.sleep(for:.seconds(1))
            }
            try Task.checkCancellation()
            let file = try CardMovieFile()
            recording = true
            try await camera.record(to:file,seconds:seconds)
            try Task.checkCancellation()
            camera.stop()
            recorded = file; player = AVPlayer(url:file.url)
        } catch is CancellationError {} catch { message = error.localizedDescription }
    }
}

extension PhotoCamera: AVCapturePhotoCaptureDelegate {
    nonisolated func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        let image = error == nil ? photo.fileDataRepresentation().flatMap(UIImage.init(data:)) : nil
        Task { @MainActor in finish(image) }
    }

    /// Always called last, also when the capture failed before any photo was processed: never leave the shutter stuck.
    nonisolated func photoOutput(_ output: AVCapturePhotoOutput, didFinishCaptureFor resolvedSettings: AVCaptureResolvedPhotoSettings, error: Error?) {
        Task { @MainActor in finish(nil) }
    }

    /// Resumes the waiting capture once (the first call wins).
    private func finish(_ image: UIImage?) {
        guard let p = pending else { return }
        pending = nil
        p.resume(returning: image)
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

/// A foreground workflow owns its viewfinder and receives bytes only after explicit review.
struct AppletCameraSheet: View {
    let requested: String
    let size: AppletPackage.CameraSize?
    var countdown = 0
    var outputEdge: CGFloat?
    var outputQuality: CGFloat = 0.8
    let accept: (LocalAppletLibrary.ReviewedPhoto) -> Void
    let cancel: () -> Void
    @StateObject private var camera = PhotoCamera()
    @Environment(\.scenePhase) private var phase
    @State private var image: UIImage?
    @State private var actualCamera = ""
    @State private var taking = false
    @State private var remaining = 0
    @State private var shutter: Task<Void,Never>?
    @State private var error: String?

    var body: some View {
        VStack {
            if let image {
                Image(uiImage: image).resizable().scaledToFit()
                HStack {
                    Button("Retake") { self.image = nil; camera.start(preferFront: requested == "front") }
                    Button("Use photo") {
                        let prepared: Data?
                        if let outputEdge {
                            prepared = OwnerPhotos.shrink(image,maxEdge:outputEdge,quality:outputQuality)
                        } else {
                            switch size {
                            case .large: prepared = OwnerPhotos.shrink(image,maxEdge:2048,quality:0.90)
                            case .medium: prepared = OwnerPhotos.shrink(image,maxEdge:1280,quality:0.85)
                            case nil: prepared = OwnerPhotos.shrink(image)
                            }
                        }
                        if let jpeg = prepared {
                            accept(.init(jpeg: jpeg, camera: actualCamera))
                        } else { error = "The photo could not be prepared." }
                    }
                }
            } else {
                CameraPreview(camera: camera)
                Text(camera.front ? "Front camera" : "Rear camera")
                HStack {
                    Button("Flip camera") { camera.flip() }.disabled(!camera.canFlip || taking)
                    Button(remaining > 0 ? "Taking photo in " + String(remaining) : "Take photo") {
                        taking = true
                        actualCamera = camera.front ? "front" : "rear"
                        shutter = Task {
                            defer { taking = false; remaining = 0 }
                            for second in (0..<countdown).reversed() {
                                remaining = second + 1
                                do { try await Task.sleep(for:.seconds(1)) } catch { return }
                            }
                            guard !Task.isCancelled else { return }
                            if let shot = await camera.capture(), !Task.isCancelled { image = shot; camera.stop() }
                            else { error = "The photo could not be taken." }
                        }
                    }.disabled(!camera.ready || taking)
                }
            }
            if let problem = error ?? camera.problem { Text(problem) }
            Button("Cancel", role: .cancel, action: cancel)
        }
        .padding()
        .onAppear { camera.start(preferFront: requested == "front") }
        .onDisappear { shutter?.cancel(); camera.stop() }
        .onChange(of: phase) { _, state in
            if state == .active && image == nil { camera.start(preferFront: requested == "front") }
            else { shutter?.cancel(); camera.stop() }
        }
    }
}
