package nl.amony.platform.subprocess.ffmpeg

/** The value for ffmpeg's `-f fmt` option, which forces the input or output file format. */
enum ContainerFormat(val fmt: String, val extension: String):
  case Mp4      extends ContainerFormat("mp4", "mp4")
  case Webm     extends ContainerFormat("webm", "webm")
  case Matroska extends ContainerFormat("matroska", "mkv")
