package nl.amony.modules.resources.api

opaque type ResourceId <: String = String

object ResourceId:
  def apply(id: String): ResourceId = id
