/*
 * Copyright (c) 2020-2023, NVIDIA CORPORATION.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nvidia.spark.rapids

import java.io.File
import java.math.RoundingMode

import scala.collection.mutable.ArrayBuffer

import ai.rapids.cudf.{ColumnVector, ContiguousTable, Cuda, DeviceMemoryBuffer, HostMemoryBuffer, MemoryBuffer, Table}
import com.nvidia.spark.rapids.Arm.withResource
import com.nvidia.spark.rapids.StorageTier.StorageTier
import com.nvidia.spark.rapids.format.TableMeta
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.{spy, verify}
import org.scalatest.FunSuite
import org.scalatest.mockito.MockitoSugar

import org.apache.spark.sql.rapids.RapidsDiskBlockManager
import org.apache.spark.sql.types.{DataType, DecimalType, DoubleType, IntegerType, StringType}

class RapidsDeviceMemoryStoreSuite extends FunSuite with MockitoSugar {
  private def buildTable(): Table = {
    new Table.TestBuilder()
        .column(5, null.asInstanceOf[java.lang.Integer], 3, 1)
        .column("five", "two", null, null)
        .column(5.0D, 2.0D, 3.0D, 1.0D)
        .decimal64Column(-5, RoundingMode.UNNECESSARY, 0, null, -1.4, 10.123)
        .build()
  }

  private def buildTableWithDuplicate(): Table = {
    withResource(ColumnVector.fromInts(5, null.asInstanceOf[java.lang.Integer], 3, 1)) { intCol =>
      withResource(ColumnVector.fromStrings("five", "two", null, null)) { stringCol =>
        withResource(ColumnVector.fromDoubles(5.0, 2.0, 3.0, 1.0)) { doubleCol =>
          // add intCol twice
          new Table(intCol, intCol, stringCol, doubleCol)
        }
      }
    }
  }

  private def buildContiguousTable(): ContiguousTable = {
    withResource(buildTable()) { table =>
      table.contiguousSplit()(0)
    }
  }

  test("add table registers with catalog") {
    withResource(new RapidsDeviceMemoryStore) { store =>
      val catalog = spy(new RapidsBufferCatalog(store))
      val spillPriority = 3
      val bufferId = MockRapidsBufferId(7)
      withResource(buildContiguousTable()) { ct =>
        catalog.addContiguousTable(
          bufferId, ct, spillPriority, false)
      }
      val captor: ArgumentCaptor[RapidsBuffer] = ArgumentCaptor.forClass(classOf[RapidsBuffer])
      verify(catalog).registerNewBuffer(captor.capture())
      val resultBuffer = captor.getValue
      assertResult(bufferId)(resultBuffer.id)
      assertResult(spillPriority)(resultBuffer.getSpillPriority)
    }
  }

  test("a non-contiguous table is spillable and it is handed over to the store") {
    withResource(new RapidsDeviceMemoryStore) { store =>
      val catalog = spy(new RapidsBufferCatalog(store))
      val spillPriority = 3
      val table = buildTable()
      catalog.addTable(table, spillPriority)
      val buffSize = GpuColumnVector.getTotalDeviceMemoryUsed(table)
      assertResult(buffSize)(store.currentSize)
      assertResult(buffSize)(store.currentSpillableSize)
    }
  }

  test("a non-contiguous table becomes non-spillable when batch is obtained") {
    withResource(new RapidsDeviceMemoryStore) { store =>
      val catalog = spy(new RapidsBufferCatalog(store))
      val spillPriority = 3
      val table = buildTable()
      val handle = catalog.addTable(table, spillPriority)
      val types: Array[DataType] =
          Seq(IntegerType, StringType, DoubleType, DecimalType(10, 5)).toArray
      val buffSize = GpuColumnVector.getTotalDeviceMemoryUsed(table)
      assertResult(buffSize)(store.currentSize)
      assertResult(buffSize)(store.currentSpillableSize)
      val batch = withResource(catalog.acquireBuffer(handle)) { rapidsBuffer =>
        rapidsBuffer.getColumnarBatch(types)
      }
      withResource(batch) { _ =>
        assertResult(buffSize)(store.currentSize)
        assertResult(0)(store.currentSpillableSize)
      }
      assertResult(buffSize)(store.currentSpillableSize)
    }
  }

  test("a non-contiguous table is non-spillable until all columns are returned") {
    withResource(new RapidsDeviceMemoryStore) { store =>
      val catalog = spy(new RapidsBufferCatalog(store))
      val spillPriority = 3
      val table = buildTable()
      val handle = catalog.addTable(table, spillPriority)
      val types: Array[DataType] =
        Seq(IntegerType, StringType, DoubleType, DecimalType(10, 5)).toArray
      val buffSize = GpuColumnVector.getTotalDeviceMemoryUsed(table)
      assertResult(buffSize)(store.currentSize)
      assertResult(buffSize)(store.currentSpillableSize)
      // incRefCount all the columns via `batch`
      val batch = withResource(catalog.acquireBuffer(handle)) { rapidsBuffer =>
        rapidsBuffer.getColumnarBatch(types)
      }
      val columns = GpuColumnVector.extractBases(batch)
      withResource(columns.head) { _ =>
        columns.head.incRefCount()
        withResource(batch) { _ =>
          assertResult(buffSize)(store.currentSize)
          assertResult(0)(store.currentSpillableSize)
        }
        // still 0 after the batch is closed, because of the extra incRefCount
        // for columns.head
        assertResult(0)(store.currentSpillableSize)
      }
      // columns.head is closed, so now our RapidsTable is spillable again
      assertResult(buffSize)(store.currentSpillableSize)
    }
  }

  test("an aliased non-contiguous table is not spillable (until closing the alias) ") {
    withResource(new RapidsDeviceMemoryStore) { store =>
      val catalog = spy(new RapidsBufferCatalog(store))
      val spillPriority = 3
      val table = buildTable()
      val handle = catalog.addTable(table, spillPriority)
      val types: Array[DataType] =
        Seq(IntegerType, StringType, DoubleType, DecimalType(10, 5)).toArray
      val buffSize = GpuColumnVector.getTotalDeviceMemoryUsed(table)
      assertResult(buffSize)(store.currentSize)
      assertResult(buffSize)(store.currentSpillableSize)
      val aliasHandle = withResource(catalog.acquireBuffer(handle)) { rapidsBuffer =>
        // extract the batch from the table we added, and add it back as a batch
        withResource(rapidsBuffer.getColumnarBatch(types)) { batch =>
          catalog.addBatch(batch, spillPriority)
        }
      } // we now have two copies in the store
      assertResult(buffSize*2)(store.currentSize)
      assertResult(0)(store.currentSpillableSize)

      aliasHandle.close() // remove the alias

      assertResult(buffSize)(store.currentSize)
      assertResult(buffSize)(store.currentSpillableSize)
    }
  }

  test("an aliased non-contiguous table is not spillable (until closing the original) ") {
    withResource(new RapidsDeviceMemoryStore) { store =>
      val catalog = spy(new RapidsBufferCatalog(store))
      val spillPriority = 3
      val table = buildTable()
      val handle = catalog.addTable(table, spillPriority)
      val types: Array[DataType] =
        Seq(IntegerType, StringType, DoubleType, DecimalType(10, 5)).toArray
      val buffSize = GpuColumnVector.getTotalDeviceMemoryUsed(table)
      assertResult(buffSize)(store.currentSize)
      assertResult(buffSize)(store.currentSpillableSize)
      withResource(catalog.acquireBuffer(handle)) { rapidsBuffer =>
        // extract the batch from the table we added, and add it back as a batch
        withResource(rapidsBuffer.getColumnarBatch(types)) { batch =>
          catalog.addBatch(batch, spillPriority)
        }
      } // we now have two copies in the store
      assertResult(buffSize * 2)(store.currentSize)
      assertResult(0)(store.currentSpillableSize)

      handle.close() // remove the original

      assertResult(buffSize)(store.currentSize)
      assertResult(buffSize)(store.currentSpillableSize)
    }
  }

  test("an non-contiguous table supports duplicated columns") {
    withResource(new RapidsDeviceMemoryStore) { store =>
      val catalog = spy(new RapidsBufferCatalog(store))
      val spillPriority = 3
      val table = buildTableWithDuplicate()
      val handle = catalog.addTable(table, spillPriority)
      val types: Array[DataType] =
        Seq(IntegerType, IntegerType, StringType, DoubleType).toArray
      val buffSize = GpuColumnVector.getTotalDeviceMemoryUsed(table)
      assertResult(buffSize)(store.currentSize)
      assertResult(buffSize)(store.currentSpillableSize)
      withResource(catalog.acquireBuffer(handle)) { rapidsBuffer =>
        // extract the batch from the table we added, and add it back as a batch
        withResource(rapidsBuffer.getColumnarBatch(types)) { batch =>
          catalog.addBatch(batch, spillPriority)
        }
      } // we now have two copies in the store
      assertResult(buffSize * 2)(store.currentSize)
      assertResult(0)(store.currentSpillableSize)

      handle.close() // remove the original

      assertResult(buffSize)(store.currentSize)
      assertResult(buffSize)(store.currentSpillableSize)
    }
  }

  test("a contiguous table is not spillable until the owner closes it") {
    withResource(new RapidsDeviceMemoryStore) { store =>
      val catalog = spy(new RapidsBufferCatalog(store))
      val spillPriority = 3
      val bufferId = MockRapidsBufferId(7)
      val ct = buildContiguousTable()
      val buffSize = ct.getBuffer.getLength
      withResource(ct) { _ =>
        catalog.addContiguousTable(
          bufferId,
          ct,
          spillPriority,
          false)
        assertResult(buffSize)(store.currentSize)
        assertResult(0)(store.currentSpillableSize)
      }
      // after closing the original table, the RapidsBuffer should be spillable
      assertResult(buffSize)(store.currentSize)
      assertResult(buffSize)(store.currentSpillableSize)
    }
  }

  test("a buffer is not spillable until the owner closes columns referencing it") {
    withResource(new RapidsDeviceMemoryStore) { store =>
      val spillPriority = 3
      val bufferId = MockRapidsBufferId(7)
      val ct = buildContiguousTable()
      val buffSize = ct.getBuffer.getLength
      withResource(ct) { _ =>
        val meta = MetaUtils.buildTableMeta(bufferId.tableId, ct)
        withResource(ct) { _ =>
          store.addBuffer(
            bufferId,
            ct.getBuffer,
            meta,
            spillPriority,
            false)
          assertResult(buffSize)(store.currentSize)
          assertResult(0)(store.currentSpillableSize)
        }
      }
      // after closing the original table, the RapidsBuffer should be spillable
      assertResult(buffSize)(store.currentSize)
      assertResult(buffSize)(store.currentSpillableSize)
    }
  }

  test("a buffer is not spillable when the underlying device buffer is obtained from it") {
    withResource(new RapidsDeviceMemoryStore) { store =>
      val spillPriority = 3
      val bufferId = MockRapidsBufferId(7)
      val ct = buildContiguousTable()
      val buffSize = ct.getBuffer.getLength
      val buffer = withResource(ct) { _ =>
        val meta = MetaUtils.buildTableMeta(bufferId.tableId, ct)
        val buffer = store.addBuffer(
          bufferId,
          ct.getBuffer,
          meta,
          spillPriority,
          false)
        assertResult(buffSize)(store.currentSize)
        assertResult(0)(store.currentSpillableSize)
        buffer
      }

      // after closing the original table, the RapidsBuffer should be spillable
      assertResult(buffSize)(store.currentSize)
      assertResult(buffSize)(store.currentSpillableSize)

      // if a device memory buffer is obtained from the buffer, it is no longer spillable
      withResource(buffer.getDeviceMemoryBuffer) { deviceBuffer =>
        assertResult(buffSize)(store.currentSize)
        assertResult(0)(store.currentSpillableSize)
      }

      // once the DeviceMemoryBuffer is closed, the RapidsBuffer should be spillable again
      assertResult(buffSize)(store.currentSpillableSize)
    }
  }

  test("add buffer registers with catalog") {
    withResource(new RapidsDeviceMemoryStore) { store =>
      val catalog = spy(new RapidsBufferCatalog(store))
      val spillPriority = 3
      val bufferId = MockRapidsBufferId(7)
      val meta = withResource(buildContiguousTable()) { ct =>
        val meta = MetaUtils.buildTableMeta(bufferId.tableId, ct)
        withResource(ct) { _ =>
          catalog.addBuffer(
            bufferId,
            ct.getBuffer,
            meta,
            spillPriority,
            false)
        }
        meta
      }
      val captor: ArgumentCaptor[RapidsBuffer] = ArgumentCaptor.forClass(classOf[RapidsBuffer])
      verify(catalog).registerNewBuffer(captor.capture())
      val resultBuffer = captor.getValue
      assertResult(bufferId)(resultBuffer.id)
      assertResult(spillPriority)(resultBuffer.getSpillPriority)
      assertResult(meta)(resultBuffer.meta)
    }
  }

  test("get memory buffer") {
    withResource(new RapidsDeviceMemoryStore) { store =>
      val catalog = spy(new RapidsBufferCatalog(store))
      val bufferId = MockRapidsBufferId(7)
      withResource(buildContiguousTable()) { ct =>
        withResource(HostMemoryBuffer.allocate(ct.getBuffer.getLength)) { expectedHostBuffer =>
          expectedHostBuffer.copyFromDeviceBuffer(ct.getBuffer)
          val meta = MetaUtils.buildTableMeta(bufferId.tableId, ct)
          val handle = withResource(ct) { _ =>
            catalog.addBuffer(
              bufferId,
              ct.getBuffer,
              meta,
              initialSpillPriority = 3,
              needsSync = false)
          }
          withResource(catalog.acquireBuffer(handle)) { buffer =>
            withResource(buffer.getMemoryBuffer.asInstanceOf[DeviceMemoryBuffer]) { devbuf =>
              withResource(HostMemoryBuffer.allocate(devbuf.getLength)) { actualHostBuffer =>
                actualHostBuffer.copyFromDeviceBuffer(devbuf)
                assertResult(expectedHostBuffer.asByteBuffer())(actualHostBuffer.asByteBuffer())
              }
            }
          }
        }
      }
    }
  }

  test("get column batch") {
    withResource(new RapidsDeviceMemoryStore) { store =>
      val catalog = new RapidsBufferCatalog(store)
      val sparkTypes = Array[DataType](IntegerType, StringType, DoubleType,
        DecimalType(ai.rapids.cudf.DType.DECIMAL64_MAX_PRECISION, 5))
      val bufferId = MockRapidsBufferId(7)
      withResource(buildContiguousTable()) { ct =>
        withResource(GpuColumnVector.from(ct.getTable, sparkTypes)) {
          expectedBatch =>
            val meta = MetaUtils.buildTableMeta(bufferId.tableId, ct)
            val handle = withResource(ct) { _ =>
              catalog.addBuffer(
                bufferId,
                ct.getBuffer,
                meta,
                initialSpillPriority = 3,
                false)
            }
            withResource(catalog.acquireBuffer(handle)) { buffer =>
              withResource(buffer.getColumnarBatch(sparkTypes)) { actualBatch =>
                TestUtils.compareBatches(expectedBatch, actualBatch)
              }
            }
        }
      }
    }
  }

  test("size statistics") {

    withResource(new RapidsDeviceMemoryStore) { store =>
      val catalog = new RapidsBufferCatalog(store)
      assertResult(0)(store.currentSize)
      val bufferSizes = new Array[Long](2)
      val bufferHandles = new Array[RapidsBufferHandle](2)
      bufferSizes.indices.foreach { i =>
        withResource(buildContiguousTable()) { ct =>
          bufferSizes(i) = ct.getBuffer.getLength
          // store takes ownership of the table
          bufferHandles(i) =
            catalog.addContiguousTable(
              MockRapidsBufferId(i),
              ct,
              initialSpillPriority = 0,
              false)
        }
        assertResult(bufferSizes.take(i+1).sum)(store.currentSize)
      }
      bufferHandles(0).close()
      assertResult(bufferSizes(1))(store.currentSize)
      bufferHandles(1).close()
      assertResult(0)(store.currentSize)
    }
  }

  test("spill") {
    val spillStore = new MockSpillStore
    val spillPriorities = Array(0, -1, 2)
    val bufferSizes = new Array[Long](spillPriorities.length)
    withResource(new RapidsDeviceMemoryStore) { store =>
      val catalog = new RapidsBufferCatalog(store)
      store.setSpillStore(spillStore)
      spillPriorities.indices.foreach { i =>
        withResource(buildContiguousTable()) { ct =>
          bufferSizes(i) = ct.getBuffer.getLength
          // store takes ownership of the table
          catalog.addContiguousTable(
            MockRapidsBufferId(i), ct, spillPriorities(i),
            false)
        }
      }
      assert(spillStore.spilledBuffers.isEmpty)

      // asking to spill 0 bytes should not spill
      val sizeBeforeSpill = store.currentSize
      catalog.synchronousSpill(store, sizeBeforeSpill)
      assert(spillStore.spilledBuffers.isEmpty)
      assertResult(sizeBeforeSpill)(store.currentSize)
      catalog.synchronousSpill(store, sizeBeforeSpill + 1)
      assert(spillStore.spilledBuffers.isEmpty)
      assertResult(sizeBeforeSpill)(store.currentSize)

      // spilling 1 byte should force one buffer to spill in priority order
      catalog.synchronousSpill(store, sizeBeforeSpill - 1)
      assertResult(1)(spillStore.spilledBuffers.length)
      assertResult(bufferSizes.drop(1).sum)(store.currentSize)
      assertResult(1)(spillStore.spilledBuffers(0).tableId)

      // spilling to zero should force all buffers to spill in priority order
      catalog.synchronousSpill(store, 0)
      assertResult(3)(spillStore.spilledBuffers.length)
      assertResult(0)(store.currentSize)
      assertResult(0)(spillStore.spilledBuffers(1).tableId)
      assertResult(2)(spillStore.spilledBuffers(2).tableId)
    }
  }

  case class MockRapidsBufferId(tableId: Int) extends RapidsBufferId {
    override def getDiskPath(diskBlockManager: RapidsDiskBlockManager): File =
      throw new UnsupportedOperationException
  }

  class MockSpillStore extends RapidsBufferStore(StorageTier.HOST) {
    val spilledBuffers = new ArrayBuffer[RapidsBufferId]

    override protected def createBuffer(
        b: RapidsBuffer,
        s: Cuda.Stream): RapidsBufferBase = {
      spilledBuffers += b.id
      new MockRapidsBuffer(b.id, b.getPackedSizeBytes, b.meta, b.getSpillPriority)
    }

    class MockRapidsBuffer(id: RapidsBufferId, size: Long, meta: TableMeta, spillPriority: Long)
        extends RapidsBufferBase(id, meta, spillPriority) {
      override protected def releaseResources(): Unit = {}

      override val storageTier: StorageTier = StorageTier.HOST

      override def getMemoryBuffer: MemoryBuffer =
        throw new UnsupportedOperationException

      /** The size of this buffer in bytes. */
      override def getMemoryUsedBytes: Long = size
    }
  }
}
