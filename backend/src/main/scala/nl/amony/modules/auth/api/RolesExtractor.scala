package nl.amony.modules.auth.api

import io.circe.Json
import pureconfig.generic.derivation.EnumConfigReader

/** How to interpret the roles claim of an identity provider's userinfo response. */
enum RolesFrom derives EnumConfigReader:
  case Array, ObjectKeys

object RolesExtractor:

  /**
   * Reads the roles from the userinfo response. The claim is a dot-path (e.g. `realm_access.roles`).
   * With [[RolesFrom.Array]] the value is expected to be an array of strings; with
   * [[RolesFrom.ObjectKeys]] it is an object whose keys are the roles (e.g. Zitadel's
   * `urn:zitadel:iam:org:project:roles`). Anything absent or of an unexpected shape yields no roles.
   */
  def extract(userInfo: Json, claim: String, rolesFrom: RolesFrom): Set[Role] =
    val value = claim.split("\\.").foldLeft(Option(userInfo)) { (current, key) =>
      current.flatMap(_.asObject).flatMap(_(key))
    }

    rolesFrom match
      case RolesFrom.Array      => value.flatMap(_.asArray).getOrElse(Vector.empty).flatMap(_.asString).map(Role.apply).toSet
      case RolesFrom.ObjectKeys => value.flatMap(_.asObject).map(_.keys.map(Role.apply).toSet).getOrElse(Set.empty)
