package jp.haruto.dreamcamera;

import android.hardware.camera2.*;
import android.util.Range;
import androidx.annotation.OptIn;
import androidx.camera.camera2.interop.*;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.ExposureState;
import androidx.camera.core.ZoomState;
import java.util.*;

/**
 * プロ撮影：AF・WB・シャッター速度・ISO・露出補正・ズームを、それぞれオートか手動にする。
 * 手動の値は Camera2 の設定として CameraX に渡す（プレビューと撮影の両方に効く）。
 */
@OptIn(markerClass = ExperimentalCamera2Interop.class)
final class ProCamera {
    static final int AF = 0, WB = 1, SS = 2, ISO = 3, EV = 4, ZOOM = 5;
    static final String[] LABELS = {"AF", "WB", "シャッター", "ISO", "露出", "ズーム"};

    static final long[] SS_ALL = {125_000L, 250_000L, 500_000L, 1_000_000L, 2_000_000L, 4_000_000L, 8_000_000L, 16_666_667L, 22_222_222L, 33_333_333L,
            66_666_667L, 125_000_000L, 250_000_000L, 500_000_000L, 1_000_000_000L};
    static final String[] SS_LABELS = {"1/8000", "1/4000", "1/2000", "1/1000", "1/500", "1/250", "1/125", "1/60", "1/45", "1/30", "1/15", "1/8", "1/4", "1/2", "1s"};
    static final int[] ISO_ALL = {50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400};
    static final int[] WB_ALL = {CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT, CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT,
            CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT, CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT, CameraMetadata.CONTROL_AWB_MODE_SHADE};
    static final String[] WB_LABELS = {"電球 2700K", "蛍光灯 4000K", "晴れ 5500K", "曇り 6500K", "日陰 7500K"};

    final boolean[] manual = new boolean[6];
    /** 手動の値。SS・ISO・WB は下の一覧の番号、EV は補正の段数、AF は 0（近い）〜1（∞）、ズームは倍率。 */
    int ss, iso, wb, ev;
    float focus = 1, zoom = 1;

    // この機種でできること
    boolean manualSensor;
    final List<Integer> ssIdx = new ArrayList<>(), isoIdx = new ArrayList<>(), wbIdx = new ArrayList<>();
    float minFocusDist, zoomMin = 1, zoomMax = 1, evStep;
    int evMin, evMax;

    // オートの今の値（プレビューの結果から）
    volatile long liveExp;
    volatile int liveIso;
    volatile float liveFocus = -1;

    void load(CameraInfo info) {
        Camera2CameraInfo c2 = Camera2CameraInfo.from(info);
        int[] caps = c2.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        manualSensor = false;
        if (caps != null) for (int c : caps) if (c == CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) manualSensor = true;
        ssIdx.clear(); isoIdx.clear(); wbIdx.clear();
        Range<Long> er = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        if (er != null) for (int i = 0; i < SS_ALL.length; i++) if (er.contains(SS_ALL[i])) ssIdx.add(i);
        Range<Integer> ir = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        if (ir != null) for (int i = 0; i < ISO_ALL.length; i++) if (ir.contains(ISO_ALL[i])) isoIdx.add(i);
        int[] awb = c2.getCameraCharacteristic(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES);
        if (awb != null) for (int i = 0; i < WB_ALL.length; i++) for (int m : awb) if (m == WB_ALL[i]) wbIdx.add(i);
        Float mf = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        minFocusDist = mf != null ? mf : 0;
        ZoomState z = info.getZoomState().getValue();
        if (z != null) { zoomMin = z.getMinZoomRatio(); zoomMax = z.getMaxZoomRatio(); }
        ExposureState es = info.getExposureState();
        evStep = es.isExposureCompensationSupported() ? es.getExposureCompensationStep().floatValue() : 0;
        evMin = es.getExposureCompensationRange().getLower();
        evMax = es.getExposureCompensationRange().getUpper();
        // 機種でできない手動はオートに戻す
        if (!can(SS)) manual[SS] = false;
        if (!can(ISO)) manual[ISO] = false;
        if (!can(AF)) manual[AF] = false;
        if (!can(WB)) manual[WB] = false;
        if (!can(EV)) manual[EV] = false;
        if (manual[SS] && !ssIdx.contains(ss)) ss = ssIdx.get(ssIdx.size() / 2);
        if (manual[ISO] && !isoIdx.contains(iso)) iso = isoIdx.get(0);
        if (manual[WB] && !wbIdx.contains(wb)) wb = wbIdx.get(0);
        ev = Math.max(evMin, Math.min(evMax, ev));
    }

    boolean can(int k) {
        switch (k) {
            case AF: return manualSensor && minFocusDist > 0;
            case WB: return !wbIdx.isEmpty();
            case SS: return manualSensor && !ssIdx.isEmpty();
            case ISO: return manualSensor && !isoIdx.isEmpty();
            case EV: return evStep > 0 && !manualExposure();
            default: return zoomMax > zoomMin;
        }
    }

