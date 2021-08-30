package nl.amony

package object lib {

  implicit class ListOps[T](list: List[T]) {
    def replaceAtPos(idx: Int, e: T): List[T] = {
      list.slice(0, idx) ::: (e :: list.slice(idx + 1, list.size))
    }

    def deleteAtPos(idx: Int): List[T] = {
      list.slice(0, idx) ::: list.slice(idx + 1, list.size)
    }
  }
}
