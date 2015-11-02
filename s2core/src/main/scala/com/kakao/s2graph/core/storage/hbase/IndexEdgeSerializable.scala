package com.kakao.s2graph.core.storage.hbase

import com.kakao.s2graph.core.mysqls.LabelMeta
import com.kakao.s2graph.core.storage.{StorageSerializable, SKeyValue}
import com.kakao.s2graph.core.types.VertexId
import com.kakao.s2graph.core.{GraphUtil, IndexEdge}
import org.apache.hadoop.hbase.util.Bytes

case class IndexEdgeSerializable(indexedEdge: IndexEdge) extends HStorageSerializable {
  import StorageSerializable._

  val label = indexedEdge.label
  val table = label.hbaseTableName.getBytes()
  val cf = HStorageSerializable.edgeCf

  val idxPropsMap = indexedEdge.orders.toMap
  val idxPropsBytes = propsToBytes(indexedEdge.orders)

  /** version 1 and version 2 share same code for serialize row key part */
  override def toKeyValues: Seq[SKeyValue] = {
    val srcIdBytes = VertexId.toSourceVertexId(indexedEdge.srcVertex.id).bytes
    val labelWithDirBytes = indexedEdge.labelWithDir.bytes
    val labelIndexSeqWithIsInvertedBytes = labelOrderSeqWithIsInverted(indexedEdge.labelIndexSeq, isInverted = false)
    val row = Bytes.add(srcIdBytes, labelWithDirBytes, labelIndexSeqWithIsInvertedBytes)

    val tgtIdBytes = VertexId.toTargetVertexId(indexedEdge.tgtVertex.id).bytes
    val qualifier =
      if (indexedEdge.op == GraphUtil.operations("incrementCount")) {
        Bytes.add(idxPropsBytes, tgtIdBytes, Array.fill(1)(indexedEdge.op))
      } else {
        idxPropsMap.get(LabelMeta.toSeq) match {
          case None => Bytes.add(idxPropsBytes, tgtIdBytes)
          case Some(vId) => idxPropsBytes
        }
      }

    val value = propsToKeyValues(indexedEdge.metas.toSeq)
    val kv = SKeyValue(table, row, cf, qualifier, value, indexedEdge.ts)

    Seq(kv)
  }
}