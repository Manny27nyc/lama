package co.ledger.lama.bitcoin.interpreter

import java.util.UUID

import co.ledger.lama.bitcoin.common.models.Explorer._
import co.ledger.lama.bitcoin.common.models.Service._
import co.ledger.lama.common.utils.IOAssertion
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

class QueriesIT extends AnyFlatSpecLike with Matchers with TestResources {

  val block = Block(
    "00000000000000000008c76a28e115319fb747eb29a7e0794526d0fe47608379",
    570153,
    "2019-04-04 10:03:22"
  )

  val accountId: UUID = UUID.fromString("b723c553-3a9a-4130-8883-ee2f6c2f9201")

  val outputs = List(
    Output(0, 50000, "1DtwACvd338XtHBFYJRVKRLxviD7YtYADa", "script"),
    Output(1, 9434, "1LK8UbiRwUzC8KFEbMKvgbvriM9zLMce3C", "script")
  )
  val inputs = List(
    DefaultInput(
      "0f38e5f1b12078495a9e80c6e0d77af3d674cfe6096bb6e7909993a53b6e8386",
      0,
      0,
      80000,
      "1LD1pARePgXXyZA1J3EyvRtB82vxENs5wQ",
      "script",
      Seq(),
      4294967295L
    )
  )
  val transactionToInsert: Transaction =
    Transaction(
      "txId",
      "a8a935c6bc2bd8b3a7c20f107a9eb5f10a315ce27de9d72f3f4e27ac9ec1eb1f",
      "",
      0,
      20566,
      inputs,
      outputs,
      block,
      1
    )

  "transaction saved in db" should "be returned and populated by fetch" in IOAssertion {
    setup() *>
      appResources.use { db =>
        for {
          _   <- QueryUtils.saveBlock(db, block)
          _   <- QueryUtils.saveBlock(db, block)                       // check upsert (shouldn't throw error)
          _   <- QueryUtils.saveTx(db, transactionToInsert, accountId)
          _   <- QueryUtils.saveTx(db, transactionToInsert, accountId) // check upsert
          txO <- QueryUtils.fetchTx(db, accountId, transactionToInsert.hash)
        } yield {

          val tx = txO.get

          tx.id shouldBe transactionToInsert.id
          tx.hash shouldBe transactionToInsert.hash

          tx.inputs should have size 1
          tx.inputs.head.value shouldBe 80000

          tx.outputs should have size 2
          tx.outputs.filter(_.outputIndex == 0).head.value shouldBe 50000

        }
      }
  }

  val operation: Operation = Operation(
    accountId,
    transactionToInsert.hash,
    None,
    Sent,
    transactionToInsert.inputs.collect {
      case i: DefaultInput => i.value
    }.sum,
    block.time
  )

  "operation saved in db" should "be fetched" in IOAssertion {
    setup() *>
      appResources.use { db =>
        for {
          _  <- QueryUtils.saveBlock(db, block)
          _  <- QueryUtils.saveTx(db, transactionToInsert, accountId)
          _  <- QueryUtils.saveOp(db, operation)
          op <- QueryUtils.fetchOps(db, accountId)
        } yield {
          op should have size 1
          op.head should be(operation)
        }
      }
  }

}