package com.rvslighting.qoderwake;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.getcapacitor.BridgeActivity;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;

/**
 * QoderWake Mobile —— qoderwake-cn WebUI 的 Android WebView 容器。
 *
 * 行为：
 * - 主 WebView：包住 qoderwake-cn SPA，正常导航 + 下载管理
 * - OAuth popup：override WebChromeClient.onCreateWindow，在 app 内创建第二个 WebView
 *   覆盖在主 WebView 之上。qoderwake-cn 的 OAuth Device Flow 走
 *     window.open('about:blank') → n.location.href = loginUrl
 *   默认 Capacitor 把 popup 弹到系统浏览器，主 WebView 跨源写系统浏览器被拒，
 *   popup 卡在 about:blank。这里改在 app 内创建 popup，OAuth 完成后通过 callback
 *   域名检测自动关闭。
 */
public class MainActivity extends BridgeActivity {

    private static final String HOME_URL = "https://localhost";
    private static final String UA_TAG = "QoderWakeMobile/0.1";
    private static final String CALLBACK_HOST = "qoderwake.rvs-lighting.com";

    private static final int REQ_STORAGE = 1001;
    private String[] pendingDownload = null;

    private FrameLayout container;
    private WebView popupWebView;

    @Override
    @SuppressLint("ClickableViewAccessibility")
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        WebView webView = getBridge().getWebView();
        ViewGroup originalParent = (ViewGroup) webView.getParent();

        container = new FrameLayout(this);
        if (originalParent != null) {
            ViewGroup.LayoutParams inherited = originalParent.getLayoutParams();
            originalParent.removeView(webView);
            container.setLayoutParams(inherited != null ? inherited
                    : new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));
        }
        container.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(container);

        // UA 标记：qoderwake-cn 可以据此识别"这是 QoderWake Mobile 客户端"
        WebSettings settings = webView.getSettings();
        if (!settings.getUserAgentString().contains(UA_TAG)) {
            settings.setUserAgentString(settings.getUserAgentString() + " " + UA_TAG);
        }

        // 关键：override WebChromeClient，让 SPA 的 window.open() 在 app 内创建新 WebView
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                popupWebView = new WebView(MainActivity.this);

                // 复制主 WebView 的关键设置
                WebSettings ps = popupWebView.getSettings();
                ps.setJavaScriptEnabled(true);
                ps.setDomStorageEnabled(true);
                ps.setAllowFileAccess(true);
                ps.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
                ps.setUserAgentString(view.getSettings().getUserAgentString());

                // 监听 popup 内的 URL：
                //   - 跳回 https://qoderwake.rvs-lighting.com/ 时 = OAuth callback 完成
                //     关闭 popup 并刷新主 WebView 让 SPA 拿到新 session
                //   - 其他 URL（qoder.com.cn 的 OAuth 页面）放行
                popupWebView.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, String url) {
                        if (url != null && url.startsWith("https://" + CALLBACK_HOST + "/")) {
                            v.stopLoading();
                            removePopup();
                            // 重新加载主 WebView 让 SPA 检测到新 session
                            webView.reload();
                            return true;
                        }
                        return false;
                    }
                });

                // 把 popup WebView 加到视图树，覆盖在主 WebView 之上
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
                container.addView(popupWebView, lp);

                // 通过 transport 把 WebView 交给 SPA（这样 SPA 拿到的 `n` 就是这个 WebView）
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(popupWebView);
                resultMsg.sendToTarget();
                return true;
            }

            @Override
            public void onCloseWindow(WebView window) {
                removePopup();
            }
        });

        // WebView 默认不会保存下载：交给系统 DownloadManager
        webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            String absUrl = url;
            if (url != null && url.startsWith("/")) {
                Uri page = Uri.parse(webView.getUrl() != null ? webView.getUrl() : HOME_URL);
                String port = page.getPort() != -1 ? ":" + page.getPort() : "";
                absUrl = page.getScheme() + "://" + page.getHost() + port + url;
            }
            startDownload(absUrl, contentDisposition, mimetype);
        });
    }

    private void removePopup() {
        if (popupWebView != null) {
            container.removeView(popupWebView);
            popupWebView.destroy();
            popupWebView = null;
        }
    }

    private void startDownload(String url, String contentDisposition, String mimetype) {
        if (Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
            pendingDownload = new String[] { url, contentDisposition, mimetype };
            requestPermissions(new String[] { Manifest.permission.WRITE_EXTERNAL_STORAGE }, REQ_STORAGE);
            return;
        }
        enqueueDownload(url, contentDisposition, mimetype);
    }

    private void enqueueDownload(String url, String contentDisposition, String mimetype) {
        String fileName = fileNameFromDisposition(url, contentDisposition, mimetype);
        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
        if (mimetype != null) req.setMimeType(mimetype);
        String cookies = CookieManager.getInstance().getCookie(url);
        if (cookies != null) req.addRequestHeader("Cookie", cookies);
        req.setTitle(fileName);
        req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
        DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        dm.enqueue(req);
        Toast.makeText(this, "开始下载：" + fileName + "（保存到系统「下载」目录）", Toast.LENGTH_LONG).show();
    }

    private static String fileNameFromDisposition(String url, String cd, String mimetype) {
        if (cd != null) {
            int star = cd.toLowerCase().indexOf("filename*=");
            if (star >= 0) {
                String v = cd.substring(star + 10).split(";")[0].trim();
                int q = v.indexOf("''");
                if (q >= 0) {
                    try {
                        return URLDecoder.decode(v.substring(q + 2), "UTF-8");
                    } catch (UnsupportedEncodingException | IllegalArgumentException e) {
                        /* 落到下一分支 */
                    }
                }
            }
            int plain = cd.toLowerCase().indexOf("filename=");
            if (plain >= 0) {
                String v = cd.substring(plain + 9).split(";")[0].trim().replace("\"", "");
                if (!v.isEmpty()) return v;
            }
        }
        return URLUtil.guessFileName(url, cd, mimetype);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE && pendingDownload != null) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                enqueueDownload(pendingDownload[0], pendingDownload[1], pendingDownload[2]);
            } else {
                Toast.makeText(this, "未授予存储权限，无法保存文件", Toast.LENGTH_LONG).show();
            }
            pendingDownload = null;
        }
    }
}