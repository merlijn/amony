package nl.amony.modules.resources.api

/** Whether generated previews may be stored by shared caches (`Public`) or only by the client's private cache. */
enum CacheScope(val configName: String):
  case Public  extends CacheScope("public")
  case Private extends CacheScope("private")
