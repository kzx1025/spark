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

package org.apache.spark

import java.io._
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.{GZIPInputStream, GZIPOutputStream}

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.{ArrayBuffer, HashMap, HashSet, Map}
import scala.reflect.ClassTag

import org.apache.spark.rpc.{RpcCallContext, RpcEndpoint, RpcEndpointRef, RpcEnv}
import org.apache.spark.scheduler.MapStatus
import org.apache.spark.shuffle.MetadataFetchFailedException
import org.apache.spark.storage.{BlockId, BlockManagerId, ShuffleBlockId}
import org.apache.spark.util._

private[spark] sealed trait MapOutputTrackerMessage

private[spark] case class GetMapOutputStatuses(shuffleId: Int)
  extends MapOutputTrackerMessage

private[spark] case object StopMapOutputTracker extends MapOutputTrackerMessage

/** RpcEndpoint class for MapOutputTrackerMaster */
private[spark] class MapOutputTrackerMasterEndpoint(
                                                     override val rpcEnv: RpcEnv, tracker: MapOutputTrackerMaster, conf: SparkConf)
  extends RpcEndpoint with Logging {
  val maxRpcMessageSize = RpcUtils.maxMessageSizeBytes(conf)

  override def receiveAndReply(context: RpcCallContext): PartialFunction[Any, Unit] = {
    case GetMapOutputStatuses(shuffleId: Int) =>
      val hostPort = context.senderAddress.hostPort
      logInfo("Asked to send map output locations for shuffle " + shuffleId + " to " + hostPort)
      val mapOutputStatuses = tracker.getSerializedMapOutputStatuses(shuffleId)
      val serializedSize = mapOutputStatuses.length
      if (serializedSize > maxRpcMessageSize) {

        val msg = s"Map output statuses were $serializedSize bytes which " +
          s"exceeds spark.rpc.message.maxSize ($maxRpcMessageSize bytes)."

        /* For SPARK-1244 we'll opt for just logging an error and then sending it to the sender.
         * A bigger refactoring (SPARK-1239) will ultimately remove this entire code path. */
        val exception = new SparkException(msg)
        logError(msg, exception)
        context.sendFailure(exception)
      } else {
        context.reply(mapOutputStatuses)
      }

    case StopMapOutputTracker =>
      logInfo("MapOutputTrackerMasterEndpoint stopped!")
      context.reply(true)
      stop()
  }
}

/**
  * Class that keeps track of the location of the map output of
  * a stage. This is abstract because different versions of MapOutputTracker
  * (driver and executor) use different HashMap to store its metadata.
  */
