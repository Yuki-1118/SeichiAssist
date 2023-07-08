package com.github.unchama.seichiassist.menus.gridregion

import cats.effect.IO
import com.github.unchama.itemstackbuilder.IconItemStackBuilder
import com.github.unchama.menuinventory.router.CanOpen
import com.github.unchama.menuinventory.slot.button.action.{
  ClickEventFilter,
  FilteredButtonEffect,
  LeftClickButtonEffect
}
import com.github.unchama.menuinventory.slot.button.{Button, RecomputedButton}
import com.github.unchama.menuinventory.{
  LayoutPreparationContext,
  Menu,
  MenuFrame,
  MenuSlotLayout
}
import com.github.unchama.minecraft.actions.OnMinecraftServerThread
import com.github.unchama.seichiassist.subsystems.gridregion.GridRegionAPI
import com.github.unchama.seichiassist.subsystems.gridregion.domain._
import com.github.unchama.targetedeffect.player.PlayerEffects.closeInventoryEffect
import com.github.unchama.targetedeffect.player.{CommandEffect, FocusedSoundEffect}
import com.github.unchama.targetedeffect.{DeferredEffect, SequentialEffect}
import org.bukkit.ChatColor._
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryType
import org.bukkit.{Location, Material, Sound}

object GridRegionMenu extends Menu {

  class Environment(
    implicit val onMainThread: OnMinecraftServerThread[IO],
    implicit val layoutPreparationContext: LayoutPreparationContext,
    val gridRegionAPI: GridRegionAPI[IO, Player, Location],
    val ioCanOpenGridTemplateMenu: IO CanOpen GridTemplateMenu.type
  )

  override val frame: MenuFrame =
    MenuFrame(Right(InventoryType.DISPENSER), s"${LIGHT_PURPLE}グリッド式保護設定メニュー")

  override def computeMenuLayout(
    player: Player
  )(implicit environment: Environment): IO[MenuSlotLayout] = {
    val buttons = computeButtons(player)
    import buttons._

    for {
      toggleUnitPerClick <- toggleUnitPerClickButton
      nowRegionSettings <- nowRegionSettingButton
      regionUnitExpansionAhead <- regionUnitExpansionButton(
        HorizontalAxisAlignedRelativeDirection.Ahead
      )
      regionUnitExpansionLeft <- regionUnitExpansionButton(
        HorizontalAxisAlignedRelativeDirection.Left
      )
      regionUnitExpansionBehind <- regionUnitExpansionButton(
        HorizontalAxisAlignedRelativeDirection.Behind
      )
      regionUnitExpansionRight <- regionUnitExpansionButton(
        HorizontalAxisAlignedRelativeDirection.Right
      )
      createRegion <- createRegionButton
    } yield MenuSlotLayout(
      0 -> toggleUnitPerClick,
      1 -> regionUnitExpansionAhead,
      2 -> openGridRegionSettingMenuButton,
      3 -> regionUnitExpansionLeft,
      4 -> nowRegionSettings,
      5 -> regionUnitExpansionRight,
      6 -> resetSettingButton,
      7 -> regionUnitExpansionBehind,
      8 -> createRegion
    )
  }

