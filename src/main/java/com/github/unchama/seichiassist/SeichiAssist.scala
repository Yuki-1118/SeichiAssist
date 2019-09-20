package com.github.unchama.seichiassist

import com.github.unchama.buildassist.BuildAssist
import com.github.unchama.menuinventory.MenuHandler
import com.github.unchama.seichiassist.commands._
import com.github.unchama.seichiassist.data.{GachaPrize, RankData}
import com.github.unchama.seichiassist.database.DatabaseGateway
import com.github.unchama.seichiassist.minestack.{MineStackObj, MineStackObjectCategory}
import kotlinx.coroutines.Job
import org.bukkit.ChatColor._
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.{Bukkit, Material}

import scala.collection.mutable
class SeichiAssist  extends  JavaPlugin() {
  init { instance = this }

  private var repeatedJobCoroutine: Job? = null

  val expBarSynchronization = ExpBarSynchronization()

  override def onEnable() {

    //チャンネルを追加
    Bukkit.getMessenger().registerOutgoingPluginChannel(this, "BungeeCord")

    // BungeeCordとのI/O
    Bukkit.getMessenger().registerIncomingPluginChannel(this, "SeichiAssistBungee", BungeeReceiver(this))
    Bukkit.getMessenger().registerOutgoingPluginChannel(this, "SeichiAssistBungee")


    //コンフィグ系の設定は全てConfig.javaに移動
    seichiAssistConfig = Config(this)
    seichiAssistConfig.loadConfig()

    if (SeichiAssist.seichiAssistConfig.debugMode == 1) {
      //debugmode=1の時は最初からデバッグモードで鯖を起動
      logger.info(s"${RED}seichiassistをデバッグモードで起動します")
      logger.info(s"${RED}コンソールから/seichi debugmode")
      logger.info(s"${RED}を実行するといつでもONOFFを切り替えられます")
      DEBUG = true
    } else {
      //debugmode=0の時は/seichi debugmodeによる変更コマンドも使えない
      logger.info(s"${GREEN}seichiassistを通常モードで起動します")
      logger.info(s"${GREEN}デバッグモードを使用する場合は")
      logger.info(s"${GREEN}config.ymlの設定値を書き換えて再起動してください")
    }

    try {
      databaseGateway = DatabaseGateway.createInitializedInstance(
          seichiAssistConfig.url, seichiAssistConfig.db, seichiAssistConfig.id, seichiAssistConfig.pw
      )
    } catch (e: Exception) {
      e.printStackTrace()
      logger.severe("データベース初期化に失敗しました。サーバーを停止します…")
      Bukkit.shutdown()
    }

    //mysqlからガチャデータ読み込み
    if (!databaseGateway.gachaDataManipulator.loadGachaData()) {
      logger.severe("ガチャデータのロードに失敗しました")
      Bukkit.shutdown()
    }

    //mysqlからMineStack用ガチャデータ読み込み
    if (!databaseGateway.mineStackGachaDataManipulator.loadMineStackGachaData()) {
      logger.severe("MineStack用ガチャデータのロードに失敗しました")
      Bukkit.shutdown()
    }

    MineStackObjectList.minestackGachaPrizes.addAll(generateGachaPrizes())

    MineStackObjectList.minestacklist.clear()
    MineStackObjectList.minestacklist += MineStackObjectList.minestacklistmine
    MineStackObjectList.minestacklist += MineStackObjectList.minestacklistdrop
    MineStackObjectList.minestacklist += MineStackObjectList.minestacklistfarm
    MineStackObjectList.minestacklist += MineStackObjectList.minestacklistbuild
    MineStackObjectList.minestacklist += MineStackObjectList.minestacklistrs
    MineStackObjectList.minestacklist += MineStackObjectList.minestackGachaPrizes

    // コマンドの登録
    mapOf(
        "gacha" to GachaCommand(),
        "ef" to EffectCommand.executor,
        "seichihaste" to SeichiHasteCommand.executor,
        "seichiassist" to SeichiAssistCommand.executor,
        "openpocket" to OpenPocketCommand.executor,
        "lastquit" to LastQuitCommand.executor,
        "stick" to StickCommand.executor,
        "rmp" to RmpCommand.executor,
        "shareinv" to ShareInvCommand.executor,
        "mebius" to MebiusCommand.executor,
        "achievement" to AchievementCommand.executor,
        "halfguard" to HalfBlockProtectCommand.executor,
        "event" to EventCommand.executor,
        "contribute" to ContributeCommand.executor,
        "subhome" to SubHomeCommand.executor,
        "gtfever" to GiganticFeverCommand.executor,
        "minehead" to MineHeadCommand.executor,
        "x-transfer" to RegionOwnerTransferCommand.executor
    ).forEach { (commandName, executor) => getCommand(commandName).executor = executor }

    //リスナーの登録
    List(
        PlayerJoinListener(),
        PlayerQuitListener(),
        PlayerClickListener(),
        PlayerChatEventListener(),
        PlayerBlockBreakListener(),
        PlayerInventoryListener(),
        EntityListener(),
        PlayerPickupItemListener(),
        PlayerDeathEventListener(),
        GachaItemListener(),
        MebiusListener(),
        RegionInventoryListener(),
        WorldRegenListener()
    ).forEach { server.pluginManager.registerEvents(it, this) }

    //正月イベント用
    NewYearsEvent(this)

    //Menu用Listener
    server.pluginManager.registerEvents(MenuHandler, this)

    //オンラインの全てのプレイヤーを処理
    for (p in server.onlinePlayers) {
      try {
        //プレイヤーデータを生成
        playermap[p.uniqueId] = databaseGateway.playerDataManipulator.loadPlayerData(p.uniqueId, p.name)
      } catch (e: Exception) {
        e.printStackTrace()
        p.kickPlayer("プレーヤーデータの読み込みに失敗しました。")
      }
    }

    //ランキングリストを最新情報に更新する
    if (!databaseGateway.playerDataManipulator.successRankingUpdate()) {
      logger.info("ランキングデータの作成に失敗しました")
      Bukkit.shutdown()
    }

    startRepeatedJobs()

    logger.info("SeichiAssist is Enabled!")

    buildAssist = BuildAssist(this).apply { onEnable() }
  }

