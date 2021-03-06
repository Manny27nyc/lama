package co.ledger.lama.common.models

import java.util.UUID

import co.ledger.lama.common.utils.UuidUtils
import co.ledger.lama.common.models.implicits._
import co.ledger.lama.manager.protobuf
import io.circe.generic.extras.semiauto.{deriveConfiguredDecoder, deriveConfiguredEncoder}
import io.circe.{Decoder, Encoder, JsonObject}

case class AccountInfo(
    id: UUID,
    key: String,
    coinFamily: CoinFamily,
    coin: Coin,
    syncFrequency: Long,
    lastSyncEvent: Option[SyncEvent[JsonObject]],
    label: Option[String]
) {
  def toProto: protobuf.AccountInfoResult =
    protobuf.AccountInfoResult(
      UuidUtils.uuidToBytes(id),
      key,
      syncFrequency,
      lastSyncEvent.map(_.toProto),
      coinFamily.toProto,
      coin.toProto,
      label.map(protobuf.AccountLabel(_))
    )
}

object AccountInfo {
  implicit val decoder: Decoder[AccountInfo] =
    deriveConfiguredDecoder[AccountInfo]
  implicit val encoder: Encoder[AccountInfo] =
    deriveConfiguredEncoder[AccountInfo]

  def fromProto(proto: protobuf.AccountInfoResult): AccountInfo =
    AccountInfo(
      UuidUtils.bytesToUuid(proto.accountId).get,
      proto.key,
      CoinFamily.fromProto(proto.coinFamily),
      Coin.fromProto(proto.coin),
      proto.syncFrequency,
      proto.lastSyncEvent.map(SyncEvent.fromProto[JsonObject]),
      proto.label.map(_.value)
    )
}
