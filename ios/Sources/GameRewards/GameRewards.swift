import CryptoKit
import DeviceCheck
import Foundation

/// Call `configure` once at app start, then `open(from:userId:)` from your Play & Earn button.
public enum GameRewards {
    public struct Config {
        public var clientId: String
        public var baseURL: URL
        /// Debug only: when App Attest is unsupported (simulator), send `fake:<requestHash>`,
        /// which only a server running `ATTEST_MODE=fake` accepts.
        public var devFakeProof: Bool

        public init(clientId: String, baseURL: URL, devFakeProof: Bool = false) {
            self.clientId = clientId
            self.baseURL = baseURL
            self.devFakeProof = devFakeProof
        }
    }

    static var config: Config?

    public static func configure(_ config: Config) {
        self.config = config
    }

    /// Proves this launch to the server and returns the portal URL (with the session token) to show.
    static func launch(userId: String) async throws -> URL {
        guard let c = config else {
            throw GameRewardsError(code: "not_configured", message: "Call GameRewards.configure first.")
        }
        let res: [String: Any]
        if DCAppAttestService.shared.isSupported {
            res = try await attestedLaunch(c, userId: userId)
        } else {
            // ponytail: without devFakeProof we still launch with no valid proof and let the partner's
            // onAttestFail decide (play-only session or 403), rather than failing on the device.
            res = try await postLaunch(c, userId: userId, keyId: nil) { c.devFakeProof ? "fake:" + hex($0) : "unsupported" }
        }
        guard let s = res["portalUrl"] as? String, let url = URL(string: s),
              ["http", "https"].contains(url.scheme?.lowercased()) else {
            throw GameRewardsError(code: "bad_response", message: "The server sent no portal URL.")
        }
        return url
    }

    /// Launch signed with this install's App Attest key. If the server doesn't know the key
    /// (`unknown_key`) or the device lost it (`invalidKey`), register a fresh one and retry once.
    static func attestedLaunch(_ c: Config, userId: String, retry: Bool = true) async throws -> [String: Any] {
        let keyId = try await registeredKeyId(c, userId: userId)
        do {
            let res = try await postLaunch(c, userId: userId, keyId: keyId) {
                try await DCAppAttestService.shared.generateAssertion(keyId, clientDataHash: $0).base64EncodedString()
            }
            // play-only partners answer 200 with verified=false; reject partners answer 403 (caught below)
            guard retry, res["reason"] as? String == "unknown_key" else { return res }
        } catch {
            let lostKey = (error as? GameRewardsError)?.reason == "unknown_key" || (error as? DCError)?.code == .invalidKey
            guard retry, lostKey else { throw error }
        }
        UserDefaults.standard.removeObject(forKey: keyStore(c, userId: userId))
        return try await attestedLaunch(c, userId: userId, retry: false)
    }

    /// One-time per user on this install (and per client and host): generate a Secure Enclave key, have Apple
    /// attest it against a server challenge, register it. The key id is saved only after the server accepted it.
    static func registeredKeyId(_ c: Config, userId: String) async throws -> String {
        let store = keyStore(c, userId: userId)
        if let keyId = UserDefaults.standard.string(forKey: store) { return keyId }
        let service = DCAppAttestService.shared
        let keyId = try await service.generateKey()
        guard let challenge = try await post(c, "v1/attest/challenge", ["clientId": c.clientId])["challenge"] as? String else {
            throw GameRewardsError(code: "bad_response", message: "The server sent no challenge.")
        }
        // ponytail: any attestKey failure throws and the next open makes a new key. Apple suggests retrying
        // the same key on DCError.serverUnavailable; add that if Apple outages show up in practice.
        let attestation = try await service.attestKey(keyId, clientDataHash: Data(SHA256.hash(data: Data(challenge.utf8))))
        _ = try await post(c, "v1/attest/register", [
            "clientId": c.clientId, "keyId": keyId, "attestation": attestation.base64EncodedString(), "challenge": challenge,
        ])
        UserDefaults.standard.set(keyId, forKey: store)
        return keyId
    }

    /// Apple: one key per user account per device, never shared between users.
    /// `|` can't appear in a userId and `@` can't appear in a host, so keys never collide.
    static func keyStore(_ c: Config, userId: String) -> String {
        "GameRewards.keyId.\(c.clientId)|\(userId)@\(c.baseURL.host ?? "")"
    }

    /// POST /v1/launch. `proof` turns the raw request hash into the proof string.
    static func postLaunch(_ c: Config, userId: String, keyId: String?,
                           proof: (Data) async throws -> String) async throws -> [String: Any] {
        let nonce = newNonce()
        let ts = Int64(Date().timeIntervalSince1970 * 1000)
        var body: [String: Any] = [
            "clientId": c.clientId, "userId": userId, "platform": "ios", "nonce": nonce, "ts": ts,
            "proof": try await proof(requestHash(clientId: c.clientId, userId: userId, nonce: nonce, ts: ts)),
        ]
        body["keyId"] = keyId
        return try await post(c, "v1/launch", body)
    }

    static func post(_ c: Config, _ path: String, _ body: [String: Any]) async throws -> [String: Any] {
        var req = URLRequest(url: c.baseURL.appendingPathComponent(path))
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try JSONSerialization.data(withJSONObject: body)
        let (data, response) = try await URLSession.shared.data(for: req)
        let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard status == 200 else {
            throw GameRewardsError(code: json["error"] as? String ?? "http_\(status)",
                                   message: json["message"] as? String ?? "The server answered \(status).",
                                   reason: json["reason"] as? String)
        }
        return json
    }

    // MARK: - Pure helpers (unit tested on macOS)

    /// SHA-256 of `clientId|userId|nonce|ts` (UTF-8). The raw 32 bytes are App Attest's clientDataHash;
    /// the lowercase hex is the server's requestHash (SPEC "Identifiers and hashing").
    static func requestHash(clientId: String, userId: String, nonce: String, ts: Int64) -> Data {
        Data(SHA256.hash(data: Data("\(clientId)|\(userId)|\(nonce)|\(ts)".utf8)))
    }

    static func hex(_ data: Data) -> String {
        data.map { String(format: "%02x", $0) }.joined()
    }

    /// 16 random bytes as unpadded base64url (22 chars).
    static func newNonce() -> String {
        Data((0..<16).map { _ in UInt8.random(in: .min ... .max) }).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    /// Same scheme, host and port (default ports filled in). URLs without a host never match.
    static func sameOrigin(_ a: URL, _ b: URL) -> Bool {
        func origin(_ u: URL) -> String? {
            guard let scheme = u.scheme?.lowercased(), let host = u.host?.lowercased(), !host.isEmpty else { return nil }
            return "\(scheme)://\(host):\(u.port ?? (scheme == "https" ? 443 : 80))"
        }
        guard let oa = origin(a) else { return false }
        return oa == origin(b)
    }
}

public struct GameRewardsError: LocalizedError {
    /// The server's error code (`attestation_failed`, `rate_limited`, `bad_request`, ...),
    /// or `not_configured`, `bad_response`, `http_<status>`, `busy` (the presenter already shows something or left the screen).
    public let code: String
    public let message: String
    /// Why attestation failed, when `code == "attestation_failed"` (e.g. `stale`, `replay`, `unknown_key`).
    public let reason: String?

    public var errorDescription: String? { message }

    init(code: String, message: String, reason: String? = nil) {
        self.code = code
        self.message = message
        self.reason = reason
    }
}
