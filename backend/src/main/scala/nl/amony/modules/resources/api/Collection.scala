package nl.amony.modules.resources.api

import nl.amony.modules.auth.api.UserId

case class Collection(
  id: CollectionId,
  parentId: Option[CollectionId] = None,
  userId: UserId,
  name: String,
  description: Option[String]    = None,
  tags: Set[String]              = Set.empty
)
