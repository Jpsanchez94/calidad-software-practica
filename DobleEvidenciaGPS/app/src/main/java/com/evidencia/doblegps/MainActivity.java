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
import android.location.Address;
import android.location.Geocoder;
import android.view.TextureView;
import android.text.TextUtils;
import java.util.List;
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
import android.widget.FrameLayout;
import android.view.View;
import android.graphics.drawable.GradientDrawable;
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
    private static final int PANE_W = 1080, PANE_H = 1215;
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

    private TextView gpsLabel, stepLabel, statusLabel, cameraStatus;
    private TextureView livePreview;
    private LiveCamera liveCamera;
    private Button actionButton, newButton;
    private FrameLayout shutterButton;
    private boolean secondStepStarted;
    private long pendingPhotoTime;
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
        String city, sector, valley, province, street;
        String error;
    }

    @Override public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        sessionToken = System.currentTimeMillis();
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        makeUi();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.CAMERA
            }, LOCATION_REQUEST);
        } else { startLocation(); startCameraSafely(); }
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
        LinearLayout main=new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        main.setBackgroundColor(BG);
        main.setPadding(dp(12),dp(15),dp(12),dp(16));
        main.setOnApplyWindowInsetsListener((view,insets)->{
            if(android.os.Build.VERSION.SDK_INT>=30) {
                android.graphics.Insets b=insets.getInsets(android.view.WindowInsets.Type.systemBars());
                view.setPadding(dp(12),b.top+dp(10),dp(12),b.bottom+dp(14));
            }
            return insets;
        });
        TextView heading=text("DOBLE EVIDENCIA GPS",21,Color.WHITE);
        heading.setGravity(Gravity.CENTER);
        heading.setTypeface(null,1);
        main.addView(heading);
        gpsLabel=text("Buscando ubicación para el mapa...",13,Color.rgb(143,212,240));
        gpsLabel.setGravity(Gravity.CENTER);
        main.addView(gpsLabel);
        cameraStatus=text("Cámara en vivo...",12,Color.rgb(180,205,223));
        cameraStatus.setGravity(Gravity.CENTER);
        main.addView(cameraStatus);
        FrameLayout cameraStage=new FrameLayout(this);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,0,1f);
        lp.setMargins(0,dp(10),0,dp(10));
        main.addView(cameraStage,lp);
        livePreview=new TextureView(this);
        livePreview.setOpaque(true);
        cameraStage.addView(livePreview,new FrameLayout.LayoutParams(-1,-1));
        liveCamera=new LiveCamera(this,livePreview,message->cameraStatus.setText(message));
        // Obturador circular real, superpuesto al visor sin abrir otra cámara.
        shutterButton=new FrameLayout(this);
        GradientDrawable outer=new GradientDrawable();
        outer.setShape(GradientDrawable.OVAL);
        outer.setColor(0x66000000);
        outer.setStroke(dp(4),Color.WHITE);
        shutterButton.setBackground(outer);
        shutterButton.setContentDescription("Tomar fotografía");
        shutterButton.setClickable(true);
        shutterButton.setFocusable(true);
        View circle=new View(this);
        GradientDrawable inner=new GradientDrawable();
        inner.setShape(GradientDrawable.OVAL);
        inner.setColor(Color.WHITE);
        circle.setBackground(inner);
        FrameLayout.LayoutParams center=new FrameLayout.LayoutParams(dp(59),dp(59),Gravity.CENTER);
        shutterButton.addView(circle,center);
        FrameLayout.LayoutParams shutterLayout=new FrameLayout.LayoutParams(
            dp(82),dp(82),Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);
        shutterLayout.bottomMargin=dp(20);
        cameraStage.addView(shutterButton,shutterLayout);
        shutterButton.setOnClickListener(v -> {
            if(frames[0].original==null)capture(0);
            else if(secondStepStarted && frames[1].original==null)capture(1);
        });
        stepLabel=text("PASO 1 DE 3 · Primera fotografía",16,Color.WHITE);
        stepLabel.setTypeface(null,1);
        stepLabel.setGravity(Gravity.CENTER);
        main.addView(stepLabel);
        actionButton=button(main,"SIGUIENTE",this::advance);
        actionButton.setTextSize(18);
        statusLabel=text("Cada foto incluirá mapa, fecha, hora, ciudad y sector.",12,Color.rgb(190,205,224));
        statusLabel.setGravity(Gravity.CENTER);
        main.addView(statusLabel);
        newButton=button(main,"NUEVA EVIDENCIA",this::newSession);
        newButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(55,70,86)));
        main.addView(text("Mapas © OpenStreetMap contributors",11,Color.rgb(154,174,192)));
        setContentView(main);
    }
    private void advance(){
        if(frames[0].original==null){capture(0);return;}
        if(!secondStepStarted){secondStepStarted=true;updateUi();return;}
        if(frames[1].original==null){capture(1);return;}
        if(compositeUri!=null){viewComposite();return;}
        if(frames[0].failed||frames[1].failed){retryMaps();return;}
        if(frames[0].mapReady&&frames[1].mapReady)saveComposite();
    }
    private void startCameraSafely() {
        if(liveCamera==null || checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)return;
        try {
            liveCamera.start();
        } catch (RuntimeException error) {
            if(cameraStatus!=null)cameraStatus.setText("La cámara no pudo iniciar: "+error.getClass().getSimpleName());
        }
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
        if (request == LOCATION_REQUEST) {
            startLocation();
            if (checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) startCameraSafely();
            else cameraStatus.setText("Permiso de cámara denegado.");
        }
        refreshGps();
    }
    private Location currentLocation() {
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED
            ||locationManager==null)return null;
        Location chosen=null;
        float bestScore=Float.MAX_VALUE;
        java.util.ArrayList<Location> candidates=new java.util.ArrayList<>();
        if(bestLocation!=null)candidates.add(bestLocation);
        for(String provider:new String[]{LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER}){
            try {
                Location loc=locationManager.getLastKnownLocation(provider);
                if(loc!=null)candidates.add(loc);
            }catch(Exception ignored){}
        }
        long now=SystemClock.elapsedRealtime();
        for(Location loc:candidates){
            long age=now-loc.getElapsedRealtimeNanos()/1000000L;
            if(age<0||age>45000)continue;
            float accuracy=loc.hasAccuracy()?loc.getAccuracy():150f;
            // Se evita sustituir una lectura precisa por otra reciente pero inexacta.
            float score=accuracy*2f+age/2500f;
            if(score<bestScore){chosen=loc;bestScore=score;}
        }
        return chosen==null?null:new Location(chosen);
    }
    private void refreshGps() {
        if(gpsLabel==null)return;
        Location position=currentLocation();
        if(position==null)gpsLabel.setText("Buscando ubicación precisa...");
        else if(!position.hasAccuracy()||position.getAccuracy()>35f){
            gpsLabel.setText("Mejorando la precisión de la ubicación...");
        }else gpsLabel.setText("Ubicación precisa lista");
    }

    private void capture(int index) {
        if (pendingSlot >= 0) return;
        if (index == 1 && frames[0].original == null) {
            toast("Primero toma la primera fotografía."); return;
        }
        if (frames[index].original != null) {
            toast("Inicia una nueva evidencia para volver a tomarla."); return;
        }
        Location fix=currentLocation();
        if(fix==null||!fix.hasAccuracy()||fix.getAccuracy()>35f){
            toast("Espera una ubicación precisa para identificar bien la calle y el sector.");return;
        }
        Uri uri = createImage("Original_" + sessionToken + "_" + (index+1) + ".jpg");
        if (uri == null) { toast("No se pudo crear el archivo de la cámara."); return; }
        pendingOriginal=uri;
        launchLocation=fix;
        pendingSlot=index;
        pendingPhotoTime=System.currentTimeMillis();
        if(liveCamera==null || !liveCamera.isReady()){
            getContentResolver().delete(uri,null,null);
            pendingSlot=-1;pendingOriginal=null;
            toast("La cámara en vivo todavía no está lista.");return;
        }
        liveCamera.snap(uri,(ok,why)->{
            if(!ok)toast("Error en cámara: "+why);
            onActivityResult(CAMERA_REQUEST,ok?RESULT_OK:RESULT_CANCELED,null);
        });
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
        f.capturedAt=pendingPhotoTime;
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
            // Sin vista previa de fotografías tomadas: permanece activa la cámara en vivo.
            if (f.location == null) throw new Exception("Ubicación GPS no disponible; activa GPS para nueva captura.");
            // El mapa y la geocodificación se calculan fuera de la pantalla de cámara.
            enrichAddress(f);
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
                // El usuario confirma el guardado con el botón principal.
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

    private static String part(String in){
        if(in==null)return "";
        return in.trim();
    }
    private static String firstNonEmpty(String... values) {
        for(String s:values)if(!part(s).isEmpty())return part(s);
        return "";
    }
    private String fitLabel(String label,Paint paint,float maxWidth){
        if(label==null)return "";
        if(paint.measureText(label)<=maxWidth)return label;
        String shortText=label;
        while(shortText.length()>2 && paint.measureText(shortText+"…")>maxWidth)shortText=shortText.substring(0,shortText.length()-1);
        return shortText+"…";
    }
    /** Selecciona la dirección con mayor detalle disponible; nunca completa números inexistentes. */
    private void enrichAddress(Frame f){
        f.city="";f.sector="";f.valley="";f.province="";
        f.street="";
        if(f.location==null)return;
        try{
            if(!Geocoder.isPresent())return;
            Geocoder coder=new Geocoder(this,new Locale("es","EC"));
            List<Address> all=coder.getFromLocation(
                f.location.getLatitude(),f.location.getLongitude(),3);
            if(all==null||all.isEmpty())return;
            Address best=null;
            int score=-1;
            for(Address address:all){
                int points=0;
                if(!part(address.getThoroughfare()).isEmpty())points+=8;
                if(!part(address.getSubThoroughfare()).isEmpty())points+=5;
                if(!part(address.getSubLocality()).isEmpty())points+=4;
                if(!part(address.getFeatureName()).isEmpty())points+=1;
                if(!part(address.getLocality()).isEmpty())points+=2;
                if(address.getMaxAddressLineIndex()>=0 && !part(address.getAddressLine(0)).isEmpty())points+=3;
                if(points>score){score=points;best=address;}
            }
            if(best==null)return;
            String city=firstNonEmpty(best.getLocality(),best.getSubAdminArea());
            String province=part(best.getAdminArea());
            f.city=city;
            f.province=province;
            if(!province.isEmpty()&&!city.equalsIgnoreCase(province)){
                f.city=city.isEmpty()?province:city+" • "+province;
            }
            String street=part(best.getThoroughfare());
            String number=part(best.getSubThoroughfare());
            String reference=part(best.getFeatureName());
            String complete=best.getMaxAddressLineIndex()>=0?part(best.getAddressLine(0)):"";
            String neighborhood=part(best.getSubLocality());
            if(!street.isEmpty()){
                f.street=street+(number.isEmpty()?"":(" "+number));
            } else if(!complete.isEmpty()){
                // La línea completa puede aportar una calle cuando falta thoroughfare.
                f.street=complete;
            } else if(!reference.isEmpty()&&!reference.equalsIgnoreCase(city)){
                f.street=reference;
            }
            if(!neighborhood.isEmpty()&&!neighborhood.equalsIgnoreCase(city)){
                f.sector="Barrio/sector: "+neighborhood;
            } else if(!reference.isEmpty()&&!reference.equalsIgnoreCase(street)
                    && !reference.equalsIgnoreCase(city)){
                f.sector="Referencia: "+reference;
            } else {
                String parish=part(best.getSubAdminArea());
                if(!parish.equalsIgnoreCase(city)&&!parish.equalsIgnoreCase(province)){
                    f.sector="Parroquia/sector: "+parish;
                }
            }
            for(String part:new String[]{neighborhood,part(best.getSubAdminArea()),complete}){
                if(part!=null&&part.toLowerCase(Locale.ROOT).contains("valle")){
                    f.valley=part;break;
                }
            }
            if(f.street.equalsIgnoreCase(f.city)||f.street.equalsIgnoreCase(province)){
                f.street="";
            }
        }catch(Exception ignored){
            // Sin datos suficientes: no inventar una dirección exacta.
        }
    }

    private Bitmap stamp(Bitmap source,Bitmap map,Frame f){
        Bitmap out=Bitmap.createBitmap(PANE_W,PANE_H,Bitmap.Config.ARGB_8888);
        Canvas c=new Canvas(out);
        Paint p=new Paint(3);
        // Relleno proporcional sin bordes negros. El recorte mantiene el centro de la foto.
        float ratio=(float)source.getWidth()/source.getHeight();
        float wanted=(float)PANE_W/PANE_H;
        Rect sourceCrop;
        if(ratio>wanted){
            int cropWidth=Math.round(source.getHeight()*wanted);
            int x=(source.getWidth()-cropWidth)/2;
            sourceCrop=new Rect(x,0,x+cropWidth,source.getHeight());
        }else{
            int cropHeight=Math.round(source.getWidth()/wanted);
            int y=(source.getHeight()-cropHeight)/2;
            sourceCrop=new Rect(0,y,source.getWidth(),y+cropHeight);
        }
        c.drawBitmap(source,sourceCrop,new Rect(0,0,PANE_W,PANE_H),p);
        int mapX=14,mapW=345,mapH=270,mapY=PANE_H-mapH-15;
        p.setColor(Color.WHITE);
        c.drawRoundRect(new RectF(mapX-3,mapY-3,mapX+mapW+3,mapY+mapH+3),8,8,p);
        c.drawBitmap(map,null,new Rect(mapX,mapY,mapX+mapW,mapY+mapH),p);
        p.setColor(Color.argb(80,5,11,17));
        c.drawRoundRect(new RectF(374,PANE_H-420,PANE_W-10,PANE_H-17),14,14,p);
        p.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        p.setTextAlign(Paint.Align.RIGHT);
        p.setShadowLayer(3,1,2,Color.BLACK);
        p.setColor(Color.WHITE);
        float x=PANE_W-31;
        p.setTextSize(40);
        c.drawText(formatDate(f.capturedAt),x,PANE_H-367,p);
        p.setTextSize(50);
        c.drawText(formatTime(f.capturedAt),x,PANE_H-309,p);
        p.setTextSize(30);
        String street=part(f.street).isEmpty()?"Calle sin registrar":f.street;
        c.drawText(fitLabel(street,p,646),x,PANE_H-246,p);
        p.setTextSize(29);
        if(!part(f.sector).isEmpty()){
            c.drawText(fitLabel(f.sector,p,646),x,PANE_H-188,p);
        }
        p.setTextSize(33);
        c.drawText(fitLabel(part(f.city),p,646),x,PANE_H-125,p);
        if(!part(f.valley).isEmpty()){
            p.setTextSize(29);
            c.drawText(fitLabel(f.valley,p,646),x,PANE_H-68,p);
        }
        p.clearShadowLayer();
        return out;
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
        statusLabel.setText("Guardando imagen horizontal...");
        actionButton.setEnabled(false);
        worker.execute(() -> {
            try{
                Bitmap whole=Bitmap.createBitmap(PANE_W*2,PANE_H,Bitmap.Config.ARGB_8888);
                Canvas c=new Canvas(whole);
                Paint p=new Paint(3);
                c.drawColor(BG);
                c.drawBitmap(a.preview,0,0,p);
                c.drawBitmap(b.preview,PANE_W,0,p);
                p.setColor(Color.argb(190,250,250,250));
                c.drawRect(PANE_W-2,0,PANE_W+2,PANE_H,p);
                // Franja flotante centrada, realmente encima de ambas fotografías.
                float middleY=PANE_H*0.49f;
                p.setColor(Color.argb(182,9,17,26));
                c.drawRoundRect(new RectF(PANE_W-350,middleY-102,
                    PANE_W+350,middleY+110),24,24,p);
                p.setTextAlign(Paint.Align.CENTER);
                p.setColor(Color.rgb(255,65,79));
                p.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                p.setTextSize(49);
                c.drawText("TIEMPO DE ESPERA",PANE_W,middleY-17,p);
                p.setTextSize(78);
                c.drawText(elapsed(),PANE_W,middleY+75,p);
                if(current!=sessionToken)return;
                Uri target=createImage("Tiempo_de_espera_"+current+".jpg");
                if(target==null)throw new Exception("No se pudo crear el JPG");
                writeJpeg(whole,target,90);
                runOnUiThread(() -> {
                    if(current!=sessionToken)return;
                    compositeUri=target;
                    updateUi();
                    viewComposite();
                    toast("Evidencia guardada en Galería.");
                });
            }catch(Exception e){
                runOnUiThread(() -> {
                    if(current==sessionToken){
                        statusLabel.setText("Error guardando evidencia: "+e.getMessage());
                        actionButton.setEnabled(true);
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
    private void viewComposite(){
        if(compositeUri==null)return;
        Intent i=new Intent(this,EvidenceViewerActivity.class);
        i.setData(compositeUri);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try{startActivity(i);}catch(Exception ex){toast("Abre Galería > DobleEvidenciaGPS");}
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
        if(pendingSlot>=0){toast("Termina la captura actual.");return;}
        frames[0]=new Frame();
        frames[1]=new Frame();
        compositeUri=null;
        sessionToken=System.currentTimeMillis();
        secondStepStarted=false;
        updateUi();
    }
    private void updateUi(){
        boolean first=frames[0].original!=null;
        boolean second=frames[1].original!=null;
        boolean ready=frames[0].mapReady&&frames[1].mapReady;
        boolean failed=frames[0].failed||frames[1].failed;
        if(!first){
            stepLabel.setText("PASO 1 DE 3 · Toca el botón circular para fotografiar");
            actionButton.setText("SIGUIENTE");
        }else if(!secondStepStarted){
            stepLabel.setText("PRIMERA FOTO GUARDADA");
            actionButton.setText("SIGUIENTE");
        }else if(!second){
            stepLabel.setText("PASO 2 DE 3 · Usa el botón circular");
            actionButton.setText("SIGUIENTE");
        }else if(compositeUri!=null){
            stepLabel.setText("EVIDENCIA GUARDADA");
            actionButton.setText("VER RESULTADO");
        }else{
            stepLabel.setText("PASO 3 DE 3 · Guardar");
            actionButton.setText(failed?"REINTENTAR MAPAS":ready?"GUARDAR EVIDENCIA":"PREPARANDO MAPAS...");
        }
        boolean showShutter=!first||(secondStepStarted&&!second);
        shutterButton.setVisibility(showShutter?View.VISIBLE:View.GONE);
        shutterButton.setEnabled(showShutter&&pendingSlot<0);
        shutterButton.setAlpha(pendingSlot<0?1f:0.55f);
        boolean showAction=first&&(!secondStepStarted||second);
        actionButton.setVisibility(showAction?View.VISIBLE:View.GONE);
        actionButton.setEnabled(pendingSlot<0&&(!second||failed||ready||compositeUri!=null));
        newButton.setVisibility(compositeUri!=null?View.VISIBLE:View.GONE);
        if(compositeUri!=null){
            statusLabel.setText("Evidencia guardada. Pulsa VER RESULTADO para ampliarla.");
        }else if(failed){
            statusLabel.setText("Mapa pendiente: "+(frames[0].failed?frames[0].error:frames[1].error));
        }else if(second){
            statusLabel.setText("Tiempo de espera: "+elapsed()+" · Preparando los mapas.");
        }else if(first){
            statusLabel.setText("Primera foto lista. La cámara continúa en vivo.");
        }else{
            statusLabel.setText("Fecha, hora, ciudad, sector y mapa en cada fotografía.");
        }
        refreshGps();
    }
    private String describe(Frame frame){
        if(frame.original==null)return "Pendiente";
        String desc = formatDate(frame.capturedAt)+" "+formatTime(frame.capturedAt);
        return desc+(frame.mapReady?" • Mapa integrado":frame.failed?" • Falta mapa":" • Preparando mapa...");
    }
    private void toast(String text){Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
    @Override protected void onResume(){
        super.onResume();
        startLocation();
        if(liveCamera!=null && checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) startCameraSafely();
    }
    @Override protected void onPause(){
        if(liveCamera!=null)liveCamera.stop();
        super.onPause();
    }
    @Override protected void onDestroy(){
        if(locationManager!=null&&locationActive)try{locationManager.removeUpdates(gpsListener);}catch(Exception ignored){}
        if(liveCamera!=null)liveCamera.stop();
        worker.shutdown();
        super.onDestroy();
    }
}