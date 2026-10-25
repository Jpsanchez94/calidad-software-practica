package com.evidencia.doblegps;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Locale;

final class LiveCamera {
    interface Result { void done(boolean success, String message); }
    interface Status { void changed(String message); }
    private final Activity activity;
    private final TextureView texture;
    private final Status status;
    private HandlerThread thread;
    private Handler handler;
    private CameraManager cameras;
    private String selectedId;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private Surface previewSurface;
    private CaptureRequest.Builder previewBuilder;
    private CameraCharacteristics characteristics;
    private Size previewSize;
    private float desiredZoom=1f;
    private boolean active=false, opening=false, shooting=false;
    private Uri output;
    private Result result;
    private int rotation=0;

    LiveCamera(Activity activity, TextureView texture, Status status) {
        this.activity=activity;
        this.texture=texture;
        this.status=status;
        cameras=(CameraManager)activity.getSystemService(Activity.CAMERA_SERVICE);
        texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener(){
            @Override public void onSurfaceTextureAvailable(SurfaceTexture surface,int w,int h){openIfReady();}
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface,int w,int h){}
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface){stop();return true;}
            @Override public void onSurfaceTextureUpdated(SurfaceTexture surface){}
        });
    }
    boolean isReady(){return session!=null&&!shooting;}
    void start(){
        if(!active){active=true;}
        if(thread==null){
            thread=new HandlerThread("EvidenciaCam");
            thread.start();
            handler=new Handler(thread.getLooper());
        }
        openIfReady();
    }
    void stop(){
        active=false;
        opening=false;
        shooting=false;
        if(session!=null){try{session.close();}catch(Exception ignored){}session=null;}
        if(camera!=null){try{camera.close();}catch(Exception ignored){}camera=null;}
        if(reader!=null){reader.close();reader=null;}
        if(previewSurface!=null){previewSurface.release();previewSurface=null;}
        if(thread!=null){thread.quitSafely();thread=null;handler=null;}
    }
    private void tell(String message){activity.runOnUiThread(()->status.changed(message));}
    private void selectWidest() throws Exception {
        float best=-1f;
        for(String id:cameras.getCameraIdList()){
            CameraCharacteristics cc=cameras.getCameraCharacteristics(id);
            Integer facing=cc.get(CameraCharacteristics.LENS_FACING);
            if(facing==null || facing!=CameraCharacteristics.LENS_FACING_BACK)continue;
            StreamConfigurationMap streams=cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if(streams==null||streams.getOutputSizes(ImageFormat.JPEG)==null||streams.getOutputSizes(SurfaceTexture.class)==null)continue;
            float[] focal=cc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            android.util.SizeF sensor=cc.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            float f=focal!=null&&focal.length>0?focal[0]:4f;
            float width=sensor!=null?sensor.getWidth():5f;
            float zoom=1f;
            if(Build.VERSION.SDK_INT>=30){
                Range<Float> range=cc.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
                if(range!=null&&range.getLower()!=null&&range.getLower()>0)zoom=range.getLower();
            }
            float score=width/Math.max(0.1f,f)/zoom;
            if(score>best){best=score;selectedId=id;characteristics=cc;desiredZoom=zoom;}
        }
        if(selectedId==null)throw new Exception("Sin cámara posterior compatible");
        StreamConfigurationMap map=characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size choice=null;
        for(Size s:map.getOutputSizes(SurfaceTexture.class)){
            if(s.getWidth()<640||s.getHeight()<480||s.getWidth()>1920||s.getHeight()>1080)continue;
            double score=Math.abs((double)s.getWidth()/s.getHeight()-16.0/9.0);
            if(choice==null || score<Math.abs((double)choice.getWidth()/choice.getHeight()-16.0/9.0)
                || (score<0.005&&s.getWidth()>choice.getWidth()))choice=s;
        }
        if(choice==null)choice=map.getOutputSizes(SurfaceTexture.class)[0];
        previewSize=choice;
        Integer s=characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        rotation=s!=null?s:90;
    }
    private void openIfReady(){
        if(!active||opening||camera!=null||thread==null||handler==null||!texture.isAvailable())return;
        if(activity.checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){
            tell("Autoriza la cámara para verla en tiempo real.");return;
        }
        try{
            selectWidest();
            opening=true;
            tell("Conectando cámara trasera gran angular...");
            cameras.openCamera(selectedId,new CameraDevice.StateCallback(){
                @Override public void onOpened(CameraDevice device){
                    opening=false;
                    if(!active||handler==null){device.close();return;}
                    camera=device;
                    startPreview();
                }
                @Override public void onDisconnected(CameraDevice device){device.close();camera=null;opening=false;tell("Cámara desconectada");}
                @Override public void onError(CameraDevice device,int error){device.close();camera=null;opening=false;tell("Cámara no disponible ("+error+")");}
            },handler);
        }catch(Exception e){opening=false;tell("No se abrió cámara: "+e.getMessage());}
    }
    private void zoomOn(CaptureRequest.Builder builder){
        if(Build.VERSION.SDK_INT>=30&&desiredZoom>0f){
            Range<Float> range=characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
            if(range!=null)builder.set(CaptureRequest.CONTROL_ZOOM_RATIO,desiredZoom);
        }
    }
    private void startPreview(){
        if(camera==null||handler==null)return;
        try{
            StreamConfigurationMap map=characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size jpeg=null;
            for(Size size:map.getOutputSizes(ImageFormat.JPEG)){
                long pixels=(long)size.getWidth()*size.getHeight();
                if(pixels>15000000)continue;
                if(jpeg==null || pixels>(long)jpeg.getWidth()*jpeg.getHeight())jpeg=size;
            }
            if(jpeg==null)jpeg=map.getOutputSizes(ImageFormat.JPEG)[0];
            reader=ImageReader.newInstance(jpeg.getWidth(),jpeg.getHeight(),ImageFormat.JPEG,2);
            reader.setOnImageAvailableListener(this::onImage,handler);
            SurfaceTexture surface=texture.getSurfaceTexture();
            if(surface==null)throw new Exception("Preview no disponible");
            surface.setDefaultBufferSize(previewSize.getWidth(),previewSize.getHeight());
            previewSurface=new Surface(surface);
            previewBuilder=camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewBuilder.addTarget(previewSurface);
            previewBuilder.set(CaptureRequest.CONTROL_MODE,CaptureRequest.CONTROL_MODE_AUTO);
            previewBuilder.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            zoomOn(previewBuilder);
            camera.createCaptureSession(Arrays.asList(previewSurface,reader.getSurface()),new CameraCaptureSession.StateCallback(){
                @Override public void onConfigured(CameraCaptureSession cs){
                    if(camera==null||!active){cs.close();return;}
                    session=cs;
                    try{
                        session.setRepeatingRequest(previewBuilder.build(),null,handler);
                        String label=desiredZoom<0.99f?
                            String.format(Locale.US,"Gran angular en vivo • %.1f×",desiredZoom):
                            "Cámara en vivo • lente trasero más amplio disponible";
                        tell(label);
                    }catch(CameraAccessException e){tell("No se inició vista previa");}
                }
                @Override public void onConfigureFailed(CameraCaptureSession cs){cs.close();tell("No se pudo configurar la cámara en vivo");}
            },handler);
        }catch(Exception e){tell("Error de cámara: "+e.getMessage());}
    }
    void snap(Uri uri,Result callback){
        if(!isReady()||camera==null||reader==null){callback.done(false,"Cámara no lista");return;}
        shooting=true;
        output=uri;
        result=callback;
        try{
            CaptureRequest.Builder shot=camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            shot.addTarget(reader.getSurface());
            shot.set(CaptureRequest.CONTROL_MODE,CaptureRequest.CONTROL_MODE_AUTO);
            shot.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            zoomOn(shot);
            int display=activity.getWindowManager().getDefaultDisplay().getRotation();
            int degrees=display==Surface.ROTATION_90?90:display==Surface.ROTATION_180?180:display==Surface.ROTATION_270?270:0;
            shot.set(CaptureRequest.JPEG_ORIENTATION,(rotation-degrees+360)%360);
            shot.set(CaptureRequest.JPEG_QUALITY,(byte)92);
            session.capture(shot.build(),new CameraCaptureSession.CaptureCallback(){
                @Override public void onCaptureFailed(CameraCaptureSession s,CaptureRequest r,
                    android.hardware.camera2.CaptureFailure f){finish(false,"Error al capturar imagen");}
            },handler);
        }catch(Exception e){finish(false,e.getMessage());}
    }
    private void onImage(ImageReader r){
        Image image=null;
        try{
            image=r.acquireNextImage();
            if(image==null)return;
            ByteBuffer buffer=image.getPlanes()[0].getBuffer();
            byte[] data=new byte[buffer.remaining()];
            buffer.get(data);
            if(output==null)throw new Exception("Destino no disponible");
            try(OutputStream stream=activity.getContentResolver().openOutputStream(output,"w")){
                if(stream==null)throw new Exception("No se pudo escribir JPG");
                stream.write(data);
            }
            finish(true,"");
        }catch(Exception e){finish(false,e.getMessage());}
        finally{if(image!=null)image.close();}
    }
    private void finish(boolean ok,String why){
        Result cb=result;result=null;output=null;shooting=false;
        if(cb!=null)activity.runOnUiThread(()->cb.done(ok,why));
    }
}