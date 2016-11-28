/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.memory

import org.apache.spark.SparkConf
import org.apache.spark.storage.BlockId

import scala.io.Source



/**
 * A [[MemoryManager]] that enforces a soft boundary between execution and storage such that
 * either side can borrow memory from the other.
 *
 * The region shared between execution and storage is a fraction of (the total heap space - 300MB)
 * configurable through `spark.memory.fraction` (default 0.6). The position of the boundary
 * within this space is further determined by `spark.memory.storageFraction` (default 0.5).
 * This means the size of the storage region is 0.6 * 0.5 = 0.3 of the heap space by default.
 *
 * Storage can borrow as much execution memory as is free until execution reclaims its space.
 * When this happens, cached blocks will be evicted from memory until sufficient borrowed
 * memory is released to satisfy the execution memory request.
 *
 * Similarly, execution can borrow as much storage memory as is free. However, execution
 * memory is *never* evicted by storage due to the complexities involved in implementing this.
 * The implication is that attempts to cache blocks may fail if execution has already eaten
 * up most of the storage space, in which case the new blocks will be evicted immediately
 * according to their respective storage levels.
 *
 * @param onHeapStorageRegionSize Size of the storage region, in bytes.
 *                          This region is not statically reserved; execution can borrow from
 *                          it if necessary. Cached blocks can be evicted only if actual
 *                          storage memory usage exceeds this region.
 */
