package com.postpdf.downloader;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private EditText urlInput;
    private TextView status;
    private ProgressBar progress;
    private Button fetchButton, imageButton, pdfButton;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<String> mediaUrls = new ArrayList<>();

    private static final int STORAGE_REQUEST = 20;
    private static final int MAX_IMAGES = 30;
    private static final String UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36";
    private static final Pattern DISPLAY = Pattern.compile("\\\"display_url\\\"\\s*:\\s*\\\"(https:[^\\\"]+)\\\"");
    private static final Pattern OG = Pattern.compile("<meta[^>]+property=[\\\"']og:image[\\\"'][^>]+content=[\\\"']([^\\\"']+)[\\\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern CDN = Pattern.compile("https://[^\\\"'<>\\s]+?(?:cdninstagram\\.com|fbcdn\\.net)[^\\\"'<>\\s]+", Pattern.CASE_INSENSITIVE);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        handleSharedUrl(getIntent());
    }

    private int dp(int n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }

    private TextView makeText(String value, int sp, int color) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(sp);
        v.setTextColor(color);
        return v;
    }

    private Button makeButton(String label, boolean primary) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(14);
        b.setAllCaps(false);
        if (primary) {
            b.setTextColor(Color.WHITE);
            b.setBackgroundColor(Color.rgb(91,75,219));
        } else {
            b.setTextColor(Color.rgb(64,51,174));
            b.setBackgroundColor(Color.WHITE);
        }
        return b;
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(247,247,251));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22),dp(30),dp(22),dp(30));
        scroll.addView(root, new ScrollView.LayoutParams(-1,-2));

        TextView title = makeText("PostPDF Downloader",28,Color.rgb(23,23,27));
        title.setTypeface(null,1);
        root.addView(title);

        TextView sub = makeText("Paste a public Instagram post URL. Download the images or combine the whole carousel into one PDF.",15,Color.rgb(102,102,110));
        sub.setPadding(0,dp(8),0,dp(22));
        root.addView(sub);

        urlInput = new EditText(this);
        urlInput.setHint("https://www.instagram.com/p/...");
        urlInput.setTextSize(15);
        urlInput.setMinLines(2);
        urlInput.setMaxLines(3);
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        root.addView(urlInput,new LinearLayout.LayoutParams(-1,-2));

        fetchButton = makeButton("Fetch Post",true);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1,dp(52));
        fp.setMargins(0,dp(14),0,0);
        root.addView(fetchButton,fp);

        progress = new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1,dp(7));
        pp.setMargins(0,dp(18),0,0);
        root.addView(progress,pp);

        status = makeText("Ready",14,Color.rgb(102,102,110));
        status.setGravity(Gravity.CENTER_HORIZONTAL);
        status.setPadding(0,dp(12),0,dp(10));
        root.addView(status,new LinearLayout.LayoutParams(-1,-2));

        imageButton = makeButton("Download Images",false);
        pdfButton = makeButton("Download All as PDF",true);
        imageButton.setEnabled(false);
        pdfButton.setEnabled(false);

        LinearLayout.LayoutParams bp1 = new LinearLayout.LayoutParams(-1,dp(52));
        bp1.setMargins(0,dp(8),0,0);
        root.addView(imageButton,bp1);
        LinearLayout.LayoutParams bp2 = new LinearLayout.LayoutParams(-1,dp(52));
        bp2.setMargins(0,dp(10),0,0);
        root.addView(pdfButton,bp2);

        TextView note = makeText("Public posts only. The app does not bypass private accounts or login protection.",12,Color.rgb(102,102,110));
        note.setPadding(0,dp(18),0,0);
        root.addView(note);

        setContentView(scroll);

        fetchButton.setOnClickListener(v -> fetchPost());
        imageButton.setOnClickListener(v -> downloadImages());
        pdfButton.setOnClickListener(v -> downloadPdf());
    }

    private void handleSharedUrl(Intent intent) {
        if (intent != null && Intent.ACTION_SEND.equals(intent.getAction()) && "text/plain".equals(intent.getType())) {
            String shared = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (shared != null) {
                Matcher m = Pattern.compile("https?://(?:www\\.)?instagram\\.com/[^\\s]+",Pattern.CASE_INSENSITIVE).matcher(shared);
                urlInput.setText(m.find() ? m.group() : shared.trim());
            }
        }
    }

    private String normalizePostUrl(String raw) {
        if (raw == null) return null;
        Matcher m = Pattern.compile("https?://(?:www\\.)?instagram\\.com/(?:p|reel|tv)/[A-Za-z0-9_-]+/?",Pattern.CASE_INSENSITIVE).matcher(raw);
        if (!m.find()) return null;
        String u = m.group();
        return u.endsWith("/") ? u : u + "/";
    }

    private void fetchPost() {
        final String postUrl = normalizePostUrl(urlInput.getText().toString().trim());
        if (postUrl == null) {
            alert("Invalid URL","Paste a valid Instagram post, reel, or carousel URL.");
            return;
        }
        setBusy(true,"Reading public post…");
        executor.execute(() -> {
            try {
                List<String> found = extractMediaUrls(postUrl);
                runOnUiThread(() -> {
                    mediaUrls.clear();
                    mediaUrls.addAll(found);
                    setBusy(false, found.isEmpty() ? "No downloadable images detected" : found.size() + " image" + (found.size()==1 ? "" : "s") + " detected");
                    imageButton.setEnabled(!found.isEmpty());
                    pdfButton.setEnabled(!found.isEmpty());
                    if (found.isEmpty()) alert("No images found","Instagram may require login for this post, or its page format may have changed.");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setBusy(false,"Could not read this post");
                    alert("Fetch failed",cleanError(e));
                });
            }
        });
    }

    private List<String> extractMediaUrls(String postUrl) throws Exception {
        HttpURLConnection c = (HttpURLConnection)new URL(postUrl).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(20000);
        c.setReadTimeout(25000);
        c.setRequestProperty("User-Agent",UA);
        c.setRequestProperty("Accept","text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        c.setRequestProperty("Accept-Language","en-US,en;q=0.9");
        int code = c.getResponseCode();
        if (code < 200 || code >= 400) throw new Exception("Instagram returned HTTP " + code);

        StringBuilder html = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(),StandardCharsets.UTF_8))) {
            char[] buf = new char[16384];
            int n;
            while ((n=br.read(buf))>0 && html.length() < 12*1024*1024) html.append(buf,0,n);
        } finally {
            c.disconnect();
        }

        String page = html.toString().replace("\\/","/").replace("&amp;","&").replace("\\u0026","&");
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        Matcher d = DISPLAY.matcher(page);
        while (d.find() && urls.size()<MAX_IMAGES*4) urls.add(cleanUrl(d.group(1)));
        Matcher x = CDN.matcher(page);
        while (x.find() && urls.size()<MAX_IMAGES*6) urls.add(cleanUrl(x.group()));
        Matcher o = OG.matcher(page);
        while (o.find() && urls.size()<MAX_IMAGES*6) urls.add(cleanUrl(o.group(1)));

        Map<String,String> unique = new LinkedHashMap<>();
        for (String s: urls) {
            if (!looksLikePostImage(s)) continue;
            String key = mediaKey(s);
            if (!unique.containsKey(key)) unique.put(key,s);
            if (unique.size()>=MAX_IMAGES) break;
        }
        return new ArrayList<>(unique.values());
    }

    private String cleanUrl(String s) {
        if (s == null) return "";
        return s.replace("\\/","/").replace("&amp;","&").replace("\\u0026","&").replace("\\u003d","=");
    }

    private boolean looksLikePostImage(String url) {
        String l = url.toLowerCase(Locale.US);
        if (!l.startsWith("http")) return false;
        if (!(l.contains("cdninstagram.com") || l.contains("fbcdn.net") || l.contains("scontent-"))) return false;
        if (l.contains("profile") || l.contains("t51.2885-19") || l.contains("s150x150")) return false;
        return l.contains(".jpg") || l.contains(".jpeg") || l.contains(".png") || l.contains(".webp") || l.contains("t51.29350-15") || l.contains("t51.2885-15") || l.contains("t51.82787-15");
    }

    private String mediaKey(String url) {
        try {
            URI u = URI.create(url);
            return u.getPath() == null ? url : u.getPath();
        } catch (Exception e) {
            int q = url.indexOf('?');
            return q>=0 ? url.substring(0,q) : url;
        }
    }

    private byte[] downloadBytes(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection)new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent",UA);
        c.setRequestProperty("Referer","https://www.instagram.com/");
        int code = c.getResponseCode();
        if (code < 200 || code >= 400) throw new Exception("Image request returned HTTP " + code);
        try (InputStream in = new BufferedInputStream(c.getInputStream()); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[32768];
            int n;
            while ((n=in.read(buf))>0) out.write(buf,0,n);
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    private boolean ensureStoragePermission() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},STORAGE_REQUEST);
            Toast.makeText(this,"Allow storage access, then tap download again.",Toast.LENGTH_LONG).show();
            return false;
        }
        return true;
    }

    private void downloadImages() {
        if (mediaUrls.isEmpty() || !ensureStoragePermission()) return;
        setBusy(true,"Downloading images…");
        executor.execute(() -> {
            int ok = 0;
            String stamp = stamp();
            for (int i=0;i<mediaUrls.size();i++) {
                try {
                    saveBinary("PostPDF_"+stamp+"_"+String.format(Locale.US,"%02d",i+1)+".jpg","image/jpeg",downloadBytes(mediaUrls.get(i)));
                    ok++;
                } catch (Exception ignored) {}
                final int done=i+1;
                runOnUiThread(() -> progress.setProgress(done*100/mediaUrls.size()));
            }
            final int saved=ok;
            runOnUiThread(() -> setBusy(false,saved+" image(s) saved to Downloads/PostPDF"));
        });
    }

    private void downloadPdf() {
        if (mediaUrls.isEmpty() || !ensureStoragePermission()) return;
        setBusy(true,"Creating PDF…");
        executor.execute(() -> {
            PdfDocument pdf = new PdfDocument();
            int added=0;
            try {
                for (int i=0;i<mediaUrls.size();i++) {
                    Bitmap bmp = decodeBitmap(downloadBytes(mediaUrls.get(i)));
                    if (bmp != null) {
                        addPage(pdf,bmp,added+1);
                        bmp.recycle();
                        added++;
                    }
                    final int done=i+1;
                    runOnUiThread(() -> {
                        progress.setProgress(done*100/mediaUrls.size());
                        status.setText("Creating PDF — "+done+"/"+mediaUrls.size());
                    });
                }
                if (added==0) throw new Exception("No image could be added to the PDF.");
                String name="PostPDF_"+stamp()+".pdf";
                Uri uri=savePdf(name,pdf);
                final int pages=added;
                runOnUiThread(() -> {
                    setBusy(false,pages+" page PDF saved to Downloads/PostPDF");
                    showDone(uri,name,pages);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setBusy(false,"PDF creation failed");
                    alert("Could not create PDF",cleanError(e));
                });
            } finally {
                pdf.close();
            }
        });
    }

    private Bitmap decodeBitmap(byte[] data) {
        BitmapFactory.Options b = new BitmapFactory.Options();
        b.inJustDecodeBounds=true;
        BitmapFactory.decodeByteArray(data,0,data.length,b);
        int max=Math.max(b.outWidth,b.outHeight), sample=1;
        while (max/sample>2400) sample*=2;
        BitmapFactory.Options o=new BitmapFactory.Options();
        o.inSampleSize=sample;
        o.inPreferredConfig=Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeByteArray(data,0,data.length,o);
    }

    private void addPage(PdfDocument pdf, Bitmap bitmap, int number) {
        int w=1240,h=1754,m=52;
        PdfDocument.Page page=pdf.startPage(new PdfDocument.PageInfo.Builder(w,h,number).create());
        Canvas canvas=page.getCanvas();
        canvas.drawColor(Color.WHITE);
        float scale=Math.min((w-m*2f)/bitmap.getWidth(),(h-m*2f)/bitmap.getHeight());
        float dw=bitmap.getWidth()*scale, dh=bitmap.getHeight()*scale;
        float left=(w-dw)/2f, top=(h-dh)/2f;
        canvas.drawBitmap(bitmap,null,new RectF(left,top,left+dw,top+dh),new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG));
        pdf.finishPage(page);
    }

    private Uri savePdf(String name, PdfDocument pdf) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues v=new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME,name);
            v.put(MediaStore.MediaColumns.MIME_TYPE,"application/pdf");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/PostPDF");
            v.put(MediaStore.MediaColumns.IS_PENDING,1);
            ContentResolver r=getContentResolver();
            Uri uri=r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v);
            if (uri==null) throw new Exception("Android could not create the PDF file.");
            try (OutputStream out=r.openOutputStream(uri)) {
                if (out==null) throw new Exception("Could not open PDF output.");
                pdf.writeTo(out);
            }
            v.clear();
            v.put(MediaStore.MediaColumns.IS_PENDING,0);
            r.update(uri,v,null,null);
            return uri;
        }
        File dir=new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),"PostPDF");
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("Could not create Downloads/PostPDF.");
        File file=new File(dir,name);
        try (OutputStream out=new FileOutputStream(file)) { pdf.writeTo(out); }
        return Uri.fromFile(file);
    }

    private Uri saveBinary(String name,String mime,byte[] data) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues v=new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME,name);
            v.put(MediaStore.MediaColumns.MIME_TYPE,mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/PostPDF");
            ContentResolver r=getContentResolver();
            Uri uri=r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v);
            if (uri==null) throw new Exception("Could not create output file.");
            try (OutputStream out=r.openOutputStream(uri)) {
                if (out==null) throw new Exception("Could not open output file.");
                out.write(data);
            }
            return uri;
        }
        File dir=new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),"PostPDF");
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("Could not create Downloads/PostPDF.");
        File file=new File(dir,name);
        try (OutputStream out=new FileOutputStream(file)) { out.write(data); }
        return Uri.fromFile(file);
    }

    private void showDone(Uri uri,String name,int pages) {
        AlertDialog.Builder b=new AlertDialog.Builder(this)
                .setTitle("PDF ready")
                .setMessage(name+"\n"+pages+" page"+(pages==1?"":"s")+"\nSaved in Downloads/PostPDF")
                .setNegativeButton("Done",null);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            b.setPositiveButton("Open",(d,w) -> openPdf(uri));
            b.setNeutralButton("Share",(d,w) -> sharePdf(uri));
        }
        b.show();
    }

    private void openPdf(Uri uri) {
        try {
            Intent i=new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri,"application/pdf");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this,"No PDF viewer found.",Toast.LENGTH_LONG).show();
        }
    }

    private void sharePdf(Uri uri) {
        Intent i=new Intent(Intent.ACTION_SEND);
        i.setType("application/pdf");
        i.putExtra(Intent.EXTRA_STREAM,uri);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i,"Share PDF"));
    }

    private String stamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new Date());
    }

    private void setBusy(boolean busy,String message) {
        progress.setVisibility(busy?View.VISIBLE:View.GONE);
        if (busy) progress.setProgress(5);
        status.setText(message);
        fetchButton.setEnabled(!busy);
        imageButton.setEnabled(!busy && !mediaUrls.isEmpty());
        pdfButton.setEnabled(!busy && !mediaUrls.isEmpty());
    }

    private void alert(String title,String message) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK",null).show();
    }

    private String cleanError(Exception e) {
        String m=e.getMessage();
        return m==null || m.trim().isEmpty() ? e.getClass().getSimpleName() : m;
    }

    @Override protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
