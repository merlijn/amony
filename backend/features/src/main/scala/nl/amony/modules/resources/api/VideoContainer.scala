package nl.amony.modules.resources.api

/** Video container formats amony knows how to lay out for progressive HTTP playback. */
enum VideoContainer(val extension: String):
  case Mp4      extends VideoContainer("mp4")
  case Webm     extends VideoContainer("webm")
  case Matroska extends VideoContainer("mkv")

object VideoContainer:

  /**
   * Resolves the container from the content type reported by the metadata scanner, if it is supported.
   *
   * `application/x-matroska` is Tika's shared base for the whole Matroska family (WebM is a subclass), so it is
   * reported for both `.mkv` and `.webm` files whose extension does not disambiguate them. It maps to Matroska, whose
   * muxer accepts every codec WebM does, so a copy remux cannot fail on container grounds.
   */
  def fromContentType(contentType: String): Option[VideoContainer] = contentType.toLowerCase match
    case "video/mp4" | "video/quicktime" | "video/x-m4v" => Some(Mp4)
    case "video/webm"                                    => Some(Webm)
    case "video/x-matroska" | "application/x-matroska"   => Some(Matroska)
    case _                                               => None
