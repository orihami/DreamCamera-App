package jp.haruto.dreamcamera;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.util.Range;
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
import androidx.exifinterface.media.ExifInterface;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends AppCompatActivity {
    static final String[] TABS = {"light", "color", "mixer", "grade", "effect", "camera"};
    static final String[] TAB_LABELS = {"ライト", "カラー", "ミキサー", "グレーディング", "効果", "撮影"};
    static final int ACCENT = 0xFF4FC3F7;

    PreviewView preview; OverlayView overlay;
    ImageCapture capture; Camera camera; ProcessCameraProvider provider; ExtensionsManager extensions;
    ArrayList<Preset> presets; Preset current, editing;
    LinearLayout chips, controls, editor, editorBody, tabRow;
    TextView title, status; Button gallery, gridBtn, hdrBtn; EditText nameField;
    Uri pendingSharedUri;
    final ExecutorService io = Executors.newSingleThreadExecutor();
    final Handler ui = new Handler(Looper.getMainLooper());
    long grainSeed = System.nanoTime();
    boolean hdrWanted, hdrActive, comparing, previewQueued;
    String tab = "light"; int mixColor = 4, zone = 0; float appliedEv = Float.NaN;
    SharedPreferences prefs;
    OnBackPressedCallback backToCancel;

    ActivityResultLauncher<String> permission = registerForActivityResult(new ActivityResultContracts.RequestPermission(), ok -> { if (ok) startCamera(); else status.setText("カメラ権限が必要です"); });
    ActivityResultLauncher<String> photoPicker = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> { if (uri != null) processExisting(uri); });

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("p", 0);
        hdrWanted = prefs.getBoolean("hdr", true);
        buildUi();
        presets = PresetStore.load(this);
        current = find(prefs.getString("last", PresetStore.DEFAULT_NAME));
        overlay.setGrid(prefs.getBoolean("grid", false));
        refreshChips();
        preview.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> { if (r - l != or - ol || bt - t != ob - ot) applyPreview(); });
        applyPreview();
        showHint();
        backToCancel = new OnBackPressedCallback(false) { @Override public void handleOnBackPressed() { closeEditor(false); } };
        getOnBackPressedDispatcher().addCallback(this, backToCancel);
        boolean shared = handleShare(getIntent()), granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        if (granted) startCamera(); else if (!shared) permission.launch(Manifest.permission.CAMERA);
    }

    Preset find(String name) {
        for (Preset p : presets) if (p.name.equals(name)) return p;
        return presets.get(0);
    }

    Preset active() { return editing != null ? editing : current; }

    // ---------- 画面 ----------

    void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(9, 7, 13));
        preview = new PreviewView(this);
        preview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        preview.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        root.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        overlay = new OverlayView(this);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapUp(MotionEvent e) { focusAt(e.getX(), e.getY()); return true; }
            @Override public void onLongPress(MotionEvent e) { comparing = true; applyPreview(); status.setText("加工前を表示中（指を離すと戻る）"); }
        });
        overlay.setOnTouchListener((v, e) -> {
            gd.onTouchEvent(e);
            int a = e.getActionMasked();
            if ((a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) && comparing) { comparing = false; applyPreview(); showHint(); }
            return true;
        });

        LinearLayout top = new LinearLayout(this);
        top.setPadding(dp(22), dp(44), dp(14), dp(12));
        top.setGravity(Gravity.CENTER_VERTICAL);
        title = new TextView(this);
        title.setTextColor(Color.WHITE);
        title.setTextSize(19);
        title.setTypeface(null, Typeface.BOLD);
        title.setShadowLayer(6, 0, 1, 0x99000000);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        gridBtn = small("▦");
        gridBtn.setContentDescription("グリッド");
        gridBtn.setOnClickListener(v -> { overlay.setGrid(!overlay.grid()); prefs.edit().putBoolean("grid", overlay.grid()).apply(); updateTopButtons(); });
        top.addView(gridBtn, new LinearLayout.LayoutParams(dp(48), dp(40)));
        hdrBtn = small("HDR");
        hdrBtn.setOnClickListener(v -> { hdrWanted = !hdrWanted; prefs.edit().putBoolean("hdr", hdrWanted).apply(); bindCamera(); });
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(58), dp(40));
        hp.setMargins(dp(8), 0, 0, 0);
        top.addView(hdrBtn, hp);
        root.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(12), dp(12), dp(12), dp(34));
        controls.setBackgroundColor(0xD609070D);
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        chips = new LinearLayout(this);
        scroll.addView(chips);
        controls.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        status = new TextView(this);
        status.setTextColor(0xFFD8E8F2);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(8), 0, dp(8));
        controls.addView(status);
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER);
        gallery = action("▣\n写真");
        gallery.setOnClickListener(v -> {
            if (pendingSharedUri != null) { Uri u = pendingSharedUri; pendingSharedUri = null; gallery.setText("▣\n写真"); processExisting(u); }
            else photoPicker.launch("image/*");
        });
        actions.addView(gallery, new LinearLayout.LayoutParams(0, dp(76), 1));
        Button shutter = button("●");
        shutter.setTextSize(42);
        shutter.setTextColor(Color.WHITE);
        shutter.setContentDescription("撮影");
        shutter.setBackground(oval(0xFF29B6F6, 0xFFFFFFFF, 4));
        shutter.setOnClickListener(v -> shoot());
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(88), dp(88));
        sp.setMargins(dp(18), 0, dp(18), 0);
        actions.addView(shutter, sp);
        Button edit = action("☷\n調整");
        edit.setOnClickListener(v -> openEditor());
        actions.addView(edit, new LinearLayout.LayoutParams(0, dp(76), 1));
        controls.addView(actions, new LinearLayout.LayoutParams(-1, dp(92)));
        root.addView(controls, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        buildEditor(root);
        setContentView(root);
    }

    void buildEditor(FrameLayout root) {
        editor = new LinearLayout(this);
        editor.setOrientation(LinearLayout.VERTICAL);
        editor.setPadding(dp(14), dp(10), dp(14), dp(28));
        editor.setBackgroundColor(0xF20B0F16);
        editor.setVisibility(View.GONE);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        nameField = new EditText(this);
        nameField.setSingleLine(true);
        nameField.setTextColor(Color.WHITE);
        nameField.setHintTextColor(0x88FFFFFF);
        nameField.setHint("プリセット名");
        head.addView(nameField, new LinearLayout.LayoutParams(0, -2, 1));
        Button cancel = small("取消");
        cancel.setOnClickListener(v -> closeEditor(false));
        head.addView(cancel, new LinearLayout.LayoutParams(dp(64), dp(40)));
        Button save = small("保存");
        save.setBackground(pill(true));
        save.setOnClickListener(v -> closeEditor(true));
        LinearLayout.LayoutParams svp = new LinearLayout.LayoutParams(dp(64), dp(40));
        svp.setMargins(dp(8), 0, 0, 0);
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
        editorBody.setPadding(0, dp(6), 0, dp(6));
        body.addView(editorBody);
        editor.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(editor, new FrameLayout.LayoutParams(-1, (int) (getResources().getDisplayMetrics().heightPixels * 0.52f), Gravity.BOTTOM));
    }

    Button button(String s) { Button b = new Button(this); b.setText(s); b.setAllCaps(false); return b; }

    Button action(String s) { Button b = button(s); b.setTextSize(14); b.setTextColor(Color.WHITE); b.setBackground(oval(0x33203040, 0x6680C8E8, 1)); return b; }

    Button small(String s) {
        Button b = button(s);
        b.setTextSize(13); b.setTextColor(Color.WHITE); b.setPadding(0, 0, 0, 0); b.setMinWidth(0); b.setMinHeight(0); b.setMinimumWidth(0); b.setMinimumHeight(0);
        b.setBackground(pill(false));
        return b;
    }

    GradientDrawable oval(int fill, int stroke, int sw) { GradientDrawable g = new GradientDrawable(); g.setShape(GradientDrawable.OVAL); g.setColor(fill); g.setStroke(dp(sw), stroke); return g; }

    GradientDrawable pill(boolean on) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(18));
        g.setColor(on ? 0xCC0288D1 : 0x55202A36);
        g.setStroke(dp(1), on ? 0xFFB3E5FC : 0x5590A4AE);
        return g;
    }

    int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }

    void refreshChips() {
        chips.removeAllViews();
        for (Preset p : presets) {
            Button b = small(p.name);
            b.setPadding(dp(14), 0, dp(14), 0);
            b.setBackground(pill(p == current));
            b.setOnClickListener(v -> select(p));
            if (!p.builtin) b.setOnLongClickListener(v -> { confirmDelete(p); return true; });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(38));
            lp.setMargins(0, 0, dp(8), 0);
            chips.addView(b, lp);
        }
    }

    void select(Preset p) {
        current = p;
        prefs.edit().putString("last", p.name).apply();
        refreshChips();
        applyPreview();
        showHint();
    }

    void confirmDelete(Preset p) {
        new MaterialAlertDialogBuilder(this).setTitle("「" + p.name + "」を削除しますか？")
                .setNegativeButton("やめる", null)
                .setPositiveButton("削除", (d, w) -> {
                    presets.remove(p);
                    PresetStore.saveCustom(this, presets);
                    if (current == p) current = presets.get(0);
                    refreshChips(); applyPreview(); showHint();
                }).show();
    }

    void showHint() {
        String h = PresetStore.hint(current.name);
        status.setText(h != null ? h : "プリセットを選んで撮影 · 画面長押しで加工前");
    }

    void updateTopButtons() {
        gridBtn.setBackground(pill(overlay.grid()));
        hdrBtn.setBackground(pill(hdrActive));
        hdrBtn.setAlpha(hdrWanted ? 1f : 0.55f);
    }

    // ---------- プレビュー ----------

    void applyPreview() {
        Preset p = active();
        title.setText(p.name);
        int w = preview.getWidth(), h = preview.getHeight();
        if (w == 0 || h == 0) { DisplayMetrics dm = getResources().getDisplayMetrics(); w = dm.widthPixels; h = dm.heightPixels; }
        if (comparing) {
            preview.setRenderEffect(null);
        } else {
            try { preview.setRenderEffect(PreviewEffects.build(p, w, h)); }
            catch (Exception e) { preview.setRenderEffect(null); status.setText("プレビュー効果エラー: " + e.getMessage()); }
        }
        applyCameraEv();
    }

    void queuePreview() {
        if (previewQueued) return;
        previewQueued = true;
        ui.postDelayed(() -> { previewQueued = false; applyPreview(); }, 40);
    }

    // ---------- 編集パネル ----------

    void openEditor() {
        editing = current.copy(current.builtin ? current.name + " Custom" : current.name);
        nameField.setText(editing.name);
        controls.setVisibility(View.GONE);
        editor.setVisibility(View.VISIBLE);
        backToCancel.setEnabled(true);
        showTab(tab);
        applyPreview();
    }

    void closeEditor(boolean save) {
        if (editing == null) return;
        if (save) {
            String n = nameField.getText().toString().trim();
            if (n.isEmpty()) n = "My Preset";
            for (Preset p : presets) if (p.builtin && p.name.equals(n)) { n += " Custom"; break; }
            editing.name = n;
            editing.builtin = false;
            int at = -1;
            for (int i = 0; i < presets.size(); i++) if (!presets.get(i).builtin && presets.get(i).name.equals(n)) at = i;
            if (at >= 0) presets.set(at, editing); else presets.add(editing);
            current = editing;
            prefs.edit().putString("last", n).apply();
            PresetStore.saveCustom(this, presets);
        }
        editing = null;
        editor.setVisibility(View.GONE);
        controls.setVisibility(View.VISIBLE);
        backToCancel.setEnabled(false);
        refreshChips();
        applyPreview();
        if (save) status.setText("「" + current.name + "」を保存しました"); else showHint();
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
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(36));
            lp.setMargins(0, dp(6), dp(6), dp(4));
            tabRow.addView(b, lp);
        }
        editorBody.removeAllViews();
        switch (t) {
            case "mixer": {
                LinearLayout dots = new LinearLayout(this);
                dots.setGravity(Gravity.CENTER);
                for (int k = 0; k < Param.MIX_IDS.length; k++) {
                    int kk = k;
                    View dot = new View(this);
                    int col = Param.MIX_COLORS[k];
                    dot.setBackground(oval(k == mixColor ? col : 0x00000000, col, 3));
                    dot.setContentDescription(Param.MIX_LABELS[k]);
                    dot.setOnClickListener(v -> { mixColor = kk; showTab("mixer"); });
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(32), dp(32));
                    lp.setMargins(dp(5), dp(6), dp(5), dp(6));
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
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(34), 1);
                    lp.setMargins(dp(3), dp(4), dp(3), dp(4));
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
        if (t.equals("camera")) note("空を白飛びさせないには −0.3〜−0.7 EV がおすすめ。この値はプレビューにもそのまま反映され、プリセットごとに保存されます。\n画面タップ＝そこにピントと明るさを合わせる（空をタップすると空が濃くなる）。HDR は対応端末のみ。");
        if (t.equals("effect")) note("光のにじみ＝明るい所がふわっと光る。周辺光量をマイナスにすると四隅が白く明るくなる（Aero っぽい）。明瞭度は保存画像にだけ効きます。");
        note("項目名を長押しで初期値に戻す · 画面長押しで加工前と比較");
    }

    void note(String s) {
        TextView n = new TextView(this);
        n.setText(s);
        n.setTextSize(12);
        n.setTextColor(0x99D8E8F2);
        n.setPadding(dp(4), dp(10), dp(4), dp(4));
        editorBody.addView(n);
    }

    void addSlider(Param d, String label) {
        int i = Param.idx(d.id);
        LinearLayout head = new LinearLayout(this);
        head.setPadding(dp(4), dp(8), dp(4), 0);
        TextView name = new TextView(this);
        name.setText(label);
        name.setTextColor(0xFFE8F1F7);
        name.setTextSize(14);
        head.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        TextView val = new TextView(this);
        val.setTextColor(0xFFB3E5FC);
        val.setTextSize(14);
        val.setText(format(d, editing.v[i]));
        head.addView(val);
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
                queuePreview();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        name.setOnLongClickListener(v -> { bar.setProgress(Math.round(d.def - d.min)); return true; });
        editorBody.addView(bar, new LinearLayout.LayoutParams(-1, dp(36)));
    }

    String format(Param d, float v) {
        if (d.id.equals("exposure")) return String.format(Locale.JAPAN, "%+.2f EV", v / 50f);
        if (d.id.equals("camera_ev")) return String.format(Locale.JAPAN, "%+.1f EV", v / 10f);
        if (d.id.endsWith("_hue") && d.group.equals("grade")) return Math.round(v) + "°";
        int n = Math.round(v);
        return d.min < 0 && n > 0 ? "+" + n : String.valueOf(n);
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
            } catch (Exception e) { status.setText("カメラ開始エラー: " + e.getMessage()); }
        }, ContextCompat.getMainExecutor(this));
    }

    void bindCamera() {
        if (provider == null) { updateTopButtons(); return; }
        CameraSelector sel = CameraSelector.DEFAULT_BACK_CAMERA;
        boolean hdr = false, hdrMissing = false;
        try {
            if (hdrWanted && extensions != null) {
                if (extensions.isExtensionAvailable(sel, ExtensionMode.HDR)) { sel = extensions.getExtensionEnabledCameraSelector(sel, ExtensionMode.HDR); hdr = true; }
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
            if (!hdr) { status.setText("カメラ開始エラー: " + e.getMessage()); return; }
            hdr = false; hdrMissing = true;
            try { provider.unbindAll(); camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, p, capture); }
            catch (Exception e2) { status.setText("カメラ開始エラー: " + e2.getMessage()); return; }
        }
        hdrActive = hdr;
        appliedEv = Float.NaN;
        applyCameraEv();
        updateTopButtons();
        if (hdrMissing) status.setText("この端末は HDR 撮影に対応していません（通常撮影で続けます）");
        else if (hdrWanted) status.setText("HDR オン：明るい空と影を両方残しやすい");
    }

    void applyCameraEv() {
        if (camera == null) return;
        float ev = active().get("camera_ev") / 10f;
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

    void shoot() {
        if (capture == null) return;
        Preset chosen = active().copy(active().name);
        long seed = grainSeed;
        status.setText("撮影・2枚を処理中…");
        File tmp = new File(getCacheDir(), "capture.jpg");
        capture.takePicture(new ImageCapture.OutputFileOptions.Builder(tmp).build(), io, new ImageCapture.OnImageSavedCallback() {
            @Override public void onError(ImageCaptureException e) { runOnUiThread(() -> status.setText("撮影失敗: " + e.getMessage())); }
            @Override public void onImageSaved(ImageCapture.OutputFileResults r) {
                try {
                    String stamp = stamp();
                    saveFile(tmp, "DC_" + stamp + "_ORIGINAL.jpg");
                    Bitmap fx = PhotoProcessor.apply(oriented(tmp), chosen, seed);
                    save(fx, "DC_" + stamp + "_" + safe(chosen.name) + ".jpg");
                    tmp.delete();
                    runOnUiThread(() -> { status.setText("保存しました：原本＋「" + chosen.name + "」"); grainSeed = System.nanoTime(); });
                } catch (Throwable e) { runOnUiThread(() -> status.setText("保存失敗: " + e.getMessage())); }
            }
        });
    }

    // ---------- 既存写真 ----------

    void processExisting(Uri uri) {
        Preset chosen = active().copy(active().name);
        long seed = grainSeed;
        status.setText("写真を「" + chosen.name + "」で加工中…");
        io.execute(() -> {
            try {
                Bitmap fx = PhotoProcessor.apply(oriented(uri), chosen, seed);
                save(fx, "DC_" + stamp() + "_IMPORT_" + safe(chosen.name) + ".jpg");
                runOnUiThread(() -> status.setText("加工版を保存しました（Pictures/DreamCamera）"));
            } catch (Throwable e) { runOnUiThread(() -> status.setText("写真の加工失敗: " + e.getMessage())); }
        });
    }

    boolean handleShare(Intent intent) {
        if (Intent.ACTION_SEND.equals(intent.getAction()) && intent.getType() != null && intent.getType().startsWith("image/")) {
            Uri u = Build.VERSION.SDK_INT >= 33 ? intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class) : (Uri) intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) {
                pendingSharedUri = u;
                gallery.setText("✓\n共有写真を加工");
                status.setText("共有写真を受信：プリセットを選んで左下を押す");
                return true;
            }
        }
        return false;
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

    void save(Bitmap b, String name) throws Exception {
        Uri u = newImage(name);
        try (OutputStream o = getContentResolver().openOutputStream(u)) { b.compress(Bitmap.CompressFormat.JPEG, 95, o); }
        publish(u);
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

    @Override protected void onDestroy() { super.onDestroy(); io.shutdown(); }
}
