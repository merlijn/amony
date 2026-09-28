package nl.amony.modules.resources.api

sealed trait ResourceEvent

case class ResourceAdded(resource: ResourceInfo) extends ResourceEvent

case class ResourceDeleted(resourceId: ResourceId) extends ResourceEvent

case class ResourceUpdated(resource: ResourceInfo) extends ResourceEvent

case class ResourceFileMetaChanged(resourceId: ResourceId, lastModifiedTime: Long) extends ResourceEvent

case class ResourceMoved(resourceId: ResourceId, oldPath: String, newPath: String) extends ResourceEvent
