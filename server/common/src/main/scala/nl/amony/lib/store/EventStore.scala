package nl.amony.lib.store

trait EventStore[Id, Elem, Evt] {

  def getById(id: Id): Elem

  def persist(id: Id, e: Evt): Elem
}