private[spark] abstract class MapOutputTracker(conf: SparkConf) extends Logging {

  /** Set to the MapOutputTrackerMasterEndpoint living on the driver. */
  var trackerEndpoint: RpcEndpointRef = _

  /**
    * This HashMap has different behavior for the driver and the executors.
    *
    * On the driver, it serves as the source of map outputs recorded from ShuffleMapTasks.
    * On the executors, it simply serves as a cache, in which a miss triggers a fetch from the
    * driver's corresponding HashMap.
    *
    * Note: because mapStatuses is accessed concurrently, subclasses should make sure it's a
    * thread-safe map.
    */
  protected val mapStatuses: Map[Int, Array[MapStatus]]

  /**
    * Incremented every time a fetch fails so that client nodes know to clear
    * their cache of map output locations if this happens.
    */
  protected var epoch: Long = 0
  protected val epochLock = new AnyRef

  /** Remembers which map output locations are currently being fetched on an executor. */
  private val fetching = new HashSet[Int]

  /**
    * Send a message to the trackerEndpoint and get its result within a default timeout, or
    * throw a SparkException if this fails.
    */
  protected def askTracker[T: ClassTag](message: Any): T = {
    try {
      trackerEndpoint.askWithRetry[T](message)
    } catch {
      case e: Exception =>
        logError("Error communicating with MapOutputTracker", e)
        throw new SparkException("Error communicating with MapOutputTracker", e)
    }
  }

  /** Send a one-way message to the trackerEndpoint, to which we expect it to reply with true. */
  protected def sendTracker(message: Any) {
    val response = askTracker[Boolean](message)
    if (response != true) {
      throw new SparkException(
        "Error reply received from MapOutputTracker. Expecting true, got " + response.toString)
    }
  }

  /**
    * Called from executors to get the server URIs and output sizes for each shuffle block that
    * needs to be read from a given reduce task.
    *
    * @return A sequence of 2-item tuples, where the first item in the tuple is a BlockManagerId,
    *         and the second item is a sequence of (shuffle block id, shuffle block size) tuples
    *         describing the shuffle blocks that are stored at that block manager.
    */
  def getMapSizesByExecutorId(shuffleId: Int, reduceId: Int)
  : Seq[(BlockManagerId, Seq[(BlockId, Long)])] = {
    getMapSizesByExecutorId(shuffleId, reduceId, reduceId + 1)
  }

  /**
    * Called from executors to get the server URIs and output sizes for each shuffle block that
    * needs to be read from a given range of map output partitions (startPartition is included but
    * endPartition is excluded from the range).
    *
    * @return A sequence of 2-item tuples, where the first item in the tuple is a BlockManagerId,
    *         and the second item is a sequence of (shuffle block id, shuffle block size) tuples
    *         describing the shuffle blocks that are stored at that block manager.
    */
  def getMapSizesByExecutorId(shuffleId: Int, startPartition: Int, endPartition: Int)
  : Seq[(BlockManagerId, Seq[(BlockId, Long)])] = {
    logDebug(s"Fetching outputs for shuffle $shuffleId, partitions $startPartition-$endPartition")
    val statuses = getStatuses(shuffleId)
    // Synchronize on the returned array because, on the driver, it gets mutated in place
    statuses.synchronized {
      return MapOutputTracker.convertMapStatuses(shuffleId, startPartition, endPartition, statuses)
    }
  }

  /**
    * Return statistics about all of the outputs for a given shuffle.
    */
  def getStatistics(dep: ShuffleDependency[_, _, _]): MapOutputStatistics = {
    val statuses = getStatuses(dep.shuffleId)
    // Synchronize on the returned array because, on the driver, it gets mutated in place
    statuses.synchronized {
      val totalSizes = new Array[Long](dep.partitioner.numPartitions)
      for (s <- statuses) {
        for (i <- 0 until totalSizes.length) {
          totalSizes(i) += s.getSizeForBlock(i)
        }
      }
      new MapOutputStatistics(dep.shuffleId, totalSizes)
    }
  }

  /**
    * Get or fetch the array of MapStatuses for a given shuffle ID. NOTE: clients MUST synchronize
    * on this array when reading it, because on the driver, we may be changing it in place.
    *
    * (It would be nice to remove this restriction in the future.)
    */
  private def getStatuses(shuffleId: Int): Array[MapStatus] = {
    val statuses = mapStatuses.get(shuffleId).orNull
    if (statuses == null) {
      logInfo("Don't have map outputs for shuffle " + shuffleId + ", fetching them")
      val startTime = System.currentTimeMillis
      var fetchedStatuses: Array[MapStatus] = null
      fetching.synchronized {
        // Someone else is fetching it; wait for them to be done
        while (fetching.contains(shuffleId)) {
          try {
            fetching.wait()
          } catch {
            case e: InterruptedException =>
          }
        }

        // Either while we waited the fetch happened successfully, or
        // someone fetched it in between the get and the fetching.synchronized.
        fetchedStatuses = mapStatuses.get(shuffleId).orNull
        if (fetchedStatuses == null) {
          // We have to do the fetch, get others to wait for us.
          fetching += shuffleId
        }
      }

      if (fetchedStatuses == null) {
        // We won the race to fetch the statuses; do so
        logInfo("Doing the fetch; tracker endpoint = " + trackerEndpoint)
        // This try-finally prevents hangs due to timeouts:
        try {
          val fetchedBytes = askTracker[Array[Byte]](GetMapOutputStatuses(shuffleId))
          fetchedStatuses = MapOutputTracker.deserializeMapStatuses(fetchedBytes)
          logInfo("Got the output locations")
          mapStatuses.put(shuffleId, fetchedStatuses)
        } finally {
          fetching.synchronized {
            fetching -= shuffleId
            fetching.notifyAll()
          }
        }
      }
      logDebug(s"Fetching map output statuses for shuffle $shuffleId took " +
        s"${System.currentTimeMillis - startTime} ms")

      if (fetchedStatuses != null) {
        return fetchedStatuses
      } else {
        logError("Missing all output locations for shuffle " + shuffleId)
        throw new MetadataFetchFailedException(
          shuffleId, -1, "Missing all output locations for shuffle " + shuffleId)
      }
    } else {
      return statuses
    }
  }

  /** Called to get current epoch number. */
  def getEpoch: Long = {
    epochLock.synchronized {
      return epoch
    }
  }

  /**
    * Called from executors to update the epoch number, potentially clearing old outputs
    * because of a fetch failure. Each executor task calls this with the latest epoch
    * number on the driver at the time it was created.
    */
  def updateEpoch(newEpoch: Long) {
    epochLock.synchronized {
      if (newEpoch > epoch) {
        logInfo("Updating epoch to " + newEpoch + " and clearing cache")
        epoch = newEpoch
        mapStatuses.clear()
      }
    }
  }

  /** Unregister shuffle data. */
  def unregisterShuffle(shuffleId: Int) {
    mapStatuses.remove(shuffleId)
  }

  /** Stop the tracker. */
  def stop() {}
}

