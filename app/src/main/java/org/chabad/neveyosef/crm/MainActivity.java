package org.chabad.neveyosef.crm;

import android.Manifest;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.net.http.SslError;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;
import java.util.Map;

/**
 * עטיפת WebView נייטיבית למערכת בית חב״ד ERP.
 *
 * עקרונות אבטחה:
 *  - HTTPS בלבד; שגיאות תעודה אינן ניתנות לעקיפה.
 *  - רק הדומיין המוגדר נטען בתוך ה-WebView; קישורים אחרים נפתחים בדפדפן החיצוני.
 *  - גשר ה-JavaScript, המיקום וההורדות (כולל עוגיות) פעילים רק עבור הדומיין המוגדר.
 *  - ללא גישה לקבצים מקומיים וללא תוכן מעורב (mixed content).
 */
public class MainActivity extends ComponentActivity {

    private static final String PREFS_NAME = "ChabadCrmNativePrefs";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String BRIDGE_NAME = "ChabadNativeBridge";

    private FrameLayout root;
    private WebView webView;
    private ProgressBar progressBar;

    private View overlay;
    private ProgressBar overlaySpinner;
    private TextView overlayTitle;
    private TextView overlayMessage;
    private View overlayButtons;

    private String serverUrl;
    private String serverHost;
    private boolean pageLoadFailed;
    private volatile boolean trustedPage;

    private ValueCallback<Uri[]> filePathCallback;
    private GeolocationPermissions.Callback pendingGeoCallback;
    private String pendingGeoOrigin;

