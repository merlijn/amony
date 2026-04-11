package nl.amony.lib.files.tracking


import java.io.RandomAccessFile
import java.nio.file.Path
import java.security.MessageDigest
import scala.util.Random

case class SamplingConfig(
   blockSize: Int = 4 * 1024, // this is the minimal block size that HDDs read internally
   numberOfExtraBlocks: Int = 32,
   digestAlgorithm: String = "SHA-1",
   randomProvider: Long => Random = seed => new Random(seed)
)

object DedupId {

  private val defaultSamplingConfig = SamplingConfig()

  /**
   * Calculates a deduplication ID for a file by sampling blocks of data across the file.
   *
   * The first and last blocks are always included, since they are most likely to reveal differences in files.
   * Additional blocks are sampled via stratified random sampling: the file is divided into equal segments and one
   * random block-aligned position is picked per segment.
   *
   * If the file is smaller than (numberOfExtraBlocks + 2) * blockSize, the entire file is read.
   *
   * @param path The file path to sample
   * @param config The sampling configuration
   */
  def sampledHash(path: Path, config: SamplingConfig = defaultSamplingConfig): Array[Byte] =
    val file = RandomAccessFile(path.toFile, "r")
    try
      val fileSize = file.length()
      val digest = MessageDigest.getInstance(config.digestAlgorithm)

      if fileSize == 0 then
        return digest.digest()

      val rand = config.randomProvider(fileSize)
      val readPositions = scala.collection.mutable.Set[Long]()
      val buffer = new Array[Byte](config.blockSize)

      def readBlock(pos: Long): Unit =
        if pos >= 0 && pos < fileSize && !readPositions.contains(pos) then
          readPositions += pos
          file.seek(pos)
          val bytesRead = file.read(buffer)
          if bytesRead > 0 then
            digest.update(buffer, 0, bytesRead)

      val totalBlocks = config.numberOfExtraBlocks + 2

      // If the entire file fits in totalBlocks * blockSize, read it all sequentially
      if fileSize <= totalBlocks.toLong * config.blockSize then
        var pos = 0L
        while pos < fileSize do
          readBlock(pos)
          pos += config.blockSize
      else
        // Always read the first and last blocks
        readBlock(0)
        val lastBlockPos = (fileSize - config.blockSize) / config.blockSize * config.blockSize
        readBlock(lastBlockPos)

        // Stratified random sampling for the remaining blocks
        val segmentCount = config.numberOfExtraBlocks
        val segmentSize = fileSize.toDouble / segmentCount

        for segIdx <- 0 until segmentCount do
          val segStart = (segIdx * segmentSize).toLong
          val segEnd = math.min(((segIdx + 1) * segmentSize).toLong, fileSize)
          // Pick a random block-aligned position within this segment
          val maxBlockStart = math.max(segStart, segEnd - config.blockSize)
          val blocksInSegment = (maxBlockStart - segStart) / config.blockSize + 1
          val blockIndex = if blocksInSegment > 1 then rand.nextLong().abs % blocksInSegment else 0L
          val pos = segStart + blockIndex * config.blockSize
          readBlock(math.min(pos, fileSize - config.blockSize))

      digest.digest()
    finally
      file.close()
}
