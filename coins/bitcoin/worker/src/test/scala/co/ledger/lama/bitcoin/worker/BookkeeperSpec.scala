package co.ledger.lama.bitcoin.worker

import cats.effect.{ContextShift, IO, Timer}
import co.ledger.lama.bitcoin.common.clients.grpc.mocks.InterpreterClientMock
import co.ledger.lama.bitcoin.common.clients.http.ExplorerClient
import co.ledger.lama.bitcoin.common.clients.http.ExplorerClient.Address
import co.ledger.lama.bitcoin.common.clients.http.mocks.ExplorerClientMock
import co.ledger.lama.bitcoin.common.models.explorer.UnconfirmedTransaction
import co.ledger.lama.bitcoin.worker.services.{Bookkeeper, Keychain}
import co.ledger.lama.common.models.Coin
import co.ledger.lama.common.models.Coin.Btc
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.UUID
import scala.concurrent.ExecutionContext

class BookkeeperSpec extends AnyFlatSpec with Matchers {

  implicit val cs: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  implicit val t: Timer[IO]         = IO.timer(ExecutionContext.global)

  def explorerClient(
      mempool: Map[Address, List[UnconfirmedTransaction]]
  ): Coin => ExplorerClient =
    _ => new ExplorerClientMock(blockchain = Map.empty, mempool)
  val accountId             = UUID.randomUUID()
  val keychainId            = UUID.randomUUID()
  val usedAndFreshAddresses = LazyList.from(1).map(_.toString).take(40)

  "Bookkeeper.recordUnconfirmedTransactions" should "return the currently used addresses of the mempool by an account" in {

    val bookkeeper = new Bookkeeper(
      new Keychain(KeychainFixture.keychainClient(usedAndFreshAddresses)),
      explorerClient(
        mempool = usedAndFreshAddresses
          .slice(10, 13)
          .map { address =>
            address -> List(
              TransactionFixture.transfer(fromAddress = address)
            )
          }
          .toMap
      ),
      new InterpreterClientMock,
      maxTxsToSavePerBatch = 100,
      maxConcurrent = 1
    )

    val batchResults = bookkeeper
      .record[UnconfirmedTransaction](
        Btc,
        accountId,
        keychainId,
        None
      )
      .compile
      .toList
      .unsafeRunSync()

    batchResults should have size 1
    batchResults.head.addresses.map(_.accountAddress) should be(List("11", "12", "13"))
  }

  it should "add all transactions referencing an account's address" in {

    val bookkeeper = usedAndFreshAddresses.drop(10) match {
      case a +: b +: c +: _ =>
        Map(
          a -> List(TransactionFixture.transfer(fromAddress = a)),
          b -> List(TransactionFixture.receive(toAddress = b)),
          c -> List(TransactionFixture.transfer(fromAddress = c))
        )
    }

    val crawler = new Bookkeeper(
      new Keychain(KeychainFixture.keychainClient(usedAndFreshAddresses)),
      explorerClient(bookkeeper),
      new InterpreterClientMock,
      maxTxsToSavePerBatch = 100,
      maxConcurrent = 1
    )

    val batchResults = crawler
      .record[UnconfirmedTransaction](
        Btc,
        accountId,
        keychainId,
        None
      )
      .compile
      .toList
      .unsafeRunSync()

    batchResults should have size 1
    batchResults.head.transactions should contain only (bookkeeper.values.toList.flatten: _*)
  }

  it should "mark all met addresses as used in the keychain" in {

    val keychainClient = KeychainFixture.keychainClient(usedAndFreshAddresses)
    val keychain       = new Keychain(keychainClient)

    val bookkeeper = new Bookkeeper(
      keychain,
      explorerClient(
        usedAndFreshAddresses
          .slice(10, 17)
          .map { address =>
            address -> List(
              TransactionFixture.transfer(fromAddress = address)
            )
          }
          .toMap
      ),
      new InterpreterClientMock,
      maxTxsToSavePerBatch = 100,
      maxConcurrent = 1
    )

    val _ = bookkeeper
      .record[UnconfirmedTransaction](
        Btc,
        accountId,
        keychainId,
        None
      )
      .compile
      .toList
      .unsafeRunSync()

    keychainClient.newlyMarkedAddresses.toList should contain only ((11 to 17).map(_.toString): _*)
  }

  it should "send transactions and corresponding used addresses to the interpreter" in {

    val transactions = usedAndFreshAddresses.drop(10) match {
      case a +: b +: c +: _ =>
        Map(
          a -> List(TransactionFixture.transfer(fromAddress = a)),
          b -> List(TransactionFixture.receive(toAddress = b)),
          c -> List(TransactionFixture.transfer(fromAddress = c))
        )
    }

    val interpreter = new InterpreterClientMock

    val bookkeeper = new Bookkeeper(
      new Keychain(KeychainFixture.keychainClient(usedAndFreshAddresses)),
      explorerClient(transactions),
      interpreter,
      maxTxsToSavePerBatch = 100,
      maxConcurrent = 1
    )

    val _ = bookkeeper
      .record[UnconfirmedTransaction](
        Btc,
        accountId,
        keychainId,
        None
      )
      .compile
      .toList
      .unsafeRunSync()

    val expectedSavedTransactions = transactions.values.flatten

    interpreter.savedUnconfirmedTransactions should have size 1
    interpreter.savedUnconfirmedTransactions.head._1 shouldBe accountId
    interpreter.savedUnconfirmedTransactions.head._2 should contain only (expectedSavedTransactions.toSeq: _*)
  }

  it should "be empty when no matching transaction found" in {

    val bookkeeper = new Bookkeeper(
      new Keychain(KeychainFixture.keychainClient(usedAndFreshAddresses)),
      explorerClient(
        mempool = Map.empty
      ),
      new InterpreterClientMock,
      maxTxsToSavePerBatch = 100,
      maxConcurrent = 1
    )

    val batches = bookkeeper
      .record[UnconfirmedTransaction](
        Btc,
        accountId,
        keychainId,
        None
      )
      .compile
      .toList
      .unsafeRunSync()

    batches shouldBe Seq()
  }

}
