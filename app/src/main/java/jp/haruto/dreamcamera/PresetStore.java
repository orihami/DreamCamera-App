package jp.haruto.dreamcamera;

import android.content.*;
import org.json.*;
import java.util.*;

public class PresetStore {
    public static final String DEFAULT_NAME = "Frutiger Aero";
    private static final Map<String, String> HINTS = new HashMap<>();

    static {
        HINTS.put("Frutiger Aero", "動画の Lightroom 設定そのまま。青空＋緑＋水・ガラスが映える");
        HINTS.put("Aero Sky", "空を画面の上2/3に。空をタップで測光、逆光気味でもOK");
        HINTS.put("Aqua Glass", "水しぶき・グラス・水槽に。光が当たる側から撮る");
        HINTS.put("Eco Green", "芝生・葉っぱ＋青空。低い位置から見上げて空を入れる");
        HINTS.put("Vista Aurora", "夜景・ネオン・水槽の青緑の光に");
        HINTS.put("Street Portra", "昼の街と人に。歩きながら気になった瞬間にすぐ押す");
        HINTS.put("Night Walk", "夜の街灯・看板・駅に。明るい光を少し画面に入れる");
        HINTS.put("Superia Green", "曇りの日・日陰・住宅街に。少し引いて余白を残す");
        HINTS.put("Old Days", "なんでもない日常に。ピントや水平は気にしすぎない");
        HINTS.put("Golden Snap", "夕方の斜めの光に。逆光で人や影を入れる");
        HINTS.put("Mono Street", "影と光がはっきりした所に。人の形・影を主役に");
    }

    public static String hint(String name) { return HINTS.get(name); }

