package com.rtmp.drone.database;

import androidx.room.*;
import com.rtmp.drone.model.StreamChannel;
import java.util.List;

@Dao
public interface ChannelDao {
    @Query("SELECT * FROM channels ORDER BY sortOrder ASC, id ASC")
    List<StreamChannel> getAllChannels();

    @Query("SELECT * FROM channels WHERE id = :id LIMIT 1")
    StreamChannel getChannelById(int id);

    @Query("SELECT * FROM channels WHERE isPrimary = 1 LIMIT 1")
    StreamChannel getPrimaryChannel();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insertChannel(StreamChannel channel);

    @Update
    void updateChannel(StreamChannel channel);

    @Delete
    void deleteChannel(StreamChannel channel);

    @Query("UPDATE channels SET isPrimary = 0")
    void clearAllPrimary();
}
