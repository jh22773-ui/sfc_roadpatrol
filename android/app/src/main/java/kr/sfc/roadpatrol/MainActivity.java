package kr.sfc.roadpatrol;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * GitHub Pages에 올라간 순찰 보고 웹앱을 WebView로 띄우는 껍데기 앱.
 *
 * 웹만으로 안 되는 것만 앱이 맡는다.
 * - 앨범 사진을 GPS 좌표가 지워지지 않은 원본으로 웹에 넘기기 (ACCESS_MEDIA_LOCATION + setRequireOriginal)
 * - 사진 여러 장 + 글을 밴드/카카오톡으로 공유 (WebView에는 navigator.share가 없음)
 * - 카메라 촬영, 위치 권한, 외부 링크 열기
 *
 * 웹은 User-Agent의 UA_MARK로 앱 안에서 열렸는지 판단한다.
 */
public class MainActivity extends Activity {

    static final String HOME_URL = "https://jh22773-ui.github.io/sfc_roadpatrol/";
    private static final String APP_HOST = "jh22773-ui.github.io";
    private static final String UA_MARK = "SFCRoadPatrolApp/1";
    private static final int MAX_PHOTOS = 5;
    private static final String KAKAOTALK = "com.kakao.talk";
    private static final String BAND = "com.nhn.android.band";

    private static final int REQ_PICK = 1;
    private static final int REQ_CAMERA = 2;
    private static final int REQ_PERM_LOCATION = 10;
    private static final int REQ_PERM_MEDIA = 11;

    private WebView webView;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private ValueCallback<Uri[]> fileCallback;
    private boolean pendingPickMultiple;
    private Uri cameraUri;

    private GeolocationPermissions.Callback geoCallback;
    private String geoOrigin;

