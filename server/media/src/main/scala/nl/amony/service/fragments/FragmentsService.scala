package nl.amony.service.fragments

import nl.amony.service.media.actor.MediaLibProtocol.Fragment

class FragmentsService {

  def getFragments(mediaId: String, userId: String): List[Fragment] = ???

  def addFragment(mediaId: String, userId: String, index: Int, range: (Long, Long)): Unit = ???
}
