package com.evidencia.doblegps;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.graphics.drawable.GradientDrawable;

/** Vista final apaisada: la fotografía rellena toda la pantalla y permite compartirla. */
public final class EvidenceViewerActivity extends Activity {
    private Uri image;

    @Override public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        image=getIntent().getData();
        if(image==null){finish();return;}
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        FrameLayout stage=new FrameLayout(this);
        stage.setBackgroundColor(Color.BLACK);
        ImageView photo=new ImageView(this);
        photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        photo.setImageURI(image);
        stage.addView(photo,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout actions=new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        actions.setPadding(dp(12),dp(8),dp(12),dp(8));
        GradientDrawable background=new GradientDrawable();
        background.setColor(0x8A081522);
        background.setCornerRadius(dp(16));
        actions.setBackground(background);
        Button back=smallButton("VOLVER");
        back.setOnClickListener(v->finish());
        Button share=smallButton("COMPARTIR");
        share.setOnClickListener(v->share());
        actions.addView(back);
        actions.addView(share);
        FrameLayout.LayoutParams bar=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);
        bar.bottomMargin=dp(18);
        stage.addView(actions,bar);
        photo.setOnClickListener(v->actions.setVisibility(
            actions.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE));
        setContentView(stage);
    }
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private Button smallButton(String text){
        Button b=new Button(this);
        b.setText(text);
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xCC235C8A));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(126),dp(42));
        lp.setMargins(dp(4),0,dp(4),0);
        b.setLayoutParams(lp);
        return b;
    }
    private void share(){
        Intent i=new Intent(Intent.ACTION_SEND);
        i.setType("image/jpeg");
        i.putExtra(Intent.EXTRA_STREAM,image);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i,"Compartir evidencia"));
    }
}