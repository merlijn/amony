package nl.amony.modules.resources.api

/** The fixed set of public thumbnail resolutions.
  *
  * Keys are deliberately a small, closed set: clients pick a key, never a pixel size, so a client
  * cannot make the server generate (and cache) arbitrary thumbnail sizes. Unknown keys fall back to
  * [[defaultKey]].
  */
object ThumbnailResolution:

  /** Resolution key to vertical pixel height, ordered from smallest to largest. */
  val resolutions: List[(String, Int)] = List(
    "xxs" -> 144,
    "xs"  -> 240,
    "s"   -> 352,
    "m"   -> 512,
    "l"   -> 1080, // FHD
    "xl"  -> 2160, // 4k
    "xxl" -> 4320  // 8k
  )

  val heights: Map[String, Int] = resolutions.toMap

  val defaultKey: String = "s"

  val defaultHeight: Int = heights(defaultKey)

  /** Pixel height for a resolution key, falling back to the default for unknown keys. */
  def heightFor(key: String): Int = heights.getOrElse(key, defaultHeight)
