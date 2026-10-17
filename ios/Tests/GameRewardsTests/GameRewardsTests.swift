import XCTest
@testable import GameRewards

final class GameRewardsTests: XCTestCase {
    func testRequestHashMatchesSpecVector() {
        let hash = GameRewards.requestHash(clientId: "demo", userId: "u-123", nonce: "bm9uY2U", ts: 1_700_000_000_000)
        XCTAssertEqual(hash.count, 32) // raw bytes go to generateAssertion as clientDataHash
        XCTAssertEqual(GameRewards.hex(hash), "6a219e645cf83a6a7ca21e93bf865b797ba302ad4a8006752a26f90f6171eb49")
    }

    func testNonceIsBase64urlInServerRange() {
        let nonce = GameRewards.newNonce()
        XCTAssertNotNil(nonce.range(of: "^[A-Za-z0-9_-]{16,64}$", options: .regularExpression), nonce)
        XCTAssertNotEqual(nonce, GameRewards.newNonce())
    }

    func testKeyStoreIsPerUser() {
        let c = GameRewards.Config(clientId: "demo", baseURL: URL(string: "https://rewards.example.com")!)
        XCTAssertEqual(GameRewards.keyStore(c, userId: "u-1"), "GameRewards.keyId.demo|u-1@rewards.example.com")
        XCTAssertNotEqual(GameRewards.keyStore(c, userId: "u-1"), GameRewards.keyStore(c, userId: "u-2"))
    }

    func testPortalOriginCheck() {
        let portal = URL(string: "http://localhost:8080/portal/#s=abc")!
        XCTAssertTrue(GameRewards.sameOrigin(URL(string: "http://LOCALHOST:8080/portal/app.js")!, portal))
        XCTAssertTrue(GameRewards.sameOrigin(URL(string: "https://x.com/a")!, URL(string: "https://x.com:443/b")!))
        XCTAssertFalse(GameRewards.sameOrigin(URL(string: "https://localhost:8080/portal/")!, portal))
        XCTAssertFalse(GameRewards.sameOrigin(URL(string: "http://localhost:9090/portal/")!, portal))
        XCTAssertFalse(GameRewards.sameOrigin(URL(string: "http://localhost.evil.com:8080/")!, portal))
        XCTAssertFalse(GameRewards.sameOrigin(URL(string: "file:///etc/hosts")!, URL(string: "file:///etc/hosts")!))
        XCTAssertFalse(GameRewards.sameOrigin(URL(string: "about:blank")!, URL(string: "about:blank")!))
    }
}
