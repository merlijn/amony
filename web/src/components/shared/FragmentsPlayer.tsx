import React, {CSSProperties, useState} from "react";
import {Fragment} from "../../api/Model";

const FragmentsPlayer = (props: {className?: string, style?: CSSProperties, fragments: Array<Fragment> }) => {

  const [currentPreviewIdx, setCurrentPreviewIdx] = useState(0)

  let playPromise: Promise<void> = Promise.resolve()

  const nextPreview = (v: HTMLVideoElement) => {

    // back the the 1st (0)
    if (currentPreviewIdx < props.fragments.length - 1) {
      setCurrentPreviewIdx(currentPreviewIdx + 1)
      playPromise.then(() => {
        v.load()
        playPromise = v.play()
      })
    }

    // on to the next
    if (currentPreviewIdx > 0 && currentPreviewIdx + 1 >= props.fragments.length) {
      setCurrentPreviewIdx(0)

      playPromise.then(() => {
        v.load()
        playPromise = v.play()
      })
    }

    // no need to load a new preview if there is only 1
    if (props.fragments.length === 1) {
      play(v)
    }
  }

  const play = (v: HTMLVideoElement) => {
    playPromise.then(() => { playPromise = v.play() });
  }


  const reset = () => {
    playPromise.then(() => {
      setCurrentPreviewIdx(0)
    })
  };

  return(
    <video style={props.style ? props.style : {}}
           className={props.className} muted
           onMouseOver={(e) => play(e.currentTarget) }
           onMouseLeave={ (e) =>  reset() }
           onEnded={(e) => nextPreview(e.currentTarget )} >

      <source src={props.fragments[currentPreviewIdx].uri} type="video/mp4"/>
    </video>
  );
}

export default FragmentsPlayer