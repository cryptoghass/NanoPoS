package org.byzantine.pos

import com.roundeights.hasher.Implicits._
import java.time.Instant
import scala.collection.mutable
import scala.collection.mutable.ListBuffer

object Const {
  val CoinbaseSourceAddress: Address = Address(-1)
  val CoinbaseAmount: Int = 25

  val GenesisPrevHash: Hash = new Hash("Genesis")
  val GenesisCoinbase: Coinbase = new Coinbase(Address(0))
  val GenesisTimestamp: Long = Instant.now.getEpochSecond
  val GenesisProofOfStake = new ProofOfStake(GenesisTimestamp, GenesisPrevHash, CoinbaseSourceAddress)

  val MaxAcceptedTimestampDiff: Long = 3600
  val HashTarget: String = ("007") + GenesisPrevHash.toString.substring(3)
}

class Hash(of: String) {
  val hashValue: String = of.sha1

  def this(of: Any) = this(of.toString)

  override def toString: String = hashValue

  override def equals(o: Any) = o match {
    case that: Hash => that.hashValue == this.hashValue
    case _ => false
  }

  override def hashCode = hashValue.hashCode
}

case class Address(val addr: Int) {
  override def toString: String = addr.toString

  override def equals(o: Any) = o match {
    case that: Address => that.addr == this.addr
    case _ => false
  }

  override def hashCode = addr.hashCode
}

// We are assuming that these can't be forged (e.g. they're cryptographically signed by the sender)
class Transaction(val from: Address, val to: Address, val amount: Int, val timestamp: Long) {
  require(amount >= 0, "Cannot send negative amounts.")

  def this(from: Address, to: Address, amount: Int) = this(from, to, amount, Instant.now.getEpochSecond)

  def hash = new Hash(this)

  override def toString: String = s"Tx($from, $to, $amount, $timestamp)"
}

class Coinbase(to: Address) extends Transaction(Const.CoinbaseSourceAddress, to, Const.CoinbaseAmount) {
  override def toString: String = s"Coinbase($to, $amount)"
}

// We are assuming that these can't be forged (e.g. they're cryptographically signed by the validator)
case class ProofOfStake(val timestamp: Long, val stakeModifier: Hash, val validator: Address)

class Block(val prevBlockHash: Hash, val tx: List[Transaction], val timestamp: Long, val pos: ProofOfStake) {
  require(haveAtMostOneCoinbase, "Blocks must have no more than one coinbase transaction.")
  require(coinbaseHasCorrectAmount, "Blocks cannot contain malformed coinbase transactions.")

  def this(prevBlockHash: Hash, tx: List[Transaction], pos: ProofOfStake) = this(prevBlockHash, tx, Instant.now.getEpochSecond, pos)

  override def toString: String = s"Block($prevBlockHash, $timestamp, [" + tx.mkString(", ") + s"])"

  def hash = new Hash(this)

  private def haveAtMostOneCoinbase = tx.filter(t => t.from == Const.CoinbaseSourceAddress).length <= 1

  private def coinbaseHasCorrectAmount = !tx.exists(t => t.from == Const.CoinbaseSourceAddress && t.amount != Const.CoinbaseAmount)
}

object GenesisBlock extends Block(Const.GenesisPrevHash, List(Const.GenesisCoinbase), Const.GenesisTimestamp, Const.GenesisProofOfStake)

class Blockchain(val blocks: List[Block]) {
  require(blocks.length > 0, "Blockchains must have length > 0.")
  require(blocks(0) == GenesisBlock, "Blockchains must start at the genesis block.")

  override def toString: String = "Blockchain[" + blocks.mkString(", ") + "]"
  def top: Block = blocks.last

  def compare(that: Blockchain) = consensus.chainForkCompare(this, that)

  object consensus {
    def chainForkCompare(orig: Blockchain, that: Blockchain): Int = {
      val lengthCmp = (orig.blocks.length - that.blocks.length) match {
        case diff if diff < 0 => -1
        case diff if diff == 0 => 0
        case diff if diff > 0 => 1
      }

      return lengthCmp
    }

    val POS = new POS(blocks)
  }

  val state = new State(blocks)

  class State(blocks: List[Block]) {
    private val balanceSheet = new mutable.HashMap[Address, Int]()
    for (block <- blocks) { processBlock(block) }

    def balance(of: Address): Int = balanceSheet.getOrElse(of, 0)
    private def setBalance(of: Address, amount: Int): Unit = balanceSheet.put(of, amount)
    private def +=(of: Address, diff: Int) = setBalance(of, balance(of) + diff)
    private def -=(of: Address, diff: Int) = setBalance(of, balance(of) - diff)

    protected[Blockchain] def processBlock(block: Block): Unit = {
      // To simply the logic: can always transfer CoinbaseAmount from CoinbaseSource
      setBalance(Const.CoinbaseSourceAddress, Const.CoinbaseAmount)

      for (tx <- block.tx) {
        processTransaction(tx)
      }
    }

    private def processTransaction(tx: Transaction): Unit = {
      require(balance(tx.from) >= tx.amount, s"Sender cannot send more than they have.")

      -=(tx.from, tx.amount)
      +=(tx.to, tx.amount)
    }
  }

