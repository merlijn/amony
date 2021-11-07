import React, {CSSProperties, useEffect, useState} from "react";
import {Fragment} from "../../api/Model";

type FragmentsPlayerProps = {
  id: string,
  className?: string,
  style?: CSSProperties,
  fragments: Array<Fragment>
  onClick?: () => void
}

const FragmentsPlayer = (props: FragmentsPlayerProps) => {

  const [currentPreviewIdx, setCurrentPreviewIdx] = useState(0)

  let playPromise: Promise<void> = Promise.resolve()

  // sort the fragments by start time
  props.fragments.sort((a, b) => a.timestamp_start > b.timestamp_start ? 1 : -1)

  useEffect(() => {
    const videoElement = document.getElementById(props.id) as HTMLVideoElement;

    playPromise.then(() => {
      videoElement.load()
      playPromise = videoElement.play()
    });
  }, [currentPreviewIdx])

  const nextFragment = (v: HTMLVideoElement) => {

    let idx = currentPreviewIdx + 1

    // back the 1st (0)
    if (idx >= props.fragments.length)
      idx = 0

    console.log(`size: ${props.fragments.length}, index: ${idx}`)
    setCurrentPreviewIdx(idx)
    play(v)
  }

  const play = (v: HTMLVideoElement) => {
    playPromise.then(() => { playPromise = v.play() });
  }

  const reset = (v: HTMLVideoElement) => {

    playPromise.then(() => {
      v.pause()
      setCurrentPreviewIdx(0)
    });
  };

  return(
    <video id={props.id}
           style={props.style ? props.style : {}}
           className={props.className} muted
           onClick={(e) => props.onClick && props.onClick() }
           onMouseOver={(e) => play(e.currentTarget) }
           onMouseLeave={ (e) =>  reset(e.currentTarget) }
           onEnded={(e) => nextFragment(e.currentTarget )} >

      <source src={props.fragments[currentPreviewIdx].urls[0]} type="video/mp4"/>
    </video>
  );
}

export default FragmentsPlayer
