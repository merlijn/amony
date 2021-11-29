import {Video} from "../../api/Model";
import React, {CSSProperties, useEffect, useRef} from "react";
import Plyr from 'plyr';
import {isMobile} from "react-device-detect";
import {BoundedRatioBox} from "../../api/Util";
import './VideoModal.scss';
import { useListener } from "../../api/ReactUtils";

const VideoModal = (props: { video: Video, onHide: () => void }) => {

  const videoElement = useRef<HTMLVideoElement>(null)

  // show modal video player
  useEffect(() => {

    const element = videoElement.current
    let plyr: Plyr | null = null

    if (element) {
      const plyrOptions = {
        fullscreen : { enabled: true },
        invertTime: false,
        keyboard: { focused: true, global: true },
        previewThumbnails: { enabled: false, src: props.video.preview_thumbnails_url} }

      const plyr = new Plyr(element, plyrOptions)
      element.load()
      plyr.play()
    }

    return () => {
      if (plyr)
        plyr.destroy()
    }
  },[]);

  const modalSize = (v: Video): CSSProperties => {

    const w = isMobile ? "100vw" : "75vw"
    return BoundedRatioBox(w, "75vh", v.width / v.height)
  }

  return (
    <div
      key="gallery-video-player"
      className="video-modal-container"
      style={ { display: "block" }}>

      <div key="video-model-background"
           className="video-modal-background"
           onClick = { (e) => props.onHide() }
      />

      <div key="video-model-content" className="video-modal-content">
        {
          <div style={modalSize(props.video)}>
            <video tab-index='-1' id={`video-modal-${props.video.id}`} ref={videoElement} playsInline controls>
              { props.video && <source src={props.video.video_url} type="video/mp4"/> }
            </video>
          </div>
        }
      </div>
    </div>
  );
}

export default VideoModal
