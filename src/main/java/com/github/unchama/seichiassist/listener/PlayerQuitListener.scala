package com.github.unchama.seichiassist.listener

import com.github.unchama.seichiassist.SeichiAssist
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.{EventHandler, EventPriority, Listener}
class PlayerQuitListener  extends  Listener {
  private val playerMap = SeichiAssist.playermap

  //プレイヤーがquitした時に実行
  @EventHandler(priority = EventPriority.LOWEST)
  def onplayerQuitEvent(event: PlayerQuitEvent) {
    val player = event.player
    val uuid = player.uniqueId
    SeichiAssist.instance.expBarSynchronization.desynchronizeFor(player)

    val playerData = playerMap[uuid] ?: return

    playerData.updateOnQuit()

    GlobalScope.launch { savePlayerData(playerData) }

    //不要なplayerdataを削除
    playerMap.remove(uuid)
  }
}
