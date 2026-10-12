package jp.haruto.dreamcamera;

import org.json.*;

public class Preset {
 public String name; public float exposure,contrast,temp,tint,saturation,vibrance,fade,clarity,dehaze,grain,vignette,blur,hue,red,green,blue;
 public Preset(String n,float...v){name=n; float[] a=new float[16]; System.arraycopy(v,0,a,0,Math.min(v.length,16)); exposure=a[0];contrast=a[1];temp=a[2];tint=a[3];saturation=a[4];vibrance=a[5];fade=a[6];clarity=a[7];dehaze=a[8];grain=a[9];vignette=a[10];blur=a[11];hue=a[12];red=a[13];green=a[14];blue=a[15];}
 public Preset copy(String n){return new Preset(n,exposure,contrast,temp,tint,saturation,vibrance,fade,clarity,dehaze,grain,vignette,blur,hue,red,green,blue);}
 public JSONObject json() throws JSONException {JSONObject o=new JSONObject();o.put("name",name);String[]k=keys();float[]v=vals();for(int i=0;i<k.length;i++)o.put(k[i],v[i]);return o;}
 public static Preset from(JSONObject o)throws JSONException{Preset p=new Preset(o.getString("name"));String[]k=keys();float[]v=p.vals();for(int i=0;i<k.length;i++)v[i]=(float)o.optDouble(k[i],0);p.set(v);return p;}
 public static String[] keys(){return new String[]{"露出","コントラスト","色温度","色かぶり","彩度","自然な彩度","フェード","明瞭度","かすみ除去","粒子","周辺減光","ぼかし","色相","赤","緑","青"};}
 public float[] vals(){return new float[]{exposure,contrast,temp,tint,saturation,vibrance,fade,clarity,dehaze,grain,vignette,blur,hue,red,green,blue};}
 public void set(float[]a){exposure=a[0];contrast=a[1];temp=a[2];tint=a[3];saturation=a[4];vibrance=a[5];fade=a[6];clarity=a[7];dehaze=a[8];grain=a[9];vignette=a[10];blur=a[11];hue=a[12];red=a[13];green=a[14];blue=a[15];}
}