    /** シャッターか ISO が手動なら、自動露出を止める（露出補正は効かなくなる）。 */
    boolean manualExposure() { return manual[SS] || manual[ISO]; }

    /** 手動の項目だけを入れた Camera2 の設定。全部オートなら空（＝今まで通り）。 */
    CaptureRequestOptions options() {
        CaptureRequestOptions.Builder b = new CaptureRequestOptions.Builder();
        if (manualExposure()) {
            long exp = manual[SS] ? SS_ALL[ss] : (liveExp > 0 ? liveExp : 16_666_667L);
            int sens = manual[ISO] ? ISO_ALL[iso] : (liveIso > 0 ? liveIso : 400);
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF);
            b.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, exp);
            b.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, sens);
            b.setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, Math.max(exp, 33_333_333L));
        }
        if (manual[WB]) b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, WB_ALL[wb]);
        if (manual[AF]) {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF);
            b.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, (1 - focus) * minFocusDist);
        }
        return b.build();
    }

    /** オートから手動に切り替えた時、今の見た目に近い値から始める。 */
    void startManual(int k) {
        if (k == SS) ss = nearest(ssIdx, i -> Math.abs(Math.log(SS_ALL[i]) - Math.log(liveExp > 0 ? liveExp : 16_666_667L)));
        else if (k == ISO) iso = nearest(isoIdx, i -> Math.abs(Math.log(ISO_ALL[i]) - Math.log(liveIso > 0 ? liveIso : 400)));
        else if (k == WB) wb = wbIdx.contains(2) ? 2 : wbIdx.get(0);
        else if (k == AF) focus = liveFocus >= 0 && minFocusDist > 0 ? Math.max(0, Math.min(1, 1 - liveFocus / minFocusDist)) : 1;
        manual[k] = true;
    }

    interface Cost { double of(int i); }

    static int nearest(List<Integer> list, Cost c) {
        int best = list.get(0);
        for (int i : list) if (c.of(i) < c.of(best)) best = i;
        return best;
    }

    // ---------- スライダー（0..steps(k)） ----------

    int steps(int k) {
        switch (k) {
            case SS: return ssIdx.size() - 1;
            case ISO: return isoIdx.size() - 1;
            case WB: return wbIdx.size() - 1;
            case EV: return evMax - evMin;
            default: return 100;
        }
    }

    int pos(int k) {
        switch (k) {
            case SS: return Math.max(0, ssIdx.indexOf(ss));
            case ISO: return Math.max(0, isoIdx.indexOf(iso));
            case WB: return Math.max(0, wbIdx.indexOf(wb));
            case EV: return ev - evMin;
            case AF: return Math.round(focus * 100);
            default: return Math.round((float) (Math.log(zoom / zoomMin) / Math.log(zoomMax / zoomMin) * 100));
        }
    }

    void setPos(int k, int p) {
        switch (k) {
            case SS: ss = ssIdx.get(p); break;
            case ISO: iso = isoIdx.get(p); break;
            case WB: wb = wbIdx.get(p); break;
            case EV: ev = evMin + p; break;
            case AF: focus = p / 100f; break;
            default: zoom = (float) (zoomMin * Math.pow(zoomMax / zoomMin, p / 100.0));
        }
        manual[k] = true;
    }

    // ---------- 表示 ----------

    /** チップに出す値。オートは「A」を付けて今の値。 */
    String value(int k, float presetEv) {
        if (!can(k) && k != EV) return "非対応";
        boolean m = manual[k];
        String a = m ? "" : "A ";
        switch (k) {
            case SS: return a + (m ? SS_LABELS[ss] : liveExp > 0 ? shutter(liveExp) : "");
            case ISO: return a + (m ? ISO_ALL[iso] : liveIso > 0 ? String.valueOf(liveIso) : "");
            case WB: return m ? WB_LABELS[wb].split(" ")[0] : "A";
            case EV:
                if (manualExposure()) return "—";
                float e = m ? ev * evStep : presetEv;
                return a + String.format(Locale.JAPAN, "%+.1f", e);
            case AF: return m ? distance((1 - focus) * minFocusDist) : "A";
            default: return a + String.format(Locale.JAPAN, "%.1f×", zoom);
        }
    }

    /** スライダー横の詳しい値。 */
    String detail(int k, float presetEv) {
        if (k == WB && manual[WB]) return WB_LABELS[wb];
        if (k == AF && manual[AF]) return distance((1 - focus) * minFocusDist);
        return value(k, presetEv);
    }

    static String shutter(long ns) {
        if (ns >= 500_000_000L) return String.format(Locale.JAPAN, "%.1fs", ns / 1e9);
        return "1/" + Math.round(1e9 / ns);
    }

    static String distance(float diopter) {
        if (diopter <= 0.05f) return "∞";
        float m = 1 / diopter;
        return m < 1 ? Math.round(m * 100) + "cm" : String.format(Locale.JAPAN, "%.1fm", m);
    }
}
