package nl.amony.modules.auth.api

import io.circe.Json
import pureconfig.generic.derivation.EnumConfigReader

/** How to interpret the roles claim of an identity provider's userinfo response. */
enum RolesFrom derives EnumConfigReader:
  case Array, ObjectKeys

object RolesExtractor:

  /** Reads roles from the userinfo response: a dot-path claim, read as an array of names or as an object's keys. */
  def extract(userInfo: Json, claim: String, rolesFrom: RolesFrom): Set[Role] =
    val value = claim.split("\\.").foldLeft(Option(userInfo)) { (current, key) =>
      current.flatMap(_.asObject).flatMap(_(key))
    }

    rolesFrom match
      case RolesFrom.Array      => value.flatMap(_.asArray).getOrElse(Vector.empty).flatMap(_.asString).map(Role.apply).toSet
      case RolesFrom.ObjectKeys => value.flatMap(_.asObject).map(_.keys.map(Role.apply).toSet).getOrElse(Set.empty)
