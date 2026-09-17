package com.aiknowledgevault.app;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.MimeTypeMap;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String PREFS = "knowledge_vault_prefs";
    private static final String KEY_SERVER_URL = "server_url";
    private static final int REQUEST_FILE_CHOOSER = 4101;
    private static final int REQUEST_STORAGE = 4102;

    private WebView webView;
    private View setupPanel;
    private TextView connectionStatus, offlineBanner, setupStatus;
    private ProgressBar pageProgress;
    private EditText serverUrlInput;
    private Button testButton, saveButton;
    private String serverUrl = "";
    private ValueCallback<Uri[]> fileCallback;
    private Uri cameraOutputUri;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        setupPanel = findViewById(R.id.setupPanel);
        connectionStatus = findViewById(R.id.connectionStatus);
        offlineBanner = findViewById(R.id.offlineBanner);
        setupStatus = findViewById(R.id.setupStatus);
        pageProgress = findViewById(R.id.pageProgress);
        serverUrlInput = findViewById(R.id.serverUrlInput);
        testButton = findViewById(R.id.testButton);
        saveButton = findViewById(R.id.saveButton);
        Button serverButton = findViewById(R.id.serverButton);

        configureWebView();
        observeNetwork();

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        serverUrl = prefs.getString(KEY_SERVER_URL, "");
        serverUrlInput.setText(serverUrl);

        serverButton.setOnClickListener(v -> showSetup());
        testButton.setOnClickListener(v -> testConnection(false));
        saveButton.setOnClickListener(v -> testConnection(true));

        if (serverUrl == null || serverUrl.trim().isEmpty()) showSetup();
        else {
            setupPanel.setVisibility(View.GONE);
            loadVault();
        }
    }

    private void configureWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setUserAgentString(s.getUserAgentString() + " AKVAndroid/1.0");

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);

        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
                if ((scheme.equals("http") || scheme.equals("https")) && sameServer(uri)) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
                catch (Exception e) { toast("Không mở được liên kết này"); }
                return true;
            }

            @Override public void onPageStarted(WebView view, String url, Bitmap favicon) {
                pageProgress.setVisibility(View.VISIBLE);
                connectionStatus.setText("Đang kết nối…");
                connectionStatus.setTextColor(getColor(R.color.text_secondary));
            }

            @Override public void onPageFinished(WebView view, String url) {
                pageProgress.setVisibility(View.GONE);
                connectionStatus.setText("Đã kết nối");
                connectionStatus.setTextColor(getColor(R.color.success));
                offlineBanner.setVisibility(View.GONE);
                injectNativeMode();
            }

            @Override public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame()) {
                    pageProgress.setVisibility(View.GONE);
                    connectionStatus.setText("Không kết nối được");
                    connectionStatus.setTextColor(getColor(R.color.danger));
                    offlineBanner.setText("Không truy cập được máy chủ. Kiểm tra Wi-Fi/4G và địa chỉ máy chủ.");
                    offlineBanner.setVisibility(View.VISIBLE);
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int progress) {
                pageProgress.setProgress(progress);
                pageProgress.setVisibility(progress >= 100 ? View.GONE : View.VISIBLE);
            }

            @Override public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                launchFileChooser(params);
                return true;
            }
        });

        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, len) ->
                startDownload(url, userAgent, contentDisposition, mimeType));
    }

    private void injectNativeMode() {
        webView.evaluateJavascript("(function(){document.documentElement.classList.add('native-android');document.body.classList.add('native-android');})();", null);
    }

    private boolean sameServer(Uri uri) {
        try {
            URI base = new URI(serverUrl);
            int bp = base.getPort() >= 0 ? base.getPort() : ("https".equalsIgnoreCase(base.getScheme()) ? 443 : 80);
            int up = uri.getPort() >= 0 ? uri.getPort() : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
            return base.getScheme().equalsIgnoreCase(uri.getScheme()) && base.getHost().equalsIgnoreCase(uri.getHost()) && bp == up;
        } catch (Exception e) { return false; }
    }

    private void showSetup() {
        setupPanel.setVisibility(View.VISIBLE);
        webView.setVisibility(View.INVISIBLE);
        serverUrlInput.setText(serverUrl);
        setupStatus.setText("");
    }

    private void hideSetup() {
        setupPanel.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        View f = getCurrentFocus();
        if (f != null) ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(f.getWindowToken(), 0);
    }

    private void testConnection(boolean saveIfOk) {
        final String normalized;
        try { normalized = normalizeServerUrl(serverUrlInput.getText().toString()); }
        catch (IllegalArgumentException e) { showSetupStatus(e.getMessage(), false); return; }

        setSetupBusy(true);
        showSetupStatus("Đang kiểm tra kết nối…", true);
        ioExecutor.execute(() -> {
            boolean ok = false;
            String message;
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection)new URL(normalized + "/api/health").openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setRequestMethod("GET");
                int code = conn.getResponseCode();
                ok = code >= 200 && code < 300;
                message = ok ? "Kết nối thành công" : "Máy chủ phản hồi mã " + code;
            } catch (Exception e) {
                message = "Không kết nối được. Kiểm tra địa chỉ máy chủ và mạng.";
            } finally { if (conn != null) conn.disconnect(); }

            boolean finalOk = ok;
            String finalMessage = message;
            runOnUiThread(() -> {
                setSetupBusy(false);
                showSetupStatus(finalMessage, finalOk);
                if (finalOk && saveIfOk) {
                    serverUrl = normalized;
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_SERVER_URL, serverUrl).apply();
                    hideSetup();
                    loadVault();
                }
            });
        });
    }

    private String normalizeServerUrl(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) throw new IllegalArgumentException("Hãy nhập địa chỉ máy chủ.");
        if (!value.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*$")) value = "https://" + value;
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost();
            if (!(scheme.equals("http") || scheme.equals("https")) || host == null) throw new Exception();
            if (scheme.equals("http") && !isPrivateOrLocalHost(host))
                throw new IllegalArgumentException("Máy chủ Internet phải dùng HTTPS. HTTP chỉ dùng trong mạng nội bộ.");
            return value;
        } catch (IllegalArgumentException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException("Địa chỉ không hợp lệ. Ví dụ: https://ai.example.com"); }
    }

    private boolean isPrivateOrLocalHost(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        if (h.equals("localhost") || h.endsWith(".local") || h.startsWith("127.") || h.startsWith("10.") || h.startsWith("192.168.")) return true;
        if (h.startsWith("172.")) {
            String[] p = h.split("\\.");
            if (p.length > 1) try { int n = Integer.parseInt(p[1]); if (n >= 16 && n <= 31) return true; } catch (Exception ignored) {}
        }
        try {
            InetAddress a = InetAddress.getByName(h);
            return a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress();
        } catch (Exception e) { return false; }
    }

    private void loadVault() {
        if (serverUrl == null || serverUrl.isEmpty()) { showSetup(); return; }
        webView.setVisibility(View.VISIBLE);
        webView.loadUrl(serverUrl + "/");
    }

    private void showSetupStatus(String text, boolean success) {
        setupStatus.setText(text);
        setupStatus.setTextColor(getColor(success ? R.color.success : R.color.danger));
    }

    private void setSetupBusy(boolean busy) {
        testButton.setEnabled(!busy);
        saveButton.setEnabled(!busy);
        testButton.setAlpha(busy ? .55f : 1f);
        saveButton.setAlpha(busy ? .55f : 1f);
    }

    private void launchFileChooser(WebChromeClient.FileChooserParams params) {
        String[] accepts = params == null ? new String[0] : params.getAcceptTypes();
        boolean imageAccepted = acceptsImage(accepts);
        Intent open = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        open.addCategory(Intent.CATEGORY_OPENABLE);
        open.setType(resolvePrimaryMime(accepts));
        String[] clean = cleanMimeTypes(accepts);
        if (clean.length > 1) open.putExtra(Intent.EXTRA_MIME_TYPES, clean);
        if (params != null && params.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE)
            open.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);

        Intent camera = imageAccepted ? buildCameraIntent() : null;
        try {
            Intent chooser = Intent.createChooser(open, imageAccepted ? "Chọn file hoặc chụp ảnh" : "Chọn tài liệu");
            if (camera != null) chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{camera});
            startActivityForResult(chooser, REQUEST_FILE_CHOOSER);
        } catch (Exception e) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = null;
            toast("Không mở được trình chọn file");
        }
    }

    private Intent buildCameraIntent() {
        Intent camera = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (camera.resolveActivity(getPackageManager()) == null) return null;
        try {
            File dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
            if (dir == null) dir = getCacheDir();
            File photo = File.createTempFile("akv_capture_", ".jpg", dir);
            cameraOutputUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", photo);
            camera.putExtra(MediaStore.EXTRA_OUTPUT, cameraOutputUri);
            camera.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            return camera;
        } catch (IOException e) { return null; }
    }

    private boolean acceptsImage(String[] accepts) {
        if (accepts == null || accepts.length == 0) return true;
        for (String a : accepts) if (a == null || a.isEmpty() || a.equals("*/*") || a.toLowerCase(Locale.ROOT).startsWith("image/")) return true;
        return false;
    }

    private String resolvePrimaryMime(String[] accepts) {
        String[] clean = cleanMimeTypes(accepts);
        return clean.length == 1 ? clean[0] : "*/*";
    }

    private String[] cleanMimeTypes(String[] accepts) {
        List<String> out = new ArrayList<>();
        if (accepts != null) for (String a : accepts) if (a != null) for (String part : a.split(",")) {
            String mime = part.trim();
            if (mime.startsWith(".")) {
                String mapped = MimeTypeMap.getSingleton().getMimeTypeFromExtension(mime.substring(1).toLowerCase(Locale.ROOT));
                if (mapped != null) out.add(mapped);
            } else if (mime.contains("/")) out.add(mime);
        }
        return out.toArray(new String[0]);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_FILE_CHOOSER || fileCallback == null) return;
        Uri[] result = null;
        if (resultCode == RESULT_OK) {
            if (data == null || (data.getData() == null && data.getClipData() == null)) {
                if (cameraOutputUri != null) result = new Uri[]{cameraOutputUri};
            } else if (data.getClipData() != null) {
                ClipData c = data.getClipData();
                result = new Uri[c.getItemCount()];
                for (int i = 0; i < c.getItemCount(); i++) result[i] = c.getItemAt(i).getUri();
            } else if (data.getData() != null) result = new Uri[]{data.getData()};
        }
        fileCallback.onReceiveValue(result);
        fileCallback = null;
        cameraOutputUri = null;
    }

    private void startDownload(String url, String userAgent, String contentDisposition, String mimeType) {
        try {
            String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            r.setTitle(fileName);
            r.setDescription("Knowledge Vault");
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            if (mimeType != null && !mimeType.isEmpty()) r.setMimeType(mimeType);
            if (userAgent != null) r.addRequestHeader("User-Agent", userAgent);
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) r.addRequestHeader("Cookie", cookie);
            ((DownloadManager)getSystemService(DOWNLOAD_SERVICE)).enqueue(r);
            toast("Đang tải file vào Downloads");
        } catch (Exception e) { toast("Không tải được file"); }
    }

    private void observeNetwork() {
        connectivityManager = (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) { runOnUiThread(() -> offlineBanner.setVisibility(View.GONE)); }
            @Override public void onLost(Network network) { runOnUiThread(() -> {
                offlineBanner.setText("Mất kết nối mạng. Hãy kiểm tra Wi-Fi hoặc 4G/5G.");
                offlineBanner.setVisibility(View.VISIBLE);
                connectionStatus.setText("Mất mạng");
                connectionStatus.setTextColor(getColor(R.color.danger));
            }); }
        };
        try { connectivityManager.registerDefaultNetworkCallback(networkCallback); } catch (Exception ignored) {}
    }

    private void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_SHORT).show(); }

    @Override public void onBackPressed() {
        if (setupPanel.getVisibility() == View.VISIBLE && serverUrl != null && !serverUrl.isEmpty()) { hideSetup(); return; }
        if (setupPanel.getVisibility() != View.VISIBLE && webView.canGoBack()) { webView.goBack(); return; }
        super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (connectivityManager != null && networkCallback != null) try { connectivityManager.unregisterNetworkCallback(networkCallback); } catch (Exception ignored) {}
        if (webView != null) webView.destroy();
        ioExecutor.shutdownNow();
        super.onDestroy();
    }
}
