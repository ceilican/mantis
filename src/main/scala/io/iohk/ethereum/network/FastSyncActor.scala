package io.iohk.ethereum.network

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Terminated}
import akka.util.ByteString
import io.iohk.ethereum.network.FastSyncActor._
import io.iohk.ethereum.network.PeerActor.MessageReceived
import io.iohk.ethereum.network.p2p.messages.PV62._
import io.iohk.ethereum.network.p2p.messages.PV63._
import org.spongycastle.util.encoders.Hex
import scala.concurrent.duration._

class FastSyncActor(peerActor: ActorRef) extends Actor with ActorLogging {

  import context.{dispatcher, system}

  val BlocksPerMessage = 10
  val NodesPerRequest = 10
  val NodeRequestsInterval: FiniteDuration = 3.seconds
  val GenesisBlockNumber = 0
  //TODO move to conf
  val EmptyAccountStorageHash = ByteString(Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421"))
  val EmptyAccountEvmCodeHash = ByteString(Hex.decode("c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470"))

  def handleTerminated: Receive = {
    case _: Terminated =>
      log.info("FastSync actor terminated")
      context stop self
  }

  override def receive: Receive = handleTerminated orElse {
    case StartSync(targetBlockHash) =>
      peerActor ! PeerActor.Subscribe(Set(NodeData.code, Receipts.code, BlockBodies.code, BlockHeaders.code))
      peerActor ! PeerActor.SendMessage(GetBlockHeaders(Right(targetBlockHash), 1, 0, reverse = false))
      context become waitForTargetBlockHeader(targetBlockHash)
  }

  def waitForTargetBlockHeader(targetBlockHash: ByteString): Receive = handleTerminated orElse {
    case MessageReceived(BlockHeaders(blockHeaders)) if blockHeaders.nonEmpty =>
      peerActor ! PeerActor.SendMessage(GetNodeData(Seq(blockHeaders.head.stateRoot)))
      peerActor ! PeerActor.SendMessage(GetBlockHeaders(Left(GenesisBlockNumber), BlocksPerMessage, 0, reverse = false))
      context become processMessages(ProcessingState(targetBlockHash,
        targetBlockNumber = Some(blockHeaders.head.number),
        requestedNodes = Seq(StateMptNodeHash(blockHeaders.head.stateRoot))))
  }

  def processMessages(state: ProcessingState): Receive = handleTerminated orElse handleMptDownload(state) orElse {
    case MessageReceived(BlockHeaders(headers)) =>
      val blockHashes = headers.map(_.blockHash)
      peerActor ! PeerActor.SendMessage(GetBlockBodies(blockHashes))
      peerActor ! PeerActor.SendMessage(GetReceipts(blockHashes))
      context become processMessages(state.copy(blockHeaders = headers))

    case MessageReceived(BlockBodies(bodies)) if bodies.length == state.blockHeaders.length =>
      self ! PartialDownloadDone
      context become processMessages(state.copy(blockBodies = bodies))

    case MessageReceived(Receipts(receipts: Seq[Seq[Receipt]])) if receipts.length == state.blockHeaders.length =>
      self ! PartialDownloadDone
      context become processMessages(state.copy(blockReceipts = receipts))

    case PartialDownloadDone =>
      handlePartialDownload(state)

    case MessageReceived(m) =>
      log.info("Got unexpected message {}", m)
      log.info("Requested nodes {}, blockHeaders {}",
        state.requestedNodes, state.blockHeaders)
  }

  private def handlePartialDownload(state: ProcessingState) = {
    state match {
      case ProcessingState(_, Some(targetBlockNumber), _, _, headers, receipts, bodies) if receipts.nonEmpty && bodies.nonEmpty =>
        log.info("Got complete blocks")
        log.info("headers: {}", headers)
        log.info("receipts: {}", receipts)
        log.info("bodies: {}", bodies)

        val nextBlockNumber = headers.last.number + 1

        if(nextBlockNumber + BlocksPerMessage < targetBlockNumber) {
          peerActor ! PeerActor.SendMessage(GetBlockHeaders(Left(nextBlockNumber), BlocksPerMessage, skip = 0, reverse = false))
        } else {
          peerActor ! PeerActor.SendMessage(GetBlockHeaders(Left(nextBlockNumber), BlocksPerMessage, skip = 0, reverse = false))
        }

        context become processMessages(state.copy(blockHeaders = Seq.empty, blockReceipts = Seq.empty, blockBodies = Seq.empty))
        //TODO insert all elements
      case _ =>
    }
  }

  private def handleMptDownload(state: ProcessingState): Receive = {
    case MessageReceived(m: NodeData) if m.values.length == state.requestedNodes.length =>
      state.requestedNodes.zipWithIndex.foreach {
        case (StateMptNodeHash(hash), idx) =>
          handleMptNode(hash, m.getMptNode(idx))

        case (ContractStorageMptNodeHash(hash), idx) =>
          handleContractMptNode(hash, m.getMptNode(idx))

        case (EvmCodeHash(hash), idx) =>
          val evmCode = m.values(idx)
          val msg =
            s"got EVM code: ${Hex.toHexString(evmCode.toArray[Byte])}"
          log.info(msg)
        case (StorageRootHash(hash), idx) =>
          val rootNode = m.getMptNode(idx)
          val msg =
            s"got root node for contract storage: $rootNode"
          log.info(msg)
          handleContractMptNode(hash, rootNode)
      }
      //remove hashes from state as nodes received
      context become processMessages(state.copy(requestedNodes = Seq.empty))
      self ! FetchNodes

    case RequestNodes(hashes@_*) =>
      //prep end nodesQueue to get deep first
      context become processMessages(state.copy(nodesQueue = hashes ++ state.nodesQueue))
      self ! FetchNodes

    case FetchNodes =>
      if (state.requestedNodes.isEmpty) {
        val (nonMptHashes, mptHashes) = state.nodesQueue.partition {
          case EvmCodeHash(_) => true
          case StorageRootHash(_) => true
          case StateMptNodeHash(_) => false
          case ContractStorageMptNodeHash(_) => false
        }

        val (forRequest, forQueue) = (nonMptHashes ++ mptHashes).splitAt(NodesPerRequest)
        context become processMessages(state.copy(requestedNodes = forRequest, nodesQueue = forQueue))
        peerActor ! PeerActor.SendMessage(GetNodeData(forRequest.map(_.v)))
        log.info("Requested nodes: {}", forRequest)
        log.info("nodes queue size: {}", forQueue.length)
        system.scheduler.scheduleOnce(NodeRequestsInterval) {
          self ! FetchNodes
        }
      }
  }

  private def handleContractMptNode(hash: ByteString, mptNode: MptNode) = {
    mptNode match {
      case n: MptLeaf =>
        log.info("Got contract leaf node: {}", n)
      //TODO insert node

      case n: MptBranch =>
        log.info("Got contract branch node: {}", n)
        val hashes = n.children.collect { case Left(MptHash(childHash)) => childHash }.filter(_.nonEmpty)
        self ! RequestNodes(hashes.map(ContractStorageMptNodeHash): _*)
      //TODO insert node

      case n: MptExtension =>
        log.info("Got contract extension node: {}", n)
        n.child.fold(
          { case MptHash(nodeHash) =>
            self ! RequestNodes(ContractStorageMptNodeHash(nodeHash))
          }, { case MptValue(value) =>
            log.info("Got contract value in extension node: ", Hex.toHexString(value.toArray[Byte]))
          })
      //TODO insert node
    }
  }

  private def handleMptNode(hash: ByteString, mptNode: MptNode) = {
    mptNode match {
      case n: MptLeaf =>
        log.info("Got leaf node: {}", n)
        val evm = n.getAccount.codeHash
        val storage = n.getAccount.storageRoot

        if (evm != EmptyAccountEvmCodeHash) {
          self ! RequestNodes(EvmCodeHash(evm))
        }

        if (storage != EmptyAccountStorageHash) {
          self ! RequestNodes(StorageRootHash(storage))
        }

      //TODO insert node

      case n: MptBranch =>
        log.info("Got branch node: {}", n)
        val hashes = n.children.collect { case Left(MptHash(childHash)) => childHash }.filter(_.nonEmpty)
        self ! RequestNodes(hashes.map(StateMptNodeHash): _*)
      //TODO insert node

      case n: MptExtension =>
        log.info("Got extension node: {}", n)
        n.child.fold(
          { case MptHash(nodeHash) =>
            self ! RequestNodes(StateMptNodeHash(nodeHash))
          }, { case MptValue(value) =>
            log.info("Got value in extension node: ", Hex.toHexString(value.toArray[Byte]))
          })
      //TODO insert node
    }
  }
}

object FastSyncActor {
  def props(peerActor: ActorRef): Props = Props(new FastSyncActor(peerActor))

