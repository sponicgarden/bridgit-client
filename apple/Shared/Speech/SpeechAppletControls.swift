import SwiftUI

struct SpeechAppletControls: View {
    @ObservedObject var speech: LocalSpeechModel
    let applet: String
    let hearing: Bool
    @State private var consent = false
    var body: some View {
        GroupBox("Speech") {
            VStack(alignment: .leading, spacing: 10) {
                Text(hearing ? "This applet can listen in bounded turns and speak on this device." : "This applet can speak on this device.").font(.callout)
                if speech.enabled.contains(applet) {
                    HStack {
                        Label("Speech enabled", systemImage: "waveform")
                        Button("Disable") { speech.disable(applet: applet) }
                        Button("Stop", role: .cancel) { speech.stop(applet: applet) }
                    }
                    Toggle("Allow cloud speech", isOn: Binding(get: { speech.cloudAllowed.contains(applet) }, set: { speech.setCloud(applet: applet, allowed: $0) }))
                    Text("Off by default. A cloud voice sends text; cloud hearing sends audio. Applet steps must also request cloud use. Account and applet budgets apply.").font(.caption).foregroundStyle(B.onSurfaceVariant)
                } else {
                    Button(speech.preparing ? "Preparing speech…" : "Enable speech") { consent = true }.disabled(speech.preparing)
                }
                if let problem = speech.problem { Text(problem).font(.callout).foregroundStyle(B.error) }
            }.frame(maxWidth: .infinity, alignment: .leading).padding(8)
        }
        .confirmationDialog("Enable this applet's speech?", isPresented: $consent, titleVisibility: .visible) {
            Button("Enable speech") { Task { await speech.enable(applet: applet, hearing: hearing) } }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("The device registers its signing identity and checks the model registry online. Device speech processing is free. Cloud speech stays off unless you allow it separately. Hearing requires your microphone permission and the app in the foreground. Stop cancels the current turn.")
        }
    }
}
