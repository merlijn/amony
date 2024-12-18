import React, { CSSProperties, useEffect, useRef, useState } from "react";
import { Clip } from "../../api/Model";

type FragmentsPlayerProps = {
  className?: string,
  style?: CSSProperties,
  fragments: Array<Clip>
  onClick?: () => void
}

const FragmentsPlayer = (props: FragmentsPlayerProps) => {

  const [currentPreviewIdx, setCurrentPreviewIdx] = useState(0)
  // const [playPromise, setPlayPromise] = useState<Promise<void>>(Promise.resolve())
  const videoRef = useRef<HTMLVideoElement>(null)

  // sort the fragments by start time
  props.fragments.sort((a, b) => a.range[0] > b.range[0] ? 1 : -1)

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

  return(
    <video ref = { videoRef }
           style = {props.style ? props.style : {} }
           className = {props.className} muted
           onClick = { (e) => props.onClick && props.onClick() }
           onMouseOver = { (e) => playCurrent(e.currentTarget) }
           onEnded = { (e) => playNext(e.currentTarget) }
           preload = 'none' >

      <source src = { props.fragments[currentPreviewIdx].urls[0] } type="video/mp4"/>
    </video>
  );
}

export default FragmentsPlayer
