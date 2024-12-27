package nl.amony.service.auth

opaque type Role = String

object Role:
  def apply(d: String): Role = d

extension (role: Role)
  def value: String = role

object Roles:
  val Admin = Role("admin")