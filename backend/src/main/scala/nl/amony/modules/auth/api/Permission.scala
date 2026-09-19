package nl.amony.modules.auth.api

import pureconfig.generic.derivation.EnumConfigReader

enum Permission derives EnumConfigReader:
  case SearchResources
  case ManageResources
  case PreviewResource
  case ViewResource
  case Download
  case UploadResource
  case ManageCollections
  case Admin
