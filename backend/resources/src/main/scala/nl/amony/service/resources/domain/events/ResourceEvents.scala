package nl.amony.service.resources.domain.events

import nl.amony.service.resources.domain.ResourceInfo

sealed trait ResourceEvent

case class ResourceAdded(resource: ResourceInfo) extends ResourceEvent

case class ResourceDeleted(resourceId: String) extends ResourceEvent

case class ResourceUpdated(resource: ResourceInfo) extends ResourceEvent

case class ResourceFileMetaChanged(
  resourceId: String,
  lastModifiedTime: Long
) extends ResourceEvent

case class ResourceMoved(
  resourceId: String,
  oldPath: String,
  newPath: String
) extends ResourceEvent
