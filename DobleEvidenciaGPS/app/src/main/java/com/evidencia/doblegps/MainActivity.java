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
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
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
import android.view.Gravity;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int LOCATION_REQUEST = 41, CAMERA_REQUEST = 42;
    private static final int BG = Color.rgb(12, 23, 38);
    private static final int PANE_W = 1060, PANE_H = 1100, FOOTER_H = 142;
    private static final long TILE_CACHE_MS = 7L * 24 * 60 * 60 * 1000;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Frame[] frames = {new Frame(), new Frame()};
    private LocationManager locationManager;
    private Location bestLocation;
    private boolean locationActive;
    private final LocationListener gpsListener = loc -> {
        if (loc != null && (bestLocation == null ||
            loc.getElapsedRealtimeNanos() > bestLocation.getElapsedRealtimeNanos())) {
            bestLocation = new Location(loc);
            refreshGps();
        }
    };

    private TextView gpsLabel, waitLabel, firstLabel, secondLabel, statusLabel;
    private ImageView firstPreview, secondPreview, finalPreview;
    private Button firstButton, secondButton, retryButton, saveButton, openButton, shareButton;
    private Uri pendingOriginal, compositeUri;
    private Location launchLocation;
    private int pendingSlot = -1;
    private long sessionToken;

    private static final class Frame {
        Uri original;
        Uri annotated;
        Bitmap preview;
        long capturedAt;
        Location location;
        boolean processing, mapReady, failed;
        String error;
    }

    @Override public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        sessionToken = System.currentTimeMillis();
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        makeUi();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION
            }, LOCATION_REQUEST);
        } else startLocation();
        updateUi();
    }

    private int dp(float n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }
    private TextView text(String s, int sp, int color) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextColor(color);
        v.setTextSize(sp);
        v.setPadding(0, dp(6), 0, dp(6));
        return v;
    }
    private Button button(LinearLayout root, String label, Runnable runnable) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(30, 109, 170)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(50));
        params.setMargins(0, dp(7), 0, dp(7));
        root.addView(b, params);
        b.setOnClickListener(v -> runnable.run());
        return b;
    }
    private ImageView preview(LinearLayout root, int height) {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        v.setBackgroundColor(Color.rgb(27, 38, 51));
        root.addView(v, new LinearLayout.LayoutParams(-1, dp(height)));
        return v;
    }
    private void makeUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout main = new LinearLayout(this);
        main.setPadding(dp(15), dp(18), dp(15), dp(32));
        main.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(main);

        TextView heading = text("DOBLE EVIDENCIA GPS", 22, Color.WHITE);
        heading.setTypeface(null, 1);
        main.addView(heading);
        main.addView(text("V2 • Horizontal • Mapas integrados", 13, Color.rgb(170, 189, 207)));

        gpsLabel = text("Buscando ubicación GPS...", 13, Color.rgb(132, 214, 240));
        main.addView(gpsLabel);
        firstButton = button(main, "TOMAR PRIMERA FOTOGRAFÍA", () -> capture(0));
        firstLabel = text("Primera captura pendiente", 12, Color.LTGRAY);
        main.addView(firstLabel);
        firstPreview = preview(main, 235);
        firstPreview.setOnClickListener(v -> viewFrame(0));
        waitLabel = text("TIEMPO DE ESPERA  --:--:--", 21, Color.rgb(255, 64, 76));
        waitLabel.setTypeface(null, 1);
        waitLabel.setGravity(Gravity.CENTER);
        waitLabel.setPadding(0, dp(18), 0, dp(15));
        main.addView(waitLabel);
        secondButton = button(main, "TOMAR SEGUNDA FOTOGRAFÍA", () -> capture(1));
        secondLabel = text("Segunda captura pendiente", 12, Color.LTGRAY);
        main.addView(secondLabel);
        secondPreview = preview(main, 235);
        secondPreview.setOnClickListener(v -> viewFrame(1));
        statusLabel = text("Se guardan las fotos con su mapa y la comparación final.", 13, Color.rgb(180, 204, 223));
        main.addView(statusLabel);
        retryButton = button(main, "REINTENTAR CARGA DE MAPAS", this::retryMaps);
        saveButton = button(main, "GUARDAR IMAGEN HORIZONTAL", this::saveComposite);
        openButton = button(main, "VER IMAGEN FINAL", this::viewComposite);
        finalPreview = preview(main, 230);
        finalPreview.setOnClickListener(v -> viewComposite());
        shareButton = button(main, "COMPARTIR EVIDENCIA", this::shareComposite);
        button(main, "NUEVA EVIDENCIA", this::newSession);
        main.addView(text("Mapa real © OpenStreetMap contributors. Para incrustarlo se necesita GPS e Internet al tomar las fotos. Una vez guardadas, las imágenes funcionan sin Internet.", 12, Color.rgb(172, 193, 207)));
        setContentView(scroll);
    }

    private void startLocation() {
        if (locationActive || locationManager == null ||
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        locationActive = true;
        for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
            try { locationManager.requestLocationUpdates(provider, 1200, 0.5f, gpsListener, Looper.getMainLooper()); }
            catch (Exception ignored) {}
        }
        refreshGps();
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request == LOCATION_REQUEST) startLocation();
        refreshGps();
    }
    private Location currentLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null;
        for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
            try {
                Location l = locationManager.getLastKnownLocation(provider);
                if (l != null && (bestLocation == null ||
                    l.getElapsedRealtimeNanos() > bestLocation.getElapsedRealtimeNanos())) bestLocation = new Location(l);
            } catch (Exception ignored) {}
        }
        if (bestLocation == null) return null;
        long age = SystemClock.elapsedRealtime() - bestLocation.getElapsedRealtimeNanos()/1000000L;
        return age >= 0 && age <= 60000 ? new Location(bestLocation) : null;
    }
    private void refreshGps() {
        if (gpsLabel == null) return;
        Location l = currentLocation();
        gpsLabel.setText(l == null ? "Esperando GPS reciente (necesario para el mapa real)" :
            String.format(Locale.US, "GPS %.6f, %.6f • ±%.0f m", l.getLatitude(), l.getLongitude(),
                l.hasAccuracy() ? l.getAccuracy() : 0));
    }

    private void capture(int index) {
        if (pendingSlot >= 0) return;
        if (index == 1 && frames[0].original == null) {
            toast("Primero toma la primera fotografía."); return;
        }
        if (frames[index].original != null) {
            toast("Inicia una nueva evidencia para volver a tomarla."); return;
        }
        if (currentLocation() == null) {
            toast("Para asegurar el mapa, activa ubicación precisa y espera la señal GPS."); return;
        }
        Uri uri = createImage("Original_" + sessionToken + "_" + (index+1) + ".jpg");
        if (uri == null) { toast("No se pudo crear el archivo de la cámara."); return; }
        pendingOriginal = uri;
        launchLocation = currentLocation();
        pendingSlot = index;
        Intent capture = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        capture.putExtra(MediaStore.EXTRA_OUTPUT, uri);
        capture.setClipData(android.content.ClipData.newRawUri("Evidencia",uri));
        capture.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try { startActivityForResult(capture, CAMERA_REQUEST); }
        catch (Exception e) {
            getContentResolver().delete(uri,null,null);
            pendingSlot=-1;pendingOriginal=null;
            toast("No hay cámara disponible.");
        }
    }
    private Uri createImage(String name) {
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DobleEvidenciaGPS");
            return getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        } catch (Exception e) { return null; }
    }
    @Override protected void onActivityResult(int request, int result, Intent extras) {
        super.onActivityResult(request,result,extras);
        if (request != CAMERA_REQUEST || pendingSlot < 0) return;
        final int slot=pendingSlot;
        pendingSlot=-1;
        final Uri image=pendingOriginal;
        pendingOriginal=null;
        if (image == null || result != RESULT_OK) {
            if (image!=null) getContentResolver().delete(image,null,null);
            toast("Captura cancelada."); return;
        }
        final Location fresh=currentLocation();
        final Location coords=fresh!=null?fresh:launchLocation;
        launchLocation=null;
        if (coords==null) {
            // Conservar el original, pero no simular ubicación.
            toast("No se obtuvo GPS reciente. La imagen quedará pendiente hasta recuperar la ubicación.");
        }
        final Frame f = new Frame();
        f.original=image;
        f.location=coords;
        f.capturedAt=System.currentTimeMillis();
        f.processing=true;
        frames[slot]=f;
        compositeUri=null;
        updateUi();
        final long thisSession = sessionToken;
        worker.execute(() -> processFrame(slot,f,thisSession));
    }

    private Bitmap loadPhoto(Uri uri) throws Exception {
        BitmapFactory.Options bounds=new BitmapFactory.Options();
        bounds.inJustDecodeBounds=true;
        try(InputStream input=getContentResolver().openInputStream(uri)) {
            if(input==null) throw new Exception("No se encontró la foto");
            BitmapFactory.decodeStream(input,null,bounds);
        }
        int sample=1;
        while(Math.max(bounds.outWidth/sample,bounds.outHeight/sample)>1550) sample*=2;
        BitmapFactory.Options opts=new BitmapFactory.Options();
        opts.inSampleSize=sample;
        Bitmap image;
        try(InputStream input=getContentResolver().openInputStream(uri)) {
            image=BitmapFactory.decodeStream(input,null,opts);
        }
        if(image==null) throw new Exception("No se pudo leer la fotografía");
        try(InputStream input=getContentResolver().openInputStream(uri)) {
            ExifInterface exif=new ExifInterface(input);
            int orientation=exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL);
            Matrix m=new Matrix();
            if(orientation==ExifInterface.ORIENTATION_ROTATE_90)m.postRotate(90);
            if(orientation==ExifInterface.ORIENTATION_ROTATE_180)m.postRotate(180);
            if(orientation==ExifInterface.ORIENTATION_ROTATE_270)m.postRotate(270);
            if(orientation==ExifInterface.ORIENTATION_FLIP_HORIZONTAL)m.postScale(-1,1);
            if(orientation==ExifInterface.ORIENTATION_FLIP_VERTICAL)m.postScale(1,-1);
            if(!m.isIdentity()) {
                Bitmap rotated=Bitmap.createBitmap(image,0,0,image.getWidth(),image.getHeight(),m,true);
                if(rotated!=image)image.recycle();
                image=rotated;
            }
        } catch (Exception ignored) {}
        return image;
    }
    private void processFrame(int index, Frame f, long requestedSession) {
        try {
            Bitmap source=loadPhoto(f.original);
            // La foto original se muestra en pantalla antes de consultar el mapa.
            runOnUiThread(() -> {
                if(requestedSession==sessionToken && frames[index]==f){
                    f.preview=source;
                    updateUi();
                }
            });
            if (f.location == null) throw new Exception("Ubicación GPS no disponible; activa GPS para nueva captura.");
            Bitmap map = loadRealMap(f.location);
            Bitmap annotated = stamp(source,map,f);
            if (requestedSession != sessionToken || frames[index] != f) return;
            Uri dst=createImage("GPS_Mapa_"+requestedSession+"_"+(index+1)+".jpg");
            if(dst==null) throw new Exception("Error preparando archivo");
            writeJpeg(annotated,dst,90);
            f.annotated=dst;
            f.preview=annotated;
            f.mapReady=true;
            f.processing=false;
            f.failed=false;
            runOnUiThread(() -> {
                if(requestedSession!=sessionToken || frames[index]!=f)return;
                updateUi();
                if(frames[0].mapReady&&frames[1].mapReady)saveComposite();
            });
        } catch(Exception ex) {
            f.processing=false;
            f.failed=true;
            f.error=ex.getMessage();
            runOnUiThread(() -> {
                if(requestedSession==sessionToken && frames[index]==f)updateUi();
            });
        }
    }
    private Bitmap loadRealMap(Location loc) throws Exception {
        // OpenStreetMap: solo consulta las baldosas del pequeño mapa solicitado por la persona.
        // Las baldosas se conservan al menos 7 días en la caché local.
        int zoom=16,w=360,h=274,tile=256;
        double lat=Math.max(-85.0511,Math.min(85.0511,loc.getLatitude()));
        double n=Math.pow(2,zoom),r=Math.toRadians(lat);
        double cx=(loc.getLongitude()+180)/360*n*tile;
        double cy=(1-Math.log(Math.tan(r)+1/Math.cos(r))/Math.PI)/2*n*tile;
        int x0=(int)Math.floor((cx-w/2.0)/tile);
        int x1=(int)Math.floor((cx+w/2.0)/tile);
        int y0=(int)Math.floor((cy-h/2.0)/tile);
        int y1=(int)Math.floor((cy+h/2.0)/tile);
        Bitmap out=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(out);
        canvas.drawColor(Color.rgb(232,231,224));
        Paint p=new Paint(3);
        for(int ty=y0;ty<=y1;ty++){
            for(int tx=x0;tx<=x1;tx++){
                if(ty<0 || ty>=n)continue;
                int normalized=(int)(((long)tx+(long)n)%(long)n);
                Bitmap tileImage=loadTile(zoom,normalized,ty);
                float left=(float)(tx*tile-cx+w/2.0);
                float top=(float)(ty*tile-cy+h/2.0);
                canvas.drawBitmap(tileImage,left,top,p);
                tileImage.recycle();
            }
        }
        // Pin rojo centrado con punta sobre la ubicación verdadera.
        float px=w/2f,py=h/2f;
        p.setColor(Color.rgb(210,39,47));p.setStyle(Paint.Style.FILL);
        Path pointer=new Path();
        pointer.moveTo(px,py+11);
        pointer.lineTo(px-15,py-15);
        pointer.lineTo(px+15,py-15);
        pointer.close();
        canvas.drawPath(pointer,p);
        canvas.drawCircle(px,py-18,19,p);
        p.setColor(Color.WHITE);
        canvas.drawCircle(px,py-18,8,p);
        p.setColor(Color.argb(215,18,24,30));
        canvas.drawRect(0,h-28,w,h,p);
        p.setColor(Color.WHITE);p.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        p.setTextSize(17);
        canvas.drawText("© OpenStreetMap contributors",8,h-8,p);
        return out;
    }
    private Bitmap loadTile(int z,int x,int y) throws Exception {
        File folder=new File(getCacheDir(),"osm_tiles");
        if(!folder.exists() && !folder.mkdirs())throw new Exception("No se pudo preparar caché de mapas");
        File cached=new File(folder,z+"_"+x+"_"+y+".png");
        if(cached.exists() && cached.length()>0 &&
            System.currentTimeMillis()-cached.lastModified()<TILE_CACHE_MS){
            Bitmap img=BitmapFactory.decodeFile(cached.getAbsolutePath());
            if(img!=null)return img;
        }
        HttpURLConnection http=null;
        File temp=new File(folder,z+"_"+x+"_"+y+".tmp");
        try{
            URL url=new URL("https://tile.openstreetmap.org/"+z+"/"+x+"/"+y+".png");
            http=(HttpURLConnection)url.openConnection();
            http.setConnectTimeout(6000);
            http.setReadTimeout(6000);
            http.setRequestProperty("User-Agent","DobleEvidenciaGPS/2.0 (+https://github.com/Jpsanchez94/calidad-software-practica)");
            http.setRequestProperty("Accept","image/png");
            if(http.getResponseCode()!=200)throw new Exception("Mapa no disponible (HTTP "+http.getResponseCode()+")");
            try(InputStream in=http.getInputStream();OutputStream out=new FileOutputStream(temp)){
                byte[] buf=new byte[8192];int len,total=0;
                while((len=in.read(buf))!=-1){
                    total+=len;
                    if(total>800000)throw new Exception("Baldosa del mapa demasiado grande");
                    out.write(buf,0,len);
                }
            }
            Bitmap image=BitmapFactory.decodeFile(temp.getAbsolutePath());
            if(image==null)throw new Exception("Mapa ilegible");
            if(cached.exists())cached.delete();
            if(!temp.renameTo(cached))throw new Exception("No se pudo guardar mapa");
            return image;
        }catch(Exception e){
            // Falla de red: usar solo caché existente, nunca un mapa ficticio.
            if(cached.exists()){
                Bitmap old=BitmapFactory.decodeFile(cached.getAbsolutePath());
                if(old!=null)return old;
            }
            throw e;
        }finally{
            if(http!=null)http.disconnect();
            if(temp.exists())temp.delete();
        }
    }
    private Bitmap stamp(Bitmap source, Bitmap map, Frame f) {
        Bitmap canvasBitmap=Bitmap.createBitmap(PANE_W,PANE_H,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(canvasBitmap);
        canvas.drawColor(Color.rgb(23,28,35));
        Paint p=new Paint(3);
        // FIT_CENTER: se conserva toda la foto, sin recortar rostros, vehículos ni evidencia.
        float scale=Math.min((float)PANE_W/source.getWidth(),(float)PANE_H/source.getHeight());
        int nw=Math.round(source.getWidth()*scale),nh=Math.round(source.getHeight()*scale);
        int left=(PANE_W-nw)/2,top=(PANE_H-nh)/2;
        canvas.drawBitmap(source,null,new Rect(left,top,left+nw,top+nh),p);
        // Brújula visual simple. No inventar orientación si el GPS no tiene bearing.
        p.setColor(Color.argb(125,12,18,24));
        canvas.drawCircle(70,75,57,p);
        p.setColor(Color.WHITE);p.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        p.setTextAlign(Paint.Align.CENTER);p.setTextSize(24);
        canvas.drawText("N",70,32,p);
        p.setColor(Color.CYAN);
        Path needle=new Path();
        needle.moveTo(70,42);needle.lineTo(55,110);needle.lineTo(70,96);needle.lineTo(85,110);needle.close();
        canvas.drawPath(needle,p);
        p.setTextAlign(Paint.Align.LEFT);
        // Mapa real bajo a la izquierda, similar a la referencia.
        int mapLeft=15,mapBottom=PANE_H-18,mapWidth=360,mapHeight=274;
        p.setColor(Color.WHITE);
        canvas.drawRoundRect(new RectF(mapLeft-3,mapBottom-mapHeight-3,
                mapLeft+mapWidth+3,mapBottom+3),8,8,p);
        canvas.drawBitmap(map,mapLeft,mapBottom-mapHeight,p);
        // Información blanca, grande y semitransparente, sin "Foto 1/2".
        int boxLeft=384, boxTop=PANE_H-310;
        p.setColor(Color.argb(95,4,8,12));
        canvas.drawRoundRect(new RectF(boxLeft,boxTop,PANE_W-12,PANE_H-16),18,18,p);
        p.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        p.setTextAlign(Paint.Align.RIGHT);
        p.setShadowLayer(4,1,2,Color.BLACK);
        p.setColor(Color.WHITE);
        p.setTextSize(40);
        float right=PANE_W-31;
        canvas.drawText(formatDate(f.capturedAt),right,PANE_H-250,p);
        p.setTextSize(50);
        canvas.drawText(formatTime(f.capturedAt),right,PANE_H-183,p);
        p.setTextSize(31);
        canvas.drawText(String.format(Locale.US,"%.6f, %.6f",f.location.getLatitude(),
            f.location.getLongitude()),right,PANE_H-127,p);
        p.setTextSize(28);
        if(f.location.hasAccuracy()){
            canvas.drawText(String.format(Locale.US,"GPS ±%.0f m",f.location.getAccuracy()),right,PANE_H-79,p);
        }
        p.clearShadowLayer();
        p.setTextAlign(Paint.Align.LEFT);
        return canvasBitmap;
    }
    private String formatDate(long epoch){
        return new SimpleDateFormat("dd/MM/yyyy",Locale.getDefault()).format(new Date(epoch));
    }
    private String formatTime(long epoch){
        return new SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(new Date(epoch));
    }
    private String elapsed(){
        if(frames[0].original==null || frames[1].original==null)return "--:--:--";
        long s=Math.max(0,frames[1].capturedAt-frames[0].capturedAt)/1000;
        return String.format(Locale.US,"%02d:%02d:%02d",s/3600,(s/60)%60,s%60);
    }
    private void writeJpeg(Bitmap image,Uri where,int quality) throws Exception {
        try(OutputStream out=getContentResolver().openOutputStream(where,"w")){
            if(out==null || !image.compress(Bitmap.CompressFormat.JPEG,quality,out)){
                throw new Exception("No se pudo guardar JPG");
            }
        }
    }
    private void saveComposite(){
        if(!frames[0].mapReady || !frames[1].mapReady){
            toast("Espera a que se carguen los dos mapas reales.");return;
        }
        if(compositeUri!=null){viewComposite();return;}
        final Frame a=frames[0],b=frames[1];
        final long current=sessionToken;
        statusLabel.setText("Creando imagen horizontal...");
        saveButton.setEnabled(false);
        worker.execute(() -> {
            try{
                Bitmap whole=Bitmap.createBitmap(PANE_W*2,PANE_H+FOOTER_H,Bitmap.Config.ARGB_8888);
                Canvas c=new Canvas(whole);
                Paint p=new Paint(3);
                c.drawColor(BG);
                c.drawBitmap(a.preview,0,0,p);
                c.drawBitmap(b.preview,PANE_W,0,p);
                p.setColor(Color.WHITE);
                c.drawRect(PANE_W-3,0,PANE_W+3,PANE_H,p);
                p.setColor(Color.rgb(14,22,33));
                c.drawRect(0,PANE_H,PANE_W*2,PANE_H+FOOTER_H,p);
                p.setTextAlign(Paint.Align.CENTER);
                p.setColor(Color.rgb(255,64,76));
                p.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                p.setTextSize(48);
                c.drawText("TIEMPO DE ESPERA",PANE_W,PANE_H+54,p);
                p.setTextSize(65);
                c.drawText(elapsed(),PANE_W,PANE_H+122,p);
                if(current!=sessionToken)return;
                Uri target=createImage("Tiempo_de_espera_"+current+".jpg");
                if(target==null)throw new Exception("No se pudo crear el JPG");
                writeJpeg(whole,target,90);
                runOnUiThread(() -> {
                    if(current!=sessionToken)return;
                    compositeUri=target;
                    finalPreview.setImageBitmap(whole);
                    updateUi();
                    toast("Imagen horizontal guardada con ambos mapas.");
                });
            }catch(Exception e){
                runOnUiThread(() -> {
                    if(current==sessionToken){
                        statusLabel.setText("Error guardando evidencia: "+e.getMessage());
                        saveButton.setEnabled(true);
                    }
                });
            }
        });
    }
    private void retryMaps(){
        int retry=0;
        for(int i=0;i<2;i++){
            Frame f=frames[i];
            if(f.original!=null&&!f.mapReady&&!f.processing){
                if(f.location==null)continue;
                f.processing=true;f.failed=false;
                final int index=i;final long token=sessionToken;
                worker.execute(() -> processFrame(index,f,token));
                retry++;
            }
        }
        if(retry==0)toast("No hay mapas reintentables; para nueva ubicación usa NUEVA EVIDENCIA.");
        updateUi();
    }
    private void viewFrame(int index){
        Uri uri=frames[index].annotated!=null?frames[index].annotated:frames[index].original;
        if(uri==null)return;
        Intent intent=new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri,"image/jpeg");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try{startActivity(intent);}catch(Exception e){toast("Abre la fotografía desde Galería");}
    }
    private void viewComposite(){
        if(compositeUri==null)return;
        Intent intent=new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(compositeUri,"image/jpeg");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try{startActivity(intent);}catch(Exception e){toast("Abre Galería > DobleEvidenciaGPS");}
    }
    private void shareComposite(){
        if(compositeUri==null)return;
        Intent intent=new Intent(Intent.ACTION_SEND);
        intent.setType("image/jpeg");
        intent.putExtra(Intent.EXTRA_STREAM,compositeUri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(intent,"Compartir evidencia"));
    }
    private void newSession(){
        frames[0]=new Frame();frames[1]=new Frame();
        compositeUri=null;sessionToken=System.currentTimeMillis();
        finalPreview.setImageDrawable(null);
        updateUi();
        toast("Nueva evidencia iniciada. Las fotos guardadas permanecen en Galería.");
    }
    private void updateUi(){
        firstPreview.setImageBitmap(frames[0].preview);
        secondPreview.setImageBitmap(frames[1].preview);
        firstButton.setEnabled(frames[0].original==null);
        secondButton.setEnabled(frames[0].original!=null && frames[1].original==null);
        firstLabel.setText(describe(frames[0]));
        secondLabel.setText(describe(frames[1]));
        waitLabel.setText("TIEMPO DE ESPERA  "+elapsed());
        boolean ready=frames[0].mapReady&&frames[1].mapReady;
        saveButton.setEnabled(ready&&compositeUri==null);
        openButton.setEnabled(compositeUri!=null);
        shareButton.setEnabled(compositeUri!=null);
        retryButton.setEnabled((frames[0].failed||frames[1].failed) &&
                (frames[0].location!=null||frames[1].location!=null));
        if(compositeUri!=null)statusLabel.setText("Imagen horizontal guardada. Toca la vista previa para ampliarla.");
        else if(frames[0].failed||frames[1].failed) {
            String e=frames[0].failed?frames[0].error:frames[1].error;
            statusLabel.setText("Faltó el mapa real: "+e+" · revisa Internet y usa REINTENTAR.");
        }
        else if(frames[0].processing||frames[1].processing)statusLabel.setText("Foto visible. Cargando mapa real en segundo plano...");
        else statusLabel.setText("Las dos fotos llevarán mapa real, pin rojo y fecha/hora.");
        refreshGps();
    }
    private String describe(Frame frame){
        if(frame.original==null)return "Pendiente";
        String desc = formatDate(frame.capturedAt)+" "+formatTime(frame.capturedAt);
        return desc+(frame.mapReady?" • Mapa integrado":frame.failed?" • Falta mapa":" • Preparando mapa...");
    }
    private void toast(String text){Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
    @Override protected void onResume(){super.onResume();startLocation();}
    @Override protected void onDestroy(){
        if(locationManager!=null&&locationActive)try{locationManager.removeUpdates(gpsListener);}catch(Exception ignored){}
        worker.shutdown();
        super.onDestroy();
    }
}