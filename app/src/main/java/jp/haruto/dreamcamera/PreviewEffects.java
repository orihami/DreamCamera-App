package jp.haruto.dreamcamera;

import android.graphics.*;
import android.os.Build;
import androidx.annotation.RequiresApi;

/** ライブプレビュー用の RenderEffect。Android 13 以降は保存画像と同じ LUT を GPU で当てる。 */
final class PreviewEffects {
    private static final String LUT_SHADER =
            "uniform shader content; uniform shader lut; uniform float N;\n" +
            "half4 main(float2 p) {\n" +
            "  half4 c = content.eval(p);\n" +
            "  float3 v = clamp(float3(c.rgb), 0.0, 1.0) * (N - 1.0);\n" +
            "  float b0 = min(floor(v.b), N - 2.0); float fb = v.b - b0;\n" +
            "  half3 o0 = lut.eval(float2(b0 * N + v.r + 0.5, v.g + 0.5)).rgb;\n" +
            "  half3 o1 = lut.eval(float2((b0 + 1.0) * N + v.r + 0.5, v.g + 0.5)).rgb;\n" +
            "  return half4(mix(o0, o1, half(fb)), c.a);\n" +
            "}\n";

    private static final String FINISH_SHADER =
            "uniform shader content; uniform float2 size; uniform float vig; uniform float grain;\n" +
            "float hash(float2 p) { return fract(sin(dot(p, float2(12.9898, 78.233))) * 43758.5453); }\n" +
            "half4 main(float2 p) {\n" +
            "  half4 c = content.eval(p); float3 o = float3(c.rgb);\n" +
            "  float2 d = (p / size - 0.5) * 2.0;\n" +
            "  float m = smoothstep(0.15, 1.0, 0.5 * dot(d, d));\n" +
            "  if (vig > 0.0) o *= 1.0 - vig * 0.6 * m; else o = mix(o, float3(1.0), -vig * 0.6 * m);\n" +
            "  o += (hash(p) - 0.5) * grain * 0.22;\n" +
            "  return half4(half3(clamp(o, 0.0, 1.0)), c.a);\n" +
            "}\n";

    static RenderEffect build(Preset p, int w, int h) {
        RenderEffect e = Build.VERSION.SDK_INT >= 33 ? lutEffect(p) : RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(fallback(p)));
        float m = Math.max(1, Math.min(w, h)), soft = p.get("blur") / 100f, glow = p.get("glow") / 100f;
        if (soft > 0) {
            RenderEffect blur = RenderEffect.createBlurEffect(m * 0.008f, m * 0.008f, e, Shader.TileMode.CLAMP);
            e = RenderEffect.createBlendModeEffect(e, RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(alpha(soft * 0.5f)), blur), BlendMode.SRC_OVER);
        }
        if (glow > 0) {
            RenderEffect bright = RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(threshold(0.6f, glow * 1.1f)), e);
            e = RenderEffect.createBlendModeEffect(e, RenderEffect.createBlurEffect(m * 0.035f, m * 0.035f, bright, Shader.TileMode.CLAMP), BlendMode.SCREEN);
        }
        float vig = p.get("vignette") / 100f, grain = p.get("grain") / 100f;
        if (Build.VERSION.SDK_INT >= 33 && (vig != 0 || grain > 0)) e = finish(e, w, h, vig, grain);
        return e;
    }

    @RequiresApi(33)
    private static RenderEffect lutEffect(Preset p) {
        BitmapShader lut = new BitmapShader(ColorEngine.lutBitmap(new ColorEngine(p).lut()), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        lut.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
        RuntimeShader s = new RuntimeShader(LUT_SHADER);
        s.setInputShader("lut", lut);
        s.setFloatUniform("N", ColorEngine.N);
        return RenderEffect.createRuntimeShaderEffect(s, "content");
    }

    @RequiresApi(33)
    private static RenderEffect finish(RenderEffect input, int w, int h, float vig, float grain) {
        RuntimeShader s = new RuntimeShader(FINISH_SHADER);
        s.setFloatUniform("size", Math.max(1, w), Math.max(1, h));
        s.setFloatUniform("vig", vig);
        s.setFloatUniform("grain", grain);
        return RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(s, "content"), input);
    }

    private static ColorMatrix alpha(float a) {
        return new ColorMatrix(new float[]{1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, a, 0});
    }

    private static ColorMatrix threshold(float th, float gain) {
        float k = gain / (1 - th), off = -th * 255 * k;
        return new ColorMatrix(new float[]{k, 0, 0, 0, off, 0, k, 0, 0, off, 0, 0, k, 0, off, 0, 0, 0, 1, 0});
    }

    /** Android 12 用の簡易版（露光量・コントラスト・彩度・色温度だけ）。 */
    private static ColorMatrix fallback(Preset p) {
        float sat = 1 + p.get("saturation") / 100f + p.get("vibrance") / 250f, con = 1 + p.get("contrast") / 100f, exp = (float) Math.pow(2, p.get("exposure") / 50f);
        float tr = p.get("temp") * 0.5f, ti = p.get("tint") * 0.4f, off = 128 * (1 - con) + p.get("fade") * 0.45f + p.get("shadows") * 0.15f;
        ColorMatrix m = new ColorMatrix();
        m.setSaturation(sat);
        float r = exp * con * (1 + p.get("red") / 100f), g = exp * con * (1 + p.get("green") / 100f), b = exp * con * (1 + p.get("blue") / 100f);
        m.postConcat(new ColorMatrix(new float[]{r, 0, 0, 0, off + tr + ti, 0, g, 0, 0, off - ti, 0, 0, b, 0, off - tr, 0, 0, 0, 1, 0}));
        return m;
    }
}
