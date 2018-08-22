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

package org.apache.spark.graphx.impl

import scala.reflect.{classTag, ClassTag}

import org.apache.spark.graphx._
import org.apache.spark.graphx.util.collection.GraphXPrimitiveKeyOpenHashMap
import org.apache.spark.util.collection.BitSet

/**
 * A collection of edges, along with referenced vertex attributes and an optional active vertex set
 * for filtering computation on the edges.
  *
  * 边集合,以及引用的顶点属性和可选的活动顶点集,用于过滤边缘上的计算
 *
 * The edges are stored in columnar format in `localSrcIds`, `localDstIds`, and `data`. All
 * referenced global vertex ids are mapped to a compact set of local vertex ids according to the
 * `global2local` map. Each local vertex id is a valid index into `vertexAttrs`, which stores the
 * corresponding vertex attribute, and `local2global`, which stores the reverse mapping to global
 * vertex id. The global vertex ids that are active are optionally stored in `activeSet`.
 *
 * The edges are clustered by source vertex id, and the mapping from global vertex id to the index
 * of the corresponding edge cluster is stored in `index`.
 *
 * @tparam ED the edge attribute type
 * @tparam VD the vertex attribute type
 *
 * @param localSrcIds the local source vertex id of each edge as an index into `local2global` and
 *   `vertexAttrs`
 * @param localDstIds the local destination vertex id of each edge as an index into `local2global`
 *   and `vertexAttrs`
 * @param data the attribute associated with each edge
 * @param index a clustered index on source vertex id as a map from each global source vertex id to
 *   the offset in the edge arrays where the cluster for that vertex id begins
 * @param global2local a map from referenced vertex ids to local ids which index into vertexAttrs
 * @param local2global an array of global vertex ids where the offsets are local vertex ids
 * @param vertexAttrs an array of vertex attributes where the offsets are local vertex ids
 * @param activeSet an optional active vertex set for filtering computation on the edges
 */
