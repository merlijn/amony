package nl.amony.modules.resources.api

case class Tag(owner: String, label: String)

object Tag:
  def user(label: String): Tag   = Tag(TagOwner.User, label)
  def system(label: String): Tag = Tag(TagOwner.System, label)

object TagOwner:
  val User: String              = "user"
  val System: String            = "system"
  def app(name: String): String = s"app:$name"
