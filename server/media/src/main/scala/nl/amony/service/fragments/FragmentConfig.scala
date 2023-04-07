package nl.amony.service.fragments

import scala.concurrent.duration.FiniteDuration

case class FragmentSettings(
  defaultFragmentLength: FiniteDuration,
  minimumFragmentLength: FiniteDuration,
  maximumFragmentLength: FiniteDuration,
)
