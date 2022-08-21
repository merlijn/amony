package nl.amony.lib.store

trait EventStore[Id, Elem, Evt] {

  def index(): Iterable[Id]

  def getEvents(id: Id): Iterable[Evt]

  def getById(id: Id): Elem

  def persist(id: Id, e: Evt): Elem

  def delete(id: Id): Unit
}
