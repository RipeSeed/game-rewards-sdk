#if canImport(UIKit)
import UIKit
import WebKit

extension GameRewards {
    /// Attests this launch, starts a session and shows the rewards portal full screen over `presenter`.
    /// `completion` runs on the main thread: nil once the portal is on screen, otherwise the error.
    @MainActor
    public static func open(from presenter: UIViewController, userId: String, completion: ((Error?) -> Void)? = nil) {
        Task { @MainActor in
            do {
                let portal = PortalViewController(url: try await launch(userId: userId))
                // UIKit drops a present from a covered or off-window presenter silently (and never calls back),
                // so say so instead. The launch takes seconds, so the screen may have closed meanwhile.
                guard presenter.viewIfLoaded?.window != nil, presenter.presentedViewController == nil else {
                    throw GameRewardsError(code: "busy", message: "This screen is closed or already shows something on top.")
                }
                let nav = UINavigationController(rootViewController: portal)
                nav.modalPresentationStyle = .fullScreen
                presenter.present(nav, animated: true) { completion?(nil) }
            } catch {
                completion?(error)
            }
        }
    }
}

/// The portal in a WKWebView with a Close button. Only the portal's origin loads inside;
/// other http(s) links open in Safari, everything else is blocked.
final class PortalViewController: UIViewController, WKNavigationDelegate {
    private let url: URL
    private let webView = WKWebView()

    init(url: URL) {
        self.url = url
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { nil }

    override func loadView() {
        view = webView
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        navigationItem.rightBarButtonItem = UIBarButtonItem(barButtonSystemItem: .close, target: self, action: #selector(close))
        webView.isOpaque = false // no white flash before the portal's dark theme paints
        webView.backgroundColor = .systemBackground
        webView.navigationDelegate = self
        webView.load(URLRequest(url: url))
    }

    @objc private func close() {
        dismiss(animated: true)
    }

    /// iOS kills WebContent under memory pressure (often in the background) and leaves a blank page.
    /// Load the original URL, not reload(): the portal drops `#s=<token>` from the address once booted.
    // ponytail: reloads on every crash, no cap; add one if a page ever crashes WebContent on load
    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        webView.load(URLRequest(url: url))
    }

    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard let target = action.request.url else { return decisionHandler(.cancel) }
        if GameRewards.sameOrigin(target, url) { return decisionHandler(.allow) }
        decisionHandler(.cancel)
        // main-frame navigations and target=_blank links (targetFrame nil); never subframes
        if ["http", "https"].contains(target.scheme?.lowercased()), action.targetFrame?.isMainFrame != false {
            UIApplication.shared.open(target)
        }
    }
}
#endif
