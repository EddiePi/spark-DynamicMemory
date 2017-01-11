
package org.apache.spark.storage

import org.apache.spark.SparkConf
import org.apache.spark.memory.MemoryManager
import org.apache.spark.util.SizeEstimator


private[storage] abstract class MemoryModeDecider () {
  def levelToUse (originalLevel: StorageLevel, dataToStore: AnyRef): StorageLevel
}

private[storage] class StaticMemoryModeDecider (
  val conf: SparkConf)
  extends MemoryModeDecider () {

  val offHeapRatio: Double = conf.getDouble("spark.memory.offHeap.autoOffHeap.ratio", 0.5)
  val random = new scala.util.Random()

  override def levelToUse(originalLevel: StorageLevel, dataToStore: AnyRef): StorageLevel = {
    if (random.nextDouble() <= offHeapRatio) {
      return StorageLevel.OFF_HEAP
    } else {
      return originalLevel
    }
  }
}

// we first use on-heap memory. When the memory exceeds the threshold, we switch to off-heap memory.
private[storage] class OnHeapFirstMemoryModeDecider (
  val conf: SparkConf,
  memoryManager: MemoryManager)
  extends MemoryModeDecider () {

  val onHeapThreshold: Double =
    conf.getDouble("spark.memory.offHeap.autoOffHeap.onHeapThreshold", 0.7)

  override def levelToUse(originalLevel: StorageLevel, dataToStore: AnyRef): StorageLevel = {
    if (memoryManager.onHeapStorageMemoryUsed >=
      memoryManager.maxOnHeapStorageMemory * onHeapThreshold) {
      return StorageLevel.OFF_HEAP
    } else {
      return originalLevel
    }
  }
}

// store large rdd on off-heap memory
private[storage] class LargeFirstMemoryModeDecider (
  val conf: SparkConf)
  extends MemoryModeDecider () {

  // number of megabytes
  var sizeThreshold: Double = conf.getLong("spark.memory.offHeap.autoOffHeap.sizeThreshold", 100)

  override def levelToUse(originalLevel: StorageLevel, dataToStore: AnyRef): StorageLevel = {
    val dataSize = SizeEstimator.estimate(dataToStore)
    if (dataSize / 1024 / 1024 > sizeThreshold) {
      return StorageLevel.OFF_HEAP
    } else {
      return originalLevel
    }
  }
}