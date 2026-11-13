package com.smeta.pro2;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.ContentResolver;
import android.os.Environment;
import android.database.Cursor;
import android.provider.DocumentsContract;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.print.PrintManager;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import java.net.URLEncoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Parallel Smeta PRO with the export/share functions ported from
 * OboiSmeta (v1.9), adapted to Smeta's JSON payload.
 *
 * Why PDF/Excel did not work before: Smeta's JS calls
 * AndroidBridge.generatePdf / generateXlsx / sharePdf / shareXlsx,
 * but the first parallel wrapper had NO AndroidBridge at all, so
 * PDF fell back to window.print() (does nothing in a WebView) and
 * Excel fell back to a toast. Now the bridge exists, exactly like
 * in Oboi: generate* saves via ACTION_CREATE_DOCUMENT (user picks
 * a place), share* writes to cache/shared and opens ACTION_SEND
 * (user picks a contact in WhatsApp/Telegram/etc.), and text is
 * shared via ACTION_SEND text/plain, as in Oboi's openTextModal.
 */
public class MainActivity extends Activity {
    private WebView webView;
    private static final int REQ_CREATE_DOC = 1001;
    private static final int REQ_FILE_CHOOSER = 1003;
    private android.webkit.ValueCallback<android.net.Uri[]> filePathCallback;
    private static final int REQ_OPEN_TREE = 1002;
    private byte[] pendingSaveBytes;
    private String pendingSaveName;
    private String pendingBookText;
    private boolean pageLoaded;