private[graphx]
class EdgePartition[
    @specialized(Char, Int, Boolean, Byte, Long, Float, Double) ED: ClassTag, VD: ClassTag](
    localSrcIds: Array[Int],
    localDstIds: Array[Int],
    data: Array[ED],
    index: GraphXPrimitiveKeyOpenHashMap[VertexId, Int],
    global2local: GraphXPrimitiveKeyOpenHashMap[VertexId, Int],
    local2global: Array[VertexId],
    vertexAttrs: Array[VD],
    activeSet: Option[VertexSet])
  extends Serializable {

  /** No-arg constructor for serialization. 用于序列化的无参数构造函数*/
  private def this() = this(null, null, null, null, null, null, null, null)

  /** Return a new `EdgePartition` with the specified edge data. 返回带有指定边数据的新`EdgePartition`*/
  def withData[ED2: ClassTag](data: Array[ED2]): EdgePartition[ED2, VD] = {
    new EdgePartition(
      localSrcIds, localDstIds, data, index, global2local, local2global, vertexAttrs, activeSet)
  }

  /** Return a new `EdgePartition` with the specified active set, provided as an iterator.
    * 返回一个带有指定活动集的新`EdgePartition`,作为迭代器提供*/
  def withActiveSet(iter: Iterator[VertexId]): EdgePartition[ED, VD] = {
    val activeSet = new VertexSet
    while (iter.hasNext) { activeSet.add(iter.next()) }
    new EdgePartition(
      localSrcIds, localDstIds, data, index, global2local, local2global, vertexAttrs,
      Some(activeSet))
  }

  /** Return a new `EdgePartition` with updates to vertex attributes specified in `iter`.
    * 返回一个新的`EdgePartition`，其中包含对`iter`中指定的顶点属性的更新*/
  def updateVertices(iter: Iterator[(VertexId, VD)]): EdgePartition[ED, VD] = {
    val newVertexAttrs = new Array[VD](vertexAttrs.length)
    System.arraycopy(vertexAttrs, 0, newVertexAttrs, 0, vertexAttrs.length)
    while (iter.hasNext) {
      val kv = iter.next()
      newVertexAttrs(global2local(kv._1)) = kv._2
    }
    new EdgePartition(
      localSrcIds, localDstIds, data, index, global2local, local2global, newVertexAttrs,
      activeSet)
  }

  /** Return a new `EdgePartition` without any locally cached vertex attributes.
    * 返回一个没有任何本地缓存顶点属性的新`EdgePartition` */
  def withoutVertexAttributes[VD2: ClassTag](): EdgePartition[ED, VD2] = {
    val newVertexAttrs = new Array[VD2](vertexAttrs.length)
    new EdgePartition(
      localSrcIds, localDstIds, data, index, global2local, local2global, newVertexAttrs,
      activeSet)
  }

  @inline private def srcIds(pos: Int): VertexId = local2global(localSrcIds(pos))

  @inline private def dstIds(pos: Int): VertexId = local2global(localDstIds(pos))

  @inline private def attrs(pos: Int): ED = data(pos)

  /** Look up vid in activeSet, throwing an exception if it is None.
    * 在activeSet中查找vid，如果为None则抛出异常*/
  def isActive(vid: VertexId): Boolean = {
    activeSet.get.contains(vid)
  }

  /** The number of active vertices, if any exist.
    * 活动顶点的数量(如果存在)*/
  def numActives: Option[Int] = activeSet.map(_.size)

  /**
   * Reverse all the edges in this partition.
    * 反转此分区中的所有边
   *
   * @return a new edge partition with all edges reversed.
   */
  def reverse: EdgePartition[ED, VD] = {
    val builder = new ExistingEdgePartitionBuilder[ED, VD](
      global2local, local2global, vertexAttrs, activeSet, size)
    var i = 0
    while (i < size) {
      val localSrcId = localSrcIds(i)
      val localDstId = localDstIds(i)
      val srcId = local2global(localSrcId)
      val dstId = local2global(localDstId)
      val attr = data(i)
      builder.add(dstId, srcId, localDstId, localSrcId, attr)
      i += 1
    }
    builder.toEdgePartition
  }

  /**
   * Construct a new edge partition by applying the function f to all
   * edges in this partition.
    * 通过将函数f应用于此分区中的所有边来构造新的边分区
   *
   * Be careful not to keep references to the objects passed to `f`.
   * To improve GC performance the same object is re-used for each call.
    *
    * 注意不要保留对传递给`f`的对象的引用,
    * 为了提高GC性能,每次调用都会重复使用相同的对象
   *
   * @param f a function from an edge to a new attribute
   * @tparam ED2 the type of the new attribute
   * @return a new edge partition with the result of the function `f`
   *         applied to each edge
   */
  def map[ED2: ClassTag](f: Edge[ED] => ED2): EdgePartition[ED2, VD] = {
    val newData = new Array[ED2](data.size)
    val edge = new Edge[ED]()
    val size = data.size
    var i = 0
    while (i < size) {
      edge.srcId = srcIds(i)
      edge.dstId = dstIds(i)
      edge.attr = data(i)
      newData(i) = f(edge)
      i += 1
    }
    this.withData(newData)
  }

  /**
   * Construct a new edge partition by using the edge attributes
   * contained in the iterator.
   * 使用迭代器中包含的边缘属性构造新的边缘分区
   * @note The input iterator should return edge attributes in the
   * order of the edges returned by `EdgePartition.iterator` and
   * should return attributes equal to the number of edges.
   *
   * @param iter an iterator for the new attribute values
   * @tparam ED2 the type of the new attribute
   * @return a new edge partition with the attribute values replaced
   */
  def map[ED2: ClassTag](iter: Iterator[ED2]): EdgePartition[ED2, VD] = {
    // Faster than iter.toArray, because the expected size is known.
    val newData = new Array[ED2](data.size)
    var i = 0
    while (iter.hasNext) {
      newData(i) = iter.next()
      i += 1
    }
    assert(newData.size == i)
    this.withData(newData)
  }

  /**
   * Construct a new edge partition containing only the edges matching `epred` and where both
   * vertices match `vpred`.
    *
    * 构造一个新的边分区,其中只包含与`epred`匹配的边,并且两个顶点都匹配`vpred`
   */
  def filter(
      epred: EdgeTriplet[VD, ED] => Boolean,
      vpred: (VertexId, VD) => Boolean): EdgePartition[ED, VD] = {
    val builder = new ExistingEdgePartitionBuilder[ED, VD](
      global2local, local2global, vertexAttrs, activeSet)
    var i = 0
    while (i < size) {
      // The user sees the EdgeTriplet, so we can't reuse it and must create one per edge.
      //用户看到EdgeTriplet,因此我们无法重复使用它,并且必须为每个边创建一个
      val localSrcId = localSrcIds(i)
      val localDstId = localDstIds(i)
      val et = new EdgeTriplet[VD, ED]
      et.srcId = local2global(localSrcId)
      et.dstId = local2global(localDstId)
      et.srcAttr = vertexAttrs(localSrcId)
      et.dstAttr = vertexAttrs(localDstId)
      et.attr = data(i)
      if (vpred(et.srcId, et.srcAttr) && vpred(et.dstId, et.dstAttr) && epred(et)) {
        builder.add(et.srcId, et.dstId, localSrcId, localDstId, et.attr)
      }
      i += 1
    }
    builder.toEdgePartition
  }

  /**
   * Apply the function f to all edges in this partition.
    * 将函数f应用于此分区中的所有边
   *
   * @param f an external state mutating user defined function.
   */
  def foreach(f: Edge[ED] => Unit) {
    iterator.foreach(f)
  }

  /**
   * Merge all the edges with the same src and dest id into a single
   * edge using the `merge` function
    * 使用`merge`函数将具有相同src和dest id的所有边合并为单个边
   *
   * @param merge a commutative associative merge operation
   * @return a new edge partition without duplicate edges
   */
  def groupEdges(merge: (ED, ED) => ED): EdgePartition[ED, VD] = {
    val builder = new ExistingEdgePartitionBuilder[ED, VD](
      global2local, local2global, vertexAttrs, activeSet)
    var currSrcId: VertexId = null.asInstanceOf[VertexId]
    var currDstId: VertexId = null.asInstanceOf[VertexId]
    var currLocalSrcId = -1
    var currLocalDstId = -1
    var currAttr: ED = null.asInstanceOf[ED]
    // Iterate through the edges, accumulating runs of identical edges using the curr* variables and
    // releasing them to the builder when we see the beginning of the next run
    //通过边缘迭代,使用curr *变量累积相同边的运行,并在我们看到下一次运行的开始时将它们释放到构建器
    var i = 0
    while (i < size) {
      if (i > 0 && currSrcId == srcIds(i) && currDstId == dstIds(i)) {
        // This edge should be accumulated into the existing run
        //此边缘应累积到现有运行中
        currAttr = merge(currAttr, data(i))
      } else {
        // This edge starts a new run of edges
        //此边缘开始新的边缘运行
        if (i > 0) {
          // First release the existing run to the builder
          //首先将现有运行发布到构建器
          builder.add(currSrcId, currDstId, currLocalSrcId, currLocalDstId, currAttr)
        }
        // Then start accumulating for a new run
        //然后开始累积新的运行
        currSrcId = srcIds(i)
        currDstId = dstIds(i)
        currLocalSrcId = localSrcIds(i)
        currLocalDstId = localDstIds(i)
        currAttr = data(i)
      }
      i += 1
    }
    // Finally, release the last accumulated run
    //最后,释放最后累积的运行
    if (size > 0) {
      builder.add(currSrcId, currDstId, currLocalSrcId, currLocalDstId, currAttr)
    }
    builder.toEdgePartition
  }

  /**
   * Apply `f` to all edges present in both `this` and `other` and return a new `EdgePartition`
   * containing the resulting edges.
    * 将`f`应用于'this`和`other`中的所有边，并返回一个包含结果边的新`EdgePartition`
   *
   * If there are multiple edges with the same src and dst in `this`, `f` will be invoked once for
   * each edge, but each time it may be invoked on any corresponding edge in `other`.
    *
    * 如果在`this`中有多个具有相同src和dst的边,则每个边将调用一次`f`,但每次可以在`other`中的任何相应边上调用它
   *
   * If there are multiple edges with the same src and dst in `other`, `f` will only be invoked
   * once.
   */
  def innerJoin[ED2: ClassTag, ED3: ClassTag]
      (other: EdgePartition[ED2, _])
      (f: (VertexId, VertexId, ED, ED2) => ED3): EdgePartition[ED3, VD] = {
    val builder = new ExistingEdgePartitionBuilder[ED3, VD](
      global2local, local2global, vertexAttrs, activeSet)
    var i = 0
    var j = 0
    // For i = index of each edge in `this`...
    while (i < size && j < other.size) {
      val srcId = this.srcIds(i)
      val dstId = this.dstIds(i)
      // ... forward j to the index of the corresponding edge in `other`, and...
      while (j < other.size && other.srcIds(j) < srcId) { j += 1 }
      if (j < other.size && other.srcIds(j) == srcId) {
        while (j < other.size && other.srcIds(j) == srcId && other.dstIds(j) < dstId) { j += 1 }
        if (j < other.size && other.srcIds(j) == srcId && other.dstIds(j) == dstId) {
          // ... run `f` on the matching edge
          builder.add(srcId, dstId, localSrcIds(i), localDstIds(i),
            f(srcId, dstId, this.data(i), other.attrs(j)))
        }
      }
      i += 1
    }
    builder.toEdgePartition
  }

  /**
   * The number of edges in this partition
   * 此分区中的边数
   * @return size of the partition
   */
  val size: Int = localSrcIds.size

  /** The number of unique source vertices in the partition.
    * 分区中唯一源顶点的数量*/
  def indexSize: Int = index.size

  /**
   * Get an iterator over the edges in this partition.
   * 获取此分区边缘的迭代器
   * Be careful not to keep references to the objects from this iterator.
    * 注意不要在此迭代器中保留对象的引用
   * To improve GC performance the same object is re-used in `next()`.
   *
   * @return an iterator over edges in the partition
   */
  def iterator: Iterator[Edge[ED]] = new Iterator[Edge[ED]] {
    private[this] val edge = new Edge[ED]
    private[this] var pos = 0

    override def hasNext: Boolean = pos < EdgePartition.this.size

    override def next(): Edge[ED] = {
      edge.srcId = srcIds(pos)
      edge.dstId = dstIds(pos)
      edge.attr = data(pos)
      pos += 1
      edge
    }
  }

  /**
   * Get an iterator over the edge triplets in this partition.
    * 获取此分区中边缘三元组的迭代器
   *
   * It is safe to keep references to the objects from this iterator.
    * 从这个迭代器继续引用对象是安全的
   */
  def tripletIterator(
      includeSrc: Boolean = true, includeDst: Boolean = true)
      : Iterator[EdgeTriplet[VD, ED]] = new Iterator[EdgeTriplet[VD, ED]] {
    private[this] var pos = 0

    override def hasNext: Boolean = pos < EdgePartition.this.size

    override def next(): EdgeTriplet[VD, ED] = {
      val triplet = new EdgeTriplet[VD, ED]
      val localSrcId = localSrcIds(pos)
      val localDstId = localDstIds(pos)
      triplet.srcId = local2global(localSrcId)
      triplet.dstId = local2global(localDstId)
      if (includeSrc) {
        triplet.srcAttr = vertexAttrs(localSrcId)
      }
      if (includeDst) {
        triplet.dstAttr = vertexAttrs(localDstId)
      }
      triplet.attr = data(pos)
      pos += 1
      triplet
    }
  }

  /**
   * Send messages along edges and aggregate them at the receiving vertices. Implemented by scanning
   * all edges sequentially.
    *
    * 沿边发送消息并在接收顶点聚合它们,通过顺序扫描所有边缘来实现
   *
   * @param sendMsg generates messages to neighboring vertices of an edge
   * @param mergeMsg the combiner applied to messages destined to the same vertex
   * @param tripletFields which triplet fields `sendMsg` uses
   * @param activeness criteria for filtering edges based on activeness
   *
   * @return iterator aggregated messages keyed by the receiving vertex id
   */
  def aggregateMessagesEdgeScan[A: ClassTag](
      sendMsg: EdgeContext[VD, ED, A] => Unit,
      mergeMsg: (A, A) => A,
      tripletFields: TripletFields,
      activeness: EdgeActiveness): Iterator[(VertexId, A)] = {
    val aggregates = new Array[A](vertexAttrs.length)
    val bitset = new BitSet(vertexAttrs.length)

    var ctx = new AggregatingEdgeContext[VD, ED, A](mergeMsg, aggregates, bitset)
    var i = 0
    while (i < size) {
      val localSrcId = localSrcIds(i)
      val srcId = local2global(localSrcId)
      val localDstId = localDstIds(i)
      val dstId = local2global(localDstId)
      val edgeIsActive =
        if (activeness == EdgeActiveness.Neither) true
        else if (activeness == EdgeActiveness.SrcOnly) isActive(srcId)
        else if (activeness == EdgeActiveness.DstOnly) isActive(dstId)
        else if (activeness == EdgeActiveness.Both) isActive(srcId) && isActive(dstId)
        else if (activeness == EdgeActiveness.Either) isActive(srcId) || isActive(dstId)
        else throw new Exception("unreachable")
      if (edgeIsActive) {
        val srcAttr = if (tripletFields.useSrc) vertexAttrs(localSrcId) else null.asInstanceOf[VD]
        val dstAttr = if (tripletFields.useDst) vertexAttrs(localDstId) else null.asInstanceOf[VD]
        ctx.set(srcId, dstId, localSrcId, localDstId, srcAttr, dstAttr, data(i))
        sendMsg(ctx)
      }
      i += 1
    }

    bitset.iterator.map { localId => (local2global(localId), aggregates(localId)) }
  }

  /**
   * Send messages along edges and aggregate them at the receiving vertices. Implemented by
   * filtering the source vertex index, then scanning each edge cluster.
    * 沿边发送消息并在接收顶点聚合它们,通过过滤源顶点索引实现,然后扫描每个边缘集群
   *
   * @param sendMsg generates messages to neighboring vertices of an edge
   * @param mergeMsg the combiner applied to messages destined to the same vertex
   * @param tripletFields which triplet fields `sendMsg` uses
   * @param activeness criteria for filtering edges based on activeness
   *
   * @return iterator aggregated messages keyed by the receiving vertex id
   */
  def aggregateMessagesIndexScan[A: ClassTag](
      sendMsg: EdgeContext[VD, ED, A] => Unit,
      mergeMsg: (A, A) => A,
      tripletFields: TripletFields,
      activeness: EdgeActiveness): Iterator[(VertexId, A)] = {
    val aggregates = new Array[A](vertexAttrs.length)
    val bitset = new BitSet(vertexAttrs.length)

    var ctx = new AggregatingEdgeContext[VD, ED, A](mergeMsg, aggregates, bitset)
    index.iterator.foreach { cluster =>
      val clusterSrcId = cluster._1
      val clusterPos = cluster._2
      val clusterLocalSrcId = localSrcIds(clusterPos)

      val scanCluster =
        if (activeness == EdgeActiveness.Neither) true
        else if (activeness == EdgeActiveness.SrcOnly) isActive(clusterSrcId)
        else if (activeness == EdgeActiveness.DstOnly) true
        else if (activeness == EdgeActiveness.Both) isActive(clusterSrcId)
        else if (activeness == EdgeActiveness.Either) true
        else throw new Exception("unreachable")

      if (scanCluster) {
        var pos = clusterPos
        val srcAttr =
          if (tripletFields.useSrc) vertexAttrs(clusterLocalSrcId) else null.asInstanceOf[VD]
        ctx.setSrcOnly(clusterSrcId, clusterLocalSrcId, srcAttr)
        while (pos < size && localSrcIds(pos) == clusterLocalSrcId) {
          val localDstId = localDstIds(pos)
          val dstId = local2global(localDstId)
          val edgeIsActive =
            if (activeness == EdgeActiveness.Neither) true
            else if (activeness == EdgeActiveness.SrcOnly) true
            else if (activeness == EdgeActiveness.DstOnly) isActive(dstId)
            else if (activeness == EdgeActiveness.Both) isActive(dstId)
            else if (activeness == EdgeActiveness.Either) isActive(clusterSrcId) || isActive(dstId)
            else throw new Exception("unreachable")
          if (edgeIsActive) {
            val dstAttr =
              if (tripletFields.useDst) vertexAttrs(localDstId) else null.asInstanceOf[VD]
            ctx.setRest(dstId, localDstId, dstAttr, data(pos))
            sendMsg(ctx)
          }
          pos += 1
        }
      }
    }

    bitset.iterator.map { localId => (local2global(localId), aggregates(localId)) }
  }
}

