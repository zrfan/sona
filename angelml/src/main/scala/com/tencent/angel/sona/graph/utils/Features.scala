/*
 * Tencent is pleased to support the open source community by making Angel available.
 *
 * Copyright (C) 2017-2018 THL A29 Limited, a Tencent company. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 * https://opensource.org/licenses/Apache-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 *
 */

package com.tencent.angel.sona.graph.utils

import java.util.{HashMap => JHashMap, HashSet => JHashSet}

import com.tencent.angel.ml.core.utils.PSMatrixUtils
import com.tencent.angel.ml.math2.VFactory
import com.tencent.angel.ml.math2.utils.{LabeledData, RowType}
import com.tencent.angel.ml.math2.vector.LongIntVector
import com.tencent.angel.mlcore.conf.MLCoreConf
import com.tencent.angel.model.output.format.ColIdValueTextRowFormat
import com.tencent.angel.sona.context.PSContext
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import org.apache.spark.rdd.RDD

import scala.collection.Map
import scala.collection.mutable.ArrayBuffer

/**
  * This class provide some feature pre-processing utils for input data.
  */
object Features {

  def libSVMStringToIndex(data: RDD[String]): (RDD[LabeledData], Map[String, Int]) = {
    null
  }

  def corpusStringToIntWithoutRemapping(data: RDD[String]): RDD[Array[Int]] = {
    data.filter(f => f != null && f.length > 0)
      .map(f => f.stripLineEnd.split("[\\s+|,]").map(s => s.toInt))
  }


  def corpusStringToInt(data: RDD[String]): (RDD[Array[Int]], RDD[(Int, String)]) = {

    // All distinct strings
    val strings = data.filter(f => f != null && f.length > 0)
      .map(f => f.stripLineEnd.split("[\\s+|,]")).flatMap(f => f)
      .map(t => (t, 1)).reduceByKey(_ + _).map(f => f._1)
    // 字符串到索引值的映射
    val stringsWithIndex = strings.zipWithIndex().cache()

    // 建立字符串到分区索引值的查询表
    def buildRoutingTable(index: Int, iterator: Iterator[String]): Iterator[(String, Int)] = {
      val set = new JHashSet[String]()
      while (iterator.hasNext) {
        val line = iterator.next()
        if (line != null && line.length > 0) {
          for (word <- line.stripLineEnd.split("[\\s+|,]")) {
            set.add(word)
          }
        }
      }

      val it = set.iterator()
      val result = new ArrayBuffer[(String, Int)]()
      while (it.hasNext) {
        result.append((it.next(), index))
      }

      result.iterator
    }
    // 字符串到索引值的映射路由表
    val routingTable = data.mapPartitionsWithIndex((partId, iterator) =>
      buildRoutingTable(partId, iterator),
      true)

    // 字符串 ， 分区id，索引值  ->  分区id，字符串，索引值
    val partIndex = routingTable.join(stringsWithIndex).map { case (str, (partId, index)) =>
      (partId, (str, index))
    }.groupByKey(data.getNumPartitions)
    // 绑定分区id  -> 分区id，当前分区的字符数组
    def attachPartitionId(index: Int, iterator: Iterator[String]): Iterator[(Int, Array[String])] = {
      // 创建一个一次处理一个索引+当前迭代器数组内容的迭代器
      Iterator.single((index, iterator.toArray))
    }

    val ints = data.mapPartitionsWithIndex((partId, iterator) =>
      attachPartitionId(partId, iterator),
      true).join(partIndex).map { case (_, (sentences, mapping)) =>
      val map = new JHashMap[String, Long]()
      for ((string, index) <- mapping) {
        map.put(string, index)
      }
      // 把sentences中的字符转换成对应的index值
      sentences.filter(f => f != null && f.length > 0).map { case line =>
        line.stripLineEnd.split("[\\s+|,]").map(s => map.get(s).toInt)
      }}.flatMap(f => f)

    // ints: 分区id，索引值数组，当前分区字符串-索引值的映射表，stringsWithIndex:索引值，对应字符
    (ints, stringsWithIndex.map(f => (f._2.toInt, f._1)))
  }

