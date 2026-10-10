package nl.amony.modules.resources.api

sealed trait ResourceEvent derives io.circe.Codec

case class ResourceAdded(resource: ResourceInfo) extends ResourceEvent derives io.circe.Codec

case class ResourceDeleted(resourceId: ResourceId) extends ResourceEvent derives io.circe.Codec

case class ResourceUpdated(resource: ResourceInfo) extends ResourceEvent derives io.circe.Codec

case class ResourceFileMetaChanged(resourceId: ResourceId, lastModifiedTime: Long) extends ResourceEvent derives io.circe.Codec

case class ResourceMoved(resourceId: ResourceId, oldPath: String, newPath: String) extends ResourceEvent derives io.circe.Codec
