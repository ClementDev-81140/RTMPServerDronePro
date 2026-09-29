package com.rtmp.drone.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.rtmp.drone.R;
import com.rtmp.drone.service.StreamService;

public class FullscreenPreviewActivity extends AppCompatActivity {
    private SurfaceView surfaceView;
    private StreamService streamService;
    private boolean serviceBound = false;

    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            StreamService.LocalBinder binder = (StreamService.LocalBinder) service;
            streamService = binder.getService();
            serviceBound = true;
            if (surfaceView.getHolder().getSurface().isValid()) {
                streamService.setPreviewSurface(surfaceView.getHolder().getSurface());
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Immersive mode: the drone feed uses the whole screen.
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (controller != null) {
            controller.hide(WindowInsetsCompat.Type.systemBars());
            controller.setSystemBarsBehavior(
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }

        setContentView(R.layout.activity_fullscreen_preview);

        surfaceView = findViewById(R.id.fullscreenSurfaceView);
        View root = findViewById(R.id.fullscreenRoot);

        root.setOnClickListener(v -> finish()); // Tap anywhere to leave the fullscreen preview

        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                if (serviceBound && streamService != null) {
                    streamService.setPreviewSurface(holder.getSurface());
                }
            }
            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}
            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                // Detach the surface so the decoder stops instead of failing on a dead
                // surface; the main screen re-attaches its own surface when it comes back.
                if (serviceBound && streamService != null) {
                    streamService.setPreviewSurface(null);
                }
            }
        });

        Intent intent = new Intent(this, StreamService.class);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (serviceBound) {
            unbindService(serviceConnection);
        }
    }
}
