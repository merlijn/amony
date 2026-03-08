package nl.amony.lib.files.tracking


import java.io.RandomAccessFile
import java.nio.file.Path
import java.security.MessageDigest
import scala.util.Random

case class SamplingConfig(
   blockSize: Int = 4 * 1024, // this is the minimal block size that HDDs read internally
   minBlocks: Int = 16,
   maxBlocks: Int = 64,
   entropyThreshold: Double = 5,  // bits per byte (max is 8)
   scaleFactor: Long = 128L * 1024 * 1024, // add 1 extra min block per this many bytes of file size
   digestAlgorithm: String = "SHA-1"
)

object DedupId {
  
  private val defaultSamplingConfig = SamplingConfig()

  /**
   * Calculates a deduplication ID for a file by sampling blocks of data across the file, updating the provided MessageDigest,
   * and tracking byte frequencies to estimate entropy.
   *
   * The first and last blocks are always included, since they are most likely to reveal differences in files.
   * Additional blocks are sampled in shuffled order until either the maximum number of blocks is read or the estimated
   * entropy exceeds the specified threshold, ensuring a balance between accuracy and performance.
   *
   * If the file is smaller than minBlocks * blockSize, the entire file is read.
   *
   * @param path The file path to sample
   * @param digest The (new) digest instance
   */
  def sampledHash(path: Path, config: SamplingConfig = defaultSamplingConfig): Array[Byte] =
    val file = RandomAccessFile(path.toFile, "r")
    try
      val fileSize = file.length()
      val digest = MessageDigest.getInstance(config.digestAlgorithm)

      if fileSize == 0 then
        return digest.digest()

      val rand = Random(fileSize)

      // Track byte frequencies across all sampled blocks
      val freq = Array.fill(256)(0L)
      var totalBytes = 0L
      var blocksRead = 0
      val readPositions = scala.collection.mutable.Set[Long]()

      val buffer = new Array[Byte](config.blockSize)

      def readBlock(pos: Long): Unit =
        if pos >= 0 && pos < fileSize && !readPositions.contains(pos) then
          readPositions += pos
          file.seek(pos)
          val bytesRead = file.read(buffer)
          if bytesRead > 0 then
            digest.update(buffer, 0, bytesRead)
            for i <- 0 until bytesRead do
              freq(buffer(i) & 0xFF) += 1
            totalBytes += bytesRead
            blocksRead += 1

      // If the entire file fits in minBlocks * blockSize, read it all sequentially
      if fileSize <= config.minBlocks.toLong * config.blockSize then
        var pos = 0L
        while pos < fileSize do
          readBlock(pos)
          pos += config.blockSize
      else
        // Scale baseline with file size: larger files get more samples
        val sizeScaledBlocks = (fileSize / config.scaleFactor).toInt
        val effectiveMinBlocks = math.min(config.maxBlocks, config.minBlocks + sizeScaledBlocks)

        // Always read the first and last blocks first
        readBlock(0)
        val lastBlockPos = (fileSize - config.blockSize) / config.blockSize * config.blockSize
        readBlock(lastBlockPos)

        // Pre-calculate remaining block positions (excluding first and last)
        val totalFileBlocks = ((fileSize + config.blockSize - 1) / config.blockSize).toInt
        val remainingPositions = (0 until totalFileBlocks)
          .map(_.toLong * config.blockSize)
          .filter(pos => !readPositions.contains(pos))
          .toArray

        // Shuffle remaining positions deterministically
        val shuffled = rand.shuffle(remainingPositions.toSeq)

        for
          pos <- shuffled
          if blocksRead < config.maxBlocks &&
            (blocksRead < effectiveMinBlocks || currentEntropy(freq, totalBytes) < config.entropyThreshold)
        do
          readBlock(pos)

      digest.digest()
    finally
      file.close()

  private def currentEntropy(freq: Array[Long], total: Long): Double =
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
