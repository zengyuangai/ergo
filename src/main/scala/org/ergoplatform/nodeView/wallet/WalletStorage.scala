package org.ergoplatform.nodeView.wallet

import org.ergoplatform.ErgoBox.TokenId
import org.ergoplatform.modifiers.mempool.ErgoTransaction
import org.ergoplatform.nodeView.history.ErgoHistory.Height
import org.ergoplatform.nodeView.wallet.BoxCertainty.{Certain, Uncertain}
import org.ergoplatform.nodeView.wallet.OnchainStatus.{Offchain, Onchain}
import org.ergoplatform.nodeView.wallet.SpendingStatus.{Spent, Unspent}
import org.ergoplatform.settings.Constants
import scorex.core.utils.ScorexLogging
import scorex.core.{ModifierId, bytesToId}

import scala.collection.mutable

/**
  * Storage class for wallet entities:
  *   - all the boxes which are potentially belong to the user,
  *   - index for boxes which could belong to the user (it contains needed bytes, for example, public key bytes),
  *     but that is to be carefully checked yet (by successfully signing a test transaction which is spending the box).
  *   - index for unspent boxes
  *   - confirmed and unconfirmed balances
  *
  * This class is not thread-safe.
  */
class WalletStorage extends ScorexLogging {

  private val registry = mutable.Map[ModifierId, TrackedBox]()

  private val confirmedIndex = mutable.TreeMap[Height, Seq[ModifierId]]()

  private val unspentBoxes = mutable.TreeSet[ModifierId]()

  private val initialScanValue = bytesToId(Array.fill(Constants.ModifierIdSize)(0: Byte))
  private val uncertainBoxes = mutable.TreeSet[ModifierId]()
  private var lastScanned: ModifierId = initialScanValue

  def unspentBoxesIterator: Iterator[TrackedBox] =
    unspentBoxes.iterator.flatMap(id => registry.get(id))

  def nextUncertain(): Option[TrackedBox] = {
    uncertainBoxes.from(lastScanned).headOption match {
      case Some(id) =>
        lastScanned = id
        registry.get(id)
      case None =>
        lastScanned = initialScanValue
        None
    }
  }

  def uncertainExists: Boolean = uncertainBoxes.nonEmpty

  private def putToRegistry(trackedBox: TrackedBox): Option[TrackedBox] = {
    if (trackedBox.certainty == Uncertain) uncertainBoxes += trackedBox.boxId
    if (trackedBox.spendingStatus == Unspent) unspentBoxes += trackedBox.boxId
    registry.put(trackedBox.boxId, trackedBox)
  }

  private def removeFromRegistry(boxId: ModifierId): Option[TrackedBox] = {
    registry.remove(boxId).map { trackedBox: TrackedBox =>
      if (trackedBox.certainty == Uncertain) uncertainBoxes -= trackedBox.boxId
      if (trackedBox.spendingStatus == Unspent) unspentBoxes -= trackedBox.boxId
      trackedBox
    }
  }

  def registryContains(boxId: ModifierId): Boolean = {
    registry.contains(boxId)
  }

  private def putToConfirmedIndex(height: Height, boxId: ModifierId): Unit = {
    confirmedIndex.put(height, confirmedIndex.getOrElse(height, Seq.empty) :+ boxId)
  }

  def confirmedAt(height: Height): Seq[ModifierId] = {
    confirmedIndex.getOrElse(height, Seq.empty)
  }

  private var _confirmedBalance: Long = 0
  private val _confirmedAssetBalances: mutable.Map[ModifierId, Long] = mutable.Map()

  private var _unconfirmedBalance: Long = 0
  private val _unconfirmedAssetBalances: mutable.Map[ModifierId, Long] = mutable.Map()

  def confirmedBalance: Long = _confirmedBalance

  def confirmedAssetBalances: scala.collection.Map[ModifierId, Long] = _confirmedAssetBalances

  def unconfirmedBalance: Long = _unconfirmedBalance

  def unconfirmedAssetBalances: scala.collection.Map[ModifierId, Long] = _unconfirmedAssetBalances

  private def increaseBalances(unspentBox: TrackedBox): Unit = {
    if (checkUnspent(unspentBox)) {
      val box = unspentBox.box
      if (unspentBox.onchainStatus == Onchain) {
        _confirmedBalance += box.value
        increaseAssets(_confirmedAssetBalances, box.additionalTokens)
      } else {
        _unconfirmedBalance += box.value
        increaseAssets(_unconfirmedAssetBalances, box.additionalTokens)
      }
    }
  }

  private def decreaseBalances(unspentBox: TrackedBox): Unit = {
    if (checkUnspent(unspentBox)) {
      val box = unspentBox.box
      if (unspentBox.onchainStatus == Onchain) {
        _confirmedBalance -= box.value
        decreaseAssets(_confirmedAssetBalances, box.additionalTokens)
      } else {
        _unconfirmedBalance -= box.value
        decreaseAssets(_unconfirmedAssetBalances, box.additionalTokens)
      }
    }
  }

  private def increaseAssets(balanceMap: mutable.Map[ModifierId, Long], assetDeltas: Seq[(TokenId, Long)]): Unit = {
    assetDeltas.foreach { case (id, amount) =>
      val wid = bytesToId(id)
      val updBalance = _confirmedAssetBalances.getOrElse(wid, 0L) + amount
      _confirmedAssetBalances.put(wid, updBalance)
    }
  }

