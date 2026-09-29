package com.rtmp.drone.ui;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.rtmp.drone.R;
import com.rtmp.drone.database.AppDatabase;
import com.rtmp.drone.model.StreamChannel;
import com.rtmp.drone.utils.NetworkUtils;

public class ChannelConfigActivity extends AppCompatActivity {
    public static final String EXTRA_CHANNEL_ID = "extra_channel_id";
    public static final int RESULT_SAVED = 101;

    private EditText editName, editUrl, editKey;
    private RadioGroup radioGroupQuality;
    private RadioButton radioLow, radioMedium, radioHigh;
    private SwitchMaterial switchIsPrimary;
    private Button btnSave, btnCancel;

    private AppDatabase db;
    private int channelId = -1;
    private StreamChannel editingChannel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_channel_config);

        db = AppDatabase.getInstance(this);
        initViews();

        if (getIntent().hasExtra(EXTRA_CHANNEL_ID)) {
            channelId = getIntent().getIntExtra(EXTRA_CHANNEL_ID, -1);
            if (channelId != -1) {
                editingChannel = db.channelDao().getChannelById(channelId);
                if (editingChannel != null) {
                    editName.setText(editingChannel.name);
                    editUrl.setText(editingChannel.getUrl());
                    editKey.setText(editingChannel.streamKey);
                    if (switchIsPrimary != null) {
                        switchIsPrimary.setChecked(editingChannel.isPrimary);
                    }
                    switch (editingChannel.quality) {
                        case 0: if (radioLow != null) radioLow.setChecked(true); break;
                        case 2: if (radioHigh != null) radioHigh.setChecked(true); break;
                        case 1:
                        default: if (radioMedium != null) radioMedium.setChecked(true); break;
                    }
                }
            }
        }
    }

    private void initViews() {
        editName = findViewById(R.id.editTextChannelName);
        editUrl = findViewById(R.id.editTextRtmpUrl);
        editKey = findViewById(R.id.editTextStreamKey);
        radioGroupQuality = findViewById(R.id.radioGroupQuality);
        radioLow = findViewById(R.id.radioLow);
        radioMedium = findViewById(R.id.radioMedium);
        radioHigh = findViewById(R.id.radioHigh);
        switchIsPrimary = findViewById(R.id.switchIsPrimary);
        btnSave = findViewById(R.id.buttonSaveChannel);
        btnCancel = findViewById(R.id.buttonCancelChannel);

        if (switchIsPrimary != null) {
            int[][] states = new int[][] {
                new int[] { android.R.attr.state_checked },
                new int[] { -android.R.attr.state_checked }
            };
            int[] thumbColors = new int[] { Color.parseColor("#FFD700"), Color.parseColor("#FFFFFF") };
            int[] trackColors = new int[] { Color.parseColor("#66FFD700"), Color.parseColor("#3D3D48") };
            switchIsPrimary.setThumbTintList(new ColorStateList(states, thumbColors));
            switchIsPrimary.setTrackTintList(new ColorStateList(states, trackColors));
        }

        if (btnSave != null) btnSave.setOnClickListener(v -> saveChannel());
        if (btnCancel != null) btnCancel.setOnClickListener(v -> finish());
    }

    private void saveChannel() {
        String name = editName != null ? editName.getText().toString().trim() : "";
        String url = editUrl != null ? editUrl.getText().toString().trim() : "";
        String key = editKey != null ? editKey.getText().toString().trim() : "";

        if (name.isEmpty()) {
            Toast.makeText(this, R.string.error_name_required, Toast.LENGTH_SHORT).show();
            return;
        }
        if (url.isEmpty()) {
            Toast.makeText(this, R.string.error_url_required, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!NetworkUtils.isValidUrl(url)) {
            Toast.makeText(this, R.string.error_url_invalid, Toast.LENGTH_LONG).show();
            return;
        }

        int quality = 1;
        if (radioLow != null && radioLow.isChecked()) quality = 0;
        else if (radioHigh != null && radioHigh.isChecked()) quality = 2;

        boolean isPrimary = switchIsPrimary != null && switchIsPrimary.isChecked();

        if (editingChannel == null) {
            editingChannel = new StreamChannel();
            editingChannel.isActive = false;
        }

        editingChannel.name = name;
        editingChannel.rtmpUrl = url;
        editingChannel.url = url;
        editingChannel.streamKey = key;
        editingChannel.quality = quality;
        editingChannel.targetBitrate = editingChannel.getBitrateForQuality();

        if (isPrimary) {
            db.channelDao().clearAllPrimary();
        }
        editingChannel.isPrimary = isPrimary;

        if (editingChannel.id > 0) {
            db.channelDao().updateChannel(editingChannel);
        } else {
            db.channelDao().insertChannel(editingChannel);
        }

        Toast.makeText(this, R.string.channel_saved, Toast.LENGTH_SHORT).show();
        setResult(RESULT_SAVED);
        finish();
    }
}