/**
  * MapOutputTracker for the driver.
  */
private[spark] class MapOutputTrackerMaster(conf: SparkConf)
  extends MapOutputTracker(conf) {

  /** Cache a serialized version of the output statuses for each shuffle to send them out faster */
  private var cacheEpoch = epoch

  /** Whether to compute locality preferences for reduce tasks */
  private val shuffleLocalityEnabled = conf.getBoolean("spark.shuffle.reduceLocality.enabled", true)

  // Number of map and reduce tasks above which we do not assign preferred locations based on map
  // output sizes. We limit the size of jobs for which assign preferred locations as computing the
  // top locations by size becomes expensive.
  private val SHUFFLE_PREF_MAP_THRESHOLD = 1000
  // NOTE: This should be less than 2000 as we use HighlyCompressedMapStatus beyond that
  private val SHUFFLE_PREF_REDUCE_THRESHOLD = 1000

  // Fraction of total map output that must be at a location for it to considered as a preferred
  // location for a reduce task. Making this larger will focus on fewer locations where most data
  // can be read locally, but may lead to more delay in scheduling if those locations are busy.
  private val REDUCER_PREF_LOCS_FRACTION = 0.2

  // HashMaps for storing mapStatuses and cached serialized statuses in the driver.
  // Statuses are dropped only by explicit de-registering.
  protected val mapStatuses = new ConcurrentHashMap[Int, Array[MapStatus]]().asScala
  private val cachedSerializedStatuses = new ConcurrentHashMap[Int, Array[Byte]]().asScala

  private val LOG_BASE = 1.1

  def registerShuffle(shuffleId: Int, numMaps: Int) {
    if (mapStatuses.put(shuffleId, new Array[MapStatus](numMaps)).isDefined) {
      throw new IllegalArgumentException("Shuffle ID " + shuffleId + " registered twice")
    }
  }

  def registerMapOutput(shuffleId: Int, mapId: Int, status: MapStatus) {
    val array = mapStatuses(shuffleId)
    array.synchronized {
      array(mapId) = status
    }
  }

  /** Register multiple map output information for the given shuffle */
  def registerMapOutputs(shuffleId: Int, statuses: Array[MapStatus], changeEpoch: Boolean = false) {
    mapStatuses.put(shuffleId, Array[MapStatus]() ++ statuses)
    if (changeEpoch) {
      incrementEpoch()
    }
  }

  /** Unregister map output information of the given shuffle, mapper and block manager */
  def unregisterMapOutput(shuffleId: Int, mapId: Int, bmAddress: BlockManagerId) {
    val arrayOpt = mapStatuses.get(shuffleId)
    if (arrayOpt.isDefined && arrayOpt.get != null) {
      val array = arrayOpt.get
      array.synchronized {
        if (array(mapId) != null && array(mapId).location == bmAddress) {
          array(mapId) = null
        }
      }
      incrementEpoch()
    } else {
      throw new SparkException("unregisterMapOutput called for nonexistent shuffle ID")
    }
  }

  /** Unregister shuffle data */
  override def unregisterShuffle(shuffleId: Int) {
    mapStatuses.remove(shuffleId)
    cachedSerializedStatuses.remove(shuffleId)
  }

  /** Check if the given shuffle is being tracked */
  def containsShuffle(shuffleId: Int): Boolean = {
    cachedSerializedStatuses.contains(shuffleId) || mapStatuses.contains(shuffleId)
  }

  /**
    * Return the preferred hosts on which to run the given map output partition in a given shuffle,
    * i.e. the nodes that the most outputs for that partition are on.
    *
    * @param dep         shuffle dependency object
    * @param partitionId map output partition that we want to read
    * @return a sequence of host names
    */
  def getPreferredLocationsForShuffle(dep: ShuffleDependency[_, _, _], partitionId: Int)
  : Seq[String] = {
    if (shuffleLocalityEnabled && dep.rdd.partitions.length < SHUFFLE_PREF_MAP_THRESHOLD &&
      dep.partitioner.numPartitions < SHUFFLE_PREF_REDUCE_THRESHOLD) {
      // replace getLocationsWithLargestOutputs with getLocationsWithOverAllSituation
      val blockManagerIds = getLocationsWithGlobalMode(dep.shuffleId,
        dep.partitioner.numPartitions)
      if (blockManagerIds.nonEmpty) {
        blockManagerIds.get.map(_.host)
      } else {
        Nil
      }
    } else {
      Nil
    }
  }

  /**
    * Return a list of locations that each have fraction of map output greater than the specified
    * threshold.
    *
    * @param shuffleId         id of the shuffle
    * @param reducerId         id of the reduce task
    * @param numReducers       total number of reducers in the shuffle
    * @param fractionThreshold fraction of total map output size that a location must have
    *                          for it to be considered large.
    */
  def getLocationsWithLargestOutputs(
                                      shuffleId: Int,
                                      reducerId: Int,
                                      numReducers: Int,
                                      fractionThreshold: Double)
  : Option[Array[BlockManagerId]] = {

    val statuses = mapStatuses.get(shuffleId).orNull
    if (statuses != null) {
      statuses.synchronized {
        if (statuses.nonEmpty) {
          // HashMap to add up sizes of all blocks at the same location
          val locs = new HashMap[BlockManagerId, Long]
          var totalOutputSize = 0L
          var mapIdx = 0
          while (mapIdx < statuses.length) {
            val status = statuses(mapIdx)
            // status may be null here if we are called between registerShuffle, which creates an
            // array with null entries for each output, and registerMapOutputs, which populates it
            // with valid status entries. This is possible if one thread schedules a job which
            // depends on an RDD which is currently being computed by another thread.
            if (status != null) {
              val blockSize = status.getSizeForBlock(reducerId)
              if (blockSize > 0) {
                locs(status.location) = locs.getOrElse(status.location, 0L) + blockSize
                totalOutputSize += blockSize
              }
            }
            mapIdx = mapIdx + 1
          }
          val topLocs = locs.filter { case (loc, size) =>
            size.toDouble / totalOutputSize >= fractionThreshold
          }
          // Return if we have any locations which satisfy the required threshold
          if (topLocs.nonEmpty) {
            return Some(topLocs.keys.toArray)
          }
        }
      }
    }
    None
  }


  /**
    * Return a list of locations that each have fraction of map output according to load balancing
    * and achieve  fetching least data.
    *
    * @param shuffleId   id of the shuffle
    * @param numReducers total number of reducers in the shuffle
    *
    */
  def getLocationsWithGlobalMode(
                                        shuffleId: Int,
                                        numReducers: Int
                                      )
  : Option[Array[BlockManagerId]] = {
    val statuses = mapStatuses.get(shuffleId).orNull
    assert(statuses != null)
    val splitsByLocation = new HashMap[BlockManagerId, Array[Long]]
    var sumOfAllBytes: Long = 0
    statuses.foreach {
      status =>
        if (status == null) {
          throw new MetadataFetchFailedException(
            shuffleId, -1, "Missing an output location for shuffle " + shuffleId)
        } else {
          val location = status.location
          if (!splitsByLocation.contains(location)) {
            splitsByLocation(location) = new Array[Long](numReducers)
          }
          var i = 0
          while (i < numReducers) {
            val byteSize = status.getSizeForBlock(i)
            splitsByLocation(location)(i) += byteSize
            sumOfAllBytes += byteSize
            i += 1
          }
        }
    }
    if (splitsByLocation.nonEmpty) {
      val numOfLocations = splitsByLocation.size
      val preferredLocationsOfReduces = new Array[BlockManagerId](numReducers)
      val bytesOfReduces = new Array[Long](numReducers)
      val blockManagerIdMaps = new HashMap[Int, BlockManagerId]
      val splitIndexOfLocation = new Array[HashSet[Int]](numOfLocations)
      var i = 0
      var j = 0
      //caclulate the bytesize of each reducer
      splitsByLocation.toSeq.map(
        s => {
          val (blockManagerId, byteSize) = s
          blockManagerIdMaps(i) = blockManagerId
          splitIndexOfLocation(i) = new HashSet[Int]
          j = 0
          byteSize.map(
            b => {
              bytesOfReduces(j) += b
              j += 1
            })
          i += 1
        })

      val indexOfBytesOfReduces = new HashMap[Int, Long]
      for ((size, index) <- bytesOfReduces.zipWithIndex) {
        indexOfBytesOfReduces.getOrElseUpdate(index, size)
      }
      val sortedIndexOfBytesOfReducer = indexOfBytesOfReduces.toSeq.sortWith(_._2 > _._2)
      val splitSumOfByteSizeOfLocation = new Array[Long](numOfLocations)

      //Divide the tasks into n groups according to the number of nodes and data size,
      // ensuring that the data size for each group is nearly equal to achieve load balancing.
      for (i <- sortedIndexOfBytesOfReducer.indices) {
        var minIndex = 0
        for (j <- 1 until numOfLocations) {
          if (splitSumOfByteSizeOfLocation(j) < splitSumOfByteSizeOfLocation(minIndex)) {
            minIndex = j
          }
        }
        val (index, byteSize) = sortedIndexOfBytesOfReducer(i)
        splitSumOfByteSizeOfLocation(minIndex) += byteSize
        splitIndexOfLocation(minIndex).add(index)
      }

      // Determine the amount of fetching data if the tasks of every group are executed on every node.
      // Thus, a n × n matrix is created.
      val splitBytesOfLocationsAndGroup = new Array[Array[Long]](numOfLocations)
      for (i <- splitBytesOfLocationsAndGroup.indices) {
        splitBytesOfLocationsAndGroup(i) = new Array[Long](numOfLocations)
      }
      for (i <- splitIndexOfLocation.indices) {
        val iter: Iterator[Int] = splitIndexOfLocation(i).iterator
        while (iter.hasNext) {
          val index = iter.next()
          val bytesOfLocations: Seq[(BlockManagerId, Long)] = splitsByLocation.toSeq.map(s => (s._1, s._2(index)))
          for (j <- bytesOfLocations.indices) {
            splitBytesOfLocationsAndGroup(i)(j) += bytesOfLocations(j)._2
          }
        }
      }
      //Choose the largest value in the matrix to identify which group is allocated to which node.
      // Mark the row and column at which the selected group is located to ensure that the group
      // is not chosen next time. Goto Step 4 until no group is available.
      for (i <- 0 until numOfLocations) {
        var maxCol = 0
        var maxRow = 0
        var maxValue = splitBytesOfLocationsAndGroup(maxRow)(maxCol)
        for (j <- splitBytesOfLocationsAndGroup.indices) {
          for (k <- splitBytesOfLocationsAndGroup(j).indices) {
            if (splitBytesOfLocationsAndGroup(j)(k) > maxValue) {
              maxRow = j
              maxCol = k
              maxValue = splitBytesOfLocationsAndGroup(j)(k)
            }
          }
        }
        val iter: Iterator[Int] = splitIndexOfLocation(maxRow).iterator
        while (iter.hasNext) {
          val index = iter.next()
          preferredLocationsOfReduces(index) = blockManagerIdMaps(maxCol)
        }
        for (j <- splitBytesOfLocationsAndGroup.indices) {
          splitBytesOfLocationsAndGroup(j)(maxCol) = -1
        }
        for (k <- splitBytesOfLocationsAndGroup.indices) {
          splitBytesOfLocationsAndGroup(maxRow)(k) = -1
        }
      }
      Some(preferredLocationsOfReduces)
    }
    else
      None
  }


  /**
    * Decompress an 8-bit encoded block size, using the reverse operation of compressSize.
    */
  def decompressSize(compressedSize: Byte): Long = {
    if (compressedSize == 0) {
      0
    } else {
      math.pow(LOG_BASE, compressedSize & 0xFF).toLong
    }
  }

  def incrementEpoch() {
    epochLock.synchronized {
      epoch += 1
      logDebug("Increasing epoch to " + epoch)
    }
  }

  def getSerializedMapOutputStatuses(shuffleId: Int): Array[Byte] = {
    var statuses: Array[MapStatus] = null
    var epochGotten: Long = -1
    epochLock.synchronized {
      if (epoch > cacheEpoch) {
        cachedSerializedStatuses.clear()
        cacheEpoch = epoch
      }
      cachedSerializedStatuses.get(shuffleId) match {
        case Some(bytes) =>
          return bytes
        case None =>
          statuses = mapStatuses.getOrElse(shuffleId, Array[MapStatus]())
          epochGotten = epoch
      }
    }
    // If we got here, we failed to find the serialized locations in the cache, so we pulled
    // out a snapshot of the locations as "statuses"; let's serialize and return that
    val bytes = MapOutputTracker.serializeMapStatuses(statuses)
    logInfo("Size of output statuses for shuffle %d is %d bytes".format(shuffleId, bytes.length))
    // Add them into the table only if the epoch hasn't changed while we were working
    epochLock.synchronized {
      if (epoch == epochGotten) {
        cachedSerializedStatuses(shuffleId) = bytes
      }
    }
    bytes
  }

  override def stop() {
    sendTracker(StopMapOutputTracker)
    mapStatuses.clear()
    trackerEndpoint = null
    cachedSerializedStatuses.clear()
  }
}