  override def onDisable() {
    cancelRepeatedJobs()

    //全てのエンティティを削除
    entitylist.forEach { it.remove() }

    //全てのスキルで破壊されるブロックを強制破壊
    for (b in allblocklist) b.type = Material.AIR

    //sqlコネクションチェック
    databaseGateway.ensureConnection()
    for (p in server.onlinePlayers) {
      //UUIDを取得
      val uuid = p.uniqueId
      //プレイヤーデータ取得
      val playerdata = playermap[uuid]
      //念のためエラー分岐
      if (playerdata == null) {
        p.sendMessage(RED.toString() + "playerdataの保存に失敗しました。管理者に報告してください")
        server.consoleSender.sendMessage(RED.toString() + "SeichiAssist[Ondisable処理]でエラー発生")
        logger.warning(p.name + "のplayerdataの保存失敗。開発者に報告してください")
        continue
      }
      //quit時とondisable時、プレイヤーデータを最新の状態に更新
      playerdata.updateOnQuit()

      runBlocking {
        savePlayerData(playerdata)
      }
    }

    if (databaseGateway.disconnect() === Fail) {
      logger.info("データベース切断に失敗しました")
    }

    logger.info("SeichiAssist is Disabled!")

    buildAssist.onDisable()
  }

  override def onCommand(sender: CommandSender?, command: Command?, label: String?, args: Array[String]?)
      = buildAssist.onCommand(sender, command, label, args)

  private def startRepeatedJobs() {
    repeatedJobCoroutine = CoroutineScope(Schedulers.sync).launch {
      launch { HalfHourRankingRoutine.launch() }
      launch { PlayerDataPeriodicRecalculation.launch() }
      launch { PlayerDataBackupTask.launch() }
    }
  }

  private def cancelRepeatedJobs() {
    repeatedJobCoroutine?.cancel()
  }

  def restartRepeatedJobs() {
    cancelRepeatedJobs()
    startRepeatedJobs()
  }
}

object SeichiAssist {
  lateinit var instance: SeichiAssist

  //デバッグフラグ(デバッグモード使用時はここで変更するのではなくconfig.ymlの設定値を変更すること！)
  var DEBUG = false

  //ガチャシステムのメンテナンスフラグ
  var gachamente = false

  val SEICHIWORLDNAME = "world_sw"
  val DEBUGWORLDNAME = "world"

  // TODO staticであるべきではない
  lateinit var databaseGateway: DatabaseGateway
  lateinit var seichiAssistConfig: Config

  lateinit var buildAssist: BuildAssist

  //Gachadataに依存するデータリスト
  val gachadatalist: mutable.MutableList[GachaPrize] = ArrayList()

  //(minestackに格納する)Gachadataに依存するデータリスト
  var msgachadatalist: MutableList[MineStackGachaData] = ArrayList()

  //Playerdataに依存するデータリスト
  val playermap = HashMap[UUID, PlayerData]()

  //総採掘量ランキング表示用データリスト
  val ranklist: mutable.MutableList[RankData] = ArrayList()

  //プレイ時間ランキング表示用データリスト
  val ranklist_playtick: mutable.MutableList[RankData] = ArrayList()

  //投票ポイント表示用データリスト
  val ranklist_p_vote: mutable.MutableList[RankData] = ArrayList()

  //マナ妖精表示用のデータリスト
  val ranklist_p_apple: mutable.MutableList[RankData] = ArrayList()

  //プレミアムエフェクトポイント表示用データリスト
  val ranklist_premiumeffectpoint: mutable.MutableList[RankData] = ArrayList()

  //総採掘量表示用
  var allplayerbreakblockint = 0L

  var allplayergiveapplelong = 0L

  //プラグインで出すエンティティの保存
  val entitylist: mutable.MutableList[Entity] = ArrayList()

  //プレイヤーがスキルで破壊するブロックリスト
  val allblocklist: MutableList[Block] = mutable.LinkedList()

  private def generateGachaPrizes(): List[MineStackObj] = {
    val minestacklist = util.ArrayList[MineStackObj]()
    for (i in msgachadatalist.indices) {
    val g = msgachadatalist[i]
    if (g.itemStack.type !== Material.EXP_BOTTLE) { //経験値瓶だけはすでにリストにあるので除外
    minestacklist += MineStackObj(g.objName, null, g.level, g.itemStack, true, i, MineStackObjectCategory.GACHA_PRIZES)
  }
  }
    return minestacklist
  }
}
