package com.rtmp.drone.database;

import android.database.Cursor;
import androidx.annotation.NonNull;
import androidx.room.EntityDeletionOrUpdateAdapter;
import androidx.room.EntityInsertionAdapter;
import androidx.room.RoomDatabase;
import androidx.room.RoomSQLiteQuery;
import androidx.room.SharedSQLiteStatement;
import androidx.room.util.CursorUtil;
import androidx.room.util.DBUtil;
import androidx.sqlite.db.SupportSQLiteStatement;
import com.rtmp.drone.model.StreamChannel;
import java.lang.Class;
import java.lang.Override;
import java.lang.String;
import java.lang.SuppressWarnings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.processing.Generated;

@Generated("androidx.room.RoomProcessor")
@SuppressWarnings({"unchecked", "deprecation"})
public final class ChannelDao_Impl implements ChannelDao {
  private final RoomDatabase __db;

  private final EntityInsertionAdapter<StreamChannel> __insertionAdapterOfStreamChannel;

  private final EntityDeletionOrUpdateAdapter<StreamChannel> __deletionAdapterOfStreamChannel;

  private final EntityDeletionOrUpdateAdapter<StreamChannel> __updateAdapterOfStreamChannel;

  private final SharedSQLiteStatement __preparedStmtOfClearAllPrimary;

