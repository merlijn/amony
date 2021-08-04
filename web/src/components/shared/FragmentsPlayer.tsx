import React, {CSSProperties, SyntheticEvent, useState} from "react";
import {Fragment} from "../../api/Model";

const FragmentsPlayer = (props: {className?: string, style?: CSSProperties, fragments: Array<Fragment> }) => {

  const [currentPreviewIdx, setCurrentPreviewIdx] = useState(0)

  const nextPreview = (e: SyntheticEvent<HTMLVideoElement>) => {

    // back the the 1st (0)
    if (currentPreviewIdx < props.fragments.length - 1) {
      setCurrentPreviewIdx(currentPreviewIdx + 1)
      e.currentTarget.load()
      e.currentTarget.play()
    }

    // on to the next
    if (currentPreviewIdx > 0 && currentPreviewIdx + 1 >= props.fragments.length) {
      setCurrentPreviewIdx(0)
      e.currentTarget.load()
      e.currentTarget.play()
    }

    // no need to load a new preview if there is only 1
    if (props.fragments.length === 1) {
      e.currentTarget.play()
    }
  }

  return(
    <video style={props.style ? props.style : {}}
           className={props.className} muted
           onMouseOver={(e) => e.currentTarget.play()}
           onMouseLeave={ (e) => setCurrentPreviewIdx(0) }
           onEnded={nextPreview} >

      <source src={props.fragments[currentPreviewIdx].uri} type="video/mp4"/>
    </video>
  );
}

export default FragmentsPlayer