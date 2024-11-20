import {Resource} from "../../api/Model";
import React, {CSSProperties, useEffect, useRef, useState} from "react";
import Plyr from 'plyr';
import {isMobile} from "react-device-detect";
import {boundedRatioBox} from "../../api/Util";
import './MediaModal.scss';
import Modal from "./Modal";

const MediaModal = (props: { resource?: Resource, onHide: () => void }) => {

  const videoElement = useRef<HTMLVideoElement>(null)
  const [plyr, setPlyr] = useState<Plyr | null>(null)

  // show modal video player
  useEffect(() => {

    const element = videoElement.current

    if (element && !plyr) {
      const plyrOptions = {
        fullscreen: {enabled: true},
        invertTime: false,
        keyboard: {focused: true, global: true},
      }

      const plyr = new Plyr(element, plyrOptions)
      setPlyr(plyr)
    }
  }, [props, videoElement, plyr]);

  useEffect(() => {

    if (plyr && props.resource) {
      plyr.source = {
        type: 'video',
        sources: [
          {
            src: props.resource.urls.originalResourceUrl,
            type: 'video/mp4',
          },
        ],
      }
    }

  }, [props.resource, plyr])

  const onHideFn = () => {
    if (plyr)
      plyr.pause()
    props.onHide()
  }

  const modalSize = (v: Resource): CSSProperties => {

    const w = isMobile ? "100vw" : "75vw"
    return boundedRatioBox(w, "75vh", v.resourceMeta.width / v.resourceMeta.height)
  }

  // let content = <div />

  // if(props.resource && props.resource.contentType.startsWith("video"))
  //   content =
  //     <video tab-index='-1' id = { `video-modal-${props.resource.resourceId}` } ref = { videoElement } playsInline controls>
  //       { props.resource && <source src={props.resource.urls.originalResourceUrl} type="video/mp4"/> }
  //     </video>
  // if(props.resource && props.resource.contentType.startsWith("image"))
  //   content = <img style = {{ width : "100%", height: "100%" }} src={props.resource.urls.originalResourceUrl} />

  return (
      <Modal visible = { props.resource !== undefined } onHide = { onHideFn }>
        <div key="video-model-content" className="video-modal-content">
          <div style={props.resource && modalSize(props.resource)}>
            <video tab-index='-1' id={`video-modal`} ref={videoElement} playsInline controls autoPlay={true}>
              {/*{props.resource && <source src = { props.resource.urls.originalResourceUrl } type="video/mp4"/>}*/}
            </video>
          </div>
        </div>
      </Modal>
  );
}


export default MediaModal
