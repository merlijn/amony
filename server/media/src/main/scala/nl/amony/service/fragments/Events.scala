package nl.amony.service.fragments

object Events {
  sealed trait Event

  object Events {

    //  def eventSource(state: Map[String, List[Fragment]], e: Event) = e match {
    //
    //    case FragmentAdded(id, from, to) =>
    //      val media        = state.media(id)
    //      val newFragments = Fragment(from, to, None, Nil) :: media.fragments
    //
    //      state.copy(media = state.media + (media.id -> media.copy(fragments = newFragments)))
    //
    //    case FragmentRangeUpdated(id, index, from, to) =>
    //      val media = state.media(id)
    //
    //      // check if specific range already exists
    //      val oldFragment      = media.fragments(index)
    //      val newFragment      = oldFragment.copy(fromTimestamp = from, toTimestamp = to)
    //      val primaryThumbnail = if (index == 0) from else media.thumbnailTimestamp
    //      val newMedia =
    //        media.copy(
    //          thumbnailTimestamp = primaryThumbnail,
    //          fragments          = media.fragments.replaceAtPos(index, newFragment)
    //        )
    //
    //      state.copy(media = state.media + (media.id -> newMedia))
    //
    //    case FragmentDeleted(id, index) =>
    //      val media        = state.media(id)
    //      val newFragments = media.fragments.deleteAtPos(index)
    //
    //      state.copy(media = state.media + (media.id -> media.copy(fragments = newFragments)))
    //
    //    case FragmentMetaDataUpdated(mediaId, index, comment, tagsAdded, tagsRemoved) =>
    //      val media           = state.media(mediaId)
    //      val fragment        = media.fragments(index)
    //      val tags            = (fragment.tags.toSet -- tagsRemoved ++ tagsAdded).toList
    //      val fragmentUpdated = fragment.copy(tags = tags, comment = comment.orElse(fragment.comment))
    //      val newFragments    = media.fragments.replaceAtPos(index, fragmentUpdated)
    //
    //      state.copy(media = state.media + (mediaId -> media.copy(fragments = newFragments)))
    //  }
  }

  case class FragmentDeleted(mediaId: String, index: Int)                                              extends Event
  case class FragmentAdded(mediaId: String, fromTimeStamp: Long, toTimestamp: Long)                    extends Event
  case class FragmentRangeUpdated(mediaId: String, index: Int, fromTimestamp: Long, toTimestamp: Long) extends Event
  case class FragmentMetaDataUpdated(
                                      mediaId: String,
                                      index: Int,
                                      comment: Option[String],
                                      tagsAdded: Set[String],
                                      tagsRemoved: Set[String]
                                    ) extends Event
}