    public static ArrayList<Preset> builtins() {
        ArrayList<Preset> a = new ArrayList<>();
        a.add(frutigerAero());
        a.add(new Preset("Aero Sky").set("exposure", -12).set("contrast", 30).set("highlights", -85).set("shadows", 60).set("whites", 45).set("blacks", 5)
                .set("temp", -15).set("tint", 2).set("vibrance", 40).set("saturation", 10)
                .set("mix_h_green", -12).set("mix_s_green", 10).set("mix_h_aqua", -20).set("mix_s_aqua", 40).set("mix_s_blue", 35).set("mix_l_blue", -10)
                .set("mid_hue", 60).set("mid_sat", 25).set("sh_hue", 220).set("sh_sat", 45).set("hl_hue", 195).set("hl_sat", 12)
                .set("dehaze", 15).set("clarity", 10).set("glow", 25).set("camera_ev", -7));
        a.add(new Preset("Aqua Glass").set("exposure", 5).set("contrast", 20).set("highlights", -70).set("shadows", 50).set("whites", 55).set("blacks", 15)
                .set("temp", -25).set("tint", -4).set("vibrance", 35).set("saturation", 5)
                .set("mix_s_green", -20).set("mix_h_aqua", -10).set("mix_s_aqua", 60).set("mix_l_aqua", 15).set("mix_s_blue", 30).set("mix_l_blue", 10)
                .set("sh_hue", 210).set("sh_sat", 50).set("mid_hue", 185).set("mid_sat", 20).set("hl_hue", 180).set("hl_sat", 10)
                .set("glow", 45).set("blur", 10).set("vignette", -25).set("camera_ev", -3));
        a.add(new Preset("Eco Green").set("contrast", 35).set("highlights", -85).set("shadows", 70).set("whites", 50).set("blacks", 5)
                .set("temp", 5).set("tint", -6).set("vibrance", 40).set("saturation", 15)
                .set("mix_h_yellow", -10).set("mix_s_yellow", 20).set("mix_h_green", -18).set("mix_s_green", 25).set("mix_l_green", 10)
                .set("mix_h_aqua", -25).set("mix_s_aqua", 40).set("mix_s_blue", 25)
                .set("mid_hue", 70).set("mid_sat", 35).set("sh_hue", 200).set("sh_sat", 40)
                .set("clarity", 15).set("glow", 20).set("camera_ev", -5));
        a.add(new Preset("Vista Aurora").set("contrast", 25).set("highlights", -60).set("shadows", 40).set("whites", 30).set("blacks", -10)
                .set("temp", -30).set("tint", -10).set("vibrance", 30).set("saturation", 10)
                .set("mix_h_green", 15).set("mix_s_green", 30).set("mix_s_aqua", 50).set("mix_s_blue", 30)
                .set("sh_hue", 230).set("sh_sat", 70).set("mid_hue", 165).set("mid_sat", 35).set("hl_hue", 120).set("hl_sat", 20)
                .set("glow", 60).set("vignette", 20).set("camera_ev", -7));
        // 0.3.0 までの組み込み
        a.add(Preset.legacy("Natural", 0, 5, 0, 0, 4, 8, 0, 4, 0, 0, 0, 0));
        a.add(Preset.legacy("Dreamcore", 8, -12, -8, 5, 28, 35, 20, -35, -20, 62, 18, 8, 4, 4, -2, 8));
        a.add(Preset.legacy("Mallsoft", 5, -20, -12, 12, -8, 18, 32, -28, -12, 48, 24, 5, -6, -5, 4, 10));
        a.add(Preset.legacy("Liminal Fluorescent", 3, 18, -20, 8, -18, 8, 12, 18, 22, 25, 22, 0, -8, -6, 8, 2));
        a.add(Preset.legacy("VHS Memory", -2, -12, 10, 12, -14, 4, 28, -18, -8, 72, 30, 3, 8, 8, -4, -2));
        a.add(Preset.legacy("Y2K Candy", 8, 12, -5, 16, 24, 32, 6, 8, 4, 30, 8, 0, 10, 8, -2, 12));
        a.add(Preset.legacy("Disposable Flash", 12, 22, 8, -3, 12, 15, 8, 16, 10, 58, 28, 0, 0, 8, 2, -4));
        a.add(Preset.legacy("Film Gold", 2, 8, 18, 2, -6, 12, 20, 5, 4, 42, 20, 0, -4, 10, 4, -6));
        a.add(Preset.legacy("Neon Night", -6, 28, -18, 18, 18, 35, 8, 22, 26, 32, 38, 0, 12, -4, 2, 14));
        a.add(Preset.legacy("Blue Hour", -3, 10, -30, 6, -10, 22, 12, 5, 12, 24, 18, 0, -8, -8, 2, 14));
        a.add(Preset.legacy("Pastel Haze", 12, -28, 4, 10, -12, 18, 35, -40, -25, 28, 8, 10, 5, 8, 0, 8));
        a.add(Preset.legacy("Mono Mist", 5, 16, -4, 0, -100, 0, 18, -12, -6, 46, 26, 5));
        for (int i = 0; i < a.size(); i++) { a.get(i).builtin = true; a.get(i).category = i < 5 ? "aero" : "film"; }
        for (Preset p : snaps()) { p.builtin = true; p.category = "snap"; a.add(p); }
        return a;
    }