    // Книга, открытая тапом по .smetabook: читаем только этот URI,
    // текст отдаём в JS, где он строго проверяется как книга
    // (importBookObject) и показывается только в предпросмотре —
    // в каталог ничего не добавляется без кнопки «Сохранить».
    private void captureBookIntent(Intent intent) {
        try {
            if (intent == null) return;
            // VIEW — тап по файлу; SEND со stream — некоторые проводники
            // и мессенджеры отдают файл именно так («Поделиться» → app).
            Uri data = intent.getData();
            if (data == null && Intent.ACTION_SEND.equals(intent.getAction())) {
                android.os.Parcelable extra = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (extra instanceof Uri) data = (Uri) extra;
            }
            if (data == null) return;
            if (!Intent.ACTION_VIEW.equals(intent.getAction()) && !Intent.ACTION_SEND.equals(intent.getAction())) return;
            java.io.InputStream in = null;
            try {
                // file:// напрямую может быть недоступен без доступа
                // ко всем файлам — тогда пробуем через ContentResolver.
                try {
                    if ("file".equals(data.getScheme())) in = new java.io.FileInputStream(new File(data.getPath()));
                    else in = getContentResolver().openInputStream(data);
                } catch (Exception e) {
                    try { in = getContentResolver().openInputStream(data); } catch (Exception ignored) {}
                }
                if (in == null) {
                    Toast.makeText(this, "Не удалось прочитать файл книги", Toast.LENGTH_LONG).show();
                    return;
                }
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                String text = bos.toString("UTF-8");
                if (text != null && text.length() > 0 && text.length() < 5000000) pendingBookText = text;
                else Toast.makeText(this, "Файл книги пустой или слишком большой", Toast.LENGTH_LONG).show();
            } finally {
                if (in != null) try { in.close(); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            // Раньше ошибка здесь молча глоталась — и программа просто
            // открывалась в меню без предпросмотра. Теперь — тост.
            try { Toast.makeText(this, "Не удалось прочитать файл книги", Toast.LENGTH_LONG).show(); } catch (Exception ignored) {}
        }
    }
    private void deliverBookToWebView() {
        if (!pageLoaded || webView == null || pendingBookText == null) return;
        final String text = pendingBookText;
        pendingBookText = null;
        runOnUiThread(new Runnable() { @Override public void run() {
            if (webView != null) webView.evaluateJavascript("window.__bookFileOpened && window.__bookFileOpened(" + JSONObject.quote(text) + ")", null);
        }});
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        captureBookIntent(getIntent());
        setContentView(R.layout.activity_main);
        webView = findViewById(R.id.webview);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                pageLoaded = true;
                deliverBookToWebView();
            }
        });
        // Выбор файла для <input type=file> (импорт книги и импорт
        // данных): без этого обработчика WebView просто игнорирует
        // нажатие на скрытый input — окно закрывалось, а выбор файла
        // не открывался вообще. Это и была причина: JS вызывал
        // importBookFile.click(), а Java некуда было передать выбор.
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, android.webkit.ValueCallback<android.net.Uri[]> callback, FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/vnd.smeta.book", "application/json", "application/octet-stream", "text/plain"});
                try { startActivityForResult(Intent.createChooser(i, "Выбрать файл книги"), REQ_FILE_CHOOSER); }
                catch (Exception e) { filePathCallback = null; Toast.makeText(MainActivity.this, "Не удалось открыть выбор файла", Toast.LENGTH_LONG).show(); return false; }
                return true;
            }
        });
        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        captureBookIntent(intent);
        deliverBookToWebView();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE_CHOOSER) {
            if (filePathCallback != null) {
                android.net.Uri[] res = null;
                if (resultCode == RESULT_OK && data != null) {
                    try { res = WebChromeClient.FileChooserParams.parseResult(resultCode, data); } catch (Exception ignored) {}
                }
                filePathCallback.onReceiveValue(res);
                filePathCallback = null;
            }
        }
        if (requestCode == REQ_CREATE_DOC && resultCode == RESULT_OK && data != null && data.getData() != null && pendingSaveBytes != null) {
            try {
                OutputStream os = getContentResolver().openOutputStream(data.getData());
                os.write(pendingSaveBytes); os.close();
                Toast.makeText(this, "Сохранено: " + pendingSaveName, Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, "Не удалось сохранить файл: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
            pendingSaveBytes = null; pendingSaveName = null;
        }
        if (requestCode == REQ_OPEN_TREE && data != null && data.getData() != null) {
            try {
                Uri treeUri = data.getData();
                int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                getContentResolver().takePersistableUriPermission(treeUri, flags);
                getSharedPreferences("smeta_folder", MODE_PRIVATE).edit().putString("tree_uri", treeUri.toString()).apply();
                final String js = "window.__folderPicked && window.__folderPicked(true)";
                runOnUiThread(new Runnable() { @Override public void run() { if (webView != null) webView.evaluateJavascript(js, null); } });
                Toast.makeText(this, "Папка для смет выбрана", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, "Не удалось сохранить доступ к папке: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == REQ_OPEN_TREE) {
            runOnUiThread(new Runnable() { @Override public void run() { if (webView != null) webView.evaluateJavascript("window.__folderPicked && window.__folderPicked(false)", null); } });
        }
    }

    // ---------- Папка "Смета PRO" автоматически в корне ----------
    // Пользователь просил: после подтверждения папка должна создаться
    // САМА в корне, без ручного создания в системном выборе папки.
    // На Android 11+ это возможно только с доступом ко всем файлам
    // (MANAGE_EXTERNAL_STORAGE). Поэтому: подтверждение -> если доступ
    // уже есть, папка создаётся сразу через File API; если нет -
    // открывается экран "Доступ ко всем файлам", а после возврата
    // папка создаётся автоматически в onResume, без повторного нажатия.
    private boolean isFileBackend() {
        return "file".equals(getSharedPreferences("smeta_folder", MODE_PRIVATE).getString("folder_backend", ""));
    }
    private java.io.File getFileFolder() {
        String path = getSharedPreferences("smeta_folder", MODE_PRIVATE).getString("folder_path", "");
        return path.isEmpty() ? null : new java.io.File(path);
    }
    private java.io.File fileForPath(String relPath) throws Exception {
        java.io.File base = getFileFolder(); if (base == null) throw new Exception("Папка не выбрана");
        java.io.File f = relPath == null || relPath.isEmpty() ? base : new java.io.File(base, relPath);
        String canonBase = base.getCanonicalPath(), canonF = f.getCanonicalPath();
        if (!canonF.equals(canonBase) && !canonF.startsWith(canonBase + java.io.File.separator)) throw new Exception("Недопустимый путь");
        return f;
    }
    private boolean tryCreateFileFolder() {
        try {
            java.io.File root = Environment.getExternalStorageDirectory();
            java.io.File dir = new java.io.File(root, "Смета PRO");
            if (!dir.exists() && !dir.mkdirs() && !dir.exists()) return false;
            getSharedPreferences("smeta_folder", MODE_PRIVATE).edit().putString("folder_backend", "file").putString("folder_path", dir.getAbsolutePath()).remove("pending_auto_create").apply();
            return true;
        } catch (Exception e) { return false; }
    }
    private boolean hasAllFilesAccess() {
        try { return android.os.Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager(); } catch (Exception e) { return false; }
    }
    @Override protected void onResume() {
        super.onResume();
        boolean pending = getSharedPreferences("smeta_folder", MODE_PRIVATE).getBoolean("pending_auto_create", false);
        if (pending && hasAllFilesAccess()) {
            if (tryCreateFileFolder()) {
                runOnUiThread(new Runnable() { @Override public void run() { if (webView != null) webView.evaluateJavascript("window.__folderAutoCreated && window.__folderAutoCreated()", null); } });
                Toast.makeText(this, "Папка «Смета PRO» создана в корне", Toast.LENGTH_LONG).show();
            }
        }
    }
    private String fileListJson(String relPath) {
        try {
            java.io.File dir = fileForPath(relPath);
            java.io.File[] files = dir.listFiles();
            JSONArray arr = new JSONArray();
            if (files != null) for (java.io.File f : files) {
                JSONObject e = new JSONObject(); e.put("name", f.getName()); e.put("dir", f.isDirectory()); e.put("mime", f.isDirectory() ? "vnd.android.document/directory" : mimeForName(f.getName())); e.put("size", f.isDirectory() ? 0 : f.length()); e.put("modified", f.lastModified());
                if (f.isDirectory()) {
                    int dc = 0, fc = 0; java.io.File[] kids = f.listFiles(); if (kids != null) for (java.io.File k : kids) { if (k.isDirectory()) dc++; else fc++; }
                    e.put("dirs", dc); e.put("files", fc);
                }
                arr.put(e);
            }
            JSONObject out = new JSONObject(); out.put("ok", true); out.put("path", relPath == null ? "" : relPath); out.put("rootName", "Смета PRO"); out.put("entries", arr); return out.toString();
        } catch (Exception e) { return okJson(false, e.getMessage()); }
    }
    private void copyFileRecursive(java.io.File src, java.io.File dst) throws Exception {
        if (src.isDirectory()) {
            dst.mkdirs();
            java.io.File[] kids = src.listFiles(); if (kids != null) for (java.io.File k : kids) copyFileRecursive(k, new java.io.File(dst, k.getName()));
        } else {
            java.io.InputStream in = new java.io.FileInputStream(src); java.io.OutputStream out = new java.io.FileOutputStream(dst);
            byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) > 0) out.write(buf, 0, n); in.close(); out.close();
        }
    }
    private void deleteFileRecursive(java.io.File f) throws Exception {
        if (f.isDirectory()) { java.io.File[] kids = f.listFiles(); if (kids != null) for (java.io.File k : kids) deleteFileRecursive(k); }
        if (!f.delete()) throw new Exception("Не удалось удалить: " + f.getName());
    }

    // ---------- Папка "Смета PRO" через SAF (Storage Access Framework) ----------
    // Android 11+ не разрешает приложению просто создать папку в корне
    // внутренней памяти. Правильный путь: один раз пользователь сам
    // выбирает/создаёт папку "Смета PRO" в системном выборе папки,
    // приложение запоминает разрешение на неё навсегда, а дальше
    // работает внутри неё как в своём файловом менеджере: список,
    // подпапки, копирование, перенос, удаление, сохранение экспорта.
    private Uri getTreeUri() {
        String s = getSharedPreferences("smeta_folder", MODE_PRIVATE).getString("tree_uri", null);
        return s == null ? null : Uri.parse(s);
    }
    private Uri findChildDoc(Uri parentDoc, String name) throws Exception {
        Uri tree = getTreeUri();
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parentDoc));
        Cursor c = getContentResolver().query(childrenUri, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
        try {
            if (c != null) while (c.moveToNext()) {
                if (name.equals(c.getString(1))) return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0));
            }
        } finally { if (c != null) c.close(); }
        return null;
    }
    private Uri docForPath(String relPath) throws Exception {
        Uri tree = getTreeUri(); if (tree == null) throw new Exception("Папка не выбрана");
        Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        if (relPath == null || relPath.isEmpty() || relPath.equals("/")) return doc;
        for (String part : relPath.split("/")) {
            if (part.isEmpty()) continue;
            Uri next = findChildDoc(doc, part);
            if (next == null) throw new Exception("Папка не найдена: " + part);
            doc = next;
        }
        return doc;
    }
    private String docDisplayName(Uri doc) {
        Cursor c = null;
        try {
            c = getContentResolver().query(doc, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {} finally { if (c != null) c.close(); }
        return "";
    }
    private String docMime(Uri doc) {
        Cursor c = null;
        try {
            c = getContentResolver().query(doc, new String[]{DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null);
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {} finally { if (c != null) c.close(); }
        return "application/octet-stream";
    }
    private String folderListJson(String relPath) {
        try {
            Uri doc = docForPath(relPath);
            Uri tree = getTreeUri();
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(doc));
            Cursor c = getContentResolver().query(childrenUri, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED}, null, null, null);
            JSONArray arr = new JSONArray();
            try {
                if (c != null) while (c.moveToNext()) {
                    JSONObject e = new JSONObject();
                    String docId = c.getString(0);
                    e.put("name", c.getString(1));
                    String mime = c.getString(2);
                    boolean isDir = "vnd.android.document/directory".equals(mime);
                    e.put("dir", isDir);
                    e.put("mime", mime == null ? "" : mime);
                    e.put("size", c.isNull(3) ? 0 : c.getLong(3));
                    e.put("modified", c.isNull(4) ? 0 : c.getLong(4));
                    if (isDir) {
                        int dc = 0, fc = 0;
                        try {
                            Uri subDoc = DocumentsContract.buildDocumentUriUsingTree(tree, docId);
                            Uri subChildren = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(subDoc));
                            Cursor sc = getContentResolver().query(subChildren, new String[]{DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null);
                            try { if (sc != null) while (sc.moveToNext()) { if ("vnd.android.document/directory".equals(sc.getString(0))) dc++; else fc++; } } finally { if (sc != null) sc.close(); }
                        } catch (Exception ignored) {}
                        e.put("dirs", dc); e.put("files", fc);
                    }
                    arr.put(e);
                }
            } finally { if (c != null) c.close(); }
            JSONObject out = new JSONObject(); out.put("ok", true); out.put("path", relPath == null ? "" : relPath); out.put("rootName", docDisplayName(doc)); out.put("entries", arr);
            return out.toString();
        } catch (Exception e) { try { JSONObject out = new JSONObject(); out.put("ok", false); out.put("error", String.valueOf(e.getMessage())); return out.toString(); } catch (Exception ignored) { return "{\"ok\":false}"; } }
    }
    private String okJson(boolean ok, String err) {
        try { JSONObject o = new JSONObject(); o.put("ok", ok); if (!ok) o.put("error", err == null ? "" : err); return o.toString(); } catch (Exception e) { return "{\"ok\":false}"; }
    }
    private Uri writeSafFile(Uri parentDoc, String fileName, String mime, byte[] bytes) throws Exception {
        Uri existing = findChildDoc(parentDoc, fileName);
        if (existing != null) DocumentsContract.deleteDocument(getContentResolver(), existing);
        Uri newDoc = DocumentsContract.createDocument(getContentResolver(), parentDoc, mime == null || mime.isEmpty() ? "application/octet-stream" : mime, fileName);
        if (newDoc == null) throw new Exception("Не удалось создать файл");
        OutputStream os = getContentResolver().openOutputStream(newDoc); os.write(bytes); os.close();
        return newDoc;
    }
    private void copySafDoc(Uri srcDoc, Uri dstParent, String name) throws Exception {
        String mime = docMime(srcDoc);
        if ("vnd.android.document/directory".equals(mime)) {
            Uri newDir = DocumentsContract.createDocument(getContentResolver(), dstParent, "vnd.android.document/directory", name);
            if (newDir == null) throw new Exception("Не удалось создать папку");
            Uri tree = getTreeUri();
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(srcDoc));
            Cursor c = getContentResolver().query(childrenUri, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
            try {
                if (c != null) while (c.moveToNext()) {
                    Uri child = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0));
                    copySafDoc(child, newDir, c.getString(1));
                }
            } finally { if (c != null) c.close(); }
        } else {
            Uri newDoc = DocumentsContract.createDocument(getContentResolver(), dstParent, mime, name);
            if (newDoc == null) throw new Exception("Не удалось создать файл");
            java.io.InputStream in = getContentResolver().openInputStream(srcDoc);
            OutputStream out = getContentResolver().openOutputStream(newDoc);
            byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close(); out.close();
        }
    }
    private void deleteSafDoc(Uri doc) throws Exception {
        String mime = docMime(doc);
        if ("vnd.android.document/directory".equals(mime)) {
            Uri tree = getTreeUri();
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(doc));
            Cursor c = getContentResolver().query(childrenUri, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID}, null, null, null);
            List<Uri> kids = new ArrayList<>();
            try { if (c != null) while (c.moveToNext()) kids.add(DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))); } finally { if (c != null) c.close(); }
            for (Uri kid : kids) deleteSafDoc(kid);
        }
        DocumentsContract.deleteDocument(getContentResolver(), doc);
    }
    private String mimeForName(String name) {
        if (name.endsWith(".pdf")) return "application/pdf";
        if (name.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (name.endsWith(".txt")) return "text/plain";
        if (name.endsWith(".json")) return "application/json";
        if (name.endsWith(".smetabook")) return "application/vnd.smeta.book";
        return "application/octet-stream";
    }

    private void toast(final String msg) {
        runOnUiThread(new Runnable() { @Override public void run() { Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show(); } });
    }
    private void launchCreateDocument(final String name, final String mime, final byte[] bytes) {
        runOnUiThread(new Runnable() { @Override public void run() {
            pendingSaveBytes = bytes; pendingSaveName = name;
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType(mime);
            i.putExtra(Intent.EXTRA_TITLE, name);
            try { startActivityForResult(i, REQ_CREATE_DOC); }
            catch (Exception e) { Toast.makeText(MainActivity.this, "Не удалось открыть выбор места: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
        }});
    }
    private void shareFile(final String name, final String mime, final byte[] bytes) {
        runOnUiThread(new Runnable() { @Override public void run() {
            try {
                File dir = new File(getCacheDir(), "shared"); dir.mkdirs();
                String safe = name.replace('/', '_').replace('\\', '_');
                File f = new File(dir, safe);
                FileOutputStream fos = new FileOutputStream(f); fos.write(bytes); fos.close();
                Uri uri = Uri.parse("content://com.smeta.pro2.fileprovider/shared/" + safe);
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType(mime);
                i.putExtra(Intent.EXTRA_STREAM, uri);
                i.putExtra(Intent.EXTRA_SUBJECT, safe);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(i, "Отправить файл"));
            } catch (Exception e) { Toast.makeText(MainActivity.this, "Не удалось отправить файл: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
        }});
    }
    private void shareTextRun(final String text) {
        runOnUiThread(new Runnable() { @Override public void run() {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TEXT, text);
            startActivity(Intent.createChooser(i, "Отправить сообщением"));
        }});
    }

    // ---------- PDF / XLSX generation from Smeta JSON payload ----------
    private static String money(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) return String.format(java.util.Locale.US, "%,.0f", v).replace(',', ' ') + " ₽";
        return String.format(java.util.Locale.US, "%,.2f", v).replace(',', ' ') + " ₽";
    }
    private static String escXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
    // ---------- Oboi-style PDF numbers / wrapping ----------
    // Oboi PDF cells have no ₽ sign (the header already says "Цена, ₽"):
    // prices/sums are plain grouped numbers, quantities use a comma.
    private static String pdfNum(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "";
        boolean neg = v < 0; v = Math.abs(v);
        long ip = (long) Math.floor(v + 1e-9);
        int frac = (int) Math.round((v - ip) * 100);
        if (frac == 100) { ip++; frac = 0; }
        String ipStr = Long.toString(ip);
        StringBuilder g = new StringBuilder();
        for (int i = 0; i < ipStr.length(); i++) {
            if (i > 0 && (ipStr.length() - i) % 3 == 0) g.append(' ');
            g.append(ipStr.charAt(i));
        }
        String out = g.toString();
        if (frac != 0) out += "," + (frac < 10 ? "0" + frac : Integer.toString(frac));
        return neg ? "-" + out : out;
    }
    private static List<String> pdfWrap(Paint pt, String text, float maxW) {
        List<String> lines = new ArrayList<String>();
        if (text == null) text = "";
        if (text.isEmpty()) { lines.add(""); return lines; }
        for (String para : text.split("\n")) {
            String cur = "";
            for (String w : para.split(" ")) {
                if (w.isEmpty()) continue;
                String test = cur.isEmpty() ? w : cur + " " + w;
                if (pt.measureText(test) <= maxW) cur = test;
                else {
                    if (!cur.isEmpty()) { lines.add(cur); cur = w; }
                    else {
                        // single word longer than the cell: hard-split it
                        String rest = w;
                        while (pt.measureText(rest) > maxW && rest.length() > 1) {
                            int cut = 1;
                            while (cut < rest.length() && pt.measureText(rest.substring(0, cut + 1)) <= maxW) cut++;
                            lines.add(rest.substring(0, cut)); rest = rest.substring(cut);
                        }
                        cur = rest;
                    }
                }
            }
            lines.add(cur);
        }
        return lines;
    }
    private static class PdfRowData {
        String[] cells; List<String>[] cellLines; int h;
        PdfRowData(String[] c) { cells = c; }
    }
    private static class PdfBlock {
        String group; List<PdfRowData> rows = new ArrayList<PdfRowData>(); double sectionTotal;
    }

    // ---------- PDF in OboiSmeta design (from the uploaded Oboi example) ----------
    // A4 595x842, margin 42, content 511. Title bold 16, meta regular 10 grey,
    // header bar dark green #17291C height 18 white bold 9.5, rows regular 9.5
    // line 12 + padding 6, light grey-green grid lines, total row light fill
    // #EDF5ED bold, signatures, footer "Стр. N из M" centred 8pt grey.
    // Columns (fractions from Oboi pdfDocCols, all columns on):
    // № 30.66 | Наименование 214.62 | Ед. 40.88 | Цена 71.54 | Объём 61.32 | Сумма 91.98
    // One section = exactly the Oboi example: flat numbered rows, one ИТОГО row.
    // Several sections = a group row per section + "Итого по разделу", then a
    // final ИТОГО row with the grand total (so no section data is lost).
    // A coefficient k != 1 is shown in the Объём cell as "q × k", so the sum
    // still checks out as price × (q × k). Markup/transport, when present,
    // are printed as plain lines after the table, before the signatures.
    private byte[] buildPdf(JSONObject p) throws Exception {
        // hideTotals: экспорт прайса — без итогов и подписей
        // (заказчик смотрит только расценки; итоги — только в смете).
        boolean hideTotals = p.optBoolean("hideTotals", false);
        final int W = 595, H = 842, M = 42, CW = 511, BOT = H - 48;
        Paint title = new Paint(); title.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD)); title.setTextSize(16); title.setAntiAlias(true); title.setColor(0xFF1A211C);
        Paint meta = new Paint(); meta.setTextSize(10); meta.setAntiAlias(true); meta.setColor(0xFF404A42);
        Paint head = new Paint(); head.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD)); head.setTextSize(9.5f); head.setAntiAlias(true); head.setColor(0xFFFFFFFF);
        Paint body = new Paint(); body.setTextSize(9.5f); body.setAntiAlias(true); body.setColor(0xFF1A211C);
        Paint bold = new Paint(); bold.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD)); bold.setTextSize(9.5f); bold.setAntiAlias(true); bold.setColor(0xFF1A211C);
        // «Итого по разделу» — БЕЗ заливки, белым, шрифт крупнее и жирнее
        // (11.5 против 9.5), чтобы строка не сливалась с заголовком
        // раздела (светло-зелёный) и читалась отдельно. ИТОГО остаётся
        // светло-зелёным, как в Обои.
        Paint sectTot = new Paint(); sectTot.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD)); sectTot.setTextSize(11.5f); sectTot.setAntiAlias(true); sectTot.setColor(0xFF1A211C);
        Paint foot = new Paint(); foot.setTextSize(8); foot.setAntiAlias(true); foot.setColor(0xFF737F76);
        Paint lineP = new Paint(); lineP.setColor(0xFFCCD9CC); lineP.setStrokeWidth(0.5f); lineP.setAntiAlias(true);
        Paint headerFill = new Paint(); headerFill.setColor(0xFF17291C);
        Paint totalFill = new Paint(); totalFill.setColor(0xFFEDF5ED);

        final float[] cw = new float[]{30.66f, 214.62f, 40.88f, 71.54f, 61.32f, 91.98f};
        final float[] xs = new float[6]; { float x = M; for (int i = 0; i < 6; i++) { xs[i] = x; x += cw[i]; } }
        final String[] headers = new String[]{"№", "Наименование", "Ед.", "Цена, ₽", "Объём", "Сумма, ₽"};
        final int[] aligns = new int[]{1, 0, 1, 2, 2, 2}; // 0 left, 1 centre, 2 right

        // Title + meta (Oboi order: object, client, date, executor)
        List<String> titleLines = pdfWrap(title, p.optString("title", "Смета"), CW);
        List<String> metaLines = new ArrayList<String>();
        if (!p.optString("object", "").isEmpty()) metaLines.add("Объект: " + p.optString("object"));
        if (!p.optString("client", "").isEmpty()) metaLines.add("Заказчик: " + p.optString("client"));
        if (!p.optString("dateStr", "").isEmpty()) metaLines.add("Дата: " + p.optString("dateStr"));
        if (!p.optString("executor", "").isEmpty()) metaLines.add("Исполнитель: " + p.optString("executor"));

        // Blocks / rows
        List<PdfBlock> blocks = new ArrayList<PdfBlock>();
        JSONArray secs = p.optJSONArray("sections");
        int globalIdx = 0;
        if (secs != null) {
            for (int si = 0; si < secs.length(); si++) {
                JSONObject sec = secs.getJSONObject(si);
                PdfBlock bl = new PdfBlock();
                bl.group = secs.length() > 1 ? sec.optString("title", "Раздел") : null;
                bl.sectionTotal = sec.optDouble("total", 0);
                JSONArray items = sec.optJSONArray("items");
                if (items != null) {
                    for (int ii = 0; ii < items.length(); ii++) {
                        JSONObject it = items.getJSONObject(ii);
                        globalIdx++;
                        double q = it.optDouble("q", 0), k = it.optDouble("k", 1);
                        String qty = (k == 1 || k == 0) ? pdfNum(q) : pdfNum(q) + " × " + pdfNum(k);
                        PdfRowData r = new PdfRowData(new String[]{
                            Integer.toString(globalIdx), it.optString("n", ""), it.optString("u", ""),
                            pdfNum(it.optDouble("p", 0)), qty, pdfNum(it.optDouble("sum", 0))});
                        r.cellLines = new List[6];
                        int maxLines = 1;
                        for (int ci = 0; ci < 6; ci++) {
                            r.cellLines[ci] = pdfWrap(body, r.cells[ci], cw[ci] - 9);
                            if (r.cellLines[ci].size() > maxLines) maxLines = r.cellLines[ci].size();
                        }
                        r.h = maxLines * 12 + 6;
                        bl.rows.add(r);
                    }
                }
                blocks.add(bl);
            }
        }
        boolean multi = blocks.size() > 1;
        // Trailing lines after the table
        List<String> trail = new ArrayList<String>();
        if (p.optBoolean("withSummary", false) && secs != null && secs.length() > 1) {
            trail.add("Сводка по разделам");
            for (int si = 0; si < secs.length(); si++) {
                JSONObject sec = secs.getJSONObject(si);
                trail.add(sec.optString("title", "") + " — " + pdfNum(sec.optDouble("total", 0)) + " ₽");
            }
        }
        if (p.optDouble("markupSum", 0) != 0) trail.add("Наценка (" + pdfNum(p.optDouble("markupPct", 0)) + "%): " + pdfNum(p.optDouble("markupSum", 0)) + " ₽");
        if (p.optDouble("transport", 0) != 0) trail.add("Транспорт: " + pdfNum(p.optDouble("transport", 0)) + " ₽");
        String grandStr = pdfNum(p.optDouble("grandTotal", 0));

        // --- Pass 1: count pages (same heights / breaks as drawing) ---
        int totalPages = 1;
        { float y = M; y += titleLines.size() == 1 ? 24 : titleLines.size() * 20; y += metaLines.size() * 14; y += 6; y += 18;
          for (PdfBlock bl : blocks) {
            if (bl.group != null) { if (y + 17 > BOT) { totalPages++; y = M + 18; } y += 17; }
            for (PdfRowData r : bl.rows) { if (y + r.h > BOT) { totalPages++; y = M + 18; } y += r.h; }
            if (multi && !hideTotals) { if (y + 20 > BOT) { totalPages++; y = M + 18; } y += 20; }
          }
          if (!hideTotals) { if (y + 18 > BOT) { totalPages++; y = M + 18; } y += 18; }
          for (int i = 0; i < trail.size(); i++) { if (y + 14 > BOT) { totalPages++; y = M; } y += 14; }
          if (!hideTotals && y + 44 > BOT) { totalPages++; }
        }

        final int totalPagesFinal = totalPages;
        // --- Pass 2: draw ---
        PdfDocument doc = new PdfDocument();
        final int[] pageNo = new int[]{1};
        final PdfDocument.Page[] pageRef = new PdfDocument.Page[]{doc.startPage(new PdfDocument.PageInfo.Builder(W, H, 1).create())};
        final Canvas[] cv = new Canvas[]{pageRef[0].getCanvas()};
        final float[] yRef = new float[]{M};
        java.lang.Runnable finishPage = new java.lang.Runnable() {
            public void run() {
                String label = "Стр. " + pageNo[0] + " из " + totalPagesFinal;
                float w = foot.measureText(label);
                cv[0].drawText(label, (W - w) / 2, H - 24, foot);
                doc.finishPage(pageRef[0]);
            }
        };
        java.lang.Runnable newPageWithHeader = new java.lang.Runnable() {
            public void run() {
                finishPage.run();
                pageNo[0]++;
                pageRef[0] = doc.startPage(new PdfDocument.PageInfo.Builder(W, H, pageNo[0]).create());
                cv[0] = pageRef[0].getCanvas(); yRef[0] = M;
                cv[0].drawRect(M, yRef[0], M + CW, yRef[0] + 18, headerFill);
                for (int i = 0; i < 6; i++) {
                    String t = headers[i];
                    float tx = aligns[i] == 0 ? xs[i] + 5 : aligns[i] == 1 ? xs[i] + cw[i] / 2 - head.measureText(t) / 2 : xs[i] + cw[i] - 5 - head.measureText(t);
                    cv[0].drawText(t, tx, yRef[0] + 3.5f + 9.5f * 0.82f, head);
                }
                yRef[0] += 18;
            }
        };
        java.lang.Runnable drawHeaderHere = new java.lang.Runnable() {
            public void run() {
                cv[0].drawRect(M, yRef[0], M + CW, yRef[0] + 18, headerFill);
                for (int i = 0; i < 6; i++) {
                    String t = headers[i];
                    float tx = aligns[i] == 0 ? xs[i] + 5 : aligns[i] == 1 ? xs[i] + cw[i] / 2 - head.measureText(t) / 2 : xs[i] + cw[i] - 5 - head.measureText(t);
                    cv[0].drawText(t, tx, yRef[0] + 3.5f + 9.5f * 0.82f, head);
                }
                yRef[0] += 18;
            }
        };
        // Title
        for (int li = 0; li < titleLines.size(); li++) cv[0].drawText(titleLines.get(li), M, yRef[0] + 16 * 0.82f + li * 20, title);
        yRef[0] += titleLines.size() == 1 ? 24 : titleLines.size() * 20;
        for (String ml : metaLines) { cv[0].drawText(ml, M, yRef[0] + 10 * 0.82f, meta); yRef[0] += 14; }
        yRef[0] += 6;
        drawHeaderHere.run();
        // Blocks
        for (PdfBlock bl : blocks) {
            if (bl.group != null) {
                if (yRef[0] + 17 > BOT) newPageWithHeader.run();
                cv[0].drawRect(M, yRef[0], M + CW, yRef[0] + 17, totalFill);
                cv[0].drawText(bl.group, M + 5, yRef[0] + 3.5f + 9.5f * 0.82f, bold);
                cv[0].drawLine(M, yRef[0] + 17, M + CW, yRef[0] + 17, lineP);
                yRef[0] += 17;
            }
            for (PdfRowData r : bl.rows) {
                if (yRef[0] + r.h > BOT) newPageWithHeader.run();
                for (int ci = 0; ci < 6; ci++) {
                    List<String> ls = r.cellLines[ci];
                    for (int li = 0; li < ls.size(); li++) {
                        String t = ls.get(li);
                        float tx = aligns[ci] == 0 ? xs[ci] + 5 : aligns[ci] == 1 ? xs[ci] + cw[ci] / 2 - body.measureText(t) / 2 : xs[ci] + cw[ci] - 5 - body.measureText(t);
                        cv[0].drawText(t, tx, yRef[0] + 3 + li * 12 + 9.5f * 0.82f, body);
                    }
                }
                cv[0].drawLine(M, yRef[0] + r.h, M + CW, yRef[0] + r.h, lineP);
                for (int i = 1; i < 6; i++) cv[0].drawLine(xs[i], yRef[0], xs[i], yRef[0] + r.h, lineP);
                yRef[0] += r.h;
            }
            if (multi && !hideTotals) {
                if (yRef[0] + 20 > BOT) newPageWithHeader.run();
                String[] tc = new String[]{"", "Итого по разделу", "", "", "", pdfNum(bl.sectionTotal)};
                for (int ci = 0; ci < 6; ci++) {
                    String t = tc[ci]; if (t.isEmpty()) continue;
                    float tx = aligns[ci] == 0 ? xs[ci] + 5 : aligns[ci] == 1 ? xs[ci] + cw[ci] / 2 - sectTot.measureText(t) / 2 : xs[ci] + cw[ci] - 5 - sectTot.measureText(t);
                    cv[0].drawText(t, tx, yRef[0] + 3.5f + 11.5f * 0.82f, sectTot);
                }
                cv[0].drawLine(M, yRef[0] + 20, M + CW, yRef[0] + 20, lineP);
                for (int i = 1; i < 6; i++) cv[0].drawLine(xs[i], yRef[0], xs[i], yRef[0] + 20, lineP);
                yRef[0] += 20;
            }
        }
        // Final ИТОГО row (hideTotals: прайс без итогов)
        if (!hideTotals) {
        if (yRef[0] + 18 > BOT) newPageWithHeader.run();
        cv[0].drawRect(M, yRef[0], M + CW, yRef[0] + 18, totalFill);
        { String[] tc = new String[]{"", "ИТОГО", "", "", "", grandStr};
          for (int ci = 0; ci < 6; ci++) {
            String t = tc[ci]; if (t.isEmpty()) continue;
            float tx = aligns[ci] == 0 ? xs[ci] + 5 : aligns[ci] == 1 ? xs[ci] + cw[ci] / 2 - bold.measureText(t) / 2 : xs[ci] + cw[ci] - 5 - bold.measureText(t);
            cv[0].drawText(t, tx, yRef[0] + 3.5f + 9.5f * 0.82f, bold);
          }
          cv[0].drawLine(M, yRef[0] + 18, M + CW, yRef[0] + 18, lineP);
          for (int i = 1; i < 6; i++) cv[0].drawLine(xs[i], yRef[0], xs[i], yRef[0] + 18, lineP);
          yRef[0] += 18;
        }
        }
        for (String tl : trail) {
            if (yRef[0] + 14 > BOT) { finishPage.run(); pageNo[0]++; pageRef[0] = doc.startPage(new PdfDocument.PageInfo.Builder(W, H, pageNo[0]).create()); cv[0] = pageRef[0].getCanvas(); yRef[0] = M; }
            cv[0].drawText(tl, M, yRef[0] + 10 * 0.82f, meta); yRef[0] += 14;
        }
        if (!hideTotals) {
        if (yRef[0] + 44 > BOT) { finishPage.run(); pageNo[0]++; pageRef[0] = doc.startPage(new PdfDocument.PageInfo.Builder(W, H, pageNo[0]).create()); cv[0] = pageRef[0].getCanvas(); yRef[0] = M; }
        yRef[0] += 24;
        cv[0].drawText("Исполнитель: ____________________", M, yRef[0] + 10 * 0.82f, meta);
        cv[0].drawText("Заказчик: ____________________", M + 280, yRef[0] + 10 * 0.82f, meta);
        }
        finishPage.run();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        doc.writeTo(bos); doc.close();
        return bos.toByteArray();
    }

    private static String numStr(double v) {
        if (v == Math.floor(v) && Math.abs(v) < 1e15) return Long.toString((long) v);
        String s = String.format(java.util.Locale.US, "%.2f", v);
        while (s.contains(".") && s.endsWith("0")) s = s.substring(0, s.length()-1);
        if (s.endsWith(".")) s = s.substring(0, s.length()-1);
        return s;
    }
    private static String cText(String col, int row, int style, String text) {
        String s = style >= 0 ? " s=\"" + style + "\"" : "";
        return "<c r=\"" + col + row + "\" t=\"inlineStr\"" + s + "><is><t xml:space=\"preserve\">" + escXml(text) + "</t></is></c>";
    }
    private static String cRich(String col, int row, String label, String value) {
        return "<c r=\"" + col + row + "\" t=\"inlineStr\"><is><r><rPr><b/><sz val=\"11\"/><rFont val=\"Calibri\"/><color rgb=\"FF000000\"/></rPr><t xml:space=\"preserve\">" + escXml(label) + "</t></r><r><rPr><sz val=\"11\"/><rFont val=\"Calibri\"/></rPr><t xml:space=\"preserve\">" + escXml(value) + "</t></r></is></c>";
    }
    private static String cNum(String col, int row, int style, double v) {
        String s = style >= 0 ? " s=\"" + style + "\"" : "";
        return "<c r=\"" + col + row + "\"" + s + "><v>" + numStr(v) + "</v></c>";
    }
    private static String cFormula(String col, int row, int style, String formula, double cached) {
        String s = style >= 0 ? " s=\"" + style + "\"" : "";
        return "<c r=\"" + col + row + "\"" + s + "><f>" + formula + "</f><v>" + numStr(cached) + "</v></c>";
    }
    private static String cEmpty(String col, int row, int style) {
        String s = style >= 0 ? " s=\"" + style + "\"" : "";
        return "<c r=\"" + col + row + "\"" + s + "/>";
    }
    // ---- Сумма прописью (как в Обои: RuWords) ----
    private static String tripleWords(int n, boolean fem) {
        String[] hund = {"", "сто", "двести", "триста", "четыреста", "пятьсот", "шестьсот", "семьсот", "восемьсот", "девятьсот"};
        String[] tens = {"", "", "двадцать", "тридцать", "сорок", "пятьдесят", "шестьдесят", "семьдесят", "восемьдесят", "девяносто"};
        String[] teens = {"десять", "одиннадцать", "двенадцать", "тринадцать", "четырнадцать", "пятнадцать", "шестнадцать", "семнадцать", "восемнадцать", "девятнадцать"};
        String[] onesM = {"", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять"};
        String[] onesF = {"", "одна", "две", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять"};
        StringBuilder sb = new StringBuilder();
        int h = n / 100, rem = n % 100;
        if (h > 0) sb.append(hund[h]).append(" ");
        if (rem >= 10 && rem < 20) sb.append(teens[rem - 10]).append(" ");
        else {
            int tt = rem / 10, o = rem % 10;
            if (tt > 0) sb.append(tens[tt]).append(" ");
            if (o > 0) sb.append((fem ? onesF[o] : onesM[o])).append(" ");
        }
        return sb.toString().trim();
    }
    private static String form(long n, String one, String few, String many) {
        long m10 = n % 10, m100 = n % 100;
        if (m10 == 1 && m100 != 11) return one;
        if (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) return few;
        return many;
    }
    private static String sumInWords(double total) {
        long rub = (long) Math.floor(total + 1e-9);
        int kop = (int) Math.round((total - rub) * 100);
        if (kop == 100) { rub++; kop = 0; }
        StringBuilder sb = new StringBuilder();
        long billions = rub / 1000000000, millions = (rub / 1000000) % 1000, thousands = (rub / 1000) % 1000, rest = rub % 1000;
        if (billions > 0) sb.append(tripleWords((int) billions, false)).append(" ").append(form(billions, "миллиард", "миллиарда", "миллиардов")).append(" ");
        if (millions > 0) sb.append(tripleWords((int) millions, false)).append(" ").append(form(millions, "миллион", "миллиона", "миллионов")).append(" ");
        if (thousands > 0) sb.append(tripleWords((int) thousands, true)).append(" ").append(form(thousands, "тысяча", "тысячи", "тысяч")).append(" ");
        if (rest > 0 || rub == 0) sb.append(rest == 0 ? "ноль" : tripleWords((int) rest, false)).append(" ");
        sb.append(form(rub, "рубль", "рубля", "рублей")).append(" ");
        sb.append(String.format("%02d", kop)).append(" ").append(form(kop, "копейка", "копейки", "копеек"));
        String s = sb.toString().trim();
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
    private byte[] buildXlsx(JSONObject p) throws Exception {
        // hideTotals: экспорт прайса — без итогов и подписей
        // (заказчик смотрит только расценки; итоги — только в смете).
        boolean hideTotals = p.optBoolean("hideTotals", false);
        // Oboi-style layout, not the old plain one:
        // cols A=5 B=62 C=9 D=12 E=10 F=8 G=15, title in B, info as
        // rich-text "Label: value", section title = green bar, header =
        // dark bar, grand total = light-green bar, sum in words +
        // signatures at the end. All text = inlineStr, no sharedStrings.
        // NEW: sums are formulas, not static numbers:
        //  item G = D*E*F, section total = SUM(items G),
        //  subtotal = SUM(section totals), markup = subtotal*pct/100,
        //  grand = subtotal+markup+transport. Cached values are kept
        //  so the file shows correct numbers even before recalc.
        StringBuilder sheet = new StringBuilder();
        sheet.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
        sheet.append("<cols><col min=\"1\" max=\"1\" width=\"5\" customWidth=\"1\"/><col min=\"2\" max=\"2\" width=\"62\" customWidth=\"1\"/><col min=\"3\" max=\"3\" width=\"9\" customWidth=\"1\"/><col min=\"4\" max=\"4\" width=\"12\" customWidth=\"1\"/><col min=\"5\" max=\"5\" width=\"10\" customWidth=\"1\"/><col min=\"6\" max=\"6\" width=\"8\" customWidth=\"1\"/><col min=\"7\" max=\"7\" width=\"15\" customWidth=\"1\"/></cols><sheetData>");
        int row = 1;
        String title = p.optString("title", "Смета");
        // Row 1: title, bold 14, in B (A empty with same style, like Oboi)
        sheet.append("<row r=\"").append(row).append("\">").append(cEmpty("A", row, 3)).append(cText("B", row, 3, title)).append("</row>"); row++;
        // Info rows, only non-empty, in B as rich text
        if (!p.optString("title", "").isEmpty()) { sheet.append("<row r=\"").append(row).append("\">").append(cRich("B", row, "Название: ", title)).append("</row>"); row++; }
        if (!p.optString("object", "").isEmpty()) { sheet.append("<row r=\"").append(row).append("\">").append(cRich("B", row, "Объект: ", p.optString("object"))).append("</row>"); row++; }
        if (!p.optString("client", "").isEmpty()) { sheet.append("<row r=\"").append(row).append("\">").append(cRich("B", row, "Заказчик: ", p.optString("client"))).append("</row>"); row++; }
        if (!p.optString("dateStr", "").isEmpty()) { sheet.append("<row r=\"").append(row).append("\">").append(cRich("B", row, "Дата: ", p.optString("dateStr"))).append("</row>"); row++; }
        if (!p.optString("executor", "").isEmpty()) { sheet.append("<row r=\"").append(row).append("\">").append(cRich("B", row, "Исполнитель: ", p.optString("executor"))).append("</row>"); row++; }
        sheet.append("<row r=\"").append(row).append("\"/>"); row++;
        JSONArray secs = p.optJSONArray("sections");
        List<Integer> sectionTotalRows = new ArrayList<>();
        int globalNum = 0;
        if (secs != null) {
            for (int si = 0; si < secs.length(); si++) {
                JSONObject sec = secs.getJSONObject(si);
                // Section title: green bar A-G, title in B, centerContinuous
                sheet.append("<row r=\"").append(row).append("\">")
                    .append(cEmpty("A", row, 4)).append(cText("B", row, 5, (si + 1) + ". " + sec.optString("title", "Раздел")))
                    .append(cEmpty("C", row, 4)).append(cEmpty("D", row, 4)).append(cEmpty("E", row, 4)).append(cEmpty("F", row, 4)).append(cEmpty("G", row, 4))
                    .append("</row>"); row++;
                // Header: dark bar
                sheet.append("<row r=\"").append(row).append("\">")
                    .append(cText("A", row, 2, "№")).append(cText("B", row, 2, "Наименование")).append(cText("C", row, 2, "Ед."))
                    .append(cText("D", row, 2, "Цена, ₽")).append(cText("E", row, 2, "Объём")).append(cText("F", row, 2, "Коэф.")).append(cText("G", row, 2, "Сумма, ₽"))
                    .append("</row>"); row++;
                JSONArray items = sec.optJSONArray("items");
                int firstItemRow = row, lastItemRow = row - 1;
                if (items != null) {
                    for (int ii = 0; ii < items.length(); ii++) {
                        JSONObject it = items.getJSONObject(ii); globalNum++;
                        double price = it.optDouble("p", 0), qty = it.optDouble("q", 0), coef = it.optDouble("k", 1), sum = it.optDouble("sum", 0);
                        // If cached sum differs from price*qty*coef, trust the formula result
                        double formulaSum = price * qty * coef;
                        sheet.append("<row r=\"").append(row).append("\">")
                            .append(cNum("A", row, -1, globalNum)).append(cText("B", row, -1, it.optString("n", ""))).append(cText("C", row, -1, it.optString("u", "")))
                            .append(cNum("D", row, -1, price)).append(cNum("E", row, -1, qty)).append(cNum("F", row, -1, coef))
                            .append(cFormula("G", row, -1, "D" + row + "*E" + row + "*F" + row, formulaSum))
                            .append("</row>"); row++; lastItemRow = row - 1;
                    }
                }
                int totalRow = row;
                double secTotal = sec.optDouble("total", 0);
                // «Итого по разделу» в Excel — тоже без заливки, белым,
                // жирным 13 (стиль 7), а не 11 как позиции: заголовок
                // раздела — зелёная полоса, итог — белый и крупнее.
                // hideTotals: прайс без итогов — строка не пишется и её
                // номер НЕ добавляется в sectionTotalRows, чтобы формулы
                // ИТОГО/сводки ни на что не ссылались.
                if (!hideTotals) {
                sectionTotalRows.add(totalRow);
                sheet.append("<row r=\"").append(row).append("\">")
                    .append(cEmpty("A", row, 7)).append(cText("B", row, 7, "Итого по разделу"))
                    .append(cEmpty("C", row, 7)).append(cEmpty("D", row, 7)).append(cEmpty("E", row, 7)).append(cEmpty("F", row, 7));
                if (items != null && items.length() > 0) sheet.append(cFormula("G", row, 7, "SUM(G" + firstItemRow + ":G" + lastItemRow + ")", secTotal));
                else sheet.append(cNum("G", row, 7, secTotal));
                sheet.append("</row>"); row++;
                }
                sheet.append("<row r=\"").append(row).append("\"/>"); row++;
            }
        }
        if (p.optBoolean("withSummary", false) && secs != null && secs.length() > 0) {
            sheet.append("<row r=\"").append(row).append("\">")
                .append(cEmpty("A", row, 4)).append(cText("B", row, 5, "Сводка по разделам"))
                .append(cEmpty("C", row, 4)).append(cEmpty("D", row, 4)).append(cEmpty("E", row, 4)).append(cEmpty("F", row, 4)).append(cEmpty("G", row, 4))
                .append("</row>"); row++;
            for (int si = 0; si < secs.length(); si++) {
                JSONObject sec = secs.getJSONObject(si);
                int tr = sectionTotalRows.get(si);
                sheet.append("<row r=\"").append(row).append("\">")
                    .append(cText("B", row, -1, sec.optString("title", "")))
                    .append(cFormula("G", row, -1, "G" + tr, sec.optDouble("total", 0)))
                    .append("</row>"); row++;
            }
            sheet.append("<row r=\"").append(row).append("\"/>"); row++;
        }
        // NO "Подытог" row anywhere: when there is no markup/transport it
        // duplicates ИТОГО exactly (45590 = 45590), so it carries no
        // information. Formulas still use the sum of section totals
        // internally, without showing it as a separate row.
        int markupRow = -1, transportRow = -1;
        StringBuilder sectionsSum = new StringBuilder();
        for (int i = 0; i < sectionTotalRows.size(); i++) { if (i > 0) sectionsSum.append("+"); sectionsSum.append("G").append(sectionTotalRows.get(i)); }
        double markupPct = p.optDouble("markupPct", 0), markupSum = p.optDouble("markupSum", 0);
        if (markupSum != 0 && sectionsSum.length() > 0) {
            markupRow = row;
            sheet.append("<row r=\"").append(row).append("\">").append(cText("B", row, -1, "Наценка (" + numStr(markupPct) + "%)")).append(cFormula("G", row, -1, "(" + sectionsSum.toString() + ")*" + numStr(markupPct) + "/100", markupSum)).append("</row>"); row++;
        }
        double transport = p.optDouble("transport", 0);
        if (transport != 0) {
            transportRow = row;
            sheet.append("<row r=\"").append(row).append("\">").append(cText("B", row, -1, "Транспорт")).append(cNum("G", row, -1, transport)).append("</row>"); row++;
        }
        int grandRow = row;
        StringBuilder grandF = new StringBuilder();
        if (sectionsSum.length() > 0) grandF.append(sectionsSum.toString());
        if (markupRow > 0) { if (grandF.length() > 0) grandF.append("+"); grandF.append("G").append(markupRow); }
        if (transportRow > 0) { if (grandF.length() > 0) grandF.append("+"); grandF.append("G").append(transportRow); }
        // hideTotals: прайс без строки ИТОГО.
        if (!hideTotals) {
        sheet.append("<row r=\"").append(row).append("\">")
            .append(cEmpty("A", row, 6)).append(cText("B", row, 6, "ИТОГО"))
            .append(cEmpty("C", row, 6)).append(cEmpty("D", row, 6)).append(cEmpty("E", row, 6)).append(cEmpty("F", row, 6));
        if (grandF.length() > 0) sheet.append(cFormula("G", row, 6, grandF.toString(), p.optDouble("grandTotal", 0)));
        else sheet.append(cNum("G", row, 6, p.optDouble("grandTotal", 0)));
        sheet.append("</row>"); row++;
        sheet.append("<row r=\"").append(row).append("\">").append(cText("B", row, -1, "Сумма прописью: " + sumInWords(p.optDouble("grandTotal", 0)))).append("</row>"); row++;
        sheet.append("<row r=\"").append(row).append("\"/>"); row++;
        String exec = p.optString("executor", "");
        sheet.append("<row r=\"").append(row).append("\">").append(cText("B", row, -1, "Исполнитель: ____________________" + (exec.isEmpty() ? "" : " (" + exec + ")"))).append("</row>"); row++;
        sheet.append("<row r=\"").append(row).append("\">").append(cText("B", row, -1, "Заказчик: ____________________")).append("</row>"); row++;
        }
        sheet.append("</sheetData></worksheet>");
        String ct = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/></Types>";
        String rels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>";
        String wb = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"Смета\" sheetId=\"1\" r:id=\"rId1\"/></sheets><calcPr fullCalcOnLoad=\"1\"/></workbook>";
        String wbRels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/><Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>";
        String styles = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><fonts count=\"7\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><color rgb=\"FFFFFFFF\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"14\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"12\"/><color rgb=\"FFFFFFFF\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"12\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"13\"/><name val=\"Calibri\"/></font></fonts><fills count=\"5\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill><fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF1E293B\"/></patternFill></fill><fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF2E7D32\"/></patternFill></fill><fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFE8F5E9\"/></patternFill></fill></fills><borders count=\"1\"><border/></borders><cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs><cellXfs count=\"8\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/><xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/><xf numFmtId=\"0\" fontId=\"2\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/><xf numFmtId=\"0\" fontId=\"3\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/><xf numFmtId=\"0\" fontId=\"4\" fillId=\"3\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/><xf numFmtId=\"0\" fontId=\"4\" fillId=\"3\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyAlignment=\"1\"><alignment horizontal=\"centerContinuous\"/></xf><xf numFmtId=\"0\" fontId=\"5\" fillId=\"4\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/><xf numFmtId=\"0\" fontId=\"6\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/></cellXfs></styleSheet>";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ZipOutputStream zos = new ZipOutputStream(bos);
        java.util.function.BiConsumer<String,String> put = new java.util.function.BiConsumer<String,String>() {
            @Override public void accept(String name, String content) {
                try { zos.putNextEntry(new ZipEntry(name)); zos.write(content.getBytes("UTF-8")); zos.closeEntry(); } catch (Exception ignored) {}
            }
        };
        put.accept("[Content_Types].xml", ct);
        put.accept("_rels/.rels", rels);
        put.accept("xl/workbook.xml", wb);
        put.accept("xl/_rels/workbook.xml.rels", wbRels);
        put.accept("xl/worksheets/sheet1.xml", sheet.toString());
        put.accept("xl/styles.xml", styles);
        zos.close();
        return bos.toByteArray();
    }

    private void printPdfBytes(final byte[] bytes, final String jobName) {
        runOnUiThread(new Runnable() { @Override public void run() {
            try {
                PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
                if (pm == null) { Toast.makeText(MainActivity.this, "Печать недоступна на этом устройстве", Toast.LENGTH_LONG).show(); return; }
                PrintDocumentAdapter adapter = new PrintDocumentAdapter() {
                    @Override public void onLayout(PrintAttributes oldAttributes, PrintAttributes newAttributes, CancellationSignal cancellationSignal, LayoutResultCallback callback, Bundle extras) {
                        if (cancellationSignal.isCanceled()) { callback.onLayoutCancelled(); return; }
                        PrintDocumentInfo info = new PrintDocumentInfo.Builder(jobName).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN).build();
                        callback.onLayoutFinished(info, true);
                    }
                    @Override public void onWrite(android.print.PageRange[] pages, ParcelFileDescriptor destination, CancellationSignal cancellationSignal, WriteResultCallback callback) {
                        try {
                            java.io.FileOutputStream fos = new java.io.FileOutputStream(destination.getFileDescriptor());
                            fos.write(bytes); fos.close();
                            callback.onWriteFinished(new android.print.PageRange[]{android.print.PageRange.ALL_PAGES});
                        } catch (Exception e) { callback.onWriteFailed(e.getMessage()); }
                    }
                };
                pm.print(jobName, adapter, new PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).setColorMode(PrintAttributes.COLOR_MODE_COLOR).build());
            } catch (Exception e) { Toast.makeText(MainActivity.this, "Не удалось открыть печать: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
        }});
    }

    public class AndroidBridge {
        // Запасной путь доставки файла в программу: JS сам забирает
        // текст файла при загрузке, если push через onPageFinished
        // по какой-то причине не дошёл. Вызов забирает текст один
        // раз, повторный вызов вернёт пустую строку — двойного
        // предпросмотра не будет.
        @JavascriptInterface public String getPendingBook() {
            String text = pendingBookText;
            pendingBookText = null;
            return text == null ? "" : text;
        }
        @JavascriptInterface public void generatePdf(String json) {
            try { JSONObject p = new JSONObject(json); byte[] b = buildPdf(p); launchCreateDocument(p.optString("fileBase", "Smeta") + ".pdf", "application/pdf", b); }
            catch (Exception e) { toast("Не удалось создать PDF: " + e.getMessage()); }
        }
        @JavascriptInterface public void generateXlsx(String json) {
            try { JSONObject p = new JSONObject(json); byte[] b = buildXlsx(p); launchCreateDocument(p.optString("fileBase", "Smeta") + ".xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", b); }
            catch (Exception e) { toast("Не удалось создать Excel: " + e.getMessage()); }
        }
        @JavascriptInterface public void sharePdf(String json) {
            try { JSONObject p = new JSONObject(json); byte[] b = buildPdf(p); shareFile(p.optString("fileBase", "Smeta") + ".pdf", "application/pdf", b); }
            catch (Exception e) { toast("Не удалось создать PDF: " + e.getMessage()); }
        }
        @JavascriptInterface public void shareXlsx(String json) {
            try { JSONObject p = new JSONObject(json); byte[] b = buildXlsx(p); shareFile(p.optString("fileBase", "Smeta") + ".xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", b); }
            catch (Exception e) { toast("Не удалось создать Excel: " + e.getMessage()); }
        }
        @JavascriptInterface public void printPdf(String json) {
            try { JSONObject p = new JSONObject(json); byte[] b = buildPdf(p); printPdfBytes(b, p.optString("fileBase", "Smeta") + ".pdf"); }
            catch (Exception e) { toast("Не удалось создать PDF для печати: " + e.getMessage()); }
        }
        @JavascriptInterface public void saveFile(String name, String content, String mime) {
            try { launchCreateDocument(name, mime == null || mime.isEmpty() ? "application/octet-stream" : mime, content.getBytes("UTF-8")); } catch (Exception e) { toast("Не удалось сохранить: " + e.getMessage()); }
        }
        @JavascriptInterface public void shareTextFile(String name, String content, String mime) {
            try { shareFile(name, mime == null || mime.isEmpty() ? "text/plain" : mime, content.getBytes("UTF-8")); } catch (Exception e) { toast("Не удалось отправить файл: " + e.getMessage()); }
        }
        @JavascriptInterface public void shareText(String text) { shareTextRun(text); }
        @JavascriptInterface public void saveTextFile(String name, String text) { try { launchCreateDocument(name, "text/plain", text.getBytes("UTF-8")); } catch (Exception e) { toast("Не удалось сохранить: " + e.getMessage()); } }
        // Oboi-style base64 methods, for any JS that uses them
        @JavascriptInterface public void savePdf64(String name, String b64) { try { launchCreateDocument(name, "application/pdf", Base64.decode(b64, Base64.DEFAULT)); } catch (Exception e) { toast("Не удалось сохранить PDF: " + e.getMessage()); } }
        @JavascriptInterface public void sharePdf64(String name, String b64) { try { shareFile(name, "application/pdf", Base64.decode(b64, Base64.DEFAULT)); } catch (Exception e) { toast("Не удалось отправить PDF: " + e.getMessage()); } }
        @JavascriptInterface public void saveXlsx64(String name, String b64) { try { launchCreateDocument(name, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", Base64.decode(b64, Base64.DEFAULT)); } catch (Exception e) { toast("Не удалось сохранить Excel: " + e.getMessage()); } }
        @JavascriptInterface public void shareXlsx64(String name, String b64) { try { shareFile(name, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", Base64.decode(b64, Base64.DEFAULT)); } catch (Exception e) { toast("Не удалось отправить Excel: " + e.getMessage()); } }
        @JavascriptInterface public String folderAutoCreate() {
            try {
                if (hasAllFilesAccess()) {
                    boolean ok = tryCreateFileFolder();
                    JSONObject o = new JSONObject(); o.put("ok", ok); if (!ok) o.put("error", "Не удалось создать папку в корне"); return o.toString();
                } else {
                    getSharedPreferences("smeta_folder", MODE_PRIVATE).edit().putBoolean("pending_auto_create", true).apply();
                    runOnUiThread(new Runnable() { @Override public void run() {
                        try {
                            Intent i;
                            if (android.os.Build.VERSION.SDK_INT >= 30) { i = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION); i.setData(Uri.parse("package:" + getPackageName())); }
                            else { i = new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION); }
                            startActivity(i);
                        } catch (Exception e) { Toast.makeText(MainActivity.this, "Откройте Настройки > Доступ ко всем файлам и разрешите для Смета PRO", Toast.LENGTH_LONG).show(); }
                    }});
                    JSONObject o = new JSONObject(); o.put("ok", false); o.put("needsPermission", true); return o.toString();
                }
            } catch (Exception e) { return okJson(false, e.getMessage()); }
        }
        @JavascriptInterface public String folderStatus() {
            try {
                if (isFileBackend()) {
                    java.io.File f = getFileFolder();
                    JSONObject o = new JSONObject(); o.put("configured", f != null && f.exists()); o.put("rootName", "Смета PRO"); o.put("backend", "file"); return o.toString();
                }
                Uri tree = getTreeUri();
                JSONObject o = new JSONObject();
                if (tree == null) { o.put("configured", false); return o.toString(); }
                Uri doc = docForPath("");
                o.put("configured", true); o.put("rootName", docDisplayName(doc)); return o.toString();
            } catch (Exception e) { try { JSONObject o = new JSONObject(); o.put("configured", false); o.put("error", String.valueOf(e.getMessage())); return o.toString(); } catch (Exception ignored) { return "{\"configured\":false}"; } }
        }
        @JavascriptInterface public void folderPick() {
            runOnUiThread(new Runnable() { @Override public void run() {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                try { startActivityForResult(i, REQ_OPEN_TREE); } catch (Exception e) { Toast.makeText(MainActivity.this, "Не удалось открыть выбор папки: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
            }});
        }
        @JavascriptInterface public String folderList(String relPath) { if (isFileBackend()) return fileListJson(relPath == null ? "" : relPath); return folderListJson(relPath == null ? "" : relPath); }
        @JavascriptInterface public String folderCreate(String relPath, String name) {
            if (isFileBackend()) { try { java.io.File d = new java.io.File(fileForPath(relPath == null ? "" : relPath), name); boolean ok = d.exists() || d.mkdirs(); return okJson(ok, ok ? null : "Не удалось создать папку"); } catch (Exception e) { return okJson(false, e.getMessage()); } }
            try { Uri parent = docForPath(relPath == null ? "" : relPath); Uri d = DocumentsContract.createDocument(getContentResolver(), parent, "vnd.android.document/directory", name); return okJson(d != null, d == null ? "Не удалось создать папку" : null); } catch (Exception e) { return okJson(false, e.getMessage()); }
        }
        @JavascriptInterface public String folderDelete(String relPath, String name) {
            if (isFileBackend()) { try { deleteFileRecursive(new java.io.File(fileForPath(relPath == null ? "" : relPath), name)); return okJson(true, null); } catch (Exception e) { return okJson(false, e.getMessage()); } }
            try { Uri parent = docForPath(relPath == null ? "" : relPath); Uri doc = findChildDoc(parent, name); if (doc == null) return okJson(false, "Не найдено"); deleteSafDoc(doc); return okJson(true, null); } catch (Exception e) { return okJson(false, e.getMessage()); }
        }
        @JavascriptInterface public String folderRename(String relPath, String name, String newName) {
            if (isFileBackend()) { try { java.io.File src = new java.io.File(fileForPath(relPath == null ? "" : relPath), name); java.io.File dst = new java.io.File(fileForPath(relPath == null ? "" : relPath), newName); boolean ok = src.renameTo(dst); return okJson(ok, ok ? null : "Не удалось переименовать"); } catch (Exception e) { return okJson(false, e.getMessage()); } }
            try { Uri parent = docForPath(relPath == null ? "" : relPath); Uri doc = findChildDoc(parent, name); if (doc == null) return okJson(false, "Не найдено"); Uri r = DocumentsContract.renameDocument(getContentResolver(), doc, newName); return okJson(r != null, null); } catch (Exception e) { return okJson(false, e.getMessage()); }
        }
        @JavascriptInterface public String folderCopy(String srcRel, String srcName, String dstRel) {
            if (isFileBackend()) { try { java.io.File src = new java.io.File(fileForPath(srcRel == null ? "" : srcRel), srcName); java.io.File dst = new java.io.File(fileForPath(dstRel == null ? "" : dstRel), srcName); copyFileRecursive(src, dst); return okJson(true, null); } catch (Exception e) { return okJson(false, e.getMessage()); } }
            try { Uri srcParent = docForPath(srcRel == null ? "" : srcRel); Uri src = findChildDoc(srcParent, srcName); if (src == null) return okJson(false, "Не найдено"); Uri dstParent = docForPath(dstRel == null ? "" : dstRel); copySafDoc(src, dstParent, srcName); return okJson(true, null); } catch (Exception e) { return okJson(false, e.getMessage()); }
        }
        @JavascriptInterface public String folderMove(String srcRel, String srcName, String dstRel) {
            if (isFileBackend()) { try { java.io.File src = new java.io.File(fileForPath(srcRel == null ? "" : srcRel), srcName); java.io.File dst = new java.io.File(fileForPath(dstRel == null ? "" : dstRel), srcName); copyFileRecursive(src, dst); deleteFileRecursive(src); return okJson(true, null); } catch (Exception e) { return okJson(false, e.getMessage()); } }
            try { Uri srcParent = docForPath(srcRel == null ? "" : srcRel); Uri src = findChildDoc(srcParent, srcName); if (src == null) return okJson(false, "Не найдено"); Uri dstParent = docForPath(dstRel == null ? "" : dstRel); copySafDoc(src, dstParent, srcName); deleteSafDoc(src); return okJson(true, null); } catch (Exception e) { return okJson(false, e.getMessage()); }
        }
        @JavascriptInterface public String folderSaveText(String relPath, String fileName, String text) {
            if (isFileBackend()) { try { java.io.File f = new java.io.File(fileForPath(relPath == null ? "" : relPath), fileName); java.io.FileOutputStream fos = new java.io.FileOutputStream(f); fos.write(text.getBytes("UTF-8")); fos.close(); toast("Сохранено в папку смет: " + fileName); return okJson(true, null); } catch (Exception e) { return okJson(false, e.getMessage()); } }
            try { Uri parent = docForPath(relPath == null ? "" : relPath); writeSafFile(parent, fileName, mimeForName(fileName), text.getBytes("UTF-8")); toast("Сохранено в папку смет: " + fileName); return okJson(true, null); } catch (Exception e) { return okJson(false, e.getMessage()); }
        }
        @JavascriptInterface public String folderGeneratePdf(String json, String relPath, String fileName) {
            try { JSONObject pp = new JSONObject(json); byte[] b = buildPdf(pp); if (isFileBackend()) { java.io.File f = new java.io.File(fileForPath(relPath == null ? "" : relPath), fileName); java.io.FileOutputStream fos = new java.io.FileOutputStream(f); fos.write(b); fos.close(); toast("PDF сохранён в папку смет: " + fileName); return okJson(true, null); } Uri parent = docForPath(relPath == null ? "" : relPath); writeSafFile(parent, fileName, "application/pdf", b); toast("PDF сохранён в папку смет: " + fileName); return okJson(true, null); } catch (Exception e) { return okJson(false, e.getMessage()); }
        }
        @JavascriptInterface public String folderGenerateXlsx(String json, String relPath, String fileName) {
            try { JSONObject pp = new JSONObject(json); byte[] b = buildXlsx(pp); if (isFileBackend()) { java.io.File f = new java.io.File(fileForPath(relPath == null ? "" : relPath), fileName); java.io.FileOutputStream fos = new java.io.FileOutputStream(f); fos.write(b); fos.close(); toast("Excel сохранён в папку смет: " + fileName); return okJson(true, null); } Uri parent = docForPath(relPath == null ? "" : relPath); writeSafFile(parent, fileName, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", b); toast("Excel сохранён в папку смет: " + fileName); return okJson(true, null); } catch (Exception e) { return okJson(false, e.getMessage()); }
        }
        @JavascriptInterface public void folderOpen(String relPath, String name) {
            if (isFileBackend()) {
                try {
                    java.io.File f = new java.io.File(fileForPath(relPath == null ? "" : relPath), name);
                    java.io.File dir = new java.io.File(getCacheDir(), "shared"); dir.mkdirs();
                    String safe = name.replace('/', '_').replace('\\', '_');
                    java.io.File cf = new java.io.File(dir, safe);
                    copyFileRecursive(f, cf);
                    Uri uri = Uri.parse("content://com.smeta.pro2.fileprovider/shared/" + safe);
                    Intent i = new Intent(Intent.ACTION_VIEW); i.setDataAndType(uri, mimeForName(name)); i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(i, "Открыть файл"));
                } catch (Exception e) { toast("Не удалось открыть файл: " + e.getMessage()); }
                return;
            }
            try {
                Uri parent = docForPath(relPath == null ? "" : relPath); Uri doc = findChildDoc(parent, name); if (doc == null) { toast("Файл не найден"); return; }
                Intent i = new Intent(Intent.ACTION_VIEW); i.setDataAndType(doc, mimeForName(name)); i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(i, "Открыть файл"));
            } catch (Exception e) { toast("Не удалось открыть файл: " + e.getMessage()); }
        }
        @JavascriptInterface public void folderShare(String relPath, String name) {
            if (isFileBackend()) {
                try { java.io.File f = new java.io.File(fileForPath(relPath == null ? "" : relPath), name); byte[] data = java.nio.file.Files.readAllBytes(f.toPath()); shareFile(name, mimeForName(name), data); } catch (Exception e) { toast("Не удалось отправить файл: " + e.getMessage()); }
                return;
            }
            try {
                Uri parent = docForPath(relPath == null ? "" : relPath); Uri doc = findChildDoc(parent, name); if (doc == null) { toast("Файл не найден"); return; }
                Intent i = new Intent(Intent.ACTION_SEND); i.setType(mimeForName(name)); i.putExtra(Intent.EXTRA_STREAM, doc); i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(i, "Отправить файл"));
            } catch (Exception e) { toast("Не удалось отправить файл: " + e.getMessage()); }
        }
        @JavascriptInterface public void fetchUrl(final String url, final String callbackId) {
            new Thread(new Runnable() { @Override public void run() {
                String html = null;
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android)");
                    c.setConnectTimeout(30000); c.setReadTimeout(30000);
                    java.io.InputStream in = c.getInputStream();
                    ByteArrayOutputStream bos = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n;
                    while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                    html = bos.toString("UTF-8");
                } catch (Exception ignored) {}
                final String result = html == null ? "null" : JSONObject.quote(html);
                runOnUiThread(new Runnable() { @Override public void run() {
                    if (webView != null) webView.evaluateJavascript("window['" + callbackId + "'](" + result + ")", null);
                }});
            }}).start();
        }

        /* ===== Обновления программы из папки на Яндекс Диске =====
           Папка (публичная ссылка, APK кладёт пользователь сам):
           https://disk.yandex.ru/d/dsfP2HffxqqGsw
           Версия определяется по имени файла SmetaPRO-vX.Y.Z.apk.
           Скачанный APK перед установкой проверяется: код версии
           должен быть новее установленного, а подпись — совпадать
           с подписью установленной программы. */
        private static final String UPD_FOLDER_LINK = "https://disk.yandex.ru/d/dsfP2HffxqqGsw";
        private static final String UPD_APK_NAME = "SmetaPRO-update.apk";

        @JavascriptInterface public String updVersion() {
            try {
                PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
                return "{\"name\":" + JSONObject.quote(pi.versionName) + ",\"code\":" + pi.versionCode + "}";
            } catch (Exception e) { return "{\"name\":\"?\",\"code\":0}"; }
        }

        private String updHttpGet(String urlStr) throws Exception {
            HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android)");
            c.setConnectTimeout(30000); c.setReadTimeout(30000);
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
            java.io.InputStream in = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            return bos.toString("UTF-8");
        }

        private int updCmpVer(String a, String b) {
            String[] pa = a.split("\\."), pb = b.split("\\.");
            int len = Math.max(pa.length, pb.length);
            for (int i = 0; i < len; i++) {
                int va = i < pa.length ? Integer.parseInt(pa[i]) : 0;
                int vb = i < pb.length ? Integer.parseInt(pb[i]) : 0;
                if (va != vb) return va < vb ? -1 : 1;
            }
            return 0;
        }

        private void updJs(final String js) {
            runOnUiThread(new Runnable() { @Override public void run() {
                if (webView != null) webView.evaluateJavascript(js, null);
            }});
        }

        @JavascriptInterface public void updCheck() {
            new Thread(new Runnable() { @Override public void run() {
                String out;
                try {
                    String pub = URLEncoder.encode(UPD_FOLDER_LINK, "UTF-8");
                    String json = updHttpGet("https://cloud-api.yandex.net/v1/disk/public/resources?public_key=" + pub + "&limit=100&sort=-modified");
                    JSONObject root = new JSONObject(json);
                    JSONObject emb = root.optJSONObject("_embedded");
                    JSONArray items = emb != null ? emb.optJSONArray("items") : null;
                    String bestName = null, bestVer = null, bestPath = null; long bestSize = 0;
                    if (items != null) {
                        for (int i = 0; i < items.length(); i++) {
                            JSONObject it = items.optJSONObject(i);
                            if (it == null) continue;
                            String name = it.optString("name", "");
                            Matcher m = Pattern.compile("(?i)SmetaPRO-v([0-9]+(?:\\.[0-9]+)*)\\.apk").matcher(name);
                            if (!m.find()) continue;
                            String ver = m.group(1);
                            if (bestVer == null || updCmpVer(ver, bestVer) > 0) {
                                bestVer = ver; bestName = name; bestPath = it.optString("path", ""); bestSize = it.optLong("size", 0);
                            }
                        }
                    }
                    if (bestName == null) {
                        out = "{\"ok\":false,\"error\":\"В папке нет файлов обновлений\"}";
                    } else {
                        String dl = updHttpGet("https://cloud-api.yandex.net/v1/disk/public/resources/download?public_key=" + pub + "&path=" + URLEncoder.encode(bestPath, "UTF-8"));
                        String href = new JSONObject(dl).optString("href", "");
                        if (href.length() == 0) throw new Exception("нет ссылки для скачивания");
                        out = "{\"ok\":true,\"name\":" + JSONObject.quote(bestName) + ",\"version\":" + JSONObject.quote(bestVer) + ",\"size\":" + bestSize + ",\"href\":" + JSONObject.quote(href) + "}";
                    }
                } catch (Exception e) {
                    out = "{\"ok\":false,\"error\":" + JSONObject.quote("Не удалось проверить обновления: " + e.getMessage()) + "}";
                }
                updJs("window.__updCheckResult && window.__updCheckResult(" + out + ")");
            }}).start();
        }

        @JavascriptInterface public void updDownload(final String href) {
            new Thread(new Runnable() { @Override public void run() {
                File dir = new File(getCacheDir(), "shared"); dir.mkdirs();
                File outFile = new File(dir, UPD_APK_NAME);
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(href).openConnection();
                    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android)");
                    c.setConnectTimeout(30000); c.setReadTimeout(60000);
                    int code = c.getResponseCode();
                    if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
                    long total = c.getContentLengthLong();
                    java.io.InputStream in = c.getInputStream();
                    FileOutputStream fos = new FileOutputStream(outFile);
                    byte[] buf = new byte[32768]; int n; long done = 0;
                    long lastReport = System.currentTimeMillis(), lastBytes = 0;
                    while ((n = in.read(buf)) > 0) {
                        fos.write(buf, 0, n); done += n;
                        long now = System.currentTimeMillis();
                        if (now - lastReport >= 400) {
                            long kbps = (done - lastBytes) * 1000 / Math.max(1, now - lastReport) / 1024;
                            final long d = done, t2 = total, s = kbps;
                            updJs("window.__updProgress && window.__updProgress(" + d + "," + t2 + "," + s + ")");
                            lastReport = now; lastBytes = done;
                        }
                    }
                    fos.close(); in.close();
                    PackageManager pm = getPackageManager();
                    PackageInfo arch = pm.getPackageArchiveInfo(outFile.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
                    if (arch == null) throw new Exception("не удалось прочитать скачанный файл");
                    PackageInfo inst = pm.getPackageInfo(getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
                    if (arch.versionCode <= inst.versionCode) throw new Exception("скачанная версия не новее установленной");
                    String sArch = updSigner(arch), sInst = updSigner(inst);
                    if (sArch == null || sInst == null || !sArch.equals(sInst)) throw new Exception("подпись файла не совпадает с установленной программой");
                    updJs("window.__updDownloadDone && window.__updDownloadDone({\"ok\":true,\"versionName\":" + JSONObject.quote(arch.versionName) + ",\"versionCode\":" + arch.versionCode + "})");
                } catch (Exception e) {
                    try { outFile.delete(); } catch (Exception ignored) {}
                    updJs("window.__updDownloadDone && window.__updDownloadDone({\"ok\":false,\"error\":" + JSONObject.quote(e.getMessage()) + "})");
                }
            }}).start();
        }

        private String updSigner(PackageInfo pi) {
            try {
                if (android.os.Build.VERSION.SDK_INT >= 28 && pi.signingInfo != null) {
                    Signature[] sigs = pi.signingInfo.getApkContentsSigners();
                    if (sigs != null && sigs.length > 0) return Base64.encodeToString(sigs[0].toByteArray(), Base64.NO_WRAP);
                }
                if (pi.signatures != null && pi.signatures.length > 0) return Base64.encodeToString(pi.signatures[0].toByteArray(), Base64.NO_WRAP);
            } catch (Exception ignored) {}
            return null;
        }

        @JavascriptInterface public void updInstall() {
            runOnUiThread(new Runnable() { @Override public void run() {
                try {
                    Uri uri = Uri.parse("content://com.smeta.pro2.fileprovider/shared/" + UPD_APK_NAME);
                    Intent it = new Intent(Intent.ACTION_VIEW);
                    it.setDataAndType(uri, "application/vnd.android.package-archive");
                    it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(it);
                } catch (Exception e) { toast("Не удалось начать установку: " + e.getMessage()); }
            }});
        }
    }

    @Override
    public void onBackPressed() {
        if (webView == null) { super.onBackPressed(); return; }
        webView.evaluateJavascript(
            "(function(){ try { return (typeof window.__smetaBack === 'function') ? window.__smetaBack() : 'app'; } catch(e) { return 'app'; } })()",
            new android.webkit.ValueCallback<String>() {
                @Override public void onReceiveValue(String value) {
                    String v = (value == null) ? "" : value.replace("\"", "").trim();
                    if ("handled".equals(v)) return;
                    if (webView.canGoBack()) { webView.goBack(); return; }
                    MainActivity.super.onBackPressed();
                }
            });
    }
}
