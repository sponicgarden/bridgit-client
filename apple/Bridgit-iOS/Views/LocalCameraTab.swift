import BridgitCore
import SwiftUI

/// Standalone owner camera. The applet selection controls where a capture is saved.
/// Saving a photo does not start a model call or grant agent camera authority.
struct LocalCameraTab: View {
    @EnvironmentObject private var local: LocalAppletModel
    @Environment(\.scenePhase) private var phase
    @StateObject private var camera = PhotoCamera()
    @State private var selectedID: String?
    @State private var result: String?
    @State private var saving = false
    var body: some View {
        let available = local.cameraTargets
        let selected = available.first { $0.id == selectedID } ?? (available.count == 1 ? available.first : nil)
        ZStack {
            Color.black.ignoresSafeArea()
            CameraPreview(camera: camera)
            VStack(spacing: 10) {
                VStack(spacing: 6) {
                    if available.isEmpty {
                        Text("Photos are not connected to any Bridgit applet. No immediate action will be taken.")
                    } else {
                        Menu {
                            ForEach(available) { item in
                                Button("\(item.name) · \(item.label)") {
                                    selectedID = item.id
                                    result = nil
                                }
                            }
                        } label: {
                            Text(selected.map { "Save to: \($0.name) · \($0.label)" } ?? "Choose an applet")
                        }
                    }
                    if let problem = camera.problem { Text(problem) }
                }
                .padding(8).background(.black.opacity(0.65), in: RoundedRectangle(cornerRadius: 16))
                Spacer()
                if let result { Text(result) }
                CameraControls(camera: camera, canShoot: camera.ready && selected != nil && !saving) {
                    guard let selected, !saving else { return }
                    saving = true
                    Task {
                        defer { saving = false }
                        guard let image = await camera.capture(), let data = image.jpegData(compressionQuality: 0.9) else {
                            result = "The photo couldn't be taken."
                            return
                        }
                        do {
                            _ = try await local.importPhoto(applet: selected.applet, data: data, action: selected.action)
                            result = "Saved to \(selected.name)"
                        } catch { result = error.localizedDescription }
                    }
                }
            }
            .multilineTextAlignment(.center).foregroundStyle(.white)
            .padding().shadow(radius: 2)
        }
        .onAppear { if phase == .active { camera.start() } }
        .onDisappear { camera.stop() }
        .onChange(of: phase) { _, phase in phase == .active ? camera.start() : camera.stop() }
    }
}
