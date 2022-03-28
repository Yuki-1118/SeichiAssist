package com.github.unchama.seichiassist.commands

import cats.effect.IO
import com.github.unchama.contextualexecutor.ContextualExecutor
import com.github.unchama.contextualexecutor.executors.BranchedExecutor
import com.github.unchama.menuinventory.LayoutPreparationContext
import com.github.unchama.seichiassist.SeichiAssist
import com.github.unchama.seichiassist.commands.contextual.builder.BuilderTemplates.playerCommandBuilder
import com.github.unchama.seichiassist.concurrent.PluginExecutionContexts.onMainThread
import com.github.unchama.seichiassist.menus.minestack.CategorizedMineStackMenu
import com.github.unchama.seichiassist.minestack.MineStackObjectCategory
import com.github.unchama.targetedeffect.UnfocusedEffect
import com.github.unchama.targetedeffect.commandsender.MessageEffect
import org.bukkit.command.TabExecutor

object MineStackCommand {
  def executor(
    implicit categorizedMineStackMenuEnvironment: CategorizedMineStackMenu.Environment,
    layoutPreparationContext: LayoutPreparationContext
  ): TabExecutor =
    BranchedExecutor(
      Map(
        "on" -> ChildExecutors.setAutoCollectionExecutor(true),
        "off" -> ChildExecutors.setAutoCollectionExecutor(false),
        "open" -> ChildExecutors.openCategorizedMineStackMenu
      )
    ).asNonBlockingTabExecutor()

  object ChildExecutors {

    def setAutoCollectionExecutor(autoMineStack: Boolean): ContextualExecutor =
      playerCommandBuilder
        .execution { context =>
          IO {
            val sender = context.sender
            val pd = SeichiAssist.playermap(sender.getUniqueId).settings
            UnfocusedEffect {
              pd.autoMineStack = autoMineStack
            }
          }
        }
        .build()

    def openCategorizedMineStackMenu(
      implicit categorizedMineStackMenuEnvironment: CategorizedMineStackMenu.Environment,
      layoutPreparationContext: LayoutPreparationContext
    ): ContextualExecutor =
      playerCommandBuilder
        .execution { context =>
          val args = context.args.parsed
          val categories: Map[Int, MineStackObjectCategory] = Map(
            1 -> MineStackObjectCategory.ORES,
            2 -> MineStackObjectCategory.MOB_DROP,
            3 -> MineStackObjectCategory.AGRICULTURAL,
            4 -> MineStackObjectCategory.BUILDING,
            5 -> MineStackObjectCategory.REDSTONE_AND_TRANSPORTATION,
            6 -> MineStackObjectCategory.GACHA_PRIZES
          )
          val category = categories.get(args.head.toString.toInt)
          category match {
            case Some(_category) =>
              val _page = args(1).toString.toInt - 1
              IO.pure(CategorizedMineStackMenu(_category, _page).open)
            case None =>
              IO(MessageEffect("不明なカテゴリです。"))
          }
        }
        .build()

  }

}
