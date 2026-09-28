package com.rtmp.drone.database;

import android.content.Context;
import androidx.room.*;
import com.rtmp.drone.model.StreamChannel;

@Database(entities = {StreamChannel.class}, version = 1, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {
    
    private static volatile AppDatabase instance;
    
    public abstract ChannelDao channelDao();
    
    public static AppDatabase getInstance(Context context) {
        if (instance == null) {
            synchronized (AppDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(
                        context.getApplicationContext(),
                        AppDatabase.class,
                        "rtmp_drone_db"
                    ).allowMainThreadQueries().build();
                }
            }
        }
        return instance;
    }
    
    public static void destroyInstance() {
        instance = null;
    }
}
