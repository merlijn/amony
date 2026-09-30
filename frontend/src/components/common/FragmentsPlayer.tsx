import React, { CSSProperties, useEffect, useRef, useState } from "react";
import {ClipDto} from "../../api/generated";
import {useAppConfig} from "../../api/ConfigContext";
import {clipUrl} from "../../api/ResourceUrls";

type FragmentsPlayerProps = {
  className?: string,
  style?: CSSProperties,
  fragments: Array<ClipDto>
  /** CSS width of the media slot, used to pick an appropriately sized clip. */
  boxWidthPx: number
  onClick?: () => void
}

const FragmentsPlayer = (props: FragmentsPlayerProps) => {

  const [currentPreviewIdx, setCurrentPreviewIdx] = useState(0)
  // const [playPromise, setPlayPromise] = useState<Promise<void>>(Promise.resolve())
  const videoRef = useRef<HTMLVideoElement>(null)
  const config   = useAppConfig()

  // sort the fragments by start time
  props.fragments.sort((a, b) => a.start > b.start ? 1 : -1)

  useEffect(() => {

    if (videoRef.current && videoRef.current.paused)
      loadAndPlay(videoRef.current)

  }, [currentPreviewIdx, videoRef])

  const loadAndPlay = (v: HTMLVideoElement) => {
    // playPromise.then(() => {
      v.addEventListener("canplay", function onCanPlay() {
        v.removeEventListener("canplay", onCanPlay);
        v.play()
      });
      v.load()
    // })
  }

  const playCurrent = (v: HTMLVideoElement) => {
    v.play()
    // playPromise.then(() => {
    //   setPlayPromise(v.play())
    // });
  }

  const playNext = (v: HTMLVideoElement) => {

    let idx = currentPreviewIdx + 1
    if (idx >= props.fragments.length)
      idx = 0

    if (idx !== currentPreviewIdx)
      setCurrentPreviewIdx(idx)
    else
      playCurrent(v)
  }

  const currentFragment = props.fragments[currentPreviewIdx]

  return(
    <video ref = { videoRef }
           style = {props.style ? props.style : {} }
           className = {props.className} muted
           onClick = { (e) => props.onClick && props.onClick() }
           onMouseOver = { (e) => playCurrent(e.currentTarget) }
           onEnded = { (e) => playNext(e.currentTarget) }
           preload = 'none' >

      { currentFragment &&
        <source src = { clipUrl(currentFragment, config, props.boxWidthPx) } type="video/mp4"/> }
    </video>
  );
}

export default FragmentsPlayer
