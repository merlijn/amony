import {Media} from "../../api/Model";
import React, {CSSProperties, useEffect, useRef} from "react";
import Plyr from 'plyr';
import {isMobile} from "react-device-detect";
import {boundedRatioBox} from "../../api/Util";
import './MediaModal.scss';
import Modal from "./Modal";

const MediaModal = (props: { media?: Media, onHide: () => void }) => {

  const videoElement = useRef<HTMLVideoElement>(null)

  // show modal video player
  useEffect(() => {

    const element = videoElement.current
    let plyr: Plyr | null = null

    if (element && props.media) {
      const plyrOptions = {
        fullscreen: {enabled: true},
        invertTime: false,
        keyboard: {focused: true, global: true},
        previewThumbnails: {enabled: true, src: props.media.urls.previewThumbnailsUrl}
      }

      const plyr = new Plyr(element, plyrOptions)
      element.load()
      plyr.play()
    }

    return () => {
      plyr && plyr.destroy()
    }
  }, [props]);

  const modalSize = (v: Media): CSSProperties => {

    const w = isMobile ? "100vw" : "75vw"
    return boundedRatioBox(w, "75vh", v.mediaInfo.width / v.mediaInfo.height)
  }

  let content = <div />

  if(props.media && props.media.mediaInfo.mediaType.startsWith("video"))
    content =
      <video tab-index='-1' id={`video-modal-${props.media?.id}`} ref={videoElement} playsInline controls>
        { props.media && <source src={props.media.urls.originalResourceUrl} type="video/mp4"/> }
      </video>
  if(props.media && props.media.mediaInfo.mediaType.startsWith("image"))
    content = <img style = {{ width : "100%", height: "100%" }} src={props.media.urls.originalResourceUrl} />

  return (
      <Modal visible={props.media !== undefined} onHide={props.onHide}>
        <div key="video-model-content" className="video-modal-content">
          <div style={props.media && modalSize(props.media)}>
            {
              content
            }
            </div>
        </div>
      </Modal>
  );
}


export default MediaModal
