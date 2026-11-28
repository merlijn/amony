package nl.amony.modules.resources.api

import java.io.{EOFException, IOException, RandomAccessFile}
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.nio.file.{Path, Paths}
import java.time.Duration

import cats.effect.IO

/**
 * Whether a video's container is laid out so a browser can start playing it without first reading the end of the
 * file: MP4 needs its `moov` atom before `mdat`, Matroska/WebM needs its `Cues` index before the first `Cluster`.
 *
 * `Unknown` means the layout could not be determined (unsupported, truncated or corrupt file).
 */
enum Streamability:
  case Streamable, NotStreamable, Unknown

object Streamability:

  private val Mp4BoxTypes = Set("ftyp", "moov", "mdat", "moof", "free", "skip", "wide", "styp", "sidx", "pnot", "uuid")

  private val SegmentId = 0x18538067L
  private val InfoId    = 0x1549a966L
  private val TracksId  = 0x1654ae6bL
  private val CuesId    = 0x1c53bb6bL
  private val ClusterId = 0x1f43b675L

  private val HttpChunkSize = 64 * 1024

  private lazy val httpClient: HttpClient = HttpClient.newBuilder()
    .followRedirects(HttpClient.Redirect.NORMAL)
    .connectTimeout(Duration.ofSeconds(10))
    .build()

  private trait SeekableBytes extends AutoCloseable:
    def length: Long
    def position: Long
    def seek(pos: Long): Unit
    def readUnsignedByte(): Int
    def readInt(): Int
    def readLong(): Long
    def readFully(buf: Array[Byte]): Unit
    def skipBytes(n: Long): Unit = seek(position + n)

  final private class LocalFileBytes(file: RandomAccessFile) extends SeekableBytes:
    override def length: Long                      = file.length()
    override def position: Long                    = file.getFilePointer
    override def seek(pos: Long): Unit             = file.seek(pos)
    override def readUnsignedByte(): Int           = file.readUnsignedByte()
    override def readInt(): Int                    = file.readInt()
    override def readLong(): Long                  = file.readLong()
    override def readFully(buf: Array[Byte]): Unit = file.readFully(buf)
    override def close(): Unit                     = file.close()

  final private class HttpRangeBytes(uri: URI) extends SeekableBytes:
    private var totalLength: Long = -1L
    private var pos: Long         = 0L
    private var bufStart: Long    = 0L
    private var buf: Array[Byte]  = Array.emptyByteArray

    private def ensureLoaded(offset: Long, minBytes: Int): Unit =
      if offset < 0 then throw new IOException(s"Negative seek offset: $offset")
      if totalLength >= 0 && offset + minBytes > totalLength then throw new EOFException(s"EOF at $offset")
      if buf.isEmpty || offset < bufStart || offset + minBytes > bufStart + buf.length then
        val fetchSize = math.max(HttpChunkSize, minBytes)
        val rangeEnd  =
          if totalLength >= 0 then math.min(totalLength - 1, offset + fetchSize - 1)
          else offset + fetchSize - 1
        val request   = HttpRequest.newBuilder(uri)
          .GET()
          .header("Range", s"bytes=$offset-$rangeEnd")
          .timeout(Duration.ofSeconds(30))
          .build()
        val response  = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
        response.statusCode() match
          case 206  =>
            val body = response.body()
            if totalLength < 0 then
              response.headers().firstValue("Content-Range").toScala
                .flatMap(parseTotalLength)
                .foreach(totalLength = _)
            if totalLength < 0 then throw new IOException("Missing or invalid Content-Range header")
            bufStart = offset
            buf = body
          case 200  =>
            val body = response.body()
            totalLength = body.length.toLong
            bufStart    = 0L
            buf         = body
          case code =>
            throw new IOException(s"Unexpected HTTP status $code reading $uri")
        if offset + minBytes > bufStart + buf.length then throw new EOFException(s"EOF at $offset")

    private def parseTotalLength(contentRange: String): Option[Long] =
      val slash = contentRange.lastIndexOf('/')
      if slash < 0 then None else contentRange.substring(slash + 1).trim.toLongOption

    override def length: Long =
      if totalLength < 0 then ensureLoaded(0L, 1)
      totalLength

    override def position: Long = pos

    override def seek(newPos: Long): Unit = pos = newPos

    override def readUnsignedByte(): Int =
      ensureLoaded(pos, 1)
      val value = buf((pos - bufStart).toInt) & 0xff
      pos += 1
      value

    override def readInt(): Int =
      val b0 = readUnsignedByte()
      val b1 = readUnsignedByte()
      val b2 = readUnsignedByte()
      val b3 = readUnsignedByte()
      (b0 << 24) | (b1 << 16) | (b2 << 8) | b3

    override def readLong(): Long =
      val hi = readInt().toLong & 0xffffffffL
      val lo = readInt().toLong & 0xffffffffL
      (hi << 32) | lo

    override def readFully(target: Array[Byte]): Unit =
      var written = 0
      while written < target.length do
        val remaining = target.length - written
        ensureLoaded(pos, math.min(remaining, HttpChunkSize))
        val available = (bufStart + buf.length - pos).toInt
        val toCopy    = math.min(remaining, available)
        System.arraycopy(buf, (pos - bufStart).toInt, target, written, toCopy)
        pos += toCopy
        written += toCopy

    override def close(): Unit = ()

  extension [A](opt: java.util.Optional[A])
    private def toScala: Option[A] = if opt.isPresent then Some(opt.get()) else None

  def detect(path: Path): IO[Streamability] =
    detectWith(new LocalFileBytes(new RandomAccessFile(path.toFile, "r")))

  def detect(uri: URI): IO[Streamability] =
    if uri.getScheme == "file" then detect(Paths.get(uri))
    else detectWith(new HttpRangeBytes(uri))

  private def detectWith(open: => SeekableBytes): IO[Streamability] = IO.blocking {
    val source = open
    try
      if isEbml(source) then matroska(source)
      else if isMp4(source) then mp4(source)
      else Unknown
    catch case _: Exception => Unknown
    finally source.close()
  }

  private def isEbml(source: SeekableBytes): Boolean =
    if source.length < 4 then false
    else {
      source.seek(0)
      val bytes = new Array[Byte](4)
      source.readFully(bytes)
      bytes(0) == 0x1a.toByte && bytes(1) == 0x45.toByte && bytes(2) == 0xdf.toByte && bytes(3) == 0xa3.toByte
    }

  private def isMp4(source: SeekableBytes): Boolean =
    if source.length < 8 then false
    else {
      source.seek(0)
      val size = Integer.toUnsignedLong(source.readInt())
      val typ  = readFourCc(source)
      Mp4BoxTypes.contains(typ) && (size == 1 || size == 0 || size >= 8)
    }

  private def mp4(source: SeekableBytes): Streamability = {
    val length   = source.length
    var position = 0L
    var seenMdat = false

    while position + 8 <= length do {
      source.seek(position)
      val size32 = Integer.toUnsignedLong(source.readInt())
      val typ    = readFourCc(source)
      val size   =
        if size32 == 1 then if position + 16 <= length then source.readLong() else return Unknown
        else if size32 == 0 then length - position
        else size32

      typ match
        case "moof" => return Streamable
        case "moov" => return if seenMdat then NotStreamable else Streamable
        case "mdat" => seenMdat = true
        case _      => ()

      if size < 8 || position + size > length then return if seenMdat then NotStreamable else Unknown
      position += size
    }

    if seenMdat then NotStreamable else Unknown
  }

  private def matroska(source: SeekableBytes): Streamability = {
    source.seek(0)
    source.skipBytes(4) // EBML element id
    val headerSize = readEbmlSize(source)
    if headerSize < 0 then return Unknown
    source.skipBytes(headerSize)

    if readEbmlId(source) != SegmentId then return Unknown
    val segmentSize = readEbmlSize(source)
    val end         = if segmentSize < 0 then source.length else math.min(source.length, source.position + segmentSize)

    var seenInfo   = false
    var seenTracks = false
    var seenCues   = false

    while source.position < end do {
      val id   = readEbmlId(source)
      val size = readEbmlSize(source)

      id match
        case InfoId    => seenInfo   = true
        case TracksId  => seenTracks = true
        case CuesId    => seenCues   = true
        case ClusterId => return if seenInfo && seenTracks && seenCues then Streamable else NotStreamable
        case _         => ()

      if size < 0 then return Unknown
      source.skipBytes(size)
    }

    Unknown
  }

  private def readEbmlId(source: SeekableBytes): Long = {
    val first  = source.readUnsignedByte()
    val length = vintLength(first)
    var value  = first.toLong
    var i      = 1
    while i < length do {
      value = (value << 8) | source.readUnsignedByte()
      i += 1
    }
    value
  }

  private def readEbmlSize(source: SeekableBytes): Long = {
    val first   = source.readUnsignedByte()
    val length  = vintLength(first)
    val mask    = 0xff >>> length
    var value   = (first & mask).toLong
    var allOnes = (first & mask) == mask
    var i       = 1
    while i < length do {
      val byte = source.readUnsignedByte()
      value   = (value << 8) | byte
      allOnes = allOnes && byte == 0xff
      i += 1
    }
    if allOnes then -1L else value
  }

  private def vintLength(first: Int): Int = {
    var mask   = 0x80
    var length = 1
    while (first & mask) == 0 && length < 8 do {
      mask >>>= 1
      length += 1
    }
    length
  }

  private def readFourCc(source: SeekableBytes): String = {
    val bytes = new Array[Byte](4)
    source.readFully(bytes)
    new String(bytes, StandardCharsets.US_ASCII)
  }