  case class StartSync(targetBlockHash: ByteString)

  case object PartialDownloadDone

  private trait HashType {
    val v: ByteString
  }

  private case class StateMptNodeHash(v: ByteString) extends HashType {
    override def toString: String = s"StateMptNodeHash(${Hex.toHexString(v.toArray[Byte])})"
  }

  private case class ContractStorageMptNodeHash(v: ByteString) extends HashType {
    override def toString: String = s"ContractStorageMptNodeHash(${Hex.toHexString(v.toArray[Byte])})"
  }

  private case class EvmCodeHash(v: ByteString) extends HashType {
    override def toString: String = s"EvmCodeHash(${Hex.toHexString(v.toArray[Byte])})"
  }

  private case class StorageRootHash(v: ByteString) extends HashType {
    override def toString: String = s"StorageRootHash(${Hex.toHexString(v.toArray[Byte])})"
  }

  private case class RequestNodes(hashes: HashType*)

  private case object FetchNodes

  private case class ProcessingState(
    targetBlockHash: ByteString,
    targetBlockNumber: Option[BigInt] = None,
    requestedNodes: Seq[HashType] = Seq.empty,
    nodesQueue: Seq[HashType] = Seq.empty,
    blockHeaders: Seq[BlockHeader] = Seq.empty,
    blockReceipts: Seq[Seq[Receipt]] = Seq.empty,
    blockBodies: Seq[BlockBody] = Seq.empty)

}