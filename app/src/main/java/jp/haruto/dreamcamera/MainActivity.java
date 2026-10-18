package jp.haruto.dreamcamera;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Range;
import android.util.Size;
import android.view.*;
import android.widget.*;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.*;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.*;
import androidx.camera.core.Camera;
import androidx.camera.core.*;
import androidx.camera.extensions.ExtensionMode;
import androidx.camera.extensions.ExtensionsManager;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.exifinterface.media.ExifInterface;
import com.google.android.gms.tasks.Tasks;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.objects.DetectedObject;
import com.google.mlkit.vision.objects.ObjectDetection;
import com.google.mlkit.vision.objects.ObjectDetector;
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends AppCompatActivity {
    static final String[] TABS = {"light", "color", "mixer", "grade", "effect", "camera"};
    static final String[] TAB_LABELS = {"ライト", "カラー", "ミキサー", "グレーディング", "効果", "撮影"};
    static final String[] CATS = {"aero", "film", "my"};
    static final String[] CAT_LABELS = {"Aero", "いろいろ", "マイ"};
    /** メイン画面のかんたん調整：強さ(%)・明るさ(0.1EV)・暖かさ */
    static final String[] QUICK_LABELS = {"強さ", "明るさ", "暖かさ"};
    static final float[] QUICK_MIN = {0, -20, -50}, QUICK_MAX = {100, 20, 50}, QUICK_DEF = {100, 0, 0};
    static final int BG = 0xFF0A0D12, TEXT = 0xFFE8F1F7, SUB = 0xFF9FB3C2;

    PreviewView preview; OverlayView overlay; View flash;
    LinearLayout bottom, editor, editorBody, tabRow, catRow, thumbRow, quickTabs, shareBar, modeRow;
    TextView title, status, quickValue; Button shutter, quickReset; SeekBar quickBar; ImageView lastThumb; EditText nameField;
    ImageCapture capture; Camera camera; ProcessCameraProvider provider; ExtensionsManager extensions;
    ArrayList<Preset> presets; Preset current, editing;
    final Map<Preset, ImageView> thumbViews = new LinkedHashMap<>();
    final ExecutorService io = Executors.newSingleThreadExecutor(), thumbExec = Executors.newSingleThreadExecutor(), detectExec = Executors.newSingleThreadExecutor();
    final Handler ui = new Handler(Looper.getMainLooper());
    SharedPreferences prefs;
    OnBackPressedCallback backToCancel;
    String cat = "aero", tab = "light";
    int quick = 0, mixColor = 4, zone = 0;
    boolean back, hdrWanted, hdrActive, saveOriginal, volumeShutter, comparing, previewQueued, busy, thumbBusy, resumed, settingQuick, detecting;
    float appliedEv = Float.NaN;
    long grainSeed = System.nanoTime();
    Uri pendingShared, lastUri;
    Bitmap sample;
    final Comedian.State com = new Comedian.State();
    ObjectDetector detector;

    final Runnable thumbTick = new Runnable() { @Override public void run() { refreshThumbs(); if (resumed) ui.postDelayed(this, 2500); } };
    final Runnable hintBack = this::showHint;
    final Runnable detectTick = new Runnable() { @Override public void run() { detectCenter(); if (resumed && com.mode == Comedian.TAPE) ui.postDelayed(this, 400); } };

    ActivityResultLauncher<String> permission = registerForActivityResult(new ActivityResultContracts.RequestPermission(), ok -> { if (ok) startCamera(); else say("カメラ権限が必要です（設定から許可してください）"); });
    ActivityResultLauncher<String> photoPicker = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> { if (uri != null) processExisting(uri); });

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        prefs = getSharedPreferences("p", 0);
        hdrWanted = prefs.getBoolean("hdr", true);
        back = prefs.getBoolean("back", true);
        saveOriginal = prefs.getBoolean("original", true);
        volumeShutter = prefs.getBoolean("volume", true);
        String lu = prefs.getString("lastUri", null);
        lastUri = lu != null ? Uri.parse(lu) : null;
        presets = PresetStore.load(this);
        current = find(prefs.getString("last", PresetStore.DEFAULT_NAME));
        cat = current.category;
        com.mode = prefs.getInt("comedian", Comedian.OFF);
        com.bx = prefs.getFloat("banana_x", 0.5f);
        com.by = prefs.getFloat("banana_y", 0.5f);
        com.blen = prefs.getFloat("banana_len", 0.5f);
        buildUi();
        overlay.comedian = com;
        refreshModes();
        overlay.setGrid(prefs.getBoolean("grid", false));
        refreshCats();
        refreshQuick();
        preview.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> { if (r - l != or - ol || bt - t != ob - ot) applyPreview(); });
        applyPreview();
        showHint();
        loadLastThumb();
        backToCancel = new OnBackPressedCallback(false) { @Override public void handleOnBackPressed() { closeEditor(false); } };
        getOnBackPressedDispatcher().addCallback(this, backToCancel);
        handleShare(getIntent());
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera();
        else if (pendingShared == null) permission.launch(Manifest.permission.CAMERA);
    }

    @Override protected void onResume() { super.onResume(); resumed = true; ui.removeCallbacks(thumbTick); ui.post(thumbTick); ui.removeCallbacks(detectTick); ui.post(detectTick); }

    @Override protected void onPause() { super.onPause(); resumed = false; ui.removeCallbacks(thumbTick); ui.removeCallbacks(detectTick); }

    Preset find(String name) {
        for (Preset p : presets) if (p.name.equals(name)) return p;
        return presets.get(0);
    }

    Preset active() { return editing != null ? editing : current; }

    // ---------- かんたん調整（プリセットごとに記憶） ----------

    float[] quickOf(Preset p) {
        float[] q = QUICK_DEF.clone();
        String s = prefs.getString("q_" + p.name, null);
        if (s != null) {
            String[] a = s.split(",");
            for (int i = 0; i < 3 && i < a.length; i++) try { q[i] = Float.parseFloat(a[i]); } catch (NumberFormatException ignored) {}
        }
        return q;
    }

    void saveQuick(Preset p, float[] q) { prefs.edit().putString("q_" + p.name, q[0] + "," + q[1] + "," + q[2]).apply(); }

    /** 実際に使う設定：プリセット＋かんたん調整。 */
    Preset effective(Preset base) {
        float[] q = quickOf(editing != null && base == editing ? current : base);
        Preset e = base.copy(base.name);
        e.set("amount", q[0]);
        e.set("camera_ev", base.get("camera_ev") + q[1]);
        e.set("temp", base.get("temp") + q[2]);
        return e;
    }

    // ---------- 画面 ----------

    void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        Button settings = small("⚙");
        settings.setTextSize(18);
        settings.setContentDescription("設定");
        settings.setOnClickListener(v -> openSettings());
        top.addView(settings, new LinearLayout.LayoutParams(dp(48), dp(40)));
        title = new TextView(this);
        title.setTextColor(Color.WHITE);
        title.setTextSize(17);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button flip = small("⟲");
        flip.setTextSize(18);
        flip.setContentDescription("カメラ切替");
        flip.setOnClickListener(v -> { back = !back; prefs.edit().putBoolean("back", back).apply(); bindCamera(); });
        top.addView(flip, new LinearLayout.LayoutParams(dp(48), dp(40)));
        root.addView(top, new LinearLayout.LayoutParams(-1, -2));

        // 保存される範囲（4:3）だけを表示する
        FrameLayout stage = new FrameLayout(this);
        AspectFrame box = new AspectFrame(this);
        preview = new PreviewView(this);
        preview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        preview.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        box.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        overlay = new OverlayView(this);
        box.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        flash = new View(this);
        flash.setBackgroundColor(Color.WHITE);
        flash.setAlpha(0);
        box.addView(flash, new FrameLayout.LayoutParams(-1, -1));
        stage.addView(box, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
        setupGestures();

        bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        modeRow = new LinearLayout(this);
        modeRow.setPadding(0, dp(8), 0, 0);
        bottom.addView(modeRow);
        shareBar = new LinearLayout(this);
        shareBar.setGravity(Gravity.CENTER_VERTICAL);
        shareBar.setVisibility(View.GONE);
        TextView st = label("共有された写真があります", 13, TEXT);
        shareBar.addView(st, new LinearLayout.LayoutParams(0, -2, 1));
        Button go = small("この色で加工");
        go.setPadding(dp(12), 0, dp(12), 0);
        go.setBackground(pill(true));
        go.setOnClickListener(v -> { Uri u = pendingShared; setShared(null); if (u != null) processExisting(u); });
        shareBar.addView(go, new LinearLayout.LayoutParams(-2, dp(36)));
        Button no = small("×");
        no.setOnClickListener(v -> setShared(null));
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(dp(40), dp(36));
        np.setMargins(dp(6), 0, 0, 0);
        shareBar.addView(no, np);
        bottom.addView(shareBar, new LinearLayout.LayoutParams(-1, -2));

        catRow = new LinearLayout(this);
        catRow.setPadding(0, dp(8), 0, dp(2));
        bottom.addView(catRow);
        HorizontalScrollView ts = new HorizontalScrollView(this);
        ts.setHorizontalScrollBarEnabled(false);
        thumbRow = new LinearLayout(this);
        ts.addView(thumbRow);
        bottom.addView(ts, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout qr = new LinearLayout(this);
        qr.setGravity(Gravity.CENTER_VERTICAL);
        qr.setPadding(0, dp(6), 0, 0);
        quickTabs = new LinearLayout(this);
        qr.addView(quickTabs, new LinearLayout.LayoutParams(0, -2, 1));
        quickValue = label("", 14, 0xFFB3E5FC);
        qr.addView(quickValue);
        quickReset = small("↺");
        quickReset.setContentDescription("元に戻す");
        quickReset.setOnClickListener(v -> setQuickValue(QUICK_DEF[quick], true));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(dp(40), dp(32));
        rp.setMargins(dp(8), 0, 0, 0);
        qr.addView(quickReset, rp);
        bottom.addView(qr, new LinearLayout.LayoutParams(-1, -2));
        quickBar = new SeekBar(this);
        quickBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int n, boolean user) { if (!settingQuick) setQuickValue(QUICK_MIN[quick] + n, false); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) { refreshThumbs(); }
        });
        bottom.addView(quickBar, new LinearLayout.LayoutParams(-1, dp(36)));

        status = label("", 13, SUB);
        status.setGravity(Gravity.CENTER);
        status.setMaxLines(2);
        status.setPadding(0, dp(2), 0, dp(6));
        bottom.addView(status, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        lastThumb = new ImageView(this);
        lastThumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        lastThumb.setBackground(rounded(0x33FFFFFF, 0x66FFFFFF, 12));
        lastThumb.setClipToOutline(true);
        lastThumb.setContentDescription("最後に撮った写真");
        lastThumb.setOnClickListener(v -> openLast());
        FrameLayout l = new FrameLayout(this);
        l.addView(lastThumb, new FrameLayout.LayoutParams(dp(54), dp(54), Gravity.CENTER));
        actions.addView(l, new LinearLayout.LayoutParams(0, dp(88), 1));
        shutter = button("");
        shutter.setContentDescription("撮影");
        shutter.setBackground(shutterDrawable());
        shutter.setOnClickListener(v -> shoot());
        actions.addView(shutter, new LinearLayout.LayoutParams(dp(80), dp(80)));
        Button edit = small("調整");
        edit.setTextSize(14);
        edit.setOnClickListener(v -> openEditor());
        FrameLayout r = new FrameLayout(this);
        r.addView(edit, new FrameLayout.LayoutParams(dp(64), dp(44), Gravity.CENTER));
        actions.addView(r, new LinearLayout.LayoutParams(0, dp(88), 1));
        bottom.addView(actions, new LinearLayout.LayoutParams(-1, -2));
        root.addView(bottom, new LinearLayout.LayoutParams(-1, -2));

        buildEditor(root);
        root.setOnApplyWindowInsetsListener((v, ins) -> {
            android.graphics.Insets s = ins.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            top.setPadding(dp(8) + s.left, s.top + dp(4), dp(8) + s.right, dp(4));
            bottom.setPadding(dp(14) + s.left, 0, dp(14) + s.right, s.bottom + dp(10));
            editor.setPadding(dp(14) + s.left, dp(8), dp(14) + s.right, s.bottom + dp(8));
            return WindowInsets.CONSUMED;
        });
        setContentView(root);
    }

    void buildEditor(LinearLayout root) {
        editor = new LinearLayout(this);
        editor.setOrientation(LinearLayout.VERTICAL);
        editor.setBackgroundColor(0xFF10151C);
        editor.setVisibility(View.GONE);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        Button cancel = small("✕");
        cancel.setContentDescription("やめる");
        cancel.setOnClickListener(v -> closeEditor(false));
        head.addView(cancel, new LinearLayout.LayoutParams(dp(44), dp(40)));
        nameField = new EditText(this);
        nameField.setSingleLine(true);
        nameField.setTextColor(Color.WHITE);
        nameField.setTextSize(15);
        nameField.setHintTextColor(0x88FFFFFF);
        nameField.setHint("プリセット名");
        LinearLayout.LayoutParams nfp = new LinearLayout.LayoutParams(0, -2, 1);
        nfp.setMargins(dp(8), 0, dp(8), 0);
        head.addView(nameField, nfp);
        Button compare = small("比較");
        compare.setOnTouchListener((v, e) -> {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) { comparing = true; applyPreview(); }
            else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) { comparing = false; applyPreview(); }
            return true;
        });
        head.addView(compare, new LinearLayout.LayoutParams(dp(56), dp(40)));
        Button save = small("保存");
        save.setBackground(pill(true));
        save.setOnClickListener(v -> closeEditor(true));
        LinearLayout.LayoutParams svp = new LinearLayout.LayoutParams(dp(60), dp(40));
        svp.setMargins(dp(6), 0, 0, 0);
        head.addView(save, svp);
        editor.addView(head);
        HorizontalScrollView ts = new HorizontalScrollView(this);
        ts.setHorizontalScrollBarEnabled(false);
        tabRow = new LinearLayout(this);
        ts.addView(tabRow);
        editor.addView(ts, new LinearLayout.LayoutParams(-1, -2));
        ScrollView body = new ScrollView(this);
        editorBody = new LinearLayout(this);
        editorBody.setOrientation(LinearLayout.VERTICAL);
        editorBody.setPadding(0, dp(4), 0, dp(8));
        body.addView(editorBody);
        editor.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(editor, new LinearLayout.LayoutParams(-1, (int) (getResources().getDisplayMetrics().heightPixels * 0.46f)));
    }

    void setupGestures() {
        ScaleGestureDetector sd = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                if (com.mode == Comedian.BANANA) {
                    com.blen = Math.max(0.15f, Math.min(1.2f, com.blen * d.getScaleFactor()));
                    overlay.invalidate();
                    return true;
                }
                if (camera == null) return false;
                ZoomState z = camera.getCameraInfo().getZoomState().getValue();
                if (z == null) return false;
                float r = Math.max(z.getMinZoomRatio(), Math.min(z.getMaxZoomRatio(), z.getZoomRatio() * d.getScaleFactor()));
                camera.getCameraControl().setZoomRatio(r);
                overlay.showZoom(r);
                return true;
            }
        });
        GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapUp(MotionEvent e) {
                if (com.mode == Comedian.TAPE) { com.tx = e.getX() / overlay.getWidth(); com.ty = e.getY() / overlay.getHeight(); detectCenter(); }
                focusAt(e.getX(), e.getY());
                return true;
            }
            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (com.mode != Comedian.BANANA || sd.isInProgress()) return false;
                com.bx = Math.max(0f, Math.min(1f, com.bx - dx / overlay.getWidth()));
                com.by = Math.max(0f, Math.min(1f, com.by - dy / overlay.getHeight()));
                overlay.invalidate();
                return true;
            }
            @Override public void onLongPress(MotionEvent e) {
                if (sd.isInProgress()) return;
                comparing = true; applyPreview(); status.setText("加工前を表示中（指を離すと戻る）");
            }
        });
        overlay.setOnTouchListener((v, e) -> {
            sd.onTouchEvent(e);
            if (e.getPointerCount() == 1) gd.onTouchEvent(e);
            int a = e.getActionMasked();
            if ((a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) && comparing) { comparing = false; applyPreview(); showHint(); }
            if (a == MotionEvent.ACTION_UP && com.mode == Comedian.BANANA) prefs.edit().putFloat("banana_x", com.bx).putFloat("banana_y", com.by).putFloat("banana_len", com.blen).apply();
            return true;
        });
    }

    /** 中に 3:4 の枠を、置ける最大の大きさで作る。 */
    static final class AspectFrame extends FrameLayout {
        AspectFrame(Context c) { super(c); }

        @Override protected void onMeasure(int ws, int hs) {
            int W = MeasureSpec.getSize(ws), H = MeasureSpec.getSize(hs), w = W, h = W * 4 / 3;
            if (H > 0 && h > H) { h = H; w = H * 3 / 4; }
            super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
        }
    }

    TextView label(String s, int sp, int color) { TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(color); return t; }

    Button button(String s) { Button b = new Button(this); b.setText(s); b.setAllCaps(false); return b; }

    Button small(String s) {
        Button b = button(s);
        b.setTextSize(13); b.setTextColor(Color.WHITE); b.setPadding(0, 0, 0, 0); b.setMinWidth(0); b.setMinHeight(0); b.setMinimumWidth(0); b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        b.setBackground(pill(false));
        return b;
    }

    GradientDrawable rounded(int fill, int stroke, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(radius));
        g.setColor(fill);
        if (stroke != 0) g.setStroke(dp(2), stroke);
        return g;
    }

    GradientDrawable pill(boolean on) {
        GradientDrawable g = rounded(on ? 0xCC0288D1 : 0x33FFFFFF, 0, 18);
        g.setStroke(dp(1), on ? 0xFFB3E5FC : 0x33FFFFFF);
        return g;
    }

    Drawable shutterDrawable() {
        GradientDrawable outer = new GradientDrawable();
        outer.setShape(GradientDrawable.OVAL);
        outer.setColor(0x00000000);
        outer.setStroke(dp(4), Color.WHITE);
        GradientDrawable inner = new GradientDrawable();
        inner.setShape(GradientDrawable.OVAL);
        inner.setColor(Color.WHITE);
        LayerDrawable ld = new LayerDrawable(new Drawable[]{outer, inner});
        ld.setLayerInset(1, dp(8), dp(8), dp(8), dp(8));
        return ld;
    }

    int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }

    void say(String s) {
        status.setText(s);
        ui.removeCallbacks(hintBack);
        ui.postDelayed(hintBack, 4000);
    }

    void showHint() {
        ui.removeCallbacks(hintBack);
        if (com.mode == Comedian.BANANA) { status.setText("バナナはドラッグで移動 · 2本指で大きさ"); return; }
        if (com.mode == Comedian.TAPE) { status.setText("真ん中の物にテープを貼ります · タップで貼る物を選ぶ"); return; }
        String h = PresetStore.hint(current.name);
        status.setText(h != null ? h : "タップでピント · 2本指でズーム · 長押しで加工前");
    }

    // ---------- コメディアン（バナナ・テープ） ----------

    void refreshModes() {
        modeRow.removeAllViews();
        for (int i = 0; i < Comedian.LABELS.length; i++) {
            int m = i;
            Button b = small(Comedian.LABELS[i]);
            b.setPadding(dp(12), 0, dp(12), 0);
            b.setBackground(pill(i == com.mode));
            b.setOnClickListener(v -> setMode(m));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(32));
            lp.setMargins(0, 0, dp(6), 0);
            modeRow.addView(b, lp);
        }
    }

    void setMode(int m) {
        com.mode = m;
        prefs.edit().putInt("comedian", m).apply();
        if (m == Comedian.TAPE) { com.tx = 0.5f; com.ty = 0.5f; ui.removeCallbacks(detectTick); ui.post(detectTick); }
        refreshModes();
        overlay.invalidate();
        showHint();
    }

    ObjectDetector detector() {
        if (detector == null) detector = ObjectDetection.getClient(new ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE).enableMultipleObjects().build());
        return detector;
    }

    /** 物の範囲を探す（割合で返す）。見つからなければ target の周りの四角。重いので UI スレッド以外で呼ぶ。 */
    RectF findThing(Bitmap src, float tx, float ty) throws Exception {
        float k = 480f / Math.max(src.getWidth(), src.getHeight());
        Bitmap s = k < 1 ? Bitmap.createScaledBitmap(src, Math.max(1, Math.round(src.getWidth() * k)), Math.max(1, Math.round(src.getHeight() * k)), true) : src;
        List<DetectedObject> found = Tasks.await(detector().process(InputImage.fromBitmap(s, 0)));
        List<Rect> boxes = new ArrayList<>();
        for (DetectedObject o : found) boxes.add(o.getBoundingBox());
        return Comedian.pick(boxes, s.getWidth(), s.getHeight(), tx, ty);
    }

    void detectCenter() {
        if (com.mode != Comedian.TAPE || detecting || editor.getVisibility() == View.VISIBLE) return;
        Bitmap b = null;
        try { b = preview.getBitmap(); } catch (Exception ignored) {}
        if (b == null) return;
        Bitmap src = b;
        float tx = com.tx, ty = com.ty;
        detecting = true;
        detectExec.execute(() -> {
            RectF r = null;
            try { r = findThing(src, tx, ty); } catch (Exception ignored) {}
            RectF found = r;
            runOnUiThread(() -> {
                detecting = false;
                if (found == null) return;
                // 揺れないように少しずつ寄せる
                float a = 0.5f;
                com.box.set(com.box.left + (found.left - com.box.left) * a, com.box.top + (found.top - com.box.top) * a,
                        com.box.right + (found.right - com.box.right) * a, com.box.bottom + (found.bottom - com.box.bottom) * a);
                overlay.invalidate();
            });
        });
    }

    /** 加工版に描き込む（色の加工の前に描くので、バナナにも同じ色が乗る）。 */
    static void stick(Bitmap b, Comedian.State s, boolean mirror) {
        if (s.mode == Comedian.OFF) return;
        Comedian.draw(new Canvas(b), s, b.getWidth(), b.getHeight(), mirror);
    }

    static String suffix(Comedian.State s) { return s.mode == Comedian.BANANA ? "_BANANA" : s.mode == Comedian.TAPE ? "_TAPE" : ""; }

    // ---------- プリセット一覧 ----------

    void refreshCats() {
        catRow.removeAllViews();
        for (int i = 0; i < CATS.length; i++) {
            String c = CATS[i];
            Button b = small(CAT_LABELS[i]);
            b.setPadding(dp(14), 0, dp(14), 0);
            b.setBackground(pill(c.equals(cat)));
            b.setOnClickListener(v -> { cat = c; refreshCats(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(32));
            lp.setMargins(0, 0, dp(6), 0);
            catRow.addView(b, lp);
        }
        refreshThumbRow();
    }

    void refreshThumbRow() {
        thumbRow.removeAllViews();
        thumbViews.clear();
        for (Preset p : presets) {
            if (!p.category.equals(cat)) continue;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            boolean sel = p == current;
            iv.setBackground(rounded(0xFF1C2530, sel ? 0xFF4FC3F7 : 0, 12));
            iv.setPadding(sel ? dp(3) : 0, sel ? dp(3) : 0, sel ? dp(3) : 0, sel ? dp(3) : 0);
            iv.setClipToOutline(true);
            item.addView(iv, new LinearLayout.LayoutParams(dp(64), dp(64)));
            TextView t = label(p.name, 11, sel ? Color.WHITE : SUB);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(3), 0, 0);
            item.addView(t, new LinearLayout.LayoutParams(dp(72), -2));
            item.setOnClickListener(v -> { if (p == current) openEditor(); else select(p); });
            item.setOnLongClickListener(v -> { if (p.builtin) { String h = PresetStore.hint(p.name); say(h != null ? h : p.name); } else manage(p); return true; });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(0, dp(6), dp(4), 0);
            thumbRow.addView(item, lp);
            thumbViews.put(p, iv);
        }
        if (thumbViews.isEmpty()) {
            TextView t = label("「調整」で色を作って保存すると、ここに並びます", 13, SUB);
            t.setPadding(dp(4), dp(28), dp(4), dp(28));
            thumbRow.addView(t);
        }
        refreshThumbs();
    }

    void refreshThumbs() {
        if (thumbBusy || thumbViews.isEmpty() || editor.getVisibility() == View.VISIBLE) return;
        Bitmap src = null;
        try { src = preview.getBitmap(); } catch (Exception ignored) {}
        if (src == null) { if (sample == null) sample = Thumbs.sample(120); src = sample; }
        Bitmap sq = Thumbs.square(src, 120);
        Map<Preset, Preset> jobs = new LinkedHashMap<>();
        for (Preset p : thumbViews.keySet()) jobs.put(p, effective(p));
        thumbBusy = true;
        thumbExec.execute(() -> {
            for (Map.Entry<Preset, Preset> j : jobs.entrySet()) {
                Bitmap t = Thumbs.render(sq, j.getValue());
                runOnUiThread(() -> { ImageView iv = thumbViews.get(j.getKey()); if (iv != null) iv.setImageBitmap(t); });
            }
            runOnUiThread(() -> thumbBusy = false);
        });
    }

    void select(Preset p) {
        current = p;
        prefs.edit().putString("last", p.name).apply();
        refreshThumbRow();
        refreshQuick();
        applyPreview();
        showHint();
    }

    void manage(Preset p) {
        new MaterialAlertDialogBuilder(this).setTitle(p.name)
                .setItems(new String[]{"名前を変える", "削除する"}, (d, w) -> { if (w == 0) rename(p); else confirmDelete(p); })
                .show();
    }

    void rename(Preset p) {
        EditText f = new EditText(this);
        f.setSingleLine(true);
        f.setText(p.name);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(dp(20), dp(8), dp(20), 0);
        wrap.addView(f);
        new MaterialAlertDialogBuilder(this).setTitle("名前を変える").setView(wrap)
                .setNegativeButton("やめる", null)
                .setPositiveButton("決定", (d, w) -> {
                    String n = uniqueName(f.getText().toString().trim(), p);
                    float[] q = quickOf(p);
                    prefs.edit().remove("q_" + p.name).apply();
                    p.name = n;
                    saveQuick(p, q);
                    if (p == current) prefs.edit().putString("last", n).apply();
                    PresetStore.saveCustom(this, presets);
                    refreshThumbRow(); applyPreview();
                }).show();
    }

    String uniqueName(String n, Preset self) {
        if (n.isEmpty()) n = "マイプリセット";
        String base = n;
        for (int i = 2; ; i++) {
            boolean clash = false;
            for (Preset q : presets) if (q != self && q.name.equals(n)) { clash = true; break; }
            if (!clash) return n;
            n = base + " " + i;
        }
    }

    void confirmDelete(Preset p) {
        new MaterialAlertDialogBuilder(this).setTitle("「" + p.name + "」を削除しますか？")
                .setNegativeButton("やめる", null)
                .setPositiveButton("削除", (d, w) -> {
                    presets.remove(p);
                    prefs.edit().remove("q_" + p.name).apply();
                    PresetStore.saveCustom(this, presets);
                    if (current == p) select(presets.get(0)); else refreshThumbRow();
                }).show();
    }

    // ---------- かんたん調整 ----------

    void refreshQuick() {
        quickTabs.removeAllViews();
        for (int i = 0; i < QUICK_LABELS.length; i++) {
            int ii = i;
            Button b = small(QUICK_LABELS[i]);
            b.setPadding(dp(12), 0, dp(12), 0);
            b.setBackground(pill(i == quick));
            b.setOnClickListener(v -> { quick = ii; refreshQuick(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(32));
            lp.setMargins(0, 0, dp(6), 0);
            quickTabs.addView(b, lp);
        }
        float v = quickOf(current)[quick];
        settingQuick = true;
        quickBar.setMax(Math.round(QUICK_MAX[quick] - QUICK_MIN[quick]));
        quickBar.setProgress(Math.round(v - QUICK_MIN[quick]));
        settingQuick = false;
        showQuickValue(v);
    }

    void setQuickValue(float v, boolean moveBar) {
        float[] q = quickOf(current);
        q[quick] = v;
        saveQuick(current, q);
        if (moveBar) { settingQuick = true; quickBar.setProgress(Math.round(v - QUICK_MIN[quick])); settingQuick = false; refreshThumbs(); }
        showQuickValue(v);
        queuePreview();
    }

    void showQuickValue(float v) {
        int n = Math.round(v);
        if (quick == 0) quickValue.setText(n + "%");
        else if (quick == 1) quickValue.setText(String.format(Locale.JAPAN, "%+.1f EV", v / 10f));
        else quickValue.setText(n > 0 ? "+" + n : String.valueOf(n));
        quickReset.setVisibility(v == QUICK_DEF[quick] ? View.INVISIBLE : View.VISIBLE);
    }

    // ---------- プレビュー ----------

    void applyPreview() {
        Preset p = effective(active());
        title.setText(p.name);
        int w = preview.getWidth(), h = preview.getHeight();
        if (w == 0 || h == 0) { DisplayMetrics dm = getResources().getDisplayMetrics(); w = dm.widthPixels; h = w * 4 / 3; }
        if (comparing) preview.setRenderEffect(null);
        else {
            try { preview.setRenderEffect(PreviewEffects.build(p, w, h)); }
            catch (Exception e) { preview.setRenderEffect(null); say("プレビュー効果エラー: " + e.getMessage()); }
        }
        applyCameraEv(p);
    }

    void queuePreview() {
        if (previewQueued) return;
        previewQueued = true;
        ui.postDelayed(() -> { previewQueued = false; applyPreview(); }, 40);
    }

    // ---------- 詳しい調整 ----------

    void openEditor() {
        editing = current.copy(current.builtin ? current.name + " カスタム" : current.name);
        nameField.setText(editing.name);
        bottom.setVisibility(View.GONE);
        editor.setVisibility(View.VISIBLE);
        backToCancel.setEnabled(true);
        showTab(tab);
        applyPreview();
    }

    void closeEditor(boolean save) {
        if (editing == null) return;
        if (save) {
            Preset saved = editing;
            saved.builtin = false;
            saved.category = "my";
            Preset same = null;
            for (Preset p : presets) if (!p.builtin && p.name.equals(nameField.getText().toString().trim())) same = p;
            if (same != null) {
                saved.name = same.name;
                presets.set(presets.indexOf(same), saved);
            } else {
                saved.name = uniqueName(nameField.getText().toString().trim(), null);
                presets.add(saved);
            }
            saveQuick(saved, quickOf(current));
            PresetStore.saveCustom(this, presets);
            editing = null;
            cat = "my";
            current = saved;
            prefs.edit().putString("last", saved.name).apply();
        }
        editing = null;
        comparing = false;
        editor.setVisibility(View.GONE);
        bottom.setVisibility(View.VISIBLE);
        backToCancel.setEnabled(false);
        refreshCats();
        refreshQuick();
        applyPreview();
        if (save) say("「" + current.name + "」を保存しました（マイ）"); else showHint();
    }

    void showTab(String t) {
        tab = t;
        tabRow.removeAllViews();
        for (int i = 0; i < TABS.length; i++) {
            String id = TABS[i];
            Button b = small(TAB_LABELS[i]);
            b.setPadding(dp(12), 0, dp(12), 0);
            b.setBackground(pill(id.equals(t)));
            b.setOnClickListener(v -> showTab(id));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(34));
            lp.setMargins(0, dp(6), dp(6), dp(4));
            tabRow.addView(b, lp);
        }
        editorBody.removeAllViews();
        switch (t) {
            case "mixer": {
                LinearLayout dots = new LinearLayout(this);
                dots.setGravity(Gravity.CENTER);
                for (int k = 0; k < Param.MIX_IDS.length; k++) {
                    int kk = k, col = Param.MIX_COLORS[k];
                    View dot = new View(this);
                    GradientDrawable g = new GradientDrawable();
                    g.setShape(GradientDrawable.OVAL);
                    g.setColor(k == mixColor ? col : 0x00000000);
                    g.setStroke(dp(3), col);
                    dot.setBackground(g);
                    dot.setContentDescription(Param.MIX_LABELS[k]);
                    dot.setOnClickListener(v -> { mixColor = kk; showTab("mixer"); });
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(30), dp(30));
                    lp.setMargins(dp(5), dp(6), dp(5), dp(2));
                    dots.addView(dot, lp);
                }
                editorBody.addView(dots);
                String c = Param.MIX_IDS[mixColor];
                addSlider(Param.get("mix_h_" + c), Param.MIX_LABELS[mixColor] + "　色相");
                addSlider(Param.get("mix_s_" + c), Param.MIX_LABELS[mixColor] + "　彩度");
                addSlider(Param.get("mix_l_" + c), Param.MIX_LABELS[mixColor] + "　輝度");
                break;
            }
            case "grade": {
                LinearLayout zones = new LinearLayout(this);
                for (int z = 0; z < Param.ZONES.length; z++) {
                    int zz = z;
                    Button b = small(Param.ZONE_LABELS[z]);
                    b.setBackground(pill(z == zone));
                    b.setOnClickListener(v -> { zone = zz; showTab("grade"); });
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(32), 1);
                    lp.setMargins(dp(3), dp(4), dp(3), dp(2));
                    zones.addView(b, lp);
                }
                editorBody.addView(zones);
                String z = Param.ZONES[zone];
                addSlider(Param.get(z + "_hue"), "色相");
                addSlider(Param.get(z + "_sat"), "彩度");
                addSlider(Param.get(z + "_lum"), "輝度");
                addSlider(Param.get("grade_blend"), "ブレンド");
                addSlider(Param.get("grade_balance"), "バランス（＋でハイライト寄り）");
                break;
            }
            default:
                for (Param d : Param.group(t)) addSlider(d, d.label);
        }
        if (t.equals("camera")) note("空を白飛びさせないには −0.3〜−0.7 EV がおすすめ。この値はプリセットに保存され、プレビューにもそのまま反映されます。");
        if (t.equals("effect")) note("光のにじみ＝明るい所がふわっと光る。周辺光量をマイナスにすると四隅が白くなる。明瞭度は保存した写真にだけ効きます。");
    }

    void note(String s) {
        TextView n = label(s, 12, 0x99D8E8F2);
        n.setPadding(dp(4), dp(10), dp(4), dp(4));
        editorBody.addView(n);
    }

    void addSlider(Param d, String label) {
        int i = Param.idx(d.id);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(4), dp(6), dp(4), 0);
        head.addView(label(label, 14, TEXT), new LinearLayout.LayoutParams(0, -2, 1));
        TextView val = label(format(d, editing.v[i]), 14, 0xFFB3E5FC);
        head.addView(val);
        Button reset = small("↺");
        reset.setContentDescription("初期値に戻す");
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(dp(34), dp(26));
        rp.setMargins(dp(8), 0, 0, 0);
        head.addView(reset, rp);
        reset.setVisibility(editing.v[i] == d.def ? View.INVISIBLE : View.VISIBLE);
        editorBody.addView(head);
        SeekBar bar = new SeekBar(this);
        bar.setMax(Math.round(d.max - d.min));
        if (d.id.endsWith("_hue") && d.group.equals("grade")) {
            GradientDrawable rainbow = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFF0000FF, 0xFFFF00FF, 0xFFFF0000});
            rainbow.setCornerRadius(dp(3));
            bar.setBackground(new InsetDrawable(rainbow, dp(16), dp(13), dp(16), dp(13)));
            bar.setProgressTintList(android.content.res.ColorStateList.valueOf(0x00000000));
        }
        bar.setProgress(Math.round(editing.v[i] - d.min));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int n, boolean user) {
                if (editing == null) return;
                editing.v[i] = d.min + n;
                val.setText(format(d, editing.v[i]));
                reset.setVisibility(editing.v[i] == d.def ? View.INVISIBLE : View.VISIBLE);
                queuePreview();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        reset.setOnClickListener(v -> bar.setProgress(Math.round(d.def - d.min)));
        editorBody.addView(bar, new LinearLayout.LayoutParams(-1, dp(34)));
    }

    String format(Param d, float v) {
        if (d.id.equals("exposure")) return String.format(Locale.JAPAN, "%+.2f EV", v / 50f);
        if (d.id.equals("camera_ev")) return String.format(Locale.JAPAN, "%+.1f EV", v / 10f);
        if (d.id.endsWith("_hue") && d.group.equals("grade")) return Math.round(v) + "°";
        int n = Math.round(v);
        return d.min < 0 && n > 0 ? "+" + n : String.valueOf(n);
    }

    // ---------- 設定 ----------

    void openSettings() {
        String[] items = {"HDR（明るい空と影を両方残す・対応機種のみ）", "三分割グリッド", "原本も一緒に保存", "音量ボタンでシャッター"};
        boolean[] on = {hdrWanted, overlay.grid(), saveOriginal, volumeShutter};
        new MaterialAlertDialogBuilder(this).setTitle("設定")
                .setMultiChoiceItems(items, on, (d, w, c) -> {
                    if (w == 0) { hdrWanted = c; prefs.edit().putBoolean("hdr", c).apply(); bindCamera(); }
                    else if (w == 1) { overlay.setGrid(c); prefs.edit().putBoolean("grid", c).apply(); }
                    else if (w == 2) { saveOriginal = c; prefs.edit().putBoolean("original", c).apply(); }
                    else { volumeShutter = c; prefs.edit().putBoolean("volume", c).apply(); }
                })
                .setNeutralButton("写真を選んで加工", (d, w) -> photoPicker.launch("image/*"))
                .setPositiveButton("閉じる", null)
                .show();
    }

    // ---------- カメラ ----------

    void startCamera() {
        ListenableFuture<ProcessCameraProvider> f = ProcessCameraProvider.getInstance(this);
        f.addListener(() -> {
            try {
                provider = f.get();
                ListenableFuture<ExtensionsManager> ef = ExtensionsManager.getInstanceAsync(this, provider);
                ef.addListener(() -> {
                    try { extensions = ef.get(); } catch (Exception ignored) {}
                    bindCamera();
                }, ContextCompat.getMainExecutor(this));
            } catch (Exception e) { say("カメラ開始エラー: " + e.getMessage()); }
        }, ContextCompat.getMainExecutor(this));
    }

    void bindCamera() {
        if (provider == null) return;
        CameraSelector base = back ? CameraSelector.DEFAULT_BACK_CAMERA : CameraSelector.DEFAULT_FRONT_CAMERA, sel = base;
        boolean hdr = false, hdrMissing = false;
        try {
            if (hdrWanted && extensions != null) {
                if (extensions.isExtensionAvailable(base, ExtensionMode.HDR)) { sel = extensions.getExtensionEnabledCameraSelector(base, ExtensionMode.HDR); hdr = true; }
                else hdrMissing = true;
            }
        } catch (Exception ignored) {}
        Preview p = new Preview.Builder().build();
        p.setSurfaceProvider(preview.getSurfaceProvider());
        capture = new ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build();
        provider.unbindAll();
        try {
            camera = provider.bindToLifecycle(this, sel, p, capture);
        } catch (Exception e) {
            if (!hdr) { say("カメラ開始エラー: " + e.getMessage()); return; }
            hdr = false; hdrMissing = true;
            try { provider.unbindAll(); camera = provider.bindToLifecycle(this, base, p, capture); }
            catch (Exception e2) { say("カメラ開始エラー: " + e2.getMessage()); return; }
        }
        hdrActive = hdr;
        appliedEv = Float.NaN;
        applyCameraEv(effective(active()));
        if (hdrWanted && hdrMissing) say("この機種は HDR 撮影に対応していません（通常撮影で続けます）");
    }

    void applyCameraEv(Preset p) {
        if (camera == null) return;
        float ev = p.get("camera_ev") / 10f;
        if (ev == appliedEv) return;
        try {
            ExposureState es = camera.getCameraInfo().getExposureState();
            if (!es.isExposureCompensationSupported()) return;
            float step = es.getExposureCompensationStep().floatValue();
            if (step <= 0) return;
            Range<Integer> r = es.getExposureCompensationRange();
            camera.getCameraControl().setExposureCompensationIndex(Math.max(r.getLower(), Math.min(r.getUpper(), Math.round(ev / step))));
            appliedEv = ev;
        } catch (Exception ignored) {}
    }

    void focusAt(float x, float y) {
        if (camera == null) return;
        try {
            MeteringPoint pt = preview.getMeteringPointFactory().createPoint(x, y);
            camera.getCameraControl().startFocusAndMetering(new FocusMeteringAction.Builder(pt, FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE)
                    .setAutoCancelDuration(5, TimeUnit.SECONDS).build());
            overlay.showFocus(x, y);
        } catch (Exception ignored) {}
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (volumeShutter && (code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_UP) && editor.getVisibility() != View.VISIBLE) {
            if (e.getRepeatCount() == 0) shoot();
            return true;
        }
        return super.onKeyDown(code, e);
    }

    void setBusy(boolean b) {
        busy = b;
        shutter.setEnabled(!b);
        shutter.setAlpha(b ? 0.45f : 1f);
    }

    void shoot() {
        if (capture == null) { say("カメラの準備中です"); return; }
        if (busy) return;
        setBusy(true);
        Preset chosen = effective(active());
        long seed = grainSeed;
        boolean keepOriginal = saveOriginal, mirror = !back;
        Comedian.State art = com.copy();
        flash.setAlpha(0.8f);
        flash.animate().alpha(0).setDuration(300).start();
        status.setText("保存中…");
        File tmp = new File(getCacheDir(), "capture.jpg");
        capture.takePicture(new ImageCapture.OutputFileOptions.Builder(tmp).build(), io, new ImageCapture.OnImageSavedCallback() {
            @Override public void onError(ImageCaptureException e) { runOnUiThread(() -> { setBusy(false); say("撮影失敗: " + e.getMessage()); }); }
            @Override public void onImageSaved(ImageCapture.OutputFileResults r) {
                try {
                    String stamp = stamp();
                    if (keepOriginal) saveFile(tmp, "DC_" + stamp + "_ORIGINAL.jpg");
                    Bitmap img = oriented(tmp);
                    stick(img, art, mirror);
                    Bitmap fx = PhotoProcessor.apply(img, chosen, seed);
                    Uri u = save(fx, "DC_" + stamp + "_" + safe(chosen.name) + suffix(art) + ".jpg");
                    Bitmap th = Thumbs.square(fx, 160);
                    tmp.delete();
                    runOnUiThread(() -> { setBusy(false); setLast(u, th); grainSeed = System.nanoTime(); say(keepOriginal ? "保存しました（原本＋加工版）" : "保存しました"); });
                } catch (Throwable e) { runOnUiThread(() -> { setBusy(false); say("保存失敗: " + e.getMessage()); }); }
            }
        });
    }

    // ---------- 既存写真・最後の写真 ----------

    void processExisting(Uri uri) {
        Preset chosen = effective(active());
        long seed = grainSeed;
        Comedian.State art = com.copy();
        setBusy(true);
        status.setText("写真を「" + chosen.name + "」で加工中…");
        io.execute(() -> {
            try {
                Bitmap img = oriented(uri);
                if (art.mode == Comedian.TAPE) try { art.box.set(findThing(img, 0.5f, 0.5f)); } catch (Exception ignored) {}
                stick(img, art, false);
                Bitmap fx = PhotoProcessor.apply(img, chosen, seed);
                Uri u = save(fx, "DC_" + stamp() + "_IMPORT_" + safe(chosen.name) + suffix(art) + ".jpg");
                Bitmap th = Thumbs.square(fx, 160);
                runOnUiThread(() -> { setBusy(false); setLast(u, th); say("加工版を保存しました（左下から見られます）"); });
            } catch (Throwable e) { runOnUiThread(() -> { setBusy(false); say("写真の加工失敗: " + e.getMessage()); }); }
        });
    }

    void setLast(Uri u, Bitmap th) {
        lastUri = u;
        prefs.edit().putString("lastUri", u.toString()).apply();
        lastThumb.setImageBitmap(th);
    }

    void loadLastThumb() {
        if (lastUri == null) return;
        Uri u = lastUri;
        io.execute(() -> {
            try {
                Bitmap b = getContentResolver().loadThumbnail(u, new Size(160, 160), null);
                runOnUiThread(() -> lastThumb.setImageBitmap(b));
            } catch (Exception e) { runOnUiThread(() -> lastUri = null); }
        });
    }

    void openLast() {
        if (lastUri == null) { say("まだ写真がありません"); return; }
        try { startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(lastUri, "image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)); }
        catch (Exception e) { say("写真を開けるアプリがありません"); }
    }

    void setShared(Uri u) {
        pendingShared = u;
        shareBar.setVisibility(u != null ? View.VISIBLE : View.GONE);
    }

    void handleShare(Intent intent) {
        if (Intent.ACTION_SEND.equals(intent.getAction()) && intent.getType() != null && intent.getType().startsWith("image/")) {
            Uri u = Build.VERSION.SDK_INT >= 33 ? intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class) : (Uri) intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) { setShared(u); say("色を選んで「この色で加工」を押してください"); }
        }
    }

    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); handleShare(intent); }

    // ---------- 読み書き ----------

    static int sampleSize(int w, int h) { int s = 1; while ((long) (w / s) * (h / s) > 50_000_000L) s *= 2; return s; }

    static BitmapFactory.Options decodeOptions(int w, int h) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sampleSize(w, h);
        o.inMutable = true;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return o;
    }

    Bitmap oriented(File f) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), bounds);
        Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath(), decodeOptions(bounds.outWidth, bounds.outHeight));
        if (b == null) throw new IOException("画像を読み込めません");
        return rotate(b, new ExifInterface(f).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL));
    }

    Bitmap oriented(Uri uri) throws Exception {
        int o;
        try (InputStream e = getContentResolver().openInputStream(uri)) { o = new ExifInterface(e).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL); }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(in, null, bounds); }
        Bitmap b;
        try (InputStream in = getContentResolver().openInputStream(uri)) { b = BitmapFactory.decodeStream(in, null, decodeOptions(bounds.outWidth, bounds.outHeight)); }
        if (b == null) throw new IOException("画像を読み込めません");
        return rotate(b, o);
    }

    static Bitmap rotate(Bitmap b, int orientation) {
        int d = orientation == 6 ? 90 : orientation == 3 ? 180 : orientation == 8 ? 270 : 0;
        if (d == 0) return b;
        Matrix m = new Matrix();
        m.postRotate(d);
        Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
        if (r != b) b.recycle();
        return r;
    }

    Uri newImage(String name) throws IOException {
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DreamCamera");
        v.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri u = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        if (u == null) throw new IOException("保存先を作れません");
        return u;
    }

    void publish(Uri u) {
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.IS_PENDING, 0);
        getContentResolver().update(u, v, null, null);
    }

    Uri save(Bitmap b, String name) throws Exception {
        Uri u = newImage(name);
        try (OutputStream o = getContentResolver().openOutputStream(u)) { b.compress(Bitmap.CompressFormat.JPEG, 95, o); }
        publish(u);
        return u;
    }

    /** 原本はカメラの JPEG をそのままコピー（再圧縮しない・Exif も残る）。 */
    void saveFile(File f, String name) throws Exception {
        Uri u = newImage(name);
        try (OutputStream o = getContentResolver().openOutputStream(u); InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) o.write(buf, 0, n);
        }
        publish(u);
    }

    static String stamp() { return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.JAPAN).format(new Date()); }

    static String safe(String s) { return s.replaceAll("[^a-zA-Z0-9ぁ-んァ-ン一-龯_-]", "_"); }

    @Override protected void onDestroy() { super.onDestroy(); io.shutdown(); thumbExec.shutdown(); detectExec.shutdown(); if (detector != null) detector.close(); }
}