  public ChannelDao_Impl(@NonNull final RoomDatabase __db) {
    this.__db = __db;
    this.__insertionAdapterOfStreamChannel = new EntityInsertionAdapter<StreamChannel>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT OR REPLACE INTO `channels` (`id`,`name`,`rtmpUrl`,`url`,`streamKey`,`quality`,`targetBitrate`,`isActive`,`isPrimary`,`sortOrder`) VALUES (nullif(?, 0),?,?,?,?,?,?,?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          final StreamChannel entity) {
        statement.bindLong(1, entity.id);
        if (entity.name == null) {
          statement.bindNull(2);
        } else {
          statement.bindString(2, entity.name);
        }
        if (entity.rtmpUrl == null) {
          statement.bindNull(3);
        } else {
          statement.bindString(3, entity.rtmpUrl);
        }
        if (entity.url == null) {
          statement.bindNull(4);
        } else {
          statement.bindString(4, entity.url);
        }
        if (entity.streamKey == null) {
          statement.bindNull(5);
        } else {
          statement.bindString(5, entity.streamKey);
        }
        statement.bindLong(6, entity.quality);
        statement.bindLong(7, entity.targetBitrate);
        final int _tmp = entity.isActive ? 1 : 0;
        statement.bindLong(8, _tmp);
        final int _tmp_1 = entity.isPrimary ? 1 : 0;
        statement.bindLong(9, _tmp_1);
        statement.bindLong(10, entity.sortOrder);
      }
    };
    this.__deletionAdapterOfStreamChannel = new EntityDeletionOrUpdateAdapter<StreamChannel>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "DELETE FROM `channels` WHERE `id` = ?";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          final StreamChannel entity) {
        statement.bindLong(1, entity.id);
      }
    };
    this.__updateAdapterOfStreamChannel = new EntityDeletionOrUpdateAdapter<StreamChannel>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "UPDATE OR ABORT `channels` SET `id` = ?,`name` = ?,`rtmpUrl` = ?,`url` = ?,`streamKey` = ?,`quality` = ?,`targetBitrate` = ?,`isActive` = ?,`isPrimary` = ?,`sortOrder` = ? WHERE `id` = ?";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          final StreamChannel entity) {
        statement.bindLong(1, entity.id);
        if (entity.name == null) {
          statement.bindNull(2);
        } else {
          statement.bindString(2, entity.name);
        }
        if (entity.rtmpUrl == null) {
          statement.bindNull(3);
        } else {
          statement.bindString(3, entity.rtmpUrl);
        }
        if (entity.url == null) {
          statement.bindNull(4);
        } else {
          statement.bindString(4, entity.url);
        }
        if (entity.streamKey == null) {
          statement.bindNull(5);
        } else {
          statement.bindString(5, entity.streamKey);
        }
        statement.bindLong(6, entity.quality);
        statement.bindLong(7, entity.targetBitrate);
        final int _tmp = entity.isActive ? 1 : 0;
        statement.bindLong(8, _tmp);
        final int _tmp_1 = entity.isPrimary ? 1 : 0;
        statement.bindLong(9, _tmp_1);
        statement.bindLong(10, entity.sortOrder);
        statement.bindLong(11, entity.id);
      }
    };
    this.__preparedStmtOfClearAllPrimary = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "UPDATE channels SET isPrimary = 0";
        return _query;
      }
    };
  }

  @Override
  public long insertChannel(final StreamChannel channel) {
    __db.assertNotSuspendingTransaction();
    __db.beginTransaction();
    try {
      final long _result = __insertionAdapterOfStreamChannel.insertAndReturnId(channel);
      __db.setTransactionSuccessful();
      return _result;
    } finally {
      __db.endTransaction();
    }
  }

  @Override
  public void deleteChannel(final StreamChannel channel) {
    __db.assertNotSuspendingTransaction();
    __db.beginTransaction();
    try {
      __deletionAdapterOfStreamChannel.handle(channel);
      __db.setTransactionSuccessful();
    } finally {
      __db.endTransaction();
    }
  }

  @Override
  public void updateChannel(final StreamChannel channel) {
    __db.assertNotSuspendingTransaction();
    __db.beginTransaction();
    try {
      __updateAdapterOfStreamChannel.handle(channel);
      __db.setTransactionSuccessful();
    } finally {
      __db.endTransaction();
    }
  }

  @Override
  public void clearAllPrimary() {
    __db.assertNotSuspendingTransaction();
    final SupportSQLiteStatement _stmt = __preparedStmtOfClearAllPrimary.acquire();
    try {
      __db.beginTransaction();
      try {
        _stmt.executeUpdateDelete();
        __db.setTransactionSuccessful();
      } finally {
        __db.endTransaction();
      }
    } finally {
      __preparedStmtOfClearAllPrimary.release(_stmt);
    }
  }

  @Override
  public List<StreamChannel> getAllChannels() {
    final String _sql = "SELECT * FROM channels ORDER BY sortOrder ASC, id ASC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    __db.assertNotSuspendingTransaction();
    final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
    try {
      final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
      final int _cursorIndexOfName = CursorUtil.getColumnIndexOrThrow(_cursor, "name");
      final int _cursorIndexOfRtmpUrl = CursorUtil.getColumnIndexOrThrow(_cursor, "rtmpUrl");
      final int _cursorIndexOfUrl = CursorUtil.getColumnIndexOrThrow(_cursor, "url");
      final int _cursorIndexOfStreamKey = CursorUtil.getColumnIndexOrThrow(_cursor, "streamKey");
      final int _cursorIndexOfQuality = CursorUtil.getColumnIndexOrThrow(_cursor, "quality");
      final int _cursorIndexOfTargetBitrate = CursorUtil.getColumnIndexOrThrow(_cursor, "targetBitrate");
      final int _cursorIndexOfIsActive = CursorUtil.getColumnIndexOrThrow(_cursor, "isActive");
      final int _cursorIndexOfIsPrimary = CursorUtil.getColumnIndexOrThrow(_cursor, "isPrimary");
      final int _cursorIndexOfSortOrder = CursorUtil.getColumnIndexOrThrow(_cursor, "sortOrder");
      final List<StreamChannel> _result = new ArrayList<StreamChannel>(_cursor.getCount());
      while (_cursor.moveToNext()) {
        final StreamChannel _item;
        _item = new StreamChannel();
        _item.id = _cursor.getInt(_cursorIndexOfId);
        if (_cursor.isNull(_cursorIndexOfName)) {
          _item.name = null;
        } else {
          _item.name = _cursor.getString(_cursorIndexOfName);
        }
        if (_cursor.isNull(_cursorIndexOfRtmpUrl)) {
          _item.rtmpUrl = null;
        } else {
          _item.rtmpUrl = _cursor.getString(_cursorIndexOfRtmpUrl);
        }
        if (_cursor.isNull(_cursorIndexOfUrl)) {
          _item.url = null;
        } else {
          _item.url = _cursor.getString(_cursorIndexOfUrl);
        }
        if (_cursor.isNull(_cursorIndexOfStreamKey)) {
          _item.streamKey = null;
        } else {
          _item.streamKey = _cursor.getString(_cursorIndexOfStreamKey);
        }
        _item.quality = _cursor.getInt(_cursorIndexOfQuality);
        _item.targetBitrate = _cursor.getInt(_cursorIndexOfTargetBitrate);
        final int _tmp;
        _tmp = _cursor.getInt(_cursorIndexOfIsActive);
        _item.isActive = _tmp != 0;
        final int _tmp_1;
        _tmp_1 = _cursor.getInt(_cursorIndexOfIsPrimary);
        _item.isPrimary = _tmp_1 != 0;
        _item.sortOrder = _cursor.getInt(_cursorIndexOfSortOrder);
        _result.add(_item);
      }
      return _result;
    } finally {
      _cursor.close();
      _statement.release();
    }
  }

  @Override
  public StreamChannel getChannelById(final int id) {
    final String _sql = "SELECT * FROM channels WHERE id = ? LIMIT 1";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindLong(_argIndex, id);
    __db.assertNotSuspendingTransaction();
    final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
    try {
      final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
      final int _cursorIndexOfName = CursorUtil.getColumnIndexOrThrow(_cursor, "name");
      final int _cursorIndexOfRtmpUrl = CursorUtil.getColumnIndexOrThrow(_cursor, "rtmpUrl");
      final int _cursorIndexOfUrl = CursorUtil.getColumnIndexOrThrow(_cursor, "url");
      final int _cursorIndexOfStreamKey = CursorUtil.getColumnIndexOrThrow(_cursor, "streamKey");
      final int _cursorIndexOfQuality = CursorUtil.getColumnIndexOrThrow(_cursor, "quality");
      final int _cursorIndexOfTargetBitrate = CursorUtil.getColumnIndexOrThrow(_cursor, "targetBitrate");
      final int _cursorIndexOfIsActive = CursorUtil.getColumnIndexOrThrow(_cursor, "isActive");
      final int _cursorIndexOfIsPrimary = CursorUtil.getColumnIndexOrThrow(_cursor, "isPrimary");
      final int _cursorIndexOfSortOrder = CursorUtil.getColumnIndexOrThrow(_cursor, "sortOrder");
      final StreamChannel _result;
      if (_cursor.moveToFirst()) {
        _result = new StreamChannel();
        _result.id = _cursor.getInt(_cursorIndexOfId);
        if (_cursor.isNull(_cursorIndexOfName)) {
          _result.name = null;
        } else {
          _result.name = _cursor.getString(_cursorIndexOfName);
        }
        if (_cursor.isNull(_cursorIndexOfRtmpUrl)) {
          _result.rtmpUrl = null;
        } else {
          _result.rtmpUrl = _cursor.getString(_cursorIndexOfRtmpUrl);
        }
        if (_cursor.isNull(_cursorIndexOfUrl)) {
          _result.url = null;
        } else {
          _result.url = _cursor.getString(_cursorIndexOfUrl);
        }
        if (_cursor.isNull(_cursorIndexOfStreamKey)) {
          _result.streamKey = null;
        } else {
          _result.streamKey = _cursor.getString(_cursorIndexOfStreamKey);
        }
        _result.quality = _cursor.getInt(_cursorIndexOfQuality);
        _result.targetBitrate = _cursor.getInt(_cursorIndexOfTargetBitrate);
        final int _tmp;
        _tmp = _cursor.getInt(_cursorIndexOfIsActive);
        _result.isActive = _tmp != 0;
        final int _tmp_1;
        _tmp_1 = _cursor.getInt(_cursorIndexOfIsPrimary);
        _result.isPrimary = _tmp_1 != 0;
        _result.sortOrder = _cursor.getInt(_cursorIndexOfSortOrder);
      } else {
        _result = null;
      }
      return _result;
    } finally {
      _cursor.close();
      _statement.release();
    }
  }

  @Override
  public StreamChannel getPrimaryChannel() {
    final String _sql = "SELECT * FROM channels WHERE isPrimary = 1 LIMIT 1";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    __db.assertNotSuspendingTransaction();
    final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
    try {
      final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
      final int _cursorIndexOfName = CursorUtil.getColumnIndexOrThrow(_cursor, "name");
      final int _cursorIndexOfRtmpUrl = CursorUtil.getColumnIndexOrThrow(_cursor, "rtmpUrl");
      final int _cursorIndexOfUrl = CursorUtil.getColumnIndexOrThrow(_cursor, "url");
      final int _cursorIndexOfStreamKey = CursorUtil.getColumnIndexOrThrow(_cursor, "streamKey");
      final int _cursorIndexOfQuality = CursorUtil.getColumnIndexOrThrow(_cursor, "quality");
      final int _cursorIndexOfTargetBitrate = CursorUtil.getColumnIndexOrThrow(_cursor, "targetBitrate");
      final int _cursorIndexOfIsActive = CursorUtil.getColumnIndexOrThrow(_cursor, "isActive");
      final int _cursorIndexOfIsPrimary = CursorUtil.getColumnIndexOrThrow(_cursor, "isPrimary");
      final int _cursorIndexOfSortOrder = CursorUtil.getColumnIndexOrThrow(_cursor, "sortOrder");
      final StreamChannel _result;
      if (_cursor.moveToFirst()) {
        _result = new StreamChannel();
        _result.id = _cursor.getInt(_cursorIndexOfId);
        if (_cursor.isNull(_cursorIndexOfName)) {
          _result.name = null;
        } else {
          _result.name = _cursor.getString(_cursorIndexOfName);
        }
        if (_cursor.isNull(_cursorIndexOfRtmpUrl)) {
          _result.rtmpUrl = null;
        } else {
          _result.rtmpUrl = _cursor.getString(_cursorIndexOfRtmpUrl);
        }
        if (_cursor.isNull(_cursorIndexOfUrl)) {
          _result.url = null;
        } else {
          _result.url = _cursor.getString(_cursorIndexOfUrl);
        }
        if (_cursor.isNull(_cursorIndexOfStreamKey)) {
          _result.streamKey = null;
        } else {
          _result.streamKey = _cursor.getString(_cursorIndexOfStreamKey);
        }
        _result.quality = _cursor.getInt(_cursorIndexOfQuality);
        _result.targetBitrate = _cursor.getInt(_cursorIndexOfTargetBitrate);
        final int _tmp;
        _tmp = _cursor.getInt(_cursorIndexOfIsActive);
        _result.isActive = _tmp != 0;
        final int _tmp_1;
        _tmp_1 = _cursor.getInt(_cursorIndexOfIsPrimary);
        _result.isPrimary = _tmp_1 != 0;
        _result.sortOrder = _cursor.getInt(_cursorIndexOfSortOrder);
      } else {
        _result = null;
      }
      return _result;
    } finally {
      _cursor.close();
      _statement.release();
    }
  }

  @NonNull
  public static List<Class<?>> getRequiredConverters() {
    return Collections.emptyList();
  }
}
