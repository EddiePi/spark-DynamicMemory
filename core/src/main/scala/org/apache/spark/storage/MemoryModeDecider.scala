
package org.apache.spark.storage

import org.apache.spark.SparkConf
import org.apache.spark.memory.MemoryManager


private[storage] abstract class MemoryModeDecider () {
  def levelToUse (originalLevel: StorageLevel): StorageLevel
}

private[storage] class StaticMemoryModeDecider (
  val conf: SparkConf)
  extends MemoryModeDecider () {

  val offHeapRatio: Double = conf.getDouble("spark.memory.offHeap.autoOffHeap.ratio", 0.5)
  val random = new scala.util.Random()

  override def levelToUse(originalLevel: StorageLevel): StorageLevel = {
    if (random.nextDouble() <= offHeapRatio) {
      return StorageLevel.OFF_HEAP
    } else {
      return originalLevel
    }
  }
}

private[storage] class OnHeapFirstMemoryModeDecider (
  val conf: SparkConf,
  memoryManager: MemoryManager)
  extends MemoryModeDecider () {

  val onHeapThreshold: Double =
    conf.getDouble("spark.memory.offHeap.autoOffHeap.onHeapThreshold", 0.7)

  override def levelToUse(originalLevel: StorageLevel) : StorageLevel = {
    if (memoryManager.onHeapStorageMemoryUsed >=
      memoryManager.maxOnHeapStorageMemory * onHeapThreshold) {
      return StorageLevel.OFF_HEAP
    } else {
      return originalLevel
    }
  }
}