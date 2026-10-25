package com.evidencia.doblegps;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int PERMISSION_LOCATION = 81;
    private static final int REQUEST_CAMERA = 82;
    private final Frame[] frames = {new Frame(), new Frame()};
    private LocationManager manager;
    private Location bestGps;
    private final LocationListener listener = l -> {
        if (l != null && (bestGps == null || l.getElapsedRealtimeNanos() > bestGps.getElapsedRealtimeNanos())) {
            bestGps = new Location(l);
            updateGps();
        }
    };
    private TextView gpsStatus, elapsed, note1, note2, title;
    private ImageView image1, image2;
    private Button button1, button2, save, share;
    private int capturing = -1;
    private Uri pendingOriginal, comparison;
    private Location pendingGps;
    private long sessionId;
    private boolean gpsStarted = false;

    static class Frame {
        Uri original;
        Uri stamped;
        Bitmap bitmap;
        long time;
        Location gps;
        boolean approxTime;
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        sessionId = System.currentTimeMillis();
        getWindow().setStatusBarColor(Color.rgb(13, 25, 40));
        getWindow().setNavigationBarColor(Color.rgb(13, 25, 40));
        manager = (LocationManager) getSystemService(LOCATION_SERVICE);
        createInterface();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, PERMISSION_LOCATION);
        } else startGps();
        refresh();
    }

    private void createInterface() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(12, 23, 39));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(24), dp(16), dp(28));
        scroll.addView(root);

        title = label("DOBLE EVIDENCIA GPS", 23, Color.WHITE);
        title.setTypeface(null, 1);
        root.addView(title);
        root.addView(label("Dos fotografías • Un intervalo • Una evidencia", 13, Color.rgb(177, 191, 209)));
        gpsStatus = label("GPS: comprobando ubicación...", 13, Color.rgb(131, 205, 239));
        root.addView(gpsStatus);

        button1 = button("1  TOMAR PRIMERA FOTO", root, () -> capture(0));
        note1 = label("Primera fotografía pendiente", 13, Color.LTGRAY);
        root.addView(note1);
        image1 = preview(root);

        elapsed = label("TIEMPO TRANSCURRIDO: --:--:--", 21, Color.rgb(255, 65, 65));
        elapsed.setTypeface(null, 1);
        elapsed.setGravity(android.view.Gravity.CENTER);
        elapsed.setPadding(0, dp(18), 0, dp(18));
        root.addView(elapsed);

        button2 = button("2  TOMAR SEGUNDA FOTO", root, () -> capture(1));
        note2 = label("Segunda fotografía pendiente", 13, Color.LTGRAY);
        root.addView(note2);
        image2 = preview(root);

        save = button("GUARDAR FOTO DOBLE", root, this::saveComposite);
        share = button("COMPARTIR FOTO DOBLE", root, this::shareComposite);
        button("NUEVA SESIÓN", root, this::newSession);
        TextView tip = label("Las fotos se guardan en Galería > Imágenes > DobleEvidenciaGPS. Sin Internet ni cuentas.", 12, Color.rgb(177, 191, 209));
        tip.setPadding(0, dp(14), 0, 0);
        root.addView(tip);
        setContentView(scroll);
    }

    private TextView label(String content, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(content);
        t.setTextColor(color);
        t.setTextSize(sp);
        t.setPadding(0, dp(7), 0, dp(7));
        return t;
    }
    private int dp(float n) { return (int)(n * getResources().getDisplayMetrics().density + .5f); }
    private Button button(String s, LinearLayout root, Runnable click) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(30, 109, 172)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(52));
        lp.setMargins(0, dp(9), 0, dp(7));
        root.addView(b, lp);
        b.setOnClickListener(v -> click.run());
        return b;
    }
    private ImageView preview(LinearLayout root) {
        ImageView view = new ImageView(this);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        view.setBackgroundColor(Color.rgb(25, 38, 55));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(190));
        root.addView(view, lp);
        return view;
    }
    private void updateGps() {
        Location g = currentGps();
        gpsStatus.setText(g == null ? "GPS: esperando posición reciente (se permite fotografiar sin GPS)" :
                String.format(Locale.US, "GPS: %.6f, %.6f • precisión ±%.0f m",
                        g.getLatitude(), g.getLongitude(), g.hasAccuracy() ? g.getAccuracy() : 0));
    }
    private Location currentGps() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null;
        if (manager != null) {
            for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                try {
                    Location possible = manager.getLastKnownLocation(provider);
                    if (possible != null && (bestGps == null ||
                      possible.getElapsedRealtimeNanos() > bestGps.getElapsedRealtimeNanos())) bestGps = new Location(possible);
                } catch (Exception ignored) {}
            }
        }
        if (bestGps == null) return null;
        long age = SystemClock.elapsedRealtime() - bestGps.getElapsedRealtimeNanos()/1000000L;
        return age >= 0 && age <= 120000 ? new Location(bestGps) : null;
    }
    private void startGps() {
        if (gpsStarted || manager == null ||
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        gpsStarted = true;
        for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
            try { manager.requestLocationUpdates(provider, 1000, 0.5f, listener, Looper.getMainLooper()); }
            catch (Exception ignored) {}
        }
        updateGps();
    }
    @Override public void onRequestPermissionsResult(int rc, String[] p, int[] r) {
        super.onRequestPermissionsResult(rc,p,r);
        if (rc == PERMISSION_LOCATION) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) startGps();
            else toast("Sin permiso GPS, las fotos mostrarán ubicación no disponible.");
            updateGps();
        }
    }
    private void capture(int index) {
        if (index == 1 && frames[0].original == null) {
            toast("Primero toma la fotografía 1."); return;
        }
        if (index == 0 && frames[1].original != null) {
            toast("Usa NUEVA SESIÓN para iniciar un par nuevo."); return;
        }
        Uri uri = createMedia("Foto_" + (index + 1) + "_Original_" + System.currentTimeMillis() + ".jpg");
        if (uri == null) { toast("No se pudo preparar el archivo."); return; }
        pendingOriginal = uri;
        pendingGps = currentGps();
        capturing = index;
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        intent.putExtra(MediaStore.EXTRA_OUTPUT, uri);
        intent.setClipData(android.content.ClipData.newRawUri("Foto", uri));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try { startActivityForResult(intent, REQUEST_CAMERA); }
        catch (Exception e) {
            getContentResolver().delete(uri,null,null);
            pendingOriginal=null; capturing=-1;
            toast("No se encontró una aplicación de cámara compatible.");
        }
    }
    private Uri createMedia(String name) {
        try {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            cv.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DobleEvidenciaGPS");
            return getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
        } catch (Exception e) {return null;}
    }
    private void writeJpeg(Bitmap bmp, Uri uri) throws Exception {
        try (OutputStream out = getContentResolver().openOutputStream(uri,"w")) {
            if(out == null || !bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)) throw new Exception("No se guardó JPEG");
        }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request,result,data);
        if (request != REQUEST_CAMERA || capturing < 0 || capturing > 1) return;
        int slot = capturing;
        capturing=-1;
        Uri src = pendingOriginal;
        pendingOriginal=null;
        if (result != RESULT_OK || src == null) {
            if (src != null) getContentResolver().delete(src,null,null);
            toast("Captura cancelada."); return;
        }
        try {
            Bitmap original = decode(src);
            if (original == null) throw new Exception("Imagen vacía");
            long time = readExifDate(src);
            Frame f = new Frame();
            f.original=src;
            f.time=time>0?time:System.currentTimeMillis();
            f.approxTime=time<=0;
            f.gps=pendingGps;
            f.bitmap=stamp(original, f, slot+1);
            f.stamped=createMedia("Foto_"+(slot+1)+"_GPS_"+System.currentTimeMillis()+".jpg");
            if(f.stamped==null) throw new Exception("Sin destino JPEG");
            writeJpeg(f.bitmap, f.stamped);
            frames[slot]=f;
            comparison=null;
            refresh();
            toast("Foto "+(slot+1)+" guardada en la galería.");
        } catch(Exception e) {
            toast("Error al procesar foto: "+e.getMessage());
        }
    }
    private long readExifDate(Uri uri) {
        try(InputStream in = getContentResolver().openInputStream(uri)) {
            if (in==null) return -1;
            ExifInterface exif = new ExifInterface(in);
            String date = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL);
            if (date==null) return -1;
            SimpleDateFormat fmt=new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US);
            fmt.setLenient(false);
            Date parsed=fmt.parse(date);
            return parsed==null?-1:parsed.getTime();
        }catch(Exception e){return -1;}
    }
    private Bitmap decode(Uri uri) throws Exception {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds=true;
        try(InputStream in=getContentResolver().openInputStream(uri)){
            if (in==null) throw new Exception("Sin imagen");
            BitmapFactory.decodeStream(in,null,opts);
        }
        int sample=1;
        while (Math.max(opts.outWidth/sample,opts.outHeight/sample)>1700) sample*=2;
        opts.inSampleSize=sample; opts.inJustDecodeBounds=false;
        try(InputStream in=getContentResolver().openInputStream(uri)){
            Bitmap b=BitmapFactory.decodeStream(in,null,opts);
            if(b==null) throw new Exception("Error JPEG");
            return b;
        }
    }
    private Bitmap stamp(Bitmap input, Frame f, int n) {
        Bitmap out=input.copy(Bitmap.Config.ARGB_8888,true);
        if (out==null) out=input;
        Canvas c=new Canvas(out);
        float w=out.getWidth(),h=out.getHeight(),unit=Math.max(18f,Math.min(w,h)/42f);
        float block=unit*6.9f;
        Paint p=new Paint(3);
        p.setColor(Color.argb(195,0,0,0));
        c.drawRect(0,h-block,w,h,p);
        p.setColor(Color.WHITE); p.setTypeface(android.graphics.Typeface.create("sans-serif",1));
        p.setTextSize(unit);
        float y=h-block+unit*1.45f;
        c.drawText("FOTO "+n+" | "+formatDate(f.time)+(f.approxTime?" (hora aprox.)":""),unit,y,p);
        y+=unit*1.4f;
        if(f.gps!=null) {
            c.drawText(String.format(Locale.US,"GPS: %.6f, %.6f",f.gps.getLatitude(),f.gps.getLongitude()),unit,y,p);
            y+=unit*1.4f;
            c.drawText(String.format(Locale.US,"Precisión ±%.0f m%s",f.gps.hasAccuracy()?f.gps.getAccuracy():0,
                    f.gps.hasAltitude()?String.format(Locale.US,"  Altitud %.1f m",f.gps.getAltitude()):""),unit,y,p);
        } else c.drawText("Ubicación GPS: no disponible",unit,y,p);
        y+=unit*1.35f;
        p.setColor(Color.rgb(107,222,255));
        c.drawText("DOBLE EVIDENCIA GPS",unit,y,p);
        return out;
    }
    private String formatDate(long time) {
        return new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(new Date(time));
    }
    private String timeElapsed(){
        if(frames[0].original==null || frames[1].original==null) return "--:--:--";
        long seconds=Math.abs(frames[1].time-frames[0].time)/1000;
        return String.format(Locale.US,"%02d:%02d:%02d",seconds/3600,(seconds/60)%60,seconds%60);
    }
    private void refresh() {
        Frame a=frames[0],b=frames[1];
        image1.setImageBitmap(a.bitmap); image2.setImageBitmap(b.bitmap);
        note1.setText(a.original==null?"Foto 1 pendiente":"Foto 1 • "+formatDate(a.time)+geoShort(a.gps));
        note2.setText(b.original==null?"Foto 2 pendiente":"Foto 2 • "+formatDate(b.time)+geoShort(b.gps));
        elapsed.setText("TIEMPO TRANSCURRIDO\n"+timeElapsed());
        button1.setEnabled(a.original==null);
        button2.setEnabled(a.original!=null && b.original==null);
        save.setEnabled(a.original!=null && b.original!=null);
        share.setEnabled(comparison!=null);
        updateGps();
    }
    private String geoShort(Location loc) {
        return loc==null?" • GPS no disponible":String.format(Locale.US," • %.6f, %.6f",loc.getLatitude(),loc.getLongitude());
    }
    private void saveComposite() {
        if(frames[0].bitmap==null || frames[1].bitmap==null)return;
        try{
            int width=1080;
            int h1=Math.round((float)frames[0].bitmap.getHeight()/frames[0].bitmap.getWidth()*width);
            int h2=Math.round((float)frames[1].bitmap.getHeight()/frames[1].bitmap.getWidth()*width);
            int band=140;
            Bitmap both=Bitmap.createBitmap(width,h1+band+h2,Bitmap.Config.ARGB_8888);
            Canvas canvas=new Canvas(both);
            Paint p=new Paint(3);
            canvas.drawBitmap(frames[0].bitmap,null,new android.graphics.Rect(0,0,width,h1),p);
            p.setColor(Color.rgb(17,25,37));
            canvas.drawRect(0,h1,width,h1+band,p);
            p.setColor(Color.rgb(255,59,69));p.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            p.setTextAlign(Paint.Align.CENTER);p.setTextSize(40);
            canvas.drawText("TIEMPO TRANSCURRIDO",width/2f,h1+52,p);
            p.setTextSize(58);
            canvas.drawText(timeElapsed(),width/2f,h1+113,p);
            p.setColor(Color.WHITE);
            canvas.drawBitmap(frames[1].bitmap,null,new android.graphics.Rect(0,h1+band,width,h1+band+h2),p);
            comparison=createMedia("DOS_FOTOS_"+sessionId+".jpg");
            if(comparison==null) throw new Exception("No se pudo crear archivo");
            writeJpeg(both,comparison);
            both.recycle();
            share.setEnabled(true);
            toast("Comparativa guardada en Galería.");
        }catch(Exception e){toast("Error al guardar: "+e.getMessage());}
    }
    private void shareComposite() {
        if (comparison==null) return;
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("image/jpeg");
        shareIntent.putExtra(Intent.EXTRA_STREAM,comparison);
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(shareIntent,"Compartir evidencia"));
    }
    private void newSession() {
        frames[0]=new Frame();frames[1]=new Frame();
        comparison=null;sessionId=System.currentTimeMillis();refresh();
        toast("Nueva sesión. Las fotos anteriores siguen guardadas.");
    }
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    @Override protected void onResume(){super.onResume();startGps();}
    @Override protected void onDestroy(){
        if(manager!=null && gpsStarted)try{manager.removeUpdates(listener);}catch(Exception ignored){}
        super.onDestroy();
    }
}