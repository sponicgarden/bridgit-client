import SwiftUI
import UIKit

struct DeviceCameraView: UIViewControllerRepresentable {
    @Environment(\.dismiss) private var dismiss
    var requestedLens: String?
    var reviewed: (Data, String) -> Void
    init(captured: @escaping (Data) -> Void) { reviewed = { data, _ in captured(data) } }
    init(requestedLens: String, reviewed: @escaping (Data, String) -> Void) {
        self.requestedLens = requestedLens; self.reviewed = reviewed
    }
    func makeCoordinator() -> Coordinator { Coordinator(captured: reviewed, close: { dismiss() }) }
    func makeUIViewController(context: Context) -> UIViewController {
        guard UIImagePickerController.isSourceTypeAvailable(.camera) else {
            return UIHostingController(rootView: VStack(spacing: 16) {
                ContentUnavailableView("No camera available", systemImage: "camera")
                Button("Done", action: context.coordinator.close)
            }.padding())
        }
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        if let requestedLens {
            let requested: UIImagePickerController.CameraDevice = requestedLens == "front" ? .front : .rear
            if UIImagePickerController.isCameraDeviceAvailable(requested) { picker.cameraDevice = requested }
        }
        picker.delegate = context.coordinator; picker.allowsEditing = false
        return picker
    }
    func updateUIViewController(_ view: UIViewController, context: Context) { }
    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let captured: (Data, String) -> Void; let close: () -> Void
        init(captured: @escaping (Data, String) -> Void, close: @escaping () -> Void) { self.captured = captured; self.close = close }
        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { close() }
        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            if let image = info[.originalImage] as? UIImage, let bytes = image.jpegData(compressionQuality: 0.9) { captured(bytes, picker.cameraDevice == .front ? "front" : "rear") }; close()
        }
    }
}