    /** ストリートスナップ用：フィルムで撮ったような、少し褪せた色と粒子。 */
    static List<Preset> snaps() {
        List<Preset> a = new ArrayList<>();
        // 暖かい肌色・くすんだ緑・浮いた黒（Portra 400 っぽく）
        a.add(new Preset("Street Portra").set("exposure", 5).set("contrast", -10).set("highlights", -40).set("shadows", 25).set("whites", -10).set("blacks", 20).set("fade", 18)
                .set("temp", 12).set("tint", 4).set("vibrance", -5).set("saturation", -8)
                .set("mix_s_orange", 8).set("mix_l_orange", 8).set("mix_h_green", -20).set("mix_s_green", -25).set("mix_h_blue", -10).set("mix_s_blue", -15)
                .set("sh_hue", 200).set("sh_sat", 12).set("hl_hue", 40).set("hl_sat", 18)
                .set("clarity", -5).set("grain", 30).set("vignette", 12).set("camera_ev", -3));
        // 夜：青い影と暖かい街灯、光がにじむ（タングステンフィルムっぽく）
        a.add(new Preset("Night Walk").set("contrast", 15).set("highlights", -30).set("shadows", 20).set("blacks", 10).set("fade", 8)
                .set("temp", -25).set("tint", 6).set("vibrance", 15)
                .set("mix_s_red", 20).set("mix_h_orange", -10).set("mix_s_aqua", 25).set("mix_h_blue", -15)
                .set("sh_hue", 195).set("sh_sat", 35).set("hl_hue", 25).set("hl_sat", 20)
                .set("glow", 40).set("grain", 35).set("vignette", 15).set("camera_ev", -5));
        // 緑がかった影・あっさりした色（Superia っぽく）
        a.add(new Preset("Superia Green").set("contrast", 10).set("highlights", -35).set("shadows", 15).set("blacks", 15).set("fade", 15)
                .set("temp", -5).set("tint", -12).set("vibrance", 10).set("saturation", -5)
                .set("mix_h_red", 5).set("mix_h_green", 15).set("mix_s_green", 10).set("mix_s_blue", -10)
                .set("sh_hue", 165).set("sh_sat", 25).set("hl_hue", 55).set("hl_sat", 10)
                .set("grain", 30).set("camera_ev", -3));
        // 色あせた昔の写真
        a.add(new Preset("Old Days").set("exposure", 8).set("contrast", -25).set("highlights", -30).set("shadows", 30).set("whites", -20).set("blacks", 30).set("fade", 35)
                .set("temp", 18).set("tint", 6).set("vibrance", -10).set("saturation", -25)
                .set("sh_hue", 220).set("sh_sat", 10).set("hl_hue", 40).set("hl_sat", 25)
                .set("glow", 20).set("blur", 8).set("grain", 40).set("vignette", 20));
        // 夕方の金色
        a.add(new Preset("Golden Snap").set("exposure", 3).set("contrast", 10).set("highlights", -50).set("shadows", 25).set("blacks", 10).set("fade", 10)
                .set("temp", 30).set("tint", 8).set("vibrance", 20)
                .set("mix_h_orange", -5).set("mix_s_orange", 15).set("mix_h_blue", -15).set("mix_s_blue", -20)
                .set("sh_hue", 260).set("sh_sat", 15).set("hl_hue", 35).set("hl_sat", 30)
                .set("glow", 30).set("grain", 25).set("vignette", 15).set("camera_ev", -5));
        // 硬めの白黒（Tri-X っぽく）
        a.add(new Preset("Mono Street").set("contrast", 40).set("highlights", -20).set("shadows", -5).set("whites", 20).set("blacks", -15)
                .set("saturation", -100).set("clarity", 20).set("grain", 45).set("vignette", 20).set("camera_ev", -3));
        return a;
    }

    /** 参考動画（the_aesthetics_hunter「How to edit photos like Frutiger Aero」）の Lightroom の値。 */
    static Preset frutigerAero() {
        return new Preset("Frutiger Aero")
                .set("exposure", -32)          // −0.64 EV
                .set("contrast", 41).set("highlights", -98).set("shadows", 89).set("whites", 60).set("blacks", 10)
                .set("temp", -8)               // 5,150K（やや青寄り）
                .set("tint", 4).set("vibrance", 31).set("saturation", 13)
                .set("mix_s_green", -15)
                .set("mix_h_aqua", -33).set("mix_s_aqua", 47)
                .set("mix_s_blue", 21)
                .set("mid_hue", 55).set("mid_sat", 40)   // 中間調：黄色寄り
                .set("sh_hue", 226).set("sh_sat", 65);   // シャドウ：青
    }

    public static ArrayList<Preset> load(Context c) {
        ArrayList<Preset> a = builtins();
        try {
            JSONArray x = new JSONArray(c.getSharedPreferences("p", 0).getString("custom", "[]"));
            for (int i = 0; i < x.length(); i++) a.add(Preset.from(x.getJSONObject(i)));
        } catch (Exception ignored) {}
        return a;
    }

    public static void saveCustom(Context c, List<Preset> a) {
        try {
            JSONArray x = new JSONArray();
            for (Preset p : a) if (!p.builtin) x.put(p.json());
            c.getSharedPreferences("p", 0).edit().putString("custom", x.toString()).apply();
        } catch (Exception ignored) {}
    }
}
