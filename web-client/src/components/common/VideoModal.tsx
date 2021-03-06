import {Video} from "../../api/Model";
import React, {CSSProperties, useEffect, useRef} from "react";
import Plyr from 'plyr';
import {isMobile} from "react-device-detect";
import {BoundedRatioBox} from "../../api/Util";
import './VideoModal.scss';
import Modal from "./Modal";

const VideoModal = (props: { video?: Video, onHide: () => void }) => {

  const videoElement = useRef<HTMLVideoElement>(null)

  // show modal video player
  useEffect(() => {

    const element = videoElement.current
    let plyr: Plyr | null = null

    if (element && props.video) {
      const plyrOptions = {
        fullscreen: { enabled: true },
        invertTime: false,
        keyboard: { focused: true, global: true },
        previewThumbnails: { enabled: true, src: props.video.preview_thumbnails_url} }

      const plyr = new Plyr(element, plyrOptions)
      element.load()
      plyr.play()
    }

    return () => { plyr && plyr.destroy() }
  },[props]);

  const modalSize = (v: Video): CSSProperties => {

    const w = isMobile ? "100vw" : "75vw"
    return BoundedRatioBox(w, "75vh", v.width / v.height)
  }

  return (
    <Modal visible = { props.video !== undefined } onHide = { props.onHide }>
      <div key="video-model-content" className="video-modal-content">
        {
          <div style = { props.video && modalSize(props.video) }>
            <video tab-index='-1' id={`video-modal-${props.video?.id}`} ref={videoElement} playsInline controls>
              { props.video && <source src={props.video.video_url} type="video/mp4"/> }
            </video>
          </div>
        }
      </div>
    </Modal>
  );
}

export default VideoModal