/**
  * MapOutputTracker for the executors, which fetches map output information from the driver's
  * MapOutputTrackerMaster.
  */
private[spark] class MapOutputTrackerWorker(conf: SparkConf) extends MapOutputTracker(conf) {
  protected val mapStatuses: Map[Int, Array[MapStatus]] =
    new ConcurrentHashMap[Int, Array[MapStatus]]().asScala
}

private[spark] object MapOutputTracker extends Logging {

  val ENDPOINT_NAME = "MapOutputTracker"

  // Serialize an array of map output locations into an efficient byte format so that we can send
  // it to reduce tasks. We do this by compressing the serialized bytes using GZIP. They will
  // generally be pretty compressible because many map outputs will be on the same hostname.
  def serializeMapStatuses(statuses: Array[MapStatus]): Array[Byte] = {
    val out = new ByteArrayOutputStream
    val objOut = new ObjectOutputStream(new GZIPOutputStream(out))
    Utils.tryWithSafeFinally {
      // Since statuses can be modified in parallel, sync on it
      statuses.synchronized {
        objOut.writeObject(statuses)
      }
    } {
      objOut.close()
    }
    out.toByteArray
  }

  // Opposite of serializeMapStatuses.
  def deserializeMapStatuses(bytes: Array[Byte]): Array[MapStatus] = {
    val objIn = new ObjectInputStream(new GZIPInputStream(new ByteArrayInputStream(bytes)))
    Utils.tryWithSafeFinally {
      objIn.readObject().asInstanceOf[Array[MapStatus]]
    } {
      objIn.close()
    }
  }

  /**
    * Given an array of map statuses and a range of map output partitions, returns a sequence that,
    * for each block manager ID, lists the shuffle block IDs and corresponding shuffle block sizes
    * stored at that block manager.
    *
    * If any of the statuses is null (indicating a missing location due to a failed mapper),
    * throws a FetchFailedException.
    *
    * @param shuffleId      Identifier for the shuffle
    * @param startPartition Start of map output partition ID range (included in range)
    * @param endPartition   End of map output partition ID range (excluded from range)
    * @param statuses       List of map statuses, indexed by map ID.
    * @return A sequence of 2-item tuples, where the first item in the tuple is a BlockManagerId,
    *         and the second item is a sequence of (shuffle block ID, shuffle block size) tuples
    *         describing the shuffle blocks that are stored at that block manager.
    */
  private def convertMapStatuses(
                                  shuffleId: Int,
                                  startPartition: Int,
                                  endPartition: Int,
                                  statuses: Array[MapStatus]): Seq[(BlockManagerId, Seq[(BlockId, Long)])] = {
    assert(statuses != null)
    val splitsByAddress = new HashMap[BlockManagerId, ArrayBuffer[(BlockId, Long)]]
    for ((status, mapId) <- statuses.zipWithIndex) {
      if (status == null) {
        val errorMessage = s"Missing an output location for shuffle $shuffleId"
        logError(errorMessage)
        throw new MetadataFetchFailedException(shuffleId, startPartition, errorMessage)
      } else {
        for (part <- startPartition until endPartition) {
          splitsByAddress.getOrElseUpdate(status.location, ArrayBuffer()) +=
            ((ShuffleBlockId(shuffleId, mapId, part), status.getSizeForBlock(part)))
        }
      }
    }

    splitsByAddress.toSeq
  }
}