  class POS(blocks: List[Block]) {
    private var chainState = new State(List(blocks(0)))
    private var currentBlockHeight = 0
    private def chainTop = blocks(currentBlockHeight)

    // When constructed, verify POS conditions for all blocks
    require(chainTop == GenesisBlock, "First block must be the genesis block.")
    while (processNextBlock()) {}

    def stake(stakeholder: Address): Int = chainState.balance(stakeholder)
    def stakeModifier(): Hash = chainTop.hash
    private def timestamp(): Long = chainTop.timestamp

    private def validatorAcceptance(timestamp: Long, candidate: Address): Boolean =  {
      val amount = stake(candidate)
      val kernel: String = timestamp.toString + stakeModifier.toString + candidate.toString

      // Create a positive BigInt from the kernel's hash
      val kernelHash = new BigInt(new java.math.BigInteger(1, kernel.sha1.bytes))

      // Interpret the HashTarget (which is a string) as a hex number
      val targetHash = new BigInt(new java.math.BigInteger(Const.HashTarget, 16))

//      def stringWithLeadingZeroes(d: BigInt): String = "%040x".format(d)
//      println(stringWithLeadingZeroes(kernelHash) + " <= " + stringWithLeadingZeroes(targetHash * amount) + " (" + (kernelHash <= (targetHash * amount)) + ")")

      return kernelHash <= (targetHash * amount)
    }

    def validate(pos: ProofOfStake): Boolean = {
//      println(pos.stakeModifier + s" == $stakeModifier (" + (pos.stakeModifier == stakeModifier).toString + ")")
//      println("math.abs(" + pos.timestamp + s" - $timestamp) <= " + Const.MaxAcceptedTimestampDiff + " (" + (math.abs(pos.timestamp - timestamp) <= Const.MaxAcceptedTimestampDiff).toString + ")")

      return (pos.stakeModifier == stakeModifier &&
        math.abs(pos.timestamp - timestamp) <= Const.MaxAcceptedTimestampDiff &&
        validatorAcceptance(pos.timestamp, pos.validator)
        )
    }

    private def processNextBlock(): Boolean = {
      val nextBlockHeight = currentBlockHeight + 1

      // If there's something left to process
      if (nextBlockHeight < blocks.length) {
        val nextBlock = blocks(nextBlockHeight)
        require(validate(nextBlock.pos), "Blocks must satisfy the POS conditions.")

        chainState.processBlock(blocks(nextBlockHeight))
        currentBlockHeight = nextBlockHeight
        return true
      }
      else {
        return false
      }
    }
  }

}

class BlockTree {
  private val blocks = new mutable.HashMap[Hash, Block]()
  private var topHash = this add GenesisBlock

  def top(): Block = {
    this get topHash match {
      case Some(x) => x
      case None => throw new Exception("Our blockchain has no top!")
    }
  }

  def chain(): Blockchain = this getChainFrom topHash

  // Returned value represents whether the block is valid
  def extend(block: Block): Boolean = {
    // TODO: handle this gracefully, e.g. put in a queue
    assert(havePrevOf(block), "Trying to extend a block we don't have!")

    // Only process nodes we don't have already
    if (!have(block)) {
      val receivedChain: Option[Blockchain] = try {
        Some(this getChainFrom block)
      } catch {
        case _: Throwable => None
      }

      if (receivedChain == None) {
        return false
      }
      else {
        val currentChain = chain
        val candidateChain = receivedChain.get

        // Update topHash according to the ChainForkRule
        topHash = candidateChain.compare(currentChain) match {
          case 1 => this add block
          case _ => topHash
        }

        return true
      }
    }

    return false
  }

  private def add(block: Block): Hash = {
    blocks put(block.hash, block)
    return block.hash
  }

  private def get(hash: Hash): Option[Block] = blocks get hash

  private def getOrError(hash: Hash): Block = {
    (this get hash) match {
      case Some(x) => x
      case None => throw new Exception(s"We don't have block with hash $hash!")
    }
  }

  private def have(hash: Hash): Boolean = {
    (this get hash) match {
      case Some(x) => true
      case None => false
    }
  }

  private def have(block: Block): Boolean = this have (block.hash)

  private def havePrevOf(block: Block): Boolean = this have block.prevBlockHash

  private def prevOf(block: Block): Option[Block] = this get block.prevBlockHash

  private def prevOfOrError(block: Block): Block = this getOrError block.prevBlockHash

  private def getChainFrom(hash: Hash): Blockchain = {
    return getChainFrom(this getOrError hash)
  }

  private def getChainFrom(block: Block): Blockchain = {
    var blocks = mutable.ListBuffer[Block]()
    blocks.prepend(block)

    var currentBlock = block
    while (this havePrevOf currentBlock) {
      currentBlock = this prevOfOrError currentBlock
      blocks.prepend(currentBlock)
    }
    assert(currentBlock == GenesisBlock, "Got a chain that doesn't start with the GenesisBlock!")

    return new Blockchain(blocks.toList)
  }
}