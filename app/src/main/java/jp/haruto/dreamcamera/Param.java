package jp.haruto.dreamcamera;

import java.util.*;

/** 調整項目の定義。id は保存用のキー、legacy は 0.3.0 以前に保存されたカスタムプリセットのキー。 */
public final class Param {
    public final String id, label, group, legacy;
    public final float min, max, def;

    Param(String id, String label, String group, float min, float max, float def, String legacy) {
        this.id = id; this.label = label; this.group = group; this.min = min; this.max = max; this.def = def; this.legacy = legacy;
    }

    /** Lightroom のカラーミキサーと同じ8色。 */
    public static final String[] MIX_IDS = {"red", "orange", "yellow", "green", "aqua", "blue", "purple", "magenta"};
    public static final String[] MIX_LABELS = {"レッド", "オレンジ", "イエロー", "グリーン", "アクア", "ブルー", "パープル", "マゼンタ"};
    public static final int[] MIX_COLORS = {0xFFE53935, 0xFFFB8C00, 0xFFFDD835, 0xFF43A047, 0xFF00ACC1, 0xFF1E88E5, 0xFF8E24AA, 0xFFD81B60};
    /** カラーグレーディングのゾーン。 */
    public static final String[] ZONES = {"sh", "mid", "hl"};
    public static final String[] ZONE_LABELS = {"シャドウ", "中間調", "ハイライト"};

    public static final Param[] ALL;
    private static final Map<String, Integer> INDEX = new HashMap<>();

    static {
        List<Param> a = new ArrayList<>();
        // ライト
        a.add(new Param("exposure", "露光量", "light", -100, 100, 0, "露出"));
        a.add(new Param("contrast", "コントラスト", "light", -100, 100, 0, "コントラスト"));
        a.add(new Param("highlights", "ハイライト", "light", -100, 100, 0, null));
        a.add(new Param("shadows", "シャドウ", "light", -100, 100, 0, null));
        a.add(new Param("whites", "白レベル", "light", -100, 100, 0, null));
        a.add(new Param("blacks", "黒レベル", "light", -100, 100, 0, null));
        a.add(new Param("fade", "フェード", "light", 0, 100, 0, "フェード"));
        // カラー
        a.add(new Param("temp", "色温度", "color", -100, 100, 0, "色温度"));
        a.add(new Param("tint", "色かぶり補正", "color", -100, 100, 0, "色かぶり"));
        a.add(new Param("vibrance", "自然な彩度", "color", -100, 100, 0, "自然な彩度"));
        a.add(new Param("saturation", "彩度", "color", -100, 100, 0, "彩度"));
        a.add(new Param("hue", "色相（全体）", "color", -100, 100, 0, "色相"));
        a.add(new Param("red", "赤チャンネル", "color", -100, 100, 0, "赤"));
        a.add(new Param("green", "緑チャンネル", "color", -100, 100, 0, "緑"));
        a.add(new Param("blue", "青チャンネル", "color", -100, 100, 0, "青"));
        // カラーミキサー
        for (int i = 0; i < MIX_IDS.length; i++) {
            a.add(new Param("mix_h_" + MIX_IDS[i], MIX_LABELS[i] + " 色相", "mixer", -100, 100, 0, null));
            a.add(new Param("mix_s_" + MIX_IDS[i], MIX_LABELS[i] + " 彩度", "mixer", -100, 100, 0, null));
            a.add(new Param("mix_l_" + MIX_IDS[i], MIX_LABELS[i] + " 輝度", "mixer", -100, 100, 0, null));
        }
        // カラーグレーディング
        for (int i = 0; i < ZONES.length; i++) {
            a.add(new Param(ZONES[i] + "_hue", ZONE_LABELS[i] + " 色相", "grade", 0, 360, 0, null));
            a.add(new Param(ZONES[i] + "_sat", ZONE_LABELS[i] + " 彩度", "grade", 0, 100, 0, null));
            a.add(new Param(ZONES[i] + "_lum", ZONE_LABELS[i] + " 輝度", "grade", -100, 100, 0, null));
        }
        a.add(new Param("grade_blend", "ブレンド", "grade", 0, 100, 50, null));
        a.add(new Param("grade_balance", "バランス", "grade", -100, 100, 0, null));
        // 効果
        a.add(new Param("clarity", "明瞭度", "effect", -100, 100, 0, "明瞭度"));
        a.add(new Param("dehaze", "かすみの除去", "effect", -100, 100, 0, "かすみ除去"));
        a.add(new Param("glow", "光のにじみ", "effect", 0, 100, 0, null));
        a.add(new Param("blur", "ソフト", "effect", 0, 100, 0, "ぼかし"));
        a.add(new Param("vignette", "周辺光量（−で白く）", "effect", -100, 100, 0, "周辺減光"));
        a.add(new Param("grain", "粒子", "effect", 0, 100, 0, "粒子"));
        // 撮影（10 = 1.0EV）
        a.add(new Param("camera_ev", "撮影時の露出補正", "camera", -20, 20, 0, null));
        ALL = a.toArray(new Param[0]);
        for (int i = 0; i < ALL.length; i++) INDEX.put(ALL[i].id, i);
    }

    public static int idx(String id) {
        Integer i = INDEX.get(id);
        if (i == null) throw new IllegalArgumentException("unknown param: " + id);
        return i;
    }

    public static Param get(String id) { return ALL[idx(id)]; }

    public static List<Param> group(String g) {
        List<Param> out = new ArrayList<>();
        for (Param p : ALL) if (p.group.equals(g)) out.add(p);
        return out;
    }
}
