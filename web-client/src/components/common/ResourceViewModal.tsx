import React, {CSSProperties, MouseEventHandler, useEffect, useRef, useState} from "react";
import {isMobile} from "react-device-detect";
import {boundedRatioBox} from "../../api/Util";
import './ResourceViewModal.css';
import Modal from "./Modal";
import {MediaPlayer, MediaPlayerInstance, MediaProvider,} from "@vidstack/react";

import {defaultLayoutIcons, DefaultVideoLayout,} from '@vidstack/react/player/layouts/default';
import {ResourceDto} from "../../api/generated";

const ResourceViewModal = (props: { resource?: ResourceDto, onHide: () => void }) => {

  let player = useRef<MediaPlayerInstance>(null)
  let [src, setSrc] = useState('');

  // show modal video player
  useEffect(() => {
    setSrc(props.resource?.urls.originalResourceUrl || '')
  }, [props.resource]);

  const modalSize = (v: ResourceDto): CSSProperties => {
    return boundedRatioBox(isMobile ? "100vw" : "75vw", "75vh", v.contentMeta.width / v.contentMeta.height)
  }

  let isVideo = props.resource?.contentType.startsWith("video") || false
  let isImage = props.resource?.contentType.startsWith("image") || false
  // let src = props.resource?.urls.originalResourceUrl || ''

  const onHide = () => {
    player.current?.pause()
    props.onHide()
  }

  return (
      <Modal visible = { props.resource !== undefined } onHide = { onHide }>
        <div className="video-modal-content" style = { props.resource && modalSize(props.resource)}>
          <MediaPlayer
            className = "player"
            tab-index = '-1'
            playsInline
            ref = { player }
            src = { { src: src, type: "video/mp4"  } }
            title = { props.resource?.title }
            style = { !isVideo ? { display: "none" } : {} }
            controlsDelay = { 5000 }
            hideControlsOnMouseLeave = { true }
            volume = { 0.4 }
            // keep-alive
            // logLevel = "debug"
            autoPlay = { true }
            onDestroy = { () => console.log('destroyed') }
            // onCanPlay = { autoPlay }
            // onProviderChange = { onProviderChange }
          >
            <MediaProvider />
            <DefaultVideoLayout icons = { defaultLayoutIcons } />
          </MediaPlayer>
          { isImage &&  <img style = {{ width: "100%", height: "100%", visibility : isImage ? "visible" : "hidden" }} src = { src }/> }
        </div>
      </Modal>
  );
}


export default ResourceViewModal