private[spark] class UnifiedMemoryManager private[memory] (
    conf: SparkConf,
    var maxHeapMemory: Long, // edit by Eddie, change val to var
    onHeapStorageRegionSize: Long,
    numCores: Int)
  extends MemoryManager(
    conf,
    numCores,
    onHeapStorageRegionSize,
    maxHeapMemory - onHeapStorageRegionSize) {

  // edit by Eddie
  // Test info
  logInfo("UnifidedMemoryManager is started.")

  // edit by Eddie
  // this block is part of initialization of this class
  val isDynamicResize = conf.getBoolean("spark.memory.dynamicResize.enabled", false)
  val updateMemoryThread = new UpdateMemoryThread(
    maxHeapMemory,
    maxOffHeapMemory)
  logInfo("dynamicResize is " + isDynamicResize.toString())
  if (isDynamicResize) {
    updateMemoryThread.start()
  }

  private def assertInvariants(): Unit = {
    assert(onHeapExecutionMemoryPool.poolSize + onHeapStorageMemoryPool.poolSize == maxHeapMemory)
    assert(
      offHeapExecutionMemoryPool.poolSize + offHeapStorageMemoryPool.poolSize == maxOffHeapMemory)
  }


  assertInvariants()

  override def maxOnHeapStorageMemory: Long = synchronized {
    maxHeapMemory - onHeapExecutionMemoryPool.memoryUsed
  }

  /**
   * Try to acquire up to `numBytes` of execution memory for the current task and return the
   * number of bytes obtained, or 0 if none can be allocated.
   *
   * This call may block until there is enough free memory in some situations, to make sure each
   * task has a chance to ramp up to at least 1 / 2N of the total memory pool (where N is the # of
   * active tasks) before it is forced to spill. This can happen if the number of tasks increase
   * but an older task had a lot of memory already.
   */
  override private[memory] def acquireExecutionMemory(
      numBytes: Long,
      taskAttemptId: Long,
      memoryMode: MemoryMode): Long = synchronized {
    assertInvariants()
    assert(numBytes >= 0)
    val (executionPool, storagePool, storageRegionSize, maxMemory) = memoryMode match {
      case MemoryMode.ON_HEAP => (
        onHeapExecutionMemoryPool,
        onHeapStorageMemoryPool,
        onHeapStorageRegionSize,
        maxHeapMemory)
      case MemoryMode.OFF_HEAP => (
        offHeapExecutionMemoryPool,
        offHeapStorageMemoryPool,
        offHeapStorageMemory,
        maxOffHeapMemory)
    }

    /**
     * Grow the execution pool by evicting cached blocks, thereby shrinking the storage pool.
     *
     * When acquiring memory for a task, the execution pool may need to make multiple
     * attempts. Each attempt must be able to evict storage in case another task jumps in
     * and caches a large block between the attempts. This is called once per attempt.
     */
    def maybeGrowExecutionPool(extraMemoryNeeded: Long): Unit = {
      if (extraMemoryNeeded > 0) {
        // There is not enough free memory in the execution pool, so try to reclaim memory from
        // storage. We can reclaim any free memory from the storage pool. If the storage pool
        // has grown to become larger than `storageRegionSize`, we can evict blocks and reclaim
        // the memory that storage has borrowed from execution.
        val memoryReclaimableFromStorage = math.max(
          storagePool.memoryFree,
          storagePool.poolSize - storageRegionSize)
        if (memoryReclaimableFromStorage > 0) {
          // Only reclaim as much space as is necessary and available:
          val spaceToReclaim = storagePool.freeSpaceToShrinkPool(
            math.min(extraMemoryNeeded, memoryReclaimableFromStorage))
          storagePool.decrementPoolSize(spaceToReclaim)
          executionPool.incrementPoolSize(spaceToReclaim)


        }
      }
    }

    /**
     * The size the execution pool would have after evicting storage memory.
     *
     * The execution memory pool divides this quantity among the active tasks evenly to cap
     * the execution memory allocation for each task. It is important to keep this greater
     * than the execution pool size, which doesn't take into account potential memory that
     * could be freed by evicting storage. Otherwise we may hit SPARK-12155.
     *
     * Additionally, this quantity should be kept below `maxMemory` to arbitrate fairness
     * in execution memory allocation across tasks, Otherwise, a task may occupy more than
     * its fair share of execution memory, mistakenly thinking that other tasks can acquire
     * the portion of storage memory that cannot be evicted.
     */
    def computeMaxExecutionPoolSize(): Long = {
      maxMemory - math.min(storagePool.memoryUsed, storageRegionSize)
    }

    val acquiredMemory = executionPool.acquireMemory(
      numBytes, taskAttemptId, maybeGrowExecutionPool, computeMaxExecutionPoolSize)


    acquiredMemory
  }

  override def acquireStorageMemory(
      blockId: BlockId,
      numBytes: Long,
      memoryMode: MemoryMode): Boolean = synchronized {
    assertInvariants()
    assert(numBytes >= 0)
    val (executionPool, storagePool, maxMemory) = memoryMode match {
      case MemoryMode.ON_HEAP => (
        onHeapExecutionMemoryPool,
        onHeapStorageMemoryPool,
        maxOnHeapStorageMemory)
      case MemoryMode.OFF_HEAP => (
        offHeapExecutionMemoryPool,
        offHeapStorageMemoryPool,
        maxOffHeapMemory)
    }
    if (numBytes > maxMemory) {
      // Fail fast if the block simply won't fit
      logInfo(s"Will not store $blockId as the required space ($numBytes bytes) exceeds our " +
        s"memory limit ($maxMemory bytes)")
      return false
    }
    if (numBytes > storagePool.memoryFree) {
      // There is not enough free memory in the storage pool, so try to borrow free memory from
      // the execution pool.
      val memoryBorrowedFromExecution = Math.min(executionPool.memoryFree, numBytes)
      executionPool.decrementPoolSize(memoryBorrowedFromExecution)
      storagePool.incrementPoolSize(memoryBorrowedFromExecution)
    }
    storagePool.acquireMemory(blockId, numBytes)
  }

  override def acquireUnrollMemory(
      blockId: BlockId,
      numBytes: Long,
      memoryMode: MemoryMode): Boolean = synchronized {
    acquireStorageMemory(blockId, numBytes, memoryMode)
  }

  // edit by eddie
  // dynamically change memory pool size
  protected[this] class UpdateMemoryThread (
    var lastMaxOnHeapMemory: Long,
    var lastMaxOffHeapMemory: Long
    ) extends Thread {

    private val updateInterval: Long =
      conf.getLong("spark.memory.dynamicResize.interval", 10000)
    private val memoryFilePath: String =
      conf.get("spark.memory.dynamicResize.path", "/sys/fs/cgroup/memory/memory.limit_in_bytes")
    private var isStopped: Boolean = false
    private var stopFlag: Boolean = false
    def setStop(): Unit = {
      stopFlag = true
    }

    def getIsStopped: Boolean = {
      isStopped
    }

    override def run(): Unit = {
      logInfo("Update Memory Thread is Running")
      while (!stopFlag) {
        try {
          updateMemory()
          Thread.sleep(updateInterval)
        } catch {
          case e: InterruptedException => logInfo("updateMemoryThread is interrupted.")
        }
      }
      isStopped = true
    }

    def readMemoryFile(): Long = {
      val sourceFile = Source.fromFile(memoryFilePath)
      val memorySize = sourceFile.getLines().next().toLong
      memorySize
    }

    def updateMemory() {
      val currentMaxOnHeapMemory: Long = readMemoryFile()
      val currentMaxOffHeapMemory: Long = readMemoryFile()
      logInfo("currentMaxOnHeapMemory is " + currentMaxOnHeapMemory.toString() +
      "currentOffHeapMemory is " + currentMaxOffHeapMemory.toString())

      // update on-heap memory
      if (lastMaxOnHeapMemory != currentMaxOnHeapMemory) {
        logInfo(" Max on-heap memory has changed, " +
          "Last is " + lastMaxOnHeapMemory.toString() +
          " Current is " + currentMaxOnHeapMemory.toString())
        if (lastMaxOnHeapMemory < currentMaxOnHeapMemory) {
          val delta: Long = currentMaxOnHeapMemory - lastMaxOnHeapMemory
          lastMaxOnHeapMemory = currentMaxOnHeapMemory
          val deltaStorageMemory: Long =
            delta * conf.getDouble("spark.memory.storageFraction", 0.5).toLong
          val deltaExecutionMemory: Long = delta - deltaStorageMemory
          onHeapStorageMemoryPool.incrementPoolSize(deltaStorageMemory)
          onHeapExecutionMemoryPool.incrementPoolSize(deltaExecutionMemory)
        } else if (lastMaxOnHeapMemory > currentMaxOnHeapMemory) {
          val delta: Long = lastMaxOnHeapMemory - currentMaxOnHeapMemory
          lastMaxOnHeapMemory = currentMaxOnHeapMemory
          val deltaStorageMemory: Long =
            delta * conf.getDouble("spark.memory.storageFraction", 0.5).toLong
          val deltaExecutionMemory: Long = delta - deltaStorageMemory
          onHeapStorageMemoryPool.decrementPoolSize(deltaStorageMemory)
          onHeapExecutionMemoryPool.decrementPoolSize(deltaExecutionMemory)
        }
        maxHeapMemory = currentMaxOffHeapMemory
      }

      // update off-heap memory
      if (lastMaxOffHeapMemory != currentMaxOffHeapMemory) {
        logInfo(" Max off-heap memory has changed, " +
          "Last is " + lastMaxOffHeapMemory.toString() +
          " Current is " + currentMaxOffHeapMemory.toString())
        if (lastMaxOffHeapMemory < currentMaxOffHeapMemory) {
          val delta: Long = currentMaxOffHeapMemory - lastMaxOffHeapMemory
          lastMaxOffHeapMemory = currentMaxOffHeapMemory
          val deltaStorageMemory =
            (delta * conf.getDouble("spark.memory.storageFraction", 0.5)).toLong
          val deltaExecutionMemory = delta - deltaStorageMemory
          offHeapStorageMemoryPool.incrementPoolSize(deltaStorageMemory)
          offHeapExecutionMemoryPool.incrementPoolSize(deltaExecutionMemory)
        } else if (lastMaxOffHeapMemory > currentMaxOffHeapMemory) {
          val delta: Long = lastMaxOffHeapMemory - currentMaxOffHeapMemory
          lastMaxOffHeapMemory = currentMaxOffHeapMemory
          val deltaStorageMemory =
            (delta * conf.getDouble("spark.memory.storageFraction", 0.5)).toLong
          val deltaExecutionMemory = delta - deltaStorageMemory
          offHeapStorageMemoryPool.decrementPoolSize(deltaStorageMemory)
          offHeapExecutionMemoryPool.decrementPoolSize(deltaExecutionMemory)
        }
      }
      maxOffHeapMemory = currentMaxOffHeapMemory
    }
  }

  // Edit by Eddie
  // Stop update memory thread
  // Called in SparkEnv
  override def stopUpdateMemoryThread(): Unit = {
    logInfo("Stopping Update Memory Thread")
    updateMemoryThread.setStop()
    updateMemoryThread.interrupt()
    while(!updateMemoryThread.getIsStopped) {
      Thread.sleep(conf.getLong("spark.memory.dynamicResize.interval", 10000))
    }
    logInfo("Update Memory Thread is Stopped")
  }

}

