package nl.amony.modules.resources.api

import java.util.UUID

case class Collection(
  id: UUID,
  parentId: Option[UUID] = None,
  description: Option[String] = None,
  tags: Set[String] = Set.empty
)
