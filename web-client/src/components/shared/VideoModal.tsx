import {Video} from "../../api/Model";
import React, {CSSProperties, useEffect, useRef} from "react";
import Plyr from 'plyr';
import {isMobile} from "react-device-detect";
import {BoundedRatioBox} from "../../api/Util";
import './VideoModal.scss';

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
        previewThumbnails: { enabled: true, src: props.video.preview_thumbnails_uri} }

      const plyr = new Plyr(element, plyrOptions)
      element.load()
      plyr.play()
    }

    return () => {
      if (plyr)
        plyr.destroy()
    }
  },[props]);

  const modalSize = (v: Video | undefined): CSSProperties => {

    const w = isMobile ? "100vw" : "75vw"
    return v ? BoundedRatioBox(w, "75vh", v.resolution_x / v.resolution_y) : { }
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
            <video id={`video-modal-${props.video.id}`} ref={videoElement} playsInline controls>
              { props.video && <source src={'/files/videos/' + props.video.id} type="video/mp4"/> }
            </video>
          </div>
        }
      </div>
    </div>
  );
}

export default VideoModal