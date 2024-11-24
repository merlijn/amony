import {Resource} from "../../api/Model";
import React, {CSSProperties, useEffect, useRef, useState} from "react";
import {isMobile} from "react-device-detect";
import {boundedRatioBox} from "../../api/Util";
import './ResourceViewModal.css';
import Modal from "./Modal";
import {
  isHLSProvider,
  MediaPlayer,
  MediaPlayerInstance, MediaProvider,
  MediaProviderAdapter,
  MediaProviderChangeEvent,
} from "@vidstack/react";

import {
  defaultLayoutIcons,
  DefaultVideoLayout,
} from '@vidstack/react/player/layouts/default';

const ResourceViewModal = (props: { resource?: Resource, onHide: () => void }) => {

  let player = useRef<MediaPlayerInstance>(null)
  let [src, setSrc] = useState('');

  // show modal video player
  useEffect(() => {
    setSrc(props.resource?.urls.originalResourceUrl || '')
  }, [props.resource]);

  useEffect(() => {
    console.log('player', player.current?.title)
  }, [player])

  const modalSize = (v: Resource): CSSProperties => {

    const w = isMobile ? "100vw" : "75vw"
    return boundedRatioBox(w, "75vh", v.resourceMeta.width / v.resourceMeta.height)
  }

  function onProviderChange(
      provider: MediaProviderAdapter | null,
      nativeEvent: MediaProviderChangeEvent,
  ) {
    console.log('provider', provider)
    if (!provider) {
      return;
    }
    // We can configure provider's here.
    if (isHLSProvider(provider)) {
      provider.config = {};
    }
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
          <>
            <MediaPlayer
              className = "player"
              tab-index = '-1'
              ref = { player }
              src = { { src: src, type: "video/mp4"  } }
              title = { props.resource?.userMeta.title }
              playsInline
              style = { !isVideo ? { display: "none" } : {} }
              controlsDelay = { 5000 }
              hideControlsOnMouseLeave = { true }
              // keep-alive
              // logLevel = "debug"
              autoPlay = { true }
              onDestroy = { () => console.log('destroyed') }
              // onCanPlay = { autoPlay }
              onProviderChange = { onProviderChange }
            >
              <MediaProvider />
              <DefaultVideoLayout icons = { defaultLayoutIcons } />
            </MediaPlayer>
            { isImage &&  <img style = {{ width: "100%", height: "100%", visibility : isImage ? "visible" : "hidden" }} src = { src }/> }
          </>
        </div>
      </Modal>
  );
}


export default ResourceViewModal