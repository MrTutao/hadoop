/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hdfs.server.datanode.fsdataset.impl;

import org.apache.commons.io.IOUtils;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.hdfs.ExtendedBlockId;
import org.apache.hadoop.hdfs.server.datanode.DNConf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;

/**
 * Maps block to persistent memory by using mapped byte buffer.
 */
@InterfaceAudience.Private
@InterfaceStability.Unstable
public class PmemMappableBlockLoader extends MappableBlockLoader {
  private static final Logger LOG =
      LoggerFactory.getLogger(PmemMappableBlockLoader.class);
  private PmemVolumeManager pmemVolumeManager;

  @Override
  void initialize(FsDatasetCache cacheManager) throws IOException {
    LOG.info("Initializing cache loader: " + this.getClass().getName());
    DNConf dnConf = cacheManager.getDnConf();
    PmemVolumeManager.init(dnConf.getPmemVolumes());
    pmemVolumeManager = PmemVolumeManager.getInstance();
  }

  /**
   * Load the block.
   *
   * Map the block and verify its checksum.
   *
   * The block will be mapped to PmemDir/BlockPoolId-BlockId, in which PmemDir
   * is a persistent memory volume chosen by PmemVolumeManager.
   *
   * @param length         The current length of the block.
   * @param blockIn        The block input stream. Should be positioned at the
   *                       start. The caller must close this.
   * @param metaIn         The meta file input stream. Should be positioned at
   *                       the start. The caller must close this.
   * @param blockFileName  The block file name, for logging purposes.
   * @param key            The extended block ID.
   *
   * @throws IOException   If mapping block fails or checksum fails.
   *
   * @return               The Mappable block.
   */
  @Override
  MappableBlock load(long length, FileInputStream blockIn,
      FileInputStream metaIn, String blockFileName, ExtendedBlockId key)
      throws IOException {
    PmemMappedBlock mappableBlock = null;
    String cachePath = null;

    FileChannel blockChannel = null;
    RandomAccessFile cacheFile = null;
    try {
      blockChannel = blockIn.getChannel();
      if (blockChannel == null) {
        throw new IOException("Block InputStream has no FileChannel.");
      }
      cachePath = pmemVolumeManager.getCachePath(key);
      cacheFile = new RandomAccessFile(cachePath, "rw");
      blockChannel.transferTo(0, length, cacheFile.getChannel());

      // Verify checksum for the cached data instead of block file.
      // The file channel should be repositioned.
      cacheFile.getChannel().position(0);
      verifyChecksum(length, metaIn, cacheFile.getChannel(), blockFileName);

      mappableBlock = new PmemMappedBlock(length, key);
      LOG.info("Successfully cached one replica:{} into persistent memory"
          + ", [cached path={}, length={}]", key, cachePath, length);
    } finally {
      IOUtils.closeQuietly(blockChannel);
      IOUtils.closeQuietly(cacheFile);
      if (mappableBlock == null) {
        LOG.debug("Delete {} due to unsuccessful mapping.", cachePath);
        FsDatasetUtil.deleteMappedFile(cachePath);
      }
    }
    return mappableBlock;
  }

  @Override
  public long getCacheUsed() {
    return pmemVolumeManager.getCacheUsed();
  }

  @Override
  public long getCacheCapacity() {
    return pmemVolumeManager.getCacheCapacity();
  }

  @Override
  long reserve(ExtendedBlockId key, long bytesCount) {
    return pmemVolumeManager.reserve(key, bytesCount);
  }

  @Override
  long release(ExtendedBlockId key, long bytesCount) {
    return pmemVolumeManager.release(key, bytesCount);
  }

  @Override
  public boolean isTransientCache() {
    return false;
  }

  @Override
  public boolean isNativeLoader() {
    return false;
  }

  @Override
  void shutdown() {
    LOG.info("Clean up cache on persistent memory during shutdown.");
    PmemVolumeManager.getInstance().cleanup();
  }
}