  /**
    * Convert a corpus dataset (with strings) to dataset with IDs.
    * @param data: corpus data with strings
    * @return
    */
  def corpusStringToIntByServer(data: RDD[String]): (RDD[Array[Int]], RDD[(Int, String)]) = {
    // cache data
    data.cache().count()


    // All distinct strings
    val features = data.filter(f => f != null && f.length > 0)
      .map(f => f.stripLineEnd.split(" ")).flatMap(f => f)
      .map(t => (t, 1)).reduceByKey(_ + _).map(f => f._1)

    // Mapping each string with a hashcode ID, the hashcode is sparse
    // Now we use the default hashcode of string in Java.
    val featureWithIndex = features.map(f => (f, MurmurHash64.stringHash(f)))

    //    val featureWithIndex = features.zipWithIndex().map(f => (f._1, f._2.toInt))
    featureWithIndex.cache().count()

    println(s"feature count ${featureWithIndex.map(f => f._1).distinct().count()}")
    println(s"hashcode count ${featureWithIndex.map(f => f._2).distinct().count()}")

    // Now convert the sparse index to dense index, we use
    val sparseDim: Long = featureWithIndex.map(f => f._2).max() + 1
    val denseIndices = featureWithIndex.map(f => f._2).zipWithIndex().map(f => (f._1, f._2 + 1))
    denseIndices.cache().count()
    // The dense dimension
    val denseDim = (denseIndices.map(f => f._2).max() + 1).toInt

    println(s"sparseDim=$sparseDim denseDim=$denseDim")

    // Create an index matrix on servers to mapping from sparse index to dense index
    // ML_MATRIX_OUTPUT_FORMAT
    val sparseToDense = PSMatrixUtils.createPSMatrixCtx("sparseToDense", 1, sparseDim,
      RowType.T_INT_SPARSE_LONGKEY, "")
    val sparseToDenseMatrixId = PSMatrixUtils.createPSMatrix(sparseToDense)

    val denseToSparse = PSMatrixUtils.createPSMatrixCtx("denseToSparse", 1, denseDim,
      RowType.T_LONG_DENSE, "")
    val denseToSparseMatrixId = PSMatrixUtils.createPSMatrix(denseToSparse)

    // Function to initialize sparseToDenseMatrix and denseToSparseMatrix
    def initializeSparseAndDenseMatrix(iterator: Iterator[(Long, Long)],
                                       sparseDim: Long,
                                       sparseToDenseMatrixId: Int,
                                       denseDim: Int,
                                       denseToSparseMatrixId: Int): Iterator[Int] = {
      PSContext.instance()

      // Initialize sparseToDense and denseToDense matrix
      val sparseToDenseUpdate = VFactory.sparseLongKeyIntVector(sparseDim)
      val denseToSparseUpdate = VFactory.sparseLongVector(denseDim)
      while (iterator.hasNext) {
        val (sparseIndex, denseIndex) = iterator.next()
        sparseToDenseUpdate.set(sparseIndex, denseIndex.toInt)
        denseToSparseUpdate.set(denseIndex.toInt, sparseIndex)
      }
      // Push updates to servers
      PSMatrixUtils.incrementRow(sparseToDenseMatrixId, 0, sparseToDenseUpdate)
      PSMatrixUtils.incrementRow(denseToSparseMatrixId, 0, denseToSparseUpdate)

      Iterator.single(0)
    }

    // Function to convert corpus data from strings to int IDs.
    def doSparseToDense(iterator: Iterator[String],
                        sparseToDenseMatrixId: Int): Iterator[Array[Int]] = {
      PSContext.instance()
      val set = new LongOpenHashSet()
      val corpus = new ArrayBuffer[Array[Long]]()

      while (iterator.hasNext) {
        val line = iterator.next()
        if (line != null && line.length > 0) {
          val strings = line.stripLineEnd.split(" ")
          val longs = strings.map(s => MurmurHash64.stringHash(s))
          corpus.append(longs)
          longs.foreach(index => set.add(index))
        }
      }

      val pullIndex = VFactory.denseLongVector(set.toLongArray())
      val sparseToDenseIndex = PSMatrixUtils.getRowWithIndex(1, sparseToDenseMatrixId, 0,
        pullIndex)(0, 0.00001).asInstanceOf[LongIntVector]

      val size = corpus.size
      var i = 0
      while (i < size) {
        val longs = corpus(i)
        var j = 0
        while (j < longs.length) {
          val dense = sparseToDenseIndex.get(longs(j))
          assert(dense != 0)
          longs(j) = dense
          j += 1
        }
        i += 1
      }

      corpus.map(f => f.map(k => k.toInt)).iterator
    }

    def buildDenseToString(iterator: Iterator[(String, Long)],
                           sparseDim: Long,
                           sparseToDenseMatrixId: Int): Iterator[(Int, String)] = {
      val stringsWithInts = iterator.toArray
      val indices = new Array[Long](stringsWithInts.length)
      var i = 0
      while (i < indices.length) {
        indices(i) = stringsWithInts(i)._2
        i += 1
      }

      val sparseToDenseIndex = PSMatrixUtils.getRowWithIndex(1, sparseToDenseMatrixId, 0,
        VFactory.denseLongVector(indices))(0, 0.00001).asInstanceOf[LongIntVector]
      stringsWithInts.map(f => (sparseToDenseIndex.get(f._2), f._1)).iterator
    }

    // Initialize two index matrix
    denseIndices.mapPartitions(it =>
      initializeSparseAndDenseMatrix(it,
        sparseDim,
        sparseToDenseMatrixId,
        denseDim,
        denseToSparseMatrixId),
      true).count()

    denseIndices.unpersist()

    // Converting strings to ints
    val intCorpus = data.mapPartitions(it =>
      doSparseToDense(it, sparseToDenseMatrixId),
      true)

    intCorpus.cache().count()

    // Mapping index
    val denseToString = featureWithIndex.mapPartitions(it =>
      buildDenseToString(it,
        sparseDim,
        sparseToDenseMatrixId)).sortBy(f => f._1)

    denseToString.cache()
    println(s"denseToString.size()=${denseToString.count()}")

    data.unpersist()
    featureWithIndex.unpersist()

    (intCorpus, denseToString)
  }

}

