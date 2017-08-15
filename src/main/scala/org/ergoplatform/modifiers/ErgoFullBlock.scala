package org.ergoplatform.modifiers

import io.circe.Json
import org.ergoplatform.modifiers.history.{ADProof, BlockTransactions, Header}
import org.ergoplatform.modifiers.mempool.AnyoneCanSpendTransaction
import org.ergoplatform.modifiers.mempool.proposition.AnyoneCanSpendProposition
import org.ergoplatform.nodeView.state.ErgoState
import org.ergoplatform.settings.{Algos, Constants}
import org.ergoplatform.settings.Constants._
import scorex.core.NodeViewModifier.{ModifierId, ModifierTypeId}
import scorex.core.serialization.Serializer

//TODO we need it to be ErgoPersistentModifier just to put it to ProcessInfo
case class ErgoFullBlock(header: Header,
                         blockTransactions: BlockTransactions,
                         aDProofs: Option[ADProof],
                         extension: Option[Map[Array[Byte], Array[Byte]]]) extends ErgoPersistentModifier {
  override val modifierTypeId: ModifierTypeId = ErgoFullBlock.modifierTypeId

  override lazy val id: ModifierId = Algos.hash(header.id ++ blockTransactions.id ++
    aDProofs.map(_.id).getOrElse(Array()))

  override lazy val json: Json = ???

  override type M = ErgoFullBlock

  override lazy val serializer: Serializer[ErgoFullBlock] = ???
}

object ErgoFullBlock {
  val modifierTypeId: ModifierTypeId = (-127).toByte

  //TODO testnet genesis?
  //todo: real definition of a genesis block, do we need genesis block at all?
  lazy val genesis = {
    val genesisTimestamp = 1500203225564L
    val stateRoot = ErgoState.initialDigest
    val genesisTx = new AnyoneCanSpendTransaction(
      IndexedSeq(0L),
      IndexedSeq(0L))
    //TODO where can we get it???
    val proofs = ErgoState.genesisProofs
    val proofsRoot = ADProof.proofDigest(proofs)

    val header: Header = Header(0.toByte,
      Array.fill(hashLength)(0.toByte),
      Seq(),
      proofsRoot,
      stateRoot,
      BlockTransactions.rootHash(Seq(genesisTx.id)),
      genesisTimestamp,
      0,
      Constants.InitialDifficulty,
      Array.fill(32)(0.toByte),
      Array.fill(5)(0.toByte)
    )
    val blockTransactions: BlockTransactions = BlockTransactions(header.id, Seq(genesisTx))
    val aDProofs: ADProof = ADProof(header.id, proofs)
    assert(header.ADProofsRoot sameElements aDProofs.digest)
    assert(header.transactionsRoot sameElements blockTransactions.digest)
    ErgoFullBlock(header, blockTransactions, Some(aDProofs), None)
  }
}
