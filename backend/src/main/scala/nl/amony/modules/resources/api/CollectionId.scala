package nl.amony.modules.resources.api

import java.util.UUID

opaque type CollectionId <: UUID = UUID

object CollectionId:
  def apply(id: UUID): CollectionId = id

  extension (id: CollectionId)
    def value: UUID = id
