package com.linkedin.davinci.blob;

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.linkedin.venice.store.rocksdb.RocksDBUtils;
import com.linkedin.venice.utils.Utils;
import com.linkedin.venice.utils.concurrent.VeniceConcurrentHashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.rocksdb.Checkpoint;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.testng.Assert;
import org.testng.annotations.Test;


public class BlobSnapshotManagerTest {
  private static final long SNAPSHOT_RETENTION_TIME = 60000;
  private static final String STORE_NAME = Utils.getUniqueString("sstTest");
  private static final int PARTITION_ID = 0;
  private static final String BASE_PATH = Utils.getUniqueTempPath("sstTest");
  private static final String DB_DIR =
      BASE_PATH + "/" + STORE_NAME + "/" + RocksDBUtils.getPartitionDbName(STORE_NAME, PARTITION_ID);

  @Test
  public void testHybridSnapshot() throws RocksDBException {
    RocksDB mockRocksDB = mock(RocksDB.class);
    Checkpoint mockCheckpoint = mock(Checkpoint.class);
    BlobSnapshotManager blobSnapshotManager = spy(new BlobSnapshotManager(BASE_PATH, SNAPSHOT_RETENTION_TIME));
    doReturn(mockCheckpoint).when(blobSnapshotManager).createCheckpoint(mockRocksDB);
    doNothing().when(mockCheckpoint)
        .createCheckpoint(
            BASE_PATH + "/" + STORE_NAME + "/" + RocksDBUtils.getPartitionDbName(STORE_NAME, PARTITION_ID));

    blobSnapshotManager.maybeUpdateHybridSnapshot(mockRocksDB, STORE_NAME, PARTITION_ID);

    verify(mockCheckpoint, times(1)).createCheckpoint(DB_DIR + "/.snapshot_files");
  }

  @Test
  public void testSnapshotUpdatesWhenStale() throws RocksDBException {
    RocksDB mockRocksDB = mock(RocksDB.class);
    Checkpoint mockCheckpoint = mock(Checkpoint.class);
    BlobSnapshotManager blobSnapshotManager = spy(new BlobSnapshotManager(BASE_PATH, SNAPSHOT_RETENTION_TIME));
    doReturn(mockCheckpoint).when(blobSnapshotManager).createCheckpoint(mockRocksDB);
    doNothing().when(mockCheckpoint).createCheckpoint(DB_DIR);
    blobSnapshotManager.maybeUpdateHybridSnapshot(mockRocksDB, STORE_NAME, PARTITION_ID);
    Map<String, Map<Integer, Long>> snapShotTimestamps = new VeniceConcurrentHashMap<>();
    snapShotTimestamps.put(STORE_NAME, new VeniceConcurrentHashMap<>());
    snapShotTimestamps.get(STORE_NAME).put(PARTITION_ID, System.currentTimeMillis() - SNAPSHOT_RETENTION_TIME - 1);
    blobSnapshotManager.setSnapShotTimestamps(snapShotTimestamps);

    blobSnapshotManager.maybeUpdateHybridSnapshot(mockRocksDB, STORE_NAME, PARTITION_ID);

    verify(mockCheckpoint, times(1)).createCheckpoint(DB_DIR + "/.snapshot_files");
  }

  @Test
  public void testSameSnapshotWhenConcurrentUsers() throws RocksDBException {
    RocksDB mockRocksDB = mock(RocksDB.class);
    Checkpoint mockCheckpoint = mock(Checkpoint.class);
    BlobSnapshotManager blobSnapshotManager = spy(new BlobSnapshotManager(BASE_PATH, SNAPSHOT_RETENTION_TIME));
    doReturn(mockCheckpoint).when(blobSnapshotManager).createCheckpoint(mockRocksDB);
    doNothing().when(mockCheckpoint)
        .createCheckpoint(
            BASE_PATH + "/" + STORE_NAME + "/" + RocksDBUtils.getPartitionDbName(STORE_NAME, PARTITION_ID));
    blobSnapshotManager.maybeUpdateHybridSnapshot(mockRocksDB, STORE_NAME, PARTITION_ID);
    blobSnapshotManager.maybeUpdateHybridSnapshot(mockRocksDB, STORE_NAME, PARTITION_ID);
    blobSnapshotManager.maybeUpdateHybridSnapshot(mockRocksDB, STORE_NAME, PARTITION_ID);

    Assert.assertEquals(blobSnapshotManager.getConcurrentSnapshotUsers(mockRocksDB, STORE_NAME, PARTITION_ID), 3);
  }

  @Test
  public void testMultipleThreads() throws RocksDBException {
    final ExecutorService asyncExecutor = Executors.newFixedThreadPool(2);
    RocksDB mockRocksDB = mock(RocksDB.class);
    Random rand = new Random();
    int rand_int1 = rand.nextInt(1000);
    int rand_int2 = rand.nextInt(1000);
    Checkpoint mockCheckpoint = mock(Checkpoint.class);
    BlobSnapshotManager blobSnapshotManager = spy(new BlobSnapshotManager(BASE_PATH, SNAPSHOT_RETENTION_TIME));
    doReturn(mockCheckpoint).when(blobSnapshotManager).createCheckpoint(mockRocksDB);
    doNothing().when(mockCheckpoint)
        .createCheckpoint(
            BASE_PATH + "/" + STORE_NAME + "/" + RocksDBUtils.getPartitionDbName(STORE_NAME, PARTITION_ID));
    asyncExecutor.submit(() -> {
      blobSnapshotManager.maybeUpdateHybridSnapshot(mockRocksDB, STORE_NAME, PARTITION_ID);
      Utils.sleep(rand_int1);
      blobSnapshotManager.decreaseConcurrentUserCount(STORE_NAME, PARTITION_ID);
    });
    asyncExecutor.submit(() -> {
      blobSnapshotManager.maybeUpdateHybridSnapshot(mockRocksDB, STORE_NAME, PARTITION_ID);
      Utils.sleep(rand_int2);
      blobSnapshotManager.decreaseConcurrentUserCount(STORE_NAME, PARTITION_ID);
    });
    Assert.assertEquals(blobSnapshotManager.getConcurrentSnapshotUsers(mockRocksDB, STORE_NAME, PARTITION_ID), 0);

  }
}
