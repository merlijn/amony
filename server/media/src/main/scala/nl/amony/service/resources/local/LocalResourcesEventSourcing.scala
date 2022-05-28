package nl.amony.service.resources.local

object LocalResourcesEventSourcing {

  case class ResourceAdded(relativePath: String, hashes: Set[String])
}