    private ActivityResultLauncher<Intent> fileChooserLauncher;
    private ActivityResultLauncher<String[]> locationPermissionLauncher;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        fileChooserLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    ValueCallback<Uri[]> cb = filePathCallback;
                    filePathCallback = null;
                    if (cb != null) {
                        cb.onReceiveValue(WebChromeClient.FileChooserParams
                                .parseResult(result.getResultCode(), result.getData()));
                    }
                });

        locationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                (Map<String, Boolean> grants) -> {
                    GeolocationPermissions.Callback cb = pendingGeoCallback;
                    String origin = pendingGeoOrigin;
                    pendingGeoCallback = null;
                    pendingGeoOrigin = null;
                    if (cb == null || origin == null) return;
                    cb.invoke(origin, hasLocationPermission(), false);
                });

        setServerUrl(getSavedServerUrl());

        buildUi();
        setupBackNavigation();

        if (savedInstanceState != null && webView.restoreState(savedInstanceState) != null) {
            // המצב שוחזר; טוענים מחדש רק אם הדף ריק.
            if (webView.getUrl() == null) loadServer();
        } else {
            loadServer();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null) webView.saveState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onPause() {
        if (webView != null) webView.onPause();
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        ValueCallback<Uri[]> cb = filePathCallback;
        filePathCallback = null;
        if (cb != null) cb.onReceiveValue(null);
        pendingGeoCallback = null;
        pendingGeoOrigin = null;
        destroyWebView();
        super.onDestroy();
    }

    private void destroyWebView() {
        if (webView == null) return;
        WebView wv = webView;
        webView = null;
        trustedPage = false;
        wv.stopLoading();
        wv.removeJavascriptInterface(BRIDGE_NAME);
        wv.setWebViewClient(new WebViewClient());
        wv.setWebChromeClient(new WebChromeClient());
        if (wv.getParent() instanceof ViewGroup) ((ViewGroup) wv.getParent()).removeView(wv);
        wv.destroy();
    }

    private void setupBackNavigation() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (overlay.getVisibility() != View.VISIBLE
                        && webView != null && webView.canGoBack()) {
                    webView.goBack();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
    }

    // ------------------------------------------------------------------ UI

    private void buildUi() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0f172a"));
        setContentView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        WindowInsetsControllerCompat controller =
                new WindowInsetsControllerCompat(getWindow(), root);
        controller.setAppearanceLightStatusBars(false);
        controller.setAppearanceLightNavigationBars(false);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.parseColor("#f8fafc"));
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        configureWebView(webView);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.getProgressDrawable().setTint(Color.parseColor("#f59e0b"));
        progressBar.setVisibility(View.GONE);
        root.addView(progressBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3), Gravity.TOP));

        buildOverlay();
    }

    private void buildOverlay() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(Color.parseColor("#0f172a"));
        box.setPadding(dp(28), dp(28), dp(28), dp(28));
        box.setClickable(true); // בולע מגעים מעל ה-WebView

        overlaySpinner = new ProgressBar(this);
        box.addView(overlaySpinner, new LinearLayout.LayoutParams(dp(48), dp(48)));

        overlayTitle = new TextView(this);
        overlayTitle.setTextColor(Color.parseColor("#f8fafc"));
        overlayTitle.setTextSize(18);
        overlayTitle.setGravity(Gravity.CENTER);
        overlayTitle.setPadding(0, dp(16), 0, dp(8));
        box.addView(overlayTitle);

        overlayMessage = new TextView(this);
        overlayMessage.setTextColor(Color.parseColor("#cbd5e1"));
        overlayMessage.setTextSize(14);
        overlayMessage.setGravity(Gravity.CENTER);
        box.addView(overlayMessage);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.VERTICAL);
        buttons.setGravity(Gravity.CENTER_HORIZONTAL);
        buttons.setPadding(0, dp(20), 0, 0);

        Button retry = new Button(this);
        retry.setText(R.string.retry);
        retry.setBackgroundColor(Color.parseColor("#f59e0b"));
        retry.setTextColor(Color.parseColor("#0f172a"));
        retry.setOnClickListener(v -> loadServer());
        buttons.addView(retry, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button settings = new Button(this);
        settings.setText(R.string.server_settings);
        settings.setTextColor(Color.parseColor("#f59e0b"));
        settings.setBackgroundColor(Color.TRANSPARENT);
        settings.setOnClickListener(v -> showServerUrlDialog());
        buttons.addView(settings, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        box.addView(buttons);
        overlayButtons = buttons;

        overlay = box;
        root.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        showLoading();
    }

    private void showLoading() {
        overlaySpinner.setVisibility(View.VISIBLE);
        overlayTitle.setText(R.string.loading_title);
        overlayMessage.setText(R.string.standalone_hint);
        overlayButtons.setVisibility(View.GONE);
        overlay.setVisibility(View.VISIBLE);
    }

    private void showError(int messageRes) {
        pageLoadFailed = true;
        progressBar.setVisibility(View.GONE);
        overlaySpinner.setVisibility(View.GONE);
        overlayTitle.setText(R.string.error_title);
        overlayMessage.setText(messageRes);
        overlayButtons.setVisibility(View.VISIBLE);
        overlay.setVisibility(View.VISIBLE);
    }

    private void hideOverlay() {
        overlay.setVisibility(View.GONE);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------------ WebView

    private void configureWebView(WebView wv) {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);

        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setGeolocationEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setSafeBrowsingEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(false);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setUserAgentString(s.getUserAgentString() + " ChabadCrmNative/" + BuildConfig.VERSION_NAME);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(wv, false);

        wv.addJavascriptInterface(new NativeBridge(), BRIDGE_NAME);
        wv.setWebViewClient(new CrmWebViewClient());
        wv.setWebChromeClient(new CrmChromeClient());
        wv.setDownloadListener(this::handleDownload);
    }

    private void loadServer() {
        if (webView == null) return;
        pageLoadFailed = false;
        if (!isOnline()) {
            showError(R.string.error_offline);
            return;
        }
        showLoading();
        webView.loadUrl(serverUrl);
    }

    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return true;
        NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private final class CrmWebViewClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (!request.isForMainFrame()) return false;
            if (isTrusted(uri)) return false;

            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            switch (scheme) {
                case "http":
                case "https":
                case "tel":
                case "sms":
                case "smsto":
                case "mailto":
                case "whatsapp":
                case "geo":
                    openExternal(uri);
                    break;
                default:
                    break; // intent:, file:, javascript: וכו' – נחסמים.
            }
            return true;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            pageLoadFailed = false;
            trustedPage = url != null && isTrusted(Uri.parse(url));
            progressBar.setProgress(0);
            progressBar.setVisibility(View.VISIBLE);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            progressBar.setVisibility(View.GONE);
            if (!pageLoadFailed) hideOverlay();
            CookieManager.getInstance().flush();
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame()) showError(R.string.error_generic);
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                        WebResourceResponse response) {
            if (request.isForMainFrame() && response.getStatusCode() >= 500) {
                showError(R.string.error_server);
            }
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            handler.cancel(); // לעולם לא ממשיכים עם תעודה לא תקינה.
            showError(R.string.error_ssl);
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            // תהליך ה-WebView קרס/נהרג: חייבים להשמיד את ה-WebView וליצור מחדש.
            if (view == webView) {
                destroyWebView();
                recreate();
            }
            return true;
        }
    }

    private final class CrmChromeClient extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            progressBar.setProgress(newProgress);
            if (newProgress >= 100) progressBar.setVisibility(View.GONE);
            if (newProgress >= 60 && !pageLoadFailed) hideOverlay();
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                         FileChooserParams params) {
            if (filePathCallback != null) filePathCallback.onReceiveValue(null);
            filePathCallback = callback;
            try {
                Intent intent = params.createIntent();
                fileChooserLauncher.launch(
                        Intent.createChooser(intent, getString(R.string.file_chooser_title)));
            } catch (ActivityNotFoundException | SecurityException e) {
                filePathCallback = null;
                callback.onReceiveValue(null);
            }
            return true;
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(String origin,
                                                       GeolocationPermissions.Callback callback) {
            if (!isTrustedOrigin(origin)) {
                callback.invoke(origin, false, false);
                return;
            }
            if (hasLocationPermission()) {
                callback.invoke(origin, true, false);
                return;
            }
            if (pendingGeoCallback != null) pendingGeoCallback.invoke(pendingGeoOrigin, false, false);
            pendingGeoCallback = callback;
            pendingGeoOrigin = origin;
            locationPermissionLauncher.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION});
        }

        @Override
        public void onGeolocationPermissionsHidePrompt() {
            pendingGeoCallback = null;
            pendingGeoOrigin = null;
        }
    }

    // ------------------------------------------------------------------ downloads / external

    private void handleDownload(String url, String userAgent, String contentDisposition,
                                String mimeType, long contentLength) {
        Uri uri = Uri.parse(url);
        if ("blob".equalsIgnoreCase(uri.getScheme())) {
            // קבצים שנוצרו בדף עצמו (גיבוי, ייצוא): נשלפים מהדף ונשמרים דרך הגשר.
            downloadBlob(url, URLUtil.guessFileName(url, contentDisposition, mimeType), mimeType);
            return;
        }
        // עוגיות נשלחות רק לדומיין המוגדר; data: אינו נתמך.
        if (!isTrusted(uri)) {
            if ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) {
                openExternal(uri);
            } else {
                toast(getString(R.string.download_unsupported));
            }
            return;
        }
        try {
            String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
            DownloadManager.Request req = new DownloadManager.Request(uri);
            if (mimeType != null && !mimeType.isEmpty()) req.setMimeType(mimeType);
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) req.addRequestHeader("Cookie", cookie);
            if (userAgent != null) req.addRequestHeader("User-Agent", userAgent);
            req.setTitle(fileName);
            req.setDescription(getString(R.string.download_notification));
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                req.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, fileName);
            } else {
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            }

            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) throw new IllegalStateException("DownloadManager unavailable");
            dm.enqueue(req);
            toast(getString(R.string.download_started, fileName));
        } catch (Exception e) {
            toast(getString(R.string.download_error));
        }
    }

    private void downloadBlob(String blobUrl, String fileName, String mime) {
        if (!trustedPage || webView == null) {
            toast(getString(R.string.download_unsupported));
            return;
        }
        String type = (mime == null || mime.isEmpty()) ? "application/octet-stream" : mime;
        String js = "(function(){var n=" + JSONObject.quote(fileName) + ",t=" + JSONObject.quote(type) + ";"
                + "function f(b){" + BRIDGE_NAME + ".saveBase64File(n,t,b);}"
                + "try{var x=new XMLHttpRequest();x.open('GET'," + JSONObject.quote(blobUrl) + ",true);"
                + "x.responseType='blob';"
                + "x.onload=function(){var r=new FileReader();"
                + "r.onloadend=function(){var s=String(r.result||'');f(s.substring(s.indexOf(',')+1));};"
                + "r.onerror=function(){f('');};r.readAsDataURL(x.response);};"
                + "x.onerror=function(){f('');};x.send();}catch(e){f('');}})();";
        webView.evaluateJavascript(js, null);
    }

    private static String sanitizeFileName(String name) {
        String n = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\u0000-\\u001f]", "_").trim();
        while (n.startsWith(".")) n = n.substring(1);
        if (n.length() > 120) n = n.substring(n.length() - 120);
        return n.isEmpty() ? "download" : n;
    }

    /** שומר לתיקיית ההורדות (MediaStore ב-Android 10+, אחרת לתיקיית ההורדות של האפליקציה). */
    private String saveToDownloads(String name, String mime, byte[] bytes) throws IOException {
        String safe = sanitizeFileName(name);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver cr = getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, safe);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri item = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (item == null) throw new IOException("insert failed");
            try (OutputStream os = cr.openOutputStream(item)) {
                if (os == null) throw new IOException("no stream");
                os.write(bytes);
            } catch (IOException e) {
                cr.delete(item, null, null);
                throw e;
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(item, done, null, null);
            return safe;
        }
        File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) throw new IOException("no storage");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File out = new File(dir, safe);
        int dot = safe.lastIndexOf('.');
        String base = dot > 0 ? safe.substring(0, dot) : safe;
        String ext = dot > 0 ? safe.substring(dot) : "";
        for (int i = 1; out.exists() && i < 1000; i++) out = new File(dir, base + " (" + i + ")" + ext);
        try (FileOutputStream fos = new FileOutputStream(out)) {
            fos.write(bytes);
        }
        return out.getName();
    }

    private void openExternal(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri)
                    .addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (ActivityNotFoundException e) {
            boolean web = "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme());
            toast(getString(web ? R.string.browser_missing : R.string.external_app_missing)
                    + (web ? "\n" + uri.getHost() : ""));
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------------ permissions

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    // ------------------------------------------------------------------ server URL

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
    }

    private String getSavedServerUrl() {
        String saved = prefs().getString(KEY_SERVER_URL, null);
        String normalized = saved == null ? null : normalizeUrl(saved);
        return normalized != null ? normalized : BuildConfig.DEFAULT_SERVER_URL;
    }

    private void setServerUrl(String url) {
        serverUrl = url;
        serverHost = Uri.parse(url).getHost();
    }

    /** מחזיר כתובת HTTPS תקינה, או null. כתובת ללא סכמה מקבלת https://. */
    private static String normalizeUrl(String input) {
        if (input == null) return null;
        String value = input.trim();
        if (value.isEmpty()) return null;
        if (!value.contains("://")) value = "https://" + value;
        Uri uri = Uri.parse(value);
        if (!"https".equalsIgnoreCase(uri.getScheme())) return null;
        if (uri.getHost() == null || uri.getHost().isEmpty()) return null;
        if (uri.getUserInfo() != null) return null;
        return uri.toString();
    }

    private boolean isTrusted(Uri uri) {
        return uri != null
                && "https".equalsIgnoreCase(uri.getScheme())
                && serverHost != null
                && serverHost.equalsIgnoreCase(uri.getHost());
    }

    private boolean isTrustedOrigin(String origin) {
        return origin != null && isTrusted(Uri.parse(origin));
    }

    private void showServerUrlDialog() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint(R.string.server_dialog_hint);
        input.setText(serverUrl);
        input.setSelection(input.getText().length());
        input.setTextDirection(View.TEXT_DIRECTION_LTR);

        LinearLayout container = new LinearLayout(this);
        container.setPadding(dp(20), dp(8), dp(20), 0);
        container.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.server_dialog_title)
                .setMessage(R.string.server_dialog_message)
                .setView(container)
                .setPositiveButton(R.string.save_and_load, null)
                .setNegativeButton(R.string.cancel, null)
                .setNeutralButton(R.string.reset_default, null)
                .create();
        dialog.show();

        // מאזינים מותאמים, כדי שהדיאלוג לא ייסגר על קלט לא תקין.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String normalized = normalizeUrl(input.getText().toString());
            if (normalized == null) {
                toast(getString(R.string.invalid_url));
                return;
            }
            applyServerUrl(normalized, true);
            dialog.dismiss();
        });
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            applyServerUrl(BuildConfig.DEFAULT_SERVER_URL, false);
            dialog.dismiss();
        });
    }

    private void applyServerUrl(String url, boolean persist) {
        SharedPreferences.Editor editor = prefs().edit();
        if (persist) editor.putString(KEY_SERVER_URL, url); else editor.remove(KEY_SERVER_URL);
        editor.apply();
        setServerUrl(url);
        if (webView != null) webView.clearHistory();
        loadServer();
    }

    // ------------------------------------------------------------------ JS bridge

    /** חשוף לדפי JavaScript – רק פעולות בטוחות, ורק כשהדף הנוכחי הוא הדומיין המוגדר. */
    private final class NativeBridge {
        @JavascriptInterface
        public boolean isNativeAndroidApp() {
            return true;
        }

        @JavascriptInterface
        public String getAppVersion() {
            return BuildConfig.VERSION_NAME;
        }

        @JavascriptInterface
        public void showNativeToast(String message) {
            if (!trustedPage || message == null || message.isEmpty()) return;
            final String text = message.length() > 200 ? message.substring(0, 200) : message;
            runOnUiThread(() -> Toast.makeText(MainActivity.this, text, Toast.LENGTH_SHORT).show());
        }

        /** נקרא מהדף עם תוכן קובץ (base64) שנוצר בדף – גיבוי/ייצוא. */
        @JavascriptInterface
        public void saveBase64File(String fileName, String mime, String base64) {
            if (!trustedPage) return;
            if (base64 == null || base64.isEmpty() || base64.length() > 100_000_000) {
                runOnUiThread(() -> toast(getString(R.string.download_error)));
                return;
            }
            final String type = (mime == null || mime.isEmpty()) ? "application/octet-stream" : mime;
            try {
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                final String saved = saveToDownloads(fileName, type, bytes);
                runOnUiThread(() -> toast(getString(R.string.download_started, saved)));
            } catch (Exception e) {
                runOnUiThread(() -> toast(getString(R.string.download_error)));
            }
        }

        @JavascriptInterface
        public void openServerSettings() {
            if (!trustedPage) return;
            runOnUiThread(MainActivity.this::showServerUrlDialog);
        }
    }
}
