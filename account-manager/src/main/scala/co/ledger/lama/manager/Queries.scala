package co.ledger.lama.manager

import java.util.UUID

import cats.data.NonEmptyList
import co.ledger.lama.common.models.{
  AccountIdentifier,
  Coin,
  CoinFamily,
  SyncEvent,
  TriggerableEvent,
  TriggerableStatus,
  WorkableEvent,
  WorkableStatus
}
import co.ledger.lama.manager.models._
import co.ledger.lama.manager.models.implicits._
import doobie.Fragments
import doobie.free.connection.ConnectionIO
import doobie.implicits._
import doobie.postgres.implicits._
import fs2.Stream
import org.postgresql.util.PGInterval

import scala.concurrent.duration.FiniteDuration

object Queries {

  def countAccounts(): ConnectionIO[Int] =
    sql"""SELECT COUNT(*) FROM account_info""".query[Int].unique

  def fetchPublishableEvents(
      coinFamily: CoinFamily,
      coin: Coin
  ): Stream[ConnectionIO, WorkableEvent] =
    (
      sql"""SELECT account_id, sync_id, status, payload
          FROM account_sync_status
          WHERE coin_family = $coinFamily
          AND coin = $coin
          AND """
        ++ Fragments.in(fr"status", NonEmptyList.fromListUnsafe(WorkableStatus.all.values.toList))
    )
      .query[WorkableEvent]
      .stream

  def fetchTriggerableEvents(
      coinFamily: CoinFamily,
      coin: Coin
  ): Stream[ConnectionIO, TriggerableEvent] =
    (
      sql"""SELECT account_id, sync_id, status, payload
          FROM account_sync_status
          WHERE updated + sync_frequency < CURRENT_TIMESTAMP
          AND coin_family = $coinFamily
          AND coin = $coin
          AND """
        ++ Fragments.in(
          fr"status",
          NonEmptyList.fromListUnsafe(TriggerableStatus.all.values.toList)
        )
    )
      .query[TriggerableEvent]
      .stream

  def getAccounts(offset: Int, limit: Int): Stream[ConnectionIO, AccountInfo] =
    sql"""SELECT account_id, key, coin_family, coin, extract(epoch FROM sync_frequency)/60*60
          FROM account_info
          LIMIT $limit
          OFFSET $offset
         """
      .query[AccountInfo]
      .stream

  def getAccountInfo(accountId: UUID): ConnectionIO[Option[AccountInfo]] =
    sql"""SELECT account_id, key, coin_family, coin, extract(epoch FROM sync_frequency)/60*60
          FROM account_info
          WHERE account_id = $accountId
         """
      .query[AccountInfo]
      .option

  def updateAccountSyncFrequency(
      accountId: UUID,
      syncFrequency: FiniteDuration
  ): ConnectionIO[Int] = {
    val syncFrequencyInterval = new PGInterval()
    syncFrequencyInterval.setSeconds(syncFrequency.toSeconds.toDouble)

    sql"""UPDATE account_info SET sync_frequency=$syncFrequencyInterval WHERE account_id = $accountId
          """.update.run
  }

  def insertAccountInfo(
      accountIdentifier: AccountIdentifier,
      syncFrequency: FiniteDuration
  ): ConnectionIO[AccountInfo] = {
    val accountId  = accountIdentifier.id
    val key        = accountIdentifier.key
    val coinFamily = accountIdentifier.coinFamily
    val coin       = accountIdentifier.coin

    val syncFrequencyInterval = new PGInterval()
    syncFrequencyInterval.setSeconds(syncFrequency.toSeconds.toDouble)

    // Yes, this is weird but DO NOTHING does not return anything unfortunately
    sql"""INSERT INTO account_info(account_id, key, coin_family, coin, sync_frequency)
          VALUES($accountId, $key, $coinFamily, $coin, $syncFrequencyInterval)
          RETURNING account_id, key, coin_family, coin, extract(epoch FROM sync_frequency)/60*60
          """
      .query[AccountInfo]
      .unique
  }

  def insertSyncEvent(e: SyncEvent): ConnectionIO[Int] =
    sql"""
         INSERT INTO account_sync_event(account_id, sync_id, status, payload)
         VALUES(${e.accountId}, ${e.syncId}, ${e.status}, ${e.payload})
         """.update.run

  def getLastSyncEvent(accountId: UUID): ConnectionIO[Option[SyncEvent]] =
    sql"""SELECT account_id, sync_id, status, payload
          FROM account_sync_status
          WHERE account_id = $accountId
          """
      .query[SyncEvent]
      .option

  def getSyncEvents(accountId: UUID): Stream[ConnectionIO, SyncEvent] =
    sql"""SELECT account_id, sync_id, status, payload
          FROM account_sync_event
          WHERE account_id = $accountId
          """
      .query[SyncEvent]
      .stream
}
