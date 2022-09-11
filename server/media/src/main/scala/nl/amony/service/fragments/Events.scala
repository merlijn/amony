package nl.amony.service.fragments

object Events {
  sealed trait Event

  object Events {

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
