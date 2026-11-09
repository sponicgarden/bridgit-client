import AVFoundation
import BridgitCore
import Foundation

/// One foreground capture. OS permission is requested separately by an owner action.
/// Revocation, Stop, a deadline or foreground exit cancels before any image can be returned.
@MainActor final class AppleCamera: NSObject, AVCapturePhotoCaptureDelegate {
    private var session: AVCaptureSession?
    private var output: AVCapturePhotoOutput?
    private var pending: CheckedContinuation<Data, Error>?
    private var busy = false
    private var generation = UUID()
    private let queue = DispatchQueue(label: "cc.bridgit.host.camera")
    static var permission: Bool { AVCaptureDevice.authorizationStatus(for: .video) == .authorized }
    static var canRequestPermission: Bool { AVCaptureDevice.authorizationStatus(for: .video) == .notDetermined }
    static var hardware: Bool { AVCaptureDevice.default(for: .video) != nil }
    static func requestPermission() async -> Bool { await AVCaptureDevice.requestAccess(for: .video) }
    func stop() {
        generation = UUID()
        let pending = self.pending; self.pending = nil; pending?.resume(throwing: CancellationError())
        if let session { queue.async { session.stopRunning() } }
    }
    nonisolated static var defaultLens: String {
        #if os(macOS)
        "default"
        #else
        "rear"
        #endif
    }
    func capture(lens: String = AppleCamera.defaultLens, maxEdge: Int = 2048, quality: Double = 0.85) async throws -> Data {
        guard !busy else { throw DeviceAPIError(409, "camera_busy", "The host camera is already in use.") }
        guard Self.permission else { throw DeviceAPIError(403, "permission_denied", "The owner must allow camera access before an agent can take a photo.") }
        let device: AVCaptureDevice?
        #if os(macOS)
        guard lens == "default" else { throw DeviceAPIError(422, "unsupported_camera", "This Mac uses the default camera. Omit camera or use default; phone-style front/rear selectors are unavailable.") }
        device = AVCaptureDevice.default(for: .video)
        #else
        guard ["rear", "front"].contains(lens) else { throw DeviceAPIError(422, "invalid_parameter", "Choose rear or front camera.") }
        device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: lens == "front" ? .front : .back)
        #endif
        guard let device else { throw DeviceAPIError(409, "hardware_unavailable", "The selected camera is not connected.") }
        busy = true; let run = UUID(); generation = run
        let session = AVCaptureSession(), output = AVCapturePhotoOutput(); self.session = session; self.output = output
        defer { busy = false; self.session = nil; self.output = nil; queue.async { session.stopRunning() } }
        return try await withTaskCancellationHandler {
            session.beginConfiguration()
            let input = try AVCaptureDeviceInput(device: device)
            guard session.canAddInput(input), session.canAddOutput(output) else { session.commitConfiguration(); throw DeviceAPIError(409, "camera_unavailable", "The camera is being used by another app.") }
            session.addInput(input); session.addOutput(output); session.sessionPreset = .photo; session.commitConfiguration()
            await withCheckedContinuation { continuation in queue.async { session.startRunning(); continuation.resume() } }
            guard generation == run, Self.permission else { throw CancellationError() }; try Task.checkCancellation()
            let data: Data = try await withCheckedThrowingContinuation { continuation in
                pending = continuation
                output.capturePhoto(with: AVCapturePhotoSettings(), delegate: self)
                Task { try? await Task.sleep(nanoseconds: 15_000_000_000); if self.generation == run, let pending = self.pending { self.pending = nil; pending.resume(throwing: DeviceAPIError(504, "camera_timeout", "The camera did not return a photo.")) } }
            }
            guard generation == run, Self.permission else { throw CancellationError() }; try Task.checkCancellation()
            return try LocalAppletModel.jpeg(data, maxEdge: maxEdge, quality: quality)
        } onCancel: { Task { @MainActor in self.stop() } }
    }
    nonisolated func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        let data = error == nil ? photo.fileDataRepresentation() : nil
        Task { @MainActor in
            guard output === self.output, let continuation = self.pending else { return }; self.pending = nil
            if let data { continuation.resume(returning: data) } else { continuation.resume(throwing: DeviceAPIError(409, "capture_failed", "The camera did not produce a photo.")) }
        }
    }
}
