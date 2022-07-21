package com.github.unchama.seichiassist.subsystems.gacha

import cats.effect.ConcurrentEffect.ops.toAllConcurrentEffectOps
import cats.effect.{ConcurrentEffect, Sync, SyncIO}
import com.github.unchama.concurrent.NonServerThreadContextShift
import com.github.unchama.minecraft.actions.OnMinecraftServerThread
import com.github.unchama.seichiassist.meta.subsystem.Subsystem
import com.github.unchama.seichiassist.subsystems.gacha.application.actions.LotteryOfGachaItems
import com.github.unchama.seichiassist.subsystems.gacha.bukkit.command.GachaCommand
import com.github.unchama.seichiassist.subsystems.gacha.bukkit.listeners.PlayerPullGachaListener
import com.github.unchama.seichiassist.subsystems.gacha.domain.GachaPrizeId
import com.github.unchama.seichiassist.subsystems.gacha.domain.bukkit.GachaPrize
import com.github.unchama.seichiassist.subsystems.gacha.infrastructure.bukkit.JdbcGachaPrizeListPersistence
import com.github.unchama.seichiassist.subsystems.gacha.subsystems.gachaticket.infrastructure.JdbcGachaTicketFromAdminTeamGateway
import com.github.unchama.seichiassist.subsystems.itemmigration.domain.minecraft.UuidRepository
import org.bukkit.command.TabExecutor
import org.bukkit.event.Listener

trait System[F[_]] extends Subsystem[F] {
  val api: GachaAPI[F]
}

object System {

  def wired[F[_]: OnMinecraftServerThread: NonServerThreadContextShift: ConcurrentEffect](
    implicit syncUuidRepository: UuidRepository[SyncIO]
  ): System[F] = {
    implicit val gachaPersistence: JdbcGachaPrizeListPersistence[F] =
      new JdbcGachaPrizeListPersistence[F]()
    implicit val gachaTicketPersistence: JdbcGachaTicketFromAdminTeamGateway[F] =
      new JdbcGachaTicketFromAdminTeamGateway[F]

    val system: System[F] = new System[F] {
      override implicit val api: GachaAPI[F] = new GachaAPI[F] {
        import cats.implicits._

        override protected implicit val _FSync: Sync[F] = implicitly[ConcurrentEffect[F]]

        override def load: F[Unit] =
          gachaPrizesListRepository.set(gachaPersistence.list.toIO.unsafeRunSync())

        override def replace(gachaPrizesList: Vector[GachaPrize]): F[Unit] =
          gachaPrizesListRepository.set(gachaPrizesList)

        override def lottery(amount: Int): F[Vector[GachaPrize]] =
          LotteryOfGachaItems.using(Sync[F], api).lottery(amount)

        override def removeByGachaPrizeId(gachaPrizeId: GachaPrizeId): F[Unit] = for {
          prizes <- list
          targetPrize = prizes.filter(_.id == gachaPrizeId)
          _ <- replace(prizes.diff(targetPrize))
        } yield ()

        /**
         * `GachaPrize`を追加する。
         * `GachaPrizeId`を与えなかった場合は最大`GachaPrizeId`の次の値が指定されます
         */
        override def addGachaPrize(gachaPrize: GachaPrizeId => GachaPrize): F[Unit] = for {
          prizes <- list
          newList = prizes ++ Vector(gachaPrize(GachaPrizeId(prizes.map(_.id.id).max + 1)))
          _ <- replace(newList)
        } yield ()

      }
      override val commands: Map[String, TabExecutor] = Map(
        "gacha" -> new GachaCommand[F]().executor
      )
      override val listeners: Seq[Listener] = Seq(new PlayerPullGachaListener[F]())
    }

    system.api.load.toIO.unsafeRunAsyncAndForget()
    system
  }

}
