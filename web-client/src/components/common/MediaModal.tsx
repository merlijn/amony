import {Resource} from "../../api/Model";
import React, {CSSProperties, useEffect, useRef} from "react";
import Plyr from 'plyr';
import {isMobile} from "react-device-detect";
import {boundedRatioBox} from "../../api/Util";
import './MediaModal.scss';
import Modal from "./Modal";

const MediaModal = (props: { resource?: Resource, onHide: () => void }) => {

  const videoElement = useRef<HTMLVideoElement>(null)

  // show modal video player
  useEffect(() => {

    const element = videoElement.current
    let plyr: Plyr | null = null

    if (element && props.resource) {
      const plyrOptions = {
        fullscreen: {enabled: true},
        invertTime: false,
        keyboard: {focused: true, global: true},
        previewThumbnails: {enabled: false, src: props.resource.urls.previewThumbnailsUrl}
      }

      const plyr = new Plyr(element, plyrOptions)
      // element.load()
      plyr.play()
    }

    return () => {
      plyr && plyr.destroy()
    }
  }, [props]);

  const modalSize = (v: Resource): CSSProperties => {

    const w = isMobile ? "100vw" : "75vw"
    return boundedRatioBox(w, "75vh", v.resourceMeta.width / v.resourceMeta.height)
  }

  let content = <div />

  if(props.resource && props.resource.contentType.startsWith("video"))
    content =
      <video tab-index='-1' id={`video-modal-${props.resource.resourceId}`} ref={videoElement} playsInline controls>
        { props.resource && <source src={props.resource.urls.originalResourceUrl} type="video/mp4"/> }
      </video>
  if(props.resource && props.resource.contentType.startsWith("image"))
    content = <img style = {{ width : "100%", height: "100%" }} src={props.resource.urls.originalResourceUrl} />

  return (
      <Modal visible={props.resource !== undefined} onHide={props.onHide}>
        <div key="video-model-content" className="video-modal-content">
          <div style={props.resource && modalSize(props.resource)}>
            {
              content
            }
            </div>
        </div>
      </Modal>
  );
}


export default MediaModal
