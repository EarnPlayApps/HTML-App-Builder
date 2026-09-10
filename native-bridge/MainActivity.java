package com.apkstudio.allconvert;

import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.getcapacitor.BridgeActivity;
import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.MobileAds;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.IOException;

public class MainActivity extends BridgeActivity {
    private File temp;
    private FileOutputStream out;
    private String mode;
    private String name;
    private String mime;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        MobileAds.initialize(this, status -> {});

        WebView web = getBridge().getWebView();
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setJavaScriptEnabled(true);
        web.addJavascriptInterface(new FileBridge(), "AndroidFileBridge");

        ViewGroup old = (ViewGroup) web.getParent();
        if (old != null) old.removeView(web);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutParams(new ViewGroup.LayoutParams(-1, -1));
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1f));

        AdView ad = new AdView(this);
        ad.setAdUnitId("ca-app-pub-9940728659432865/7572128943");
        int width = Math.max(1, (int) (getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density));
        ad.setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(this, width));
        ad.setVisibility(View.GONE);
        ad.setAdListener(new AdListener() {
            @Override public void onAdLoaded() {
                runOnUiThread(() -> ad.setVisibility(View.VISIBLE));
            }
            @Override public void onAdFailedToLoad(com.google.android.gms.ads.LoadAdError error) {
                runOnUiThread(() -> ad.setVisibility(View.GONE));
            }
        });
        root.addView(ad, new LinearLayout.LayoutParams(-1, -2));

        setContentView(root);
        ad.loadAd(new AdRequest.Builder().build());
    }

    private void toast(String text) {
        runOnUiThread(() -> Toast.makeText(this, text, Toast.LENGTH_SHORT).show());
    }

    private final class FileBridge {
        @JavascriptInterface
        public synchronized void beginResult(String m, String n, String mm) throws Exception {
            mode = m;
            name = (n == null ? "converted-file" : n).replaceAll("[\\\\/:*?\"<>|]", "_");
            mime = (mm == null || mm.isEmpty()) ? "application/octet-stream" : mm;
            if (temp != null) temp.delete();
            temp = new File(getCacheDir(), "allconvert_result_" + System.currentTimeMillis());
            out = new FileOutputStream(temp);
        }

        @JavascriptInterface
        public synchronized void writeResultChunk(String chunk) throws Exception {
            if (out == null) throw new IOException("Result stream not open");
            if (chunk == null || chunk.isEmpty()) return;
            out.write(Base64.decode(chunk, Base64.DEFAULT));
        }

        @JavascriptInterface
        public synchronized void finishResult() throws Exception {
            if (out != null) {
                out.close();
                out = null;
            }
            if ("download".equals(mode) || "save".equals(mode)) {
                saveToDownloads();
            } else {
                final String action = mode;
                runOnUiThread(() -> {
                    try {
                        shareOrOpen(action);
                    } catch (Exception e) {
                        toast("Operasi fail gagal: " + e.getMessage());
                    }
                });
            }
        }

        private void saveToDownloads() throws Exception {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, name);
                values.put(MediaStore.Downloads.MIME_TYPE, mime);
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/All Convert");
                values.put(MediaStore.Downloads.IS_PENDING, 1);
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IOException("Downloads insert gagal");
                try {
                    try (FileInputStream in = new FileInputStream(temp); OutputStream dst = getContentResolver().openOutputStream(uri)) {
                        if (dst == null) throw new IOException("Output stream gagal");
                        byte[] buffer = new byte[65536];
                        int count;
                        while ((count = in.read(buffer)) > 0) dst.write(buffer, 0, count);
                    }
                    values.clear();
                    values.put(MediaStore.Downloads.IS_PENDING, 0);
                    getContentResolver().update(uri, values, null, null);
                } catch (Exception e) {
                    getContentResolver().delete(uri, null, null);
                    throw e;
                }
            } else {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists() && !dir.mkdirs()) throw new IOException("Downloads gagal");
                File dst = new File(dir, name);
                try (FileInputStream in = new FileInputStream(temp); FileOutputStream fileOut = new FileOutputStream(dst)) {
                    byte[] buffer = new byte[65536];
                    int count;
                    while ((count = in.read(buffer)) > 0) fileOut.write(buffer, 0, count);
                }
            }
            temp.delete();
            toast("Fail disimpan dalam Downloads/All Convert");
        }

        private void shareOrOpen(String action) throws Exception {
            Uri uri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".fileprovider", temp);
            Intent intent = "share".equals(action) ? new Intent(Intent.ACTION_SEND) : new Intent(Intent.ACTION_VIEW);
            if ("share".equals(action)) {
                intent.setType(mime);
                intent.putExtra(Intent.EXTRA_STREAM, uri);
            } else {
                intent.setDataAndType(uri, mime);
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "Buka dengan"));
        }
    }
}
