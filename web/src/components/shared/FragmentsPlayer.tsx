import React, {CSSProperties, useEffect, useState} from "react";
import {Fragment} from "../../api/Model";

const FragmentsPlayer = (props: {id: string, className?: string, style?: CSSProperties, fragments: Array<Fragment> }) => {

  const [currentPreviewIdx, setCurrentPreviewIdx] = useState(0)

  let playPromise: Promise<void> = Promise.resolve()

  useEffect(() => {
    const element = document.getElementById(props.id) as HTMLVideoElement;

    playPromise.then(() => {
      element.load()
      playPromise = element.play()
    });
  }, [currentPreviewIdx])

  const nextPreview = (v: HTMLVideoElement) => {

    // back the 1st (0)
    if (currentPreviewIdx < props.fragments.length - 1) {
      setCurrentPreviewIdx(currentPreviewIdx + 1)
    }

    // on to the next
    if (currentPreviewIdx > 0 && currentPreviewIdx + 1 >= props.fragments.length) {
      setCurrentPreviewIdx(0)
    }

    play(v)
  }

  const play = (v: HTMLVideoElement) => {
    playPromise.then(() => { playPromise = v.play() });
  }


  const reset = (v: HTMLVideoElement) => {

    playPromise.then(() => {
      setCurrentPreviewIdx(0)
    });
  };

  return(
    <video id={props.id}
           style={props.style ? props.style : {}}
           className={props.className} muted
           onMouseOver={(e) => play(e.currentTarget) }
           onMouseLeave={ (e) =>  reset(e.currentTarget) }
           onEnded={(e) => nextPreview(e.currentTarget )} >

      <source src={props.fragments[currentPreviewIdx].uri} type="video/mp4"/>
    </video>
  );
}

export default FragmentsPlayer