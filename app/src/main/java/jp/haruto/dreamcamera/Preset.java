package jp.haruto.dreamcamera;

import org.json.*;

public class Preset {
    /** 0.3.0 までの16項目の並び（組み込みプリセットの定義用）。 */
    static final String[] LEGACY_ORDER = {"exposure", "contrast", "temp", "tint", "saturation", "vibrance", "fade", "clarity", "dehaze", "grain", "vignette", "blur", "hue", "red", "green", "blue"};

    public String name;
    public final float[] v = new float[Param.ALL.length];
    public boolean builtin;
    /** aero / snap / film / my */
    public String category = "my";

    /** 強さ（0..1）。空間効果にかける。 */
    public float k() { return get("amount") / 100f; }

    public Preset(String name) {
        this.name = name;
        for (int i = 0; i < v.length; i++) v[i] = Param.ALL[i].def;
    }

    public static Preset legacy(String name, float... a) {
        Preset p = new Preset(name);
        for (int i = 0; i < a.length && i < LEGACY_ORDER.length; i++) p.set(LEGACY_ORDER[i], a[i]);
        return p;
    }

    public float get(String id) { return v[Param.idx(id)]; }

    public Preset set(String id, float x) {
        int i = Param.idx(id);
        Param d = Param.ALL[i];
        v[i] = Math.max(d.min, Math.min(d.max, x));
        return this;
    }

    public Preset copy(String n) {
        Preset p = new Preset(n);
        System.arraycopy(v, 0, p.v, 0, v.length);
        p.category = category;
        return p;
    }

    public JSONObject json() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("name", name);
        for (int i = 0; i < v.length; i++) if (v[i] != Param.ALL[i].def) o.put(Param.ALL[i].id, v[i]);
        return o;
    }

    public static Preset from(JSONObject o) throws JSONException {
        Preset p = new Preset(o.getString("name"));
        for (Param d : Param.ALL) {
            if (o.has(d.id)) p.set(d.id, (float) o.getDouble(d.id));
            else if (d.legacy != null && o.has(d.legacy)) p.set(d.id, (float) o.getDouble(d.legacy));
        }
        return p;
    }
}