private class AggregatingEdgeContext[VD, ED, A](
    mergeMsg: (A, A) => A,
    aggregates: Array[A],
    bitset: BitSet)
  extends EdgeContext[VD, ED, A] {

  private[this] var _srcId: VertexId = _
  private[this] var _dstId: VertexId = _
  private[this] var _localSrcId: Int = _
  private[this] var _localDstId: Int = _
  private[this] var _srcAttr: VD = _
  private[this] var _dstAttr: VD = _
  private[this] var _attr: ED = _

  def set(
      srcId: VertexId, dstId: VertexId,
      localSrcId: Int, localDstId: Int,
      srcAttr: VD, dstAttr: VD,
      attr: ED) {
    _srcId = srcId
    _dstId = dstId
    _localSrcId = localSrcId
    _localDstId = localDstId
    _srcAttr = srcAttr
    _dstAttr = dstAttr
    _attr = attr
  }

  def setSrcOnly(srcId: VertexId, localSrcId: Int, srcAttr: VD) {
    _srcId = srcId
    _localSrcId = localSrcId
    _srcAttr = srcAttr
  }

  def setRest(dstId: VertexId, localDstId: Int, dstAttr: VD, attr: ED) {
    _dstId = dstId
    _localDstId = localDstId
    _dstAttr = dstAttr
    _attr = attr
  }

  override def srcId: VertexId = _srcId
  override def dstId: VertexId = _dstId
  override def srcAttr: VD = _srcAttr
  override def dstAttr: VD = _dstAttr
  override def attr: ED = _attr

  override def sendToSrc(msg: A) {
    send(_localSrcId, msg)
  }
  override def sendToDst(msg: A) {
    send(_localDstId, msg)
  }

  @inline private def send(localId: Int, msg: A) {
    if (bitset.get(localId)) {
      aggregates(localId) = mergeMsg(aggregates(localId), msg)
    } else {
      aggregates(localId) = msg
      bitset.set(localId)
    }
  }
}