  private def decreaseAssets(balanceMap: mutable.Map[ModifierId, Long], assetDeltas: Seq[(TokenId, Long)]): Unit = {
    assetDeltas.foreach { case (id, amount) =>
      val wid = bytesToId(id)
      val currentBalance = balanceMap.getOrElse(wid, 0L)
      if (currentBalance == amount) {
        balanceMap.remove(wid)
      } else {
        val updBalance = currentBalance - amount
        balanceMap.put(wid, updBalance)
      }
    }
  }

  private def checkUnspent(trackedBox: TrackedBox): Boolean = {
    if (trackedBox.spendingStatus == Spent) {
      val msg = s"Cannot update balances with spent box $trackedBox"
      log.warn(msg, new IllegalArgumentException(msg))
    }
    trackedBox.spendingStatus == Unspent
  }

  def makeTransition(boxId: ModifierId, transition: Transition): Unit = {
    registry.get(boxId).foreach { trackedBox =>
      val targetBox: Option[TrackedBox] = convertBox(trackedBox, transition)
      targetBox.foreach(makeTransitionTo)
    }
  }

  def makeTransitionTo(updatedBox: TrackedBox): Unit = {
    deregister(updatedBox.boxId) // Actually this line is duplicated in `register` method
    register(updatedBox)
  }

  def convertBox(trackedBox: TrackedBox, transition: Transition): Option[TrackedBox] = {
    transition match {
      case ProcessRollback(toHeight) =>
        convertBack(trackedBox, toHeight)
      case CreationConfirmation(creationHeight) =>
        //todo: looks like it is never being called
        convertToConfirmed(trackedBox, creationHeight)
      case ProcessSpending(spendingTransaction, spendingHeightOpt) =>
        convertToSpent(trackedBox, spendingTransaction, spendingHeightOpt)
    }
  }

  /**
    * Register tracked box in a wallet storage
    */
  def register(trackedBox: TrackedBox): Unit = {
    deregister(trackedBox.boxId) // we need to decrease balances if somebody registers box that already known
    putToRegistry(trackedBox)
    if (trackedBox.spendingStatus == Unspent) {
        log.info(s"New ${trackedBox.onchainStatus} box arrived: " + trackedBox)
    }
    if (trackedBox.onchainStatus == Onchain) {
      putToConfirmedIndex(trackedBox.creationHeight.get, trackedBox.boxId)
    }
    if (trackedBox.spendingStatus == Unspent && trackedBox.certainty == Certain) {
      increaseBalances(trackedBox)
    }
  }

  /**
    * Remove tracked box from a wallet storage
    */
  def deregister(boxId: ModifierId): Option[TrackedBox] = {
    removeFromRegistry(boxId) map { removedBox =>
      if (removedBox.spendingStatus == Unspent && removedBox.certainty == Certain) {
        decreaseBalances(removedBox)
      }
      removedBox
    }
  }

  /**
    * Do tracked box state transition on a spending transaction (confirmed or not to come)
    * @return Some(trackedBox), if box state has been changed, None otherwise
    */
  private def convertToSpent(trackedBox: TrackedBox,
                     spendingTransaction: ErgoTransaction,
                     spendingHeightOpt: Option[Height]): Option[TrackedBox] = {
    (trackedBox.spendingStatus, trackedBox.onchainStatus) match {
      case _ if spendingHeightOpt.nonEmpty && trackedBox.creationHeight.isEmpty =>
        log.error(s"Invalid state transition for ${trackedBox.encodedBoxId}: no creation height, but spent on-chain")
        None
      case (Unspent, Offchain) if spendingHeightOpt.nonEmpty =>
        log.warn(s"Onchain transaction ${trackedBox.encodedSpendingTxId} is spending offchain box ${trackedBox.box}")
        None
      case (Spent, Offchain) if spendingHeightOpt.isEmpty =>
        log.warn(s"Double spending of an unconfirmed box ${trackedBox.encodedBoxId}")
        //todo: handle double-spending strategy for an unconfirmed tx
        None
      case (Spent, Onchain) =>
        None
      case _ =>
        Some(trackedBox.copy(spendingTx = Option(spendingTransaction), spendingHeight = spendingHeightOpt))
    }
  }

  /**
    * Do tracked box state transition on a creating transaction got confirmed
    * @return Some(trackedBox), if box state has been changed, None otherwise
    */
  private def convertToConfirmed(trackedBox: TrackedBox, creationHeight: Height): Option[TrackedBox] = {
    if (trackedBox.creationHeight.isEmpty) {
      Some(trackedBox.copy(creationHeight = Option(creationHeight)))
    } else {
      if (trackedBox.spendingStatus == Unspent || trackedBox.onchainStatus == Offchain) {
        log.warn(s"Double creation of tracked box for  ${trackedBox.encodedBoxId}")
      }
      None
    }
  }

  /**
    * Do tracked box state transition on a rollback to a certain height
    * @param toHeight - height to roll back to
    * @return Some(trackedBox), if box state has been changed, None otherwise
    */
  private def convertBack(trackedBox: TrackedBox, toHeight: Height): Option[TrackedBox] = {
    //todo: this was creationHeight < toHeight for SpentOffchainBox. Was it an error?
    val dropCreationAndSpending = trackedBox.creationHeight.exists(toHeight < _)
    val dropSpending = trackedBox.spendingHeight.exists(toHeight < _)
    if (dropCreationAndSpending) {
      Some(trackedBox.copy(spendingTx = None, creationHeight = None, spendingHeight = None))
    } else if (dropSpending) {
      Some(trackedBox.copy(spendingTx = None, spendingHeight = None))
    } else {
      None
    }
  }

}
