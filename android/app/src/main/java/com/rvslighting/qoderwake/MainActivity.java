package com.rvslighting.qoderwake;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.getcapacitor.Bridge;
import com.getcapacitor.BridgeActivity;
import com.getcapacitor.BridgeWebViewClient;

import org.json.JSONArray;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;

/**
 * QoderWake Mobile —— qoderwake-cn WebUI 的多主机 Android WebView 容器。
 *
 * 行为：
 * - 主 WebView：包住 qoderwake-cn SPA，正常导航 + 下载管理
 * - OAuth popup：override WebChromeClient.onCreateWindow，在 app 内创建第二个 WebView
 *   覆盖在主 WebView 之上。qoderwake-cn 的 OAuth Device Flow 走
 *     window.open('about:blank') → n.location.href = loginUrl
 *   默认 Capacitor 把 popup 弹到系统浏览器，主 WebView 跨源写系统浏览器被拒，
 *   popup 卡在 about:blank。这里改在 app 内创建受限 popup，OAuth 完成后通过当前
 *   主机的精确 origin 检测自动关闭。
 */
public class MainActivity extends BridgeActivity {

    private static final String HOME_URL = "https://localhost";
    private static final String UA_TAG = "QoderWakeMobile/0.2";
    private static final String PREFS_FILE = "CapacitorStorage";
    private static final String CURRENT_HOST_ORIGIN_KEY = "qoderwake-current-host-origin";
    private static final String HOSTS_KEY = "qoderwake-hosts";

    private static final int REQ_STORAGE = 1001;
    private String[] pendingDownload = null;

    private FrameLayout container;
    private WebView popupWebView;

    @Override
    @SuppressLint("ClickableViewAccessibility")
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        WebView webView = getBridge().getWebView();
        getBridge().setWebViewClient(new TrustedHostsWebViewClient(getBridge()));
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
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true);
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
                ps.setAllowFileAccess(false);
                ps.setAllowContentAccess(false);
                ps.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ps.setSafeBrowsingEnabled(true);
                }
                ps.setUserAgentString(view.getSettings().getUserAgentString());

                // 只允许当前主机 callback 和 Qoder 官方登录域。
                popupWebView.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, String url) {
                        if (isCurrentHostUrl(url)) {
                            v.stopLoading();
                            removePopup();
                            webView.reload();
                            return true;
                        }
                        if (isQoderLoginUrl(url) || "about:blank".equals(url)) return false;
                        openExternal(url);
                        removePopup();
                        return true;
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
            if (!isCurrentHostUrl(absUrl)) {
                Toast.makeText(this, "已阻止来自非当前主机的下载", Toast.LENGTH_LONG).show();
                return;
            }
            startDownload(absUrl, contentDisposition, mimetype);
        });
    }

    private String currentHostOrigin() {
        SharedPreferences prefs = getSharedPreferences(PREFS_FILE, MODE_PRIVATE);
        return prefs.getString(CURRENT_HOST_ORIGIN_KEY, "");
    }

    private boolean isCurrentHostUrl(String url) {
        String expected = currentHostOrigin();
        if (expected.isEmpty() || url == null) return false;
        try {
            Uri target = Uri.parse(url);
            Uri trusted = Uri.parse(expected);
            return "https".equalsIgnoreCase(target.getScheme())
                    && "https".equalsIgnoreCase(trusted.getScheme())
                    && target.getHost() != null
                    && target.getHost().equalsIgnoreCase(trusted.getHost())
                    && effectivePort(target) == effectivePort(trusted);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isQoderLoginUrl(String url) {
        if (url == null) return false;
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            return "https".equalsIgnoreCase(uri.getScheme())
                    && host != null
                    && (host.equalsIgnoreCase("qoder.com.cn")
                        || host.toLowerCase(java.util.Locale.ROOT).endsWith(".qoder.com.cn"));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static int effectivePort(Uri uri) {
        return uri.getPort() == -1 ? 443 : uri.getPort();
    }

    private boolean isTrustedNavigation(Uri target) {
        if (target == null) return false;
        if ("about".equalsIgnoreCase(target.getScheme())) return true;
        if (!"https".equalsIgnoreCase(target.getScheme()) || target.getUserInfo() != null) return false;
        if ("localhost".equalsIgnoreCase(target.getHost())) return true;

        SharedPreferences prefs = getSharedPreferences(PREFS_FILE, MODE_PRIVATE);
        try {
            JSONArray hosts = new JSONArray(prefs.getString(HOSTS_KEY, "[]"));
            for (int i = 0; i < hosts.length(); i++) {
                Uri saved = Uri.parse(hosts.getJSONObject(i).optString("url", ""));
                if (sameHttpsOrigin(target, saved)) return true;
            }
        } catch (Exception ignored) { /* corrupt host storage denies navigation */ }
        return false;
    }

    private static boolean sameHttpsOrigin(Uri left, Uri right) {
        return left != null
                && right != null
                && "https".equalsIgnoreCase(left.getScheme())
                && "https".equalsIgnoreCase(right.getScheme())
                && left.getHost() != null
                && left.getHost().equalsIgnoreCase(right.getHost())
                && effectivePort(left) == effectivePort(right);
    }

    private class TrustedHostsWebViewClient extends BridgeWebViewClient {
        TrustedHostsWebViewClient(Bridge bridge) {
            super(bridge);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (isTrustedNavigation(request.getUrl())) {
                return super.shouldOverrideUrlLoading(view, request);
            }
            openExternal(request.getUrl().toString());
            return true;
        }

        @Deprecated
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            Uri target = Uri.parse(url);
            if (isTrustedNavigation(target)) {
                return super.shouldOverrideUrlLoading(view, url);
            }
            openExternal(url);
            return true;
        }
    }

    private void openExternal(String url) {
        if (url == null) return;
        try {
            Uri uri = Uri.parse(url);
            if (!"https".equalsIgnoreCase(uri.getScheme())) return;
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception ignored) {
            Toast.makeText(this, "无法安全打开外部页面", Toast.LENGTH_LONG).show();
        }
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
