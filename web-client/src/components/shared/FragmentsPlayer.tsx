import React, { CSSProperties, useEffect, useState } from "react";
import { Fragment } from "../../api/Model";

type FragmentsPlayerProps = {
  id: string,
  className?: string,
  style?: CSSProperties,
  fragments: Array<Fragment>
  onClick?: () => void
  onMouseLeave?: (v: HTMLVideoElement) => void
}

const FragmentsPlayer = (props: FragmentsPlayerProps) => {

  const [currentPreviewIdx, setCurrentPreviewIdx] = useState(0)
  const [playPromise, setPlayPromise] = useState<Promise<void>>(Promise.resolve())

  // sort the fragments by start time
  props.fragments.sort((a, b) => a.timestamp_start > b.timestamp_start ? 1 : -1)

  useEffect(() => {

    play(document.getElementById(props.id) as HTMLVideoElement, true)

  }, [currentPreviewIdx])

  const playNext = (v: HTMLVideoElement) => {

    let idx = currentPreviewIdx + 1
    if (idx >= props.fragments.length)
      idx = 0

    setCurrentPreviewIdx(idx)

    if (props.fragments.length === 1)
      play(v, false)
  }

  const play = (v: HTMLVideoElement, load: boolean) => {
    playPromise.then(() => { 
      if (load) {
        // https://github.com/sampotts/plyr/issues/331#issuecomment-529398384
        v.pause()
        v.addEventListener("canplay", function onCanPlay() {
          v.removeEventListener("canplay", onCanPlay);
          setPlayPromise(v.play())
        });
        v.load()
      } 
      else if (v.paused)
        setPlayPromise(v.play())
    });
  }

  const onMouseLeave = (v: HTMLVideoElement) => {

    playPromise.then(() => {
      if (props.onMouseLeave !== undefined)
        props.onMouseLeave(v)
    });
  };

  return(
    <video id = {props.id}
           style = {props.style ? props.style : {} }
           className = {props.className} muted
           onClick = { (e) => props.onClick && props.onClick() }
           onMouseOver = { (e) => play(e.currentTarget, false) }
           onMouseLeave = { (e) =>  onMouseLeave(e.currentTarget) }
           onEnded = { (e) => playNext(e.currentTarget) } 
           preload = 'none' >

      <source src={props.fragments[currentPreviewIdx].urls[0]} type="video/mp4"/>
    </video>
  );
}

export default FragmentsPlayer
