import React, {CSSProperties, useEffect, useRef, useState} from "react";
import {isMobile} from "react-device-detect";
import {boundedRatioBox} from "../../api/Util";
import './ResourceViewModal.css';
import Modal from "./Modal";
import {MediaPlayer, MediaPlayerInstance, MediaProvider, VideoMimeType,} from "@vidstack/react";

import {defaultLayoutIcons, DefaultVideoLayout,} from '@vidstack/react/player/layouts/default';
import {ResourceDto} from "../../api/generated";
import ThumbnailEditor from "./ThumbnailEditor";
import {useEventBus} from "./EventBus";

const ResourceViewModal = (props: { resource?: ResourceDto, onHide: () => void }) => {

  let player = useRef<MediaPlayerInstance>(null)
  let [src, setSrc] = useState('');
  let [resource, setResource] = useState<ResourceDto | undefined>(props.resource);
  let eventBus = useEventBus()

  // show modal video player
  useEffect(() => {
    setResource(props.resource);
    setSrc(props.resource?.urls.originalResourceUrl || '')
  }, [props.resource]);

  const modalSize = (v: ResourceDto): CSSProperties => {
    return boundedRatioBox(isMobile ? "100vw" : "75vw", "75vh", v.contentMeta.width / v.contentMeta.height)
  }

  let isVideo = resource?.contentType.startsWith("video") || false
  let isImage = resource?.contentType.startsWith("image") || false

  function toVideoMimeType(value?: string): VideoMimeType {
    // Add basic mime type validation
    const mimePattern = /^video\/(mp4|webm|3gp|ogg|avi|mpeg|object)$/;
    return value && mimePattern.test(value) ? (value as VideoMimeType) : 'video/mp4';
  }

  let contentType: VideoMimeType = toVideoMimeType(resource?.contentType);

  const onHide = () => {
    player.current?.pause()
    props.onHide()
  }

  return (
      <Modal visible = { resource !== undefined } onHide = { onHide }>
        <div className="video-modal-content" style = { resource && modalSize(resource)}>
          <MediaPlayer
            className = "player"
            tab-index = '-1'
            playsInline
            ref = { player }
            src = { { src: src, type: contentType  } }
            title = { resource?.title }
            style = { !isVideo ? { display: "none" } : {} }
            controlsDelay = { 2000 }
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
          { isVideo && resource &&
            <ThumbnailEditor
              resource = { resource }
              player = { player }
              onResourceUpdated = { (updated) => {
                  setResource(updated)
                  eventBus.emit("resource-updated", updated)
                }
              }
            />
          }
        </div>
      </Modal>
  );
}


export default ResourceViewModal
