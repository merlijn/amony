package nl.amony.modules.resources.api

import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Path

import cats.effect.IO
import io.circe.{Codec, Decoder, Encoder}

/**
 * Whether a video's container is laid out so a browser can start playing it without first reading the end of the
 * file: MP4 needs its `moov` atom before `mdat`, Matroska/WebM needs its `Cues` index before the first `Cluster`.
 *
 * `Unknown` means the layout could not be determined (unsupported, truncated or corrupt file).
 */
enum Streamability(val configName: String):
  case Streamable    extends Streamability("streamable")
  case NotStreamable extends Streamability("not_streamable")
  case Unknown       extends Streamability("unknown")

object Streamability:

  given Codec[Streamability] = Codec.from(
    Decoder.decodeString.emap(name => values.find(_.configName == name).toRight(s"Unknown streamability: $name")),
    Encoder.encodeString.contramap(_.configName)
  )

  private val Mp4BoxTypes = Set("ftyp", "moov", "mdat", "moof", "free", "skip", "wide", "styp", "sidx", "pnot", "uuid")

  private val SegmentId = 0x18538067L
  private val InfoId    = 0x1549a966L
  private val TracksId  = 0x1654ae6bL
  private val CuesId    = 0x1c53bb6bL
  private val ClusterId = 0x1f43b675L

  def detect(path: Path): IO[Streamability] = IO.blocking {
    val file = new RandomAccessFile(path.toFile, "r")
    try
      if isEbml(file) then matroska(file)
      else if isMp4(file) then mp4(file)
      else Unknown
    catch case _: Exception => Unknown
    finally file.close()
  }

  private def isEbml(file: RandomAccessFile): Boolean =
    if file.length() < 4 then false
    else {
      file.seek(0)
      val bytes = new Array[Byte](4)
      file.readFully(bytes)
      bytes(0) == 0x1a.toByte && bytes(1) == 0x45.toByte && bytes(2) == 0xdf.toByte && bytes(3) == 0xa3.toByte
    }

  private def isMp4(file: RandomAccessFile): Boolean =
    if file.length() < 8 then false
    else {
      file.seek(0)
      val size = Integer.toUnsignedLong(file.readInt())
      val typ  = readFourCc(file)
      Mp4BoxTypes.contains(typ) && (size == 1 || size == 0 || size >= 8)
    }

  private def mp4(file: RandomAccessFile): Streamability = {
    val length   = file.length()
    var position = 0L
    var seenMdat = false

    while position + 8 <= length do {
      file.seek(position)
      val size32 = Integer.toUnsignedLong(file.readInt())
      val typ    = readFourCc(file)
      val size   =
        if size32 == 1 then if position + 16 <= length then file.readLong() else return Unknown
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

  private def matroska(file: RandomAccessFile): Streamability = {
    file.seek(0)
    file.skipBytes(4) // EBML element id
    val headerSize = readEbmlSize(file)
    if headerSize < 0 then return Unknown
    skip(file, headerSize)

    if readEbmlId(file) != SegmentId then return Unknown
    val segmentSize = readEbmlSize(file)
    val end         = if segmentSize < 0 then file.length() else math.min(file.length(), file.getFilePointer + segmentSize)

    var seenInfo   = false
    var seenTracks = false
    var seenCues   = false

    while file.getFilePointer < end do {
      val id   = readEbmlId(file)
      val size = readEbmlSize(file)

      id match
        case InfoId    => seenInfo   = true
        case TracksId  => seenTracks = true
        case CuesId    => seenCues   = true
        case ClusterId => return if seenInfo && seenTracks && seenCues then Streamable else NotStreamable
        case _         => ()

      if size < 0 then return Unknown
      skip(file, size)
    }

    Unknown
  }

  private def readEbmlId(file: RandomAccessFile): Long = {
    val first  = file.readUnsignedByte()
    val length = vintLength(first)
    var value  = first.toLong
    var i      = 1
    while i < length do {
      value = (value << 8) | file.readUnsignedByte()
      i += 1
    }
    value
  }

  private def readEbmlSize(file: RandomAccessFile): Long = {
    val first   = file.readUnsignedByte()
    val length  = vintLength(first)
    val mask    = 0xff >>> length
    var value   = (first & mask).toLong
    var allOnes = (first & mask) == mask
    var i       = 1
    while i < length do {
      val byte = file.readUnsignedByte()
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

  private def readFourCc(file: RandomAccessFile): String = {
    val bytes = new Array[Byte](4)
    file.readFully(bytes)
    new String(bytes, StandardCharsets.US_ASCII)
  }

  private def skip(file: RandomAccessFile, bytes: Long): Unit = file.seek(file.getFilePointer + bytes)