object UnifiedMemoryManager {

  // Set aside a fixed amount of memory for non-storage, non-execution purposes.
  // This serves a function similar to `spark.memory.fraction`, but guarantees that we reserve
  // sufficient memory for the system even for small heaps. E.g. if we have a 1GB JVM, then
  // the memory used for execution and storage will be (1024 - 300) * 0.6 = 434MB by default.
  private val RESERVED_SYSTEM_MEMORY_BYTES = 300 * 1024 * 1024

  def  apply(conf: SparkConf, numCores: Int): UnifiedMemoryManager = {
    val maxMemory = getMaxMemory(conf)
    new UnifiedMemoryManager(
      conf,
      maxHeapMemory = maxMemory,
      onHeapStorageRegionSize =
        (maxMemory * conf.getDouble("spark.memory.storageFraction", 0.5)).toLong,
      numCores = numCores)

  }

  /**
   * Return the total amount of memory shared between execution and storage, in bytes.
   * this is only for ON-HEAP memory.
   */
  private def getMaxMemory(conf: SparkConf): Long = {
    // 获取系统可用最大内存systemMemory，取参数spark.testing.memory，未配置的话取运行时环境中的最大内存
    val systemMemory = conf.getLong("spark.testing.memory", Runtime.getRuntime.maxMemory)
    // 获取预留内存reservedMemory，取参数spark.testing.reservedMemory，
    //  未配置的话，根据参数spark.testing来确定默认值，参数spark.testing存在的话，默认为0，否则默认为300M
    val reservedMemory = conf.getLong("spark.testing.reservedMemory",
      if (conf.contains("spark.testing")) 0 else RESERVED_SYSTEM_MEMORY_BYTES)
    // 取最小的系统内存minSystemMemory，为预留内存reservedMemory的1.5倍
    val minSystemMemory = (reservedMemory * 1.5).ceil.toLong
    // 如果系统可用最大内存systemMemory小于最小的系统内存minSystemMemory，即预留内存reservedMemory的1.5倍的话，抛出异常
    // 提醒用户调大JVM堆大小
    if (systemMemory < minSystemMemory) {
      throw new IllegalArgumentException(s"System memory $systemMemory must " +
        s"be at least $minSystemMemory. Please increase heap size using the --driver-memory " +
        s"option or spark.driver.memory in Spark configuration.")
    }
    // SPARK-12759 Check executor memory to fail fast if memory is insufficient
    if (conf.contains("spark.executor.memory")) {
      val executorMemory = conf.getSizeAsBytes("spark.executor.memory")
      if (executorMemory < minSystemMemory) {
        throw new IllegalArgumentException(s"Executor memory $executorMemory must be at least " +
          s"$minSystemMemory. Please increase executor memory using the " +
          s"--executor-memory option or spark.executor.memory in Spark configuration.")
      }
    }
    // 计算可用内存usableMemory，即系统最大可用内存systemMemory减去预留内存reservedMemory
    val usableMemory = systemMemory - reservedMemory
    // 取可用内存所占比重，即参数spark.memory.fraction，默认为0.6
    val memoryFraction = conf.getDouble("spark.memory.fraction", 0.6)
    // 返回的execution和storage区域共享的最大内存为usableMemory * memoryFraction
    (usableMemory * memoryFraction).toLong
  }
}
