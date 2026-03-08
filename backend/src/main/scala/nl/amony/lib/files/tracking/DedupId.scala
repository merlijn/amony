package nl.amony.lib.files.tracking


import java.io.RandomAccessFile
import java.security.MessageDigest
import scala.util.Random

case class SamplingConfig(
   blockSize: Int = 4 * 1024, // this is the minimal block size that HDDs read internally
   minBlocks: Int = 4,
   maxBlocks: Int = 64,
   entropyThreshold: Double = 7.5  // bits per byte (max is 8)
)

object DedupId {

  def sampleFile(path: String, config: SamplingConfig): Array[Byte] =
    val file = RandomAccessFile(path, "r")
    val fileSize = file.length()
    val rand = Random(fileSize)
    val digest = MessageDigest.getInstance("SHA-1")

    // Track byte frequencies across all sampled blocks
    val freq = Array.fill(256)(0L)
    var totalBytes = 0L
    var blocksRead = 0

    // Pre-calculate valid block positions
    val maxBlocks = math.min(config.maxBlocks, (fileSize / config.blockSize).toInt)
    val positions = (0 until maxBlocks)
      .map(_ * config.blockSize.toLong)
      .toArray

    // Shuffle positions deterministically
    val shuffled = rand.shuffle(positions.toSeq)

    val buffer = new Array[Byte](config.blockSize)

    for
      pos <- shuffled
      if blocksRead < config.maxBlocks &&
        (blocksRead < config.minBlocks || currentEntropy(freq, totalBytes) < config.entropyThreshold)
    do
      file.seek(pos)
      val bytesRead = file.read(buffer)
      if bytesRead > 0 then
        digest.update(buffer, 0, bytesRead)
        // Update frequencies
        for i <- 0 until bytesRead do
          freq(buffer(i) & 0xFF) += 1
        totalBytes += bytesRead
        blocksRead += 1

    file.close()
    digest.digest()

  def currentEntropy(freq: Array[Long], total: Long): Double =
    if total == 0 then 0.0
    else
      var entropy = 0.0
      var i = 0
      while i < 256 do
        if freq(i) > 0 then
          val p = freq(i).toDouble / total
          entropy -= p * (math.log(p) / math.log(2))
        i += 1
      entropy
}
