import SwiftUI
import UIKit

// The SDK sources are compiled straight into this target, so there is no `import GameRewards` here.
// In your app: add the Swift package, then `import GameRewards`.

@main
struct DemoApp: App {
    init() {
        #if DEBUG
        // Dev server on the Mac with fake attestation: `cd server && npm run seed && npm run dev`.
        // The simulator has no App Attest, so devFakeProof sends `fake:<requestHash>` there.
        GameRewards.configure(.init(clientId: "demo", baseURL: URL(string: "http://localhost:8080")!, devFakeProof: true))
        #else
        // ponytail: placeholder; point Release at your deployed server.
        GameRewards.configure(.init(clientId: "demo", baseURL: URL(string: "https://rewards.example.com")!))
        #endif
    }

    var body: some Scene {
        WindowGroup { ContentView() }
    }
}

struct ContentView: View {
    @State private var userId = "u-123"
    @State private var status = "Ready"
    @State private var busy = false

    var body: some View {
        VStack(spacing: 16) {
            Text("Demo Shop").font(.largeTitle.bold())
            TextField("User ID", text: $userId)
                .textFieldStyle(.roundedBorder)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            Button("Play & Earn", action: play)
                .buttonStyle(.borderedProminent)
                .disabled(busy || userId.isEmpty)
            Text(status)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .padding()
    }

    private func play() {
        // SwiftUI has no view controller to present from; use the topmost one in the key window.
        let window = UIApplication.shared.connectedScenes.compactMap { ($0 as? UIWindowScene)?.keyWindow }.first
        guard var top = window?.rootViewController else { return }
        while let next = top.presentedViewController { top = next }
        busy = true
        status = "Opening…"
        GameRewards.open(from: top, userId: userId) { error in
            busy = false
            status = error.map { "Error: \($0.localizedDescription)" } ?? "Portal opened"
        }
    }
}