    // 사진과 함께 공유한 글. 카카오톡은 사진이 있으면 글을 버리므로 돌아왔을 때 안내에 쓴다.
    private String pendingShareText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xFF1A73E8);
        clearCacheDir("shared");

        webView = new WebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setGeolocationEnabled(true);
        s.setUserAgentString(s.getUserAgentString() + " " + UA_MARK);

        webView.addJavascriptInterface(new AppBridge(), "SfcApp");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (APP_HOST.equalsIgnoreCase(uri.getHost())) return false;
                // 카카오맵, 밴드 등 외부 링크는 해당 앱이나 브라우저로 연다.
                openExternal(uri);
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                if (hasLocationPermission()) {
                    callback.invoke(origin, true, false);
                    return;
                }
                geoOrigin = origin;
                geoCallback = callback;
                requestPermissions(new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                }, REQ_PERM_LOCATION);
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                if (params.isCaptureEnabled()) {
                    openCamera();
                } else {
                    openPicker(params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                }
                return true;
            }
        });

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(HOME_URL);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 공유창에서 카카오톡을 골랐다가 돌아온 경우: 사진만 갔으므로 글 붙여넣기를 안내한다.
        String chosen = ShareChosenReceiver.lastChosenPackage;
        if (pendingShareText != null && chosen != null) {
            String text = pendingShareText;
            pendingShareText = null;
            ShareChosenReceiver.lastChosenPackage = null;
            if (KAKAOTALK.equals(chosen)) showKakaoTextGuide(text);
        }
    }

    private void showKakaoTextGuide(String text) {
        new AlertDialog.Builder(this)
                .setTitle("카카오톡은 사진만 전달됩니다")
                .setMessage("보고 글은 복사해 두었습니다.\n카카오톡 대화방 입력창을 길게 눌러 '붙여넣기' 하세요.")
                .setPositiveButton("글도 카톡으로 보내기", (d, w) -> {
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType("text/plain");
                    i.putExtra(Intent.EXTRA_TEXT, text);
                    i.setPackage(KAKAOTALK);
                    try {
                        startActivity(i);
                    } catch (ActivityNotFoundException e) {
                        toast("카카오톡을 열 수 없습니다.");
                    }
                })
                .setNegativeButton("확인", null)
                .show();
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        io.shutdown();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- 권한

    private boolean granted(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasLocationPermission() {
        return granted(Manifest.permission.ACCESS_FINE_LOCATION)
                || granted(Manifest.permission.ACCESS_COARSE_LOCATION);
    }

    private boolean hasMediaPermission() {
        if (Build.VERSION.SDK_INT >= 34) {
            // "일부 사진만 허용"도 허용으로 본다. (선택한 사진만 목록에 나옴)
            return granted(Manifest.permission.READ_MEDIA_IMAGES)
                    || granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            return granted(Manifest.permission.READ_MEDIA_IMAGES);
        }
        return granted(Manifest.permission.READ_EXTERNAL_STORAGE);
    }

    private String[] mediaPermissions() {
        if (Build.VERSION.SDK_INT >= 34) {
            return new String[]{
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                    Manifest.permission.ACCESS_MEDIA_LOCATION
            };
        }
        if (Build.VERSION.SDK_INT >= 33) {
            return new String[]{
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.ACCESS_MEDIA_LOCATION
            };
        }
        return new String[]{
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.ACCESS_MEDIA_LOCATION
        };
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_PERM_LOCATION) {
            if (geoCallback != null) {
                geoCallback.invoke(geoOrigin, hasLocationPermission(), false);
                geoCallback = null;
            }
        } else if (requestCode == REQ_PERM_MEDIA) {
            if (hasMediaPermission()) {
                launchPicker(pendingPickMultiple);
            } else {
                toast("사진을 고르려면 사진 접근 권한이 필요합니다.");
                deliverFiles(null);
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    // ---------------------------------------------------------------- 사진 선택 / 촬영

    private void openPicker(boolean multiple) {
        pendingPickMultiple = multiple;
        if (hasMediaPermission() && granted(Manifest.permission.ACCESS_MEDIA_LOCATION)) {
            launchPicker(multiple);
        } else {
            requestPermissions(mediaPermissions(), REQ_PERM_MEDIA);
        }
    }

    private void launchPicker(boolean multiple) {
        Intent i = new Intent(this, PhotoPickerActivity.class);
        i.putExtra(PhotoPickerActivity.EXTRA_MULTIPLE, multiple);
        i.putExtra(PhotoPickerActivity.EXTRA_MAX, MAX_PHOTOS);
        startActivityForResult(i, REQ_PICK);
    }

    private void openCamera() {
        try {
            File file = new File(freshCacheDir("camera"), "IMG_" + System.currentTimeMillis() + ".jpg");
            cameraUri = fileUri(file);
            Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
            i.setClipData(ClipData.newRawUri("", cameraUri));
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(i, REQ_CAMERA);
        } catch (ActivityNotFoundException e) {
            toast("카메라 앱을 열 수 없습니다.");
            deliverFiles(null);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_CAMERA) {
            deliverFiles(resultCode == RESULT_OK && cameraUri != null ? new Uri[]{cameraUri} : null);
        } else if (requestCode == REQ_PICK) {
            ArrayList<Uri> picked = (resultCode == RESULT_OK && data != null)
                    ? data.getParcelableArrayListExtra(PhotoPickerActivity.EXTRA_URIS)
                    : null;
            if (picked == null || picked.isEmpty()) {
                deliverFiles(null);
                return;
            }
            io.execute(() -> {
                Uri[] copies = copyOriginals(picked);
                runOnUiThread(() -> deliverFiles(copies));
            });
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    private void deliverFiles(Uri[] uris) {
        if (fileCallback != null) {
            fileCallback.onReceiveValue(uris);
            fileCallback = null;
        }
    }

    // 고른 사진을 GPS가 남아 있는 원본으로 캐시에 복사해 웹에 넘긴다.
    private Uri[] copyOriginals(ArrayList<Uri> sources) {
        File dir = freshCacheDir("picked");
        ArrayList<Uri> out = new ArrayList<>();
        int n = 0;
        for (Uri src : sources) {
            n++;
            File dest = new File(dir, n + "_" + displayName(src));
            try (InputStream in = openOriginal(src); OutputStream os = new FileOutputStream(dest)) {
                if (in == null) continue;
                byte[] buf = new byte[64 * 1024];
                int len;
                while ((len = in.read(buf)) > 0) os.write(buf, 0, len);
                out.add(fileUri(dest));
            } catch (IOException | SecurityException e) {
                // 읽을 수 없는 사진은 건너뛴다.
            }
        }
        return out.isEmpty() ? null : out.toArray(new Uri[0]);
    }

    private InputStream openOriginal(Uri src) throws IOException {
        try {
            // ACCESS_MEDIA_LOCATION이 있으면 위치 정보가 가려지지 않은 원본이 열린다.
            return getContentResolver().openInputStream(MediaStore.setRequireOriginal(src));
        } catch (SecurityException | UnsupportedOperationException e) {
            return getContentResolver().openInputStream(src);
        }
    }

    private String displayName(Uri uri) {
        String name = null;
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (RuntimeException ignored) {
        }
        if (name == null || name.isEmpty()) name = "photo.jpg";
        return name.replace('/', '_');
    }

    // ---------------------------------------------------------------- 공유 (웹에서 호출)

    private class AppBridge {
        /** 공유창을 띄운다. (1.0.1 이하와 같은 동작. 웹은 shareTo가 없는 구버전 앱에서 이걸 쓴다) */
        @JavascriptInterface
        public void share(String text, String imagesJson) {
            shareTo("", text, imagesJson);
        }

        /**
         * @param target     "band"면 공유창 없이 밴드 앱을 바로 연다. 그 밖의 값은 공유창.
         * @param text       공유할 글
         * @param imagesJson data URL(base64 JPEG) 문자열 배열의 JSON
         */
        @JavascriptInterface
        public void shareTo(String target, String text, String imagesJson) {
            final String targetPackage = "band".equals(target) ? BAND : null;
            ArrayList<Uri> uris = new ArrayList<>();
            try {
                JSONArray arr = new JSONArray(imagesJson == null ? "[]" : imagesJson);
                // 이전 공유를 받는 앱이 아직 파일을 읽고 있을 수 있으므로 매번 새 폴더에 쓴다.
                File dir = new File(new File(getCacheDir(), "shared"), String.valueOf(System.currentTimeMillis()));
                dir.mkdirs();
                for (int i = 0; i < arr.length(); i++) {
                    String data = arr.getString(i);
                    int comma = data.indexOf(',');
                    byte[] bytes = Base64.decode(comma >= 0 ? data.substring(comma + 1) : data, Base64.DEFAULT);
                    File f = new File(dir, "patrol_photo_" + (i + 1) + ".jpg");
                    try (OutputStream os = new FileOutputStream(f)) {
                        os.write(bytes);
                    }
                    uris.add(fileUri(f));
                }
            } catch (Exception e) {
                uris.clear(); // 사진 변환에 실패하면 글만 공유한다.
            }
            final String body = text == null ? "" : text;
            runOnUiThread(() -> startShare(body, uris, targetPackage));
        }
    }

    /** targetPackage가 있으면 그 앱을 바로 열고, 없거나 설치돼 있지 않으면 공유창을 띄운다. */
    private void startShare(String text, ArrayList<Uri> uris, String targetPackage) {
        Intent i;
        if (uris.isEmpty()) {
            i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
        } else if (uris.size() == 1) {
            i = new Intent(Intent.ACTION_SEND);
            i.setType("image/jpeg");
            i.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        } else {
            i = new Intent(Intent.ACTION_SEND_MULTIPLE);
            i.setType("image/jpeg");
            i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        }
        i.putExtra(Intent.EXTRA_TEXT, text);
        if (!uris.isEmpty()) {
            ClipData clip = ClipData.newRawUri("", uris.get(0));
            for (int k = 1; k < uris.size(); k++) clip.addItem(new ClipData.Item(uris.get(k)));
            i.setClipData(clip);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }

        if (targetPackage != null) {
            try {
                startActivity(new Intent(i).setPackage(targetPackage));
                pendingShareText = null;
                return;
            } catch (ActivityNotFoundException e) {
                toast("밴드 앱이 없어 공유할 앱을 선택합니다.");
            }
        }

        if (!uris.isEmpty()) {
            // 카카오톡처럼 사진이 있으면 글을 버리는 앱에서도 붙여넣을 수 있게 글을 복사해 둔다.
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("보고 내용", text));
            pendingShareText = text;
        } else {
            pendingShareText = null;
        }

        // 어떤 앱을 골랐는지 ShareChosenReceiver로 받는다. EXTRA_CHOSEN_COMPONENT가 채워지려면 mutable이어야 한다.
        ShareChosenReceiver.lastChosenPackage = null;
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
        PendingIntent chosenCallback = PendingIntent.getBroadcast(
                this, 0, new Intent(this, ShareChosenReceiver.class), flags);
        try {
            startActivity(Intent.createChooser(i, "보고 공유", chosenCallback.getIntentSender()));
        } catch (ActivityNotFoundException e) {
            toast("공유할 앱이 없습니다.");
        }
    }

    // ---------------------------------------------------------------- 공통

    private void openExternal(Uri uri) {
        try {
            Intent i = "intent".equals(uri.getScheme())
                    ? Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                    : new Intent(Intent.ACTION_VIEW, uri);
            i.addCategory(Intent.CATEGORY_BROWSABLE);
            i.setComponent(null);
            i.setSelector(null);
            startActivity(i);
        } catch (Exception e) {
            toast("링크를 열 수 없습니다.");
        }
    }

    private Uri fileUri(File file) {
        return FileProvider.getUriForFile(this, getPackageName() + ".files", file);
    }

    private File freshCacheDir(String name) {
        clearCacheDir(name);
        File dir = new File(getCacheDir(), name);
        dir.mkdirs();
        return dir;
    }

    private void clearCacheDir(String name) {
        deleteRecursive(new File(getCacheDir(), name));
    }

    private static void deleteRecursive(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) deleteRecursive(c);
        }
        f.delete();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