  case class computeButtons(player: Player)(implicit environment: Environment) {
    import environment._

    def toggleUnitPerClickButton: IO[Button] = RecomputedButton {
      for {
        currentRegionUnit <- gridRegionAPI.unitPerClick(player)
      } yield {
        val iconItemStack = new IconItemStackBuilder(Material.STAINED_GLASS_PANE, 1)
          .title(s"${GREEN}拡張単位の変更")
          .lore(
            List(
              s"${GREEN}現在のユニット指定量",
              s"$AQUA${currentRegionUnit.units}${GREEN}ユニット($AQUA${currentRegionUnit.computeBlockAmount}${GREEN}ブロック)/1クリック",
              s"$RED${UNDERLINE}クリックで変更"
            )
          )
          .build()

        val leftClickEffect = LeftClickButtonEffect(
          DeferredEffect(IO(gridRegionAPI.toggleUnitPerClick)),
          FocusedSoundEffect(Sound.BLOCK_STONE_BUTTON_CLICK_ON, 1.0f, 1.0f)
        )

        Button(iconItemStack, leftClickEffect)
      }
    }

    private def gridLore(
      direction: CardinalDirection,
      relativeDirection: HorizontalAxisAlignedRelativeDirection
    ): IO[List[String]] = for {
      currentRegionUnits <- gridRegionAPI.regionUnits(player)
      regionUnit = currentRegionUnits.fromRelativeDirectionToRegionUnit(relativeDirection)
    } yield List(
      s"${GREEN}左クリックで増加",
      s"${RED}右クリックで減少",
      s"$GRAY---------------",
      s"${GRAY}方向：$AQUA${direction.uiLabel}",
      s"${GRAY}現在の指定方向のユニット数：$AQUA${regionUnit.units}$GRAY($AQUA${regionUnit.computeBlockAmount}${GRAY}ブロック)"
    )

    def regionUnitExpansionButton(
      relativeDirection: HorizontalAxisAlignedRelativeDirection
    ): IO[Button] =
      RecomputedButton {
        val yaw = player.getEyeLocation.getYaw
        val direction = CardinalDirection.relativeToCardinalDirections(yaw)(relativeDirection)
        for {
          gridLore <- gridLore(direction, relativeDirection)
          regionUnits <- gridRegionAPI.regionUnits(player)
          currentPerClickRegionUnit <- gridRegionAPI.unitPerClick(player)
        } yield {
          val worldName = player.getEyeLocation.getWorld.getName
          val expandedRegionUnits =
            regionUnits.expansionRegionUnits(relativeDirection, currentPerClickRegionUnit)
          val contractedRegionUnits =
            regionUnits.contractRegionUnits(relativeDirection, currentPerClickRegionUnit)

          val lore = gridLore :+ {
            if (gridRegionAPI.isWithinLimits(expandedRegionUnits, worldName))
              s"$RED${UNDERLINE}これ以上拡張できません"
            else if (gridRegionAPI.isWithinLimits(contractedRegionUnits, worldName))
              s"$RED${UNDERLINE}これ以上縮小できません"
            else
              ""
          }

          val relativeDirectionString = relativeDirection match {
            case HorizontalAxisAlignedRelativeDirection.Ahead  => "前へ"
            case HorizontalAxisAlignedRelativeDirection.Behind => "後ろへ"
            case HorizontalAxisAlignedRelativeDirection.Left   => "左へ"
            case HorizontalAxisAlignedRelativeDirection.Right  => "右へ"
          }

          val stainedGlassPaneDurability = relativeDirection match {
            case HorizontalAxisAlignedRelativeDirection.Ahead  => 14
            case HorizontalAxisAlignedRelativeDirection.Left   => 10
            case HorizontalAxisAlignedRelativeDirection.Behind => 13
            case HorizontalAxisAlignedRelativeDirection.Right  => 5
          }

          val itemStack =
            new IconItemStackBuilder(
              Material.STAINED_GLASS_PANE,
              stainedGlassPaneDurability.toShort
            ).title(s"$DARK_GREEN${relativeDirectionString}ユニット増やす/減らす").lore(lore).build()

          val leftClickButtonEffect = FilteredButtonEffect(ClickEventFilter.LEFT_CLICK) { _ =>
            val regionSelection =
              gridRegionAPI.regionSelection(player, expandedRegionUnits, direction)
            val startPosition = regionSelection.startPosition
            val endPosition = regionSelection.endPosition

            SequentialEffect(
              DeferredEffect(IO(gridRegionAPI.saveRegionUnits(expandedRegionUnits))),
              CommandEffect("/;"),
              CommandEffect(s"/pos1 ${startPosition.getX.toInt},0,${startPosition.getZ.toInt}"),
              CommandEffect(s"/pos2 ${endPosition.getX.toInt},0,${endPosition.getZ.toInt}"),
              FocusedSoundEffect(Sound.BLOCK_STONE_BUTTON_CLICK_ON, 1f, 1f),
              GridRegionMenu.open
            )
          }

          val rightClickButtonEffect = FilteredButtonEffect(ClickEventFilter.RIGHT_CLICK) { _ =>
            val regionSelection =
              gridRegionAPI.regionSelection(player, contractedRegionUnits, direction)
            val startPosition = regionSelection.startPosition
            val endPosition = regionSelection.endPosition

            SequentialEffect(
              DeferredEffect(IO(gridRegionAPI.saveRegionUnits(contractedRegionUnits))),
              CommandEffect("/;"),
              CommandEffect(s"/pos1 ${startPosition.getX.toInt},0,${startPosition.getZ.toInt}"),
              CommandEffect(s"/pos2 ${endPosition.getX.toInt},0,${endPosition.getZ.toInt}"),
              FocusedSoundEffect(Sound.BLOCK_STONE_BUTTON_CLICK_ON, 1f, 1f),
              GridRegionMenu.open
            )
          }

          Button(itemStack, leftClickButtonEffect, rightClickButtonEffect)
        }
      }

    val openGridRegionSettingMenuButton: Button = {
      val itemStack = new IconItemStackBuilder(Material.CHEST)
        .title(s"${GREEN}設定保存メニュー")
        .lore(List(s"$RED${UNDERLINE}クリックで開く"))
        .build()

      val leftClickButtonEffect = LeftClickButtonEffect(
        ioCanOpenGridTemplateMenu.open(GridTemplateMenu),
        FocusedSoundEffect(Sound.BLOCK_STONE_BUTTON_CLICK_ON, 1.0f, 1.0f)
      )

      Button(itemStack, leftClickButtonEffect)
    }

    val resetSettingButton: Button = {
      val itemStack = new IconItemStackBuilder(Material.STAINED_GLASS_PANE, 4)
        .title(s"${RED}全設定リセット")
        .lore(List(s"$RED${UNDERLINE}取り扱い注意！！"))
        .build()

      val leftClickButtonEffect = LeftClickButtonEffect(
        DeferredEffect(IO(gridRegionAPI.saveRegionUnits(RegionUnits.initial))),
        CommandEffect("/;"),
        FocusedSoundEffect(Sound.BLOCK_ANVIL_DESTROY, 0.5f, 1.0f)
      )

      Button(itemStack, leftClickButtonEffect)
    }

    val nowRegionSettingButton: IO[Button] = RecomputedButton {
      for {
        regionUnits <- gridRegionAPI.regionUnits(player)
      } yield {
        def createUnitInformation(regionUnit: RegionUnit): String =
          s"$AQUA${regionUnit.units}${GRAY}ユニット($AQUA${regionUnit.computeBlockAmount}${GRAY}ブロック)"
        val worldName = player.getLocation.getWorld.getName

        val lore = List(
          s"${GRAY}現在の設定",
          s"${GRAY}前方向：${createUnitInformation(regionUnits.ahead)}",
          s"${GRAY}後ろ方向：${createUnitInformation(regionUnits.behind)}",
          s"${GRAY}右方向：${createUnitInformation(regionUnits.right)}",
          s"${GRAY}左方向：${createUnitInformation(regionUnits.left)}",
          s"${GRAY}保護ユニット数：$AQUA${regionUnits.computeTotalRegionUnits.units}",
          s"${GRAY}保護ユニット上限値：$RED${gridRegionAPI.regionUnitLimit(worldName).value}"
        )

        val itemStack = new IconItemStackBuilder(Material.STAINED_GLASS_PANE, 11)
          .title(s"${DARK_GREEN}設定")
          .lore(lore)
          .build()

        Button(itemStack)
      }
    }

    val createRegionButton: IO[Button] = RecomputedButton {
      for {
        regionUnits <- gridRegionAPI.regionUnits(player)
        yaw = player.getEyeLocation.getYaw
        canCreateRegionResult <- gridRegionAPI.canCreateRegion(
          player,
          regionUnits,
          CardinalDirection.relativeToCardinalDirections(yaw)(
            HorizontalAxisAlignedRelativeDirection.Ahead
          )
        )
      } yield {
        canCreateRegionResult match {
          case CreateRegionResult.Success =>
            val itemStack = new IconItemStackBuilder(Material.WOOL, 11)
              .title(s"${GREEN}保護作成")
              .lore(List(s"${DARK_GREEN}保護作成可能です", s"$RED${UNDERLINE}クリックで作成"))
              .build()
            val leftClickButtonEffect = LeftClickButtonEffect(
              DeferredEffect(IO(gridRegionAPI.createRegion)),
              FocusedSoundEffect(Sound.BLOCK_STONE_BUTTON_CLICK_ON, 1.0f, 1.0f),
              closeInventoryEffect
            )
            Button(itemStack, leftClickButtonEffect)
          case CreateRegionResult.ThisWorldRegionCanNotBeCreated =>
            val itemStack = new IconItemStackBuilder(Material.WOOL, 14)
              .title(s"${RED}保護作成")
              .lore(List(s"$RED${UNDERLINE}このワールドでは保護を作成できません"))
              .build()
            Button(itemStack)
          case CreateRegionResult.RegionCanNotBeCreatedByOtherError =>
            val itemStack = new IconItemStackBuilder(Material.WOOL, 1)
              .title(s"${RED}以下の原因により保護の作成できません")
              .lore(
                List(
                  s"$RED${UNDERLINE}以下の原因により保護を作成できません。",
                  s"$RED・保護の範囲が他の保護と重複している",
                  s"$RED・保護の作成上限に達している"
                )
              )
              .build()
            Button(itemStack)
        }
      }
    }

  }

}
