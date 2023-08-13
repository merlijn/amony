import React, { CSSProperties, useEffect, useState } from 'react';
import ProgressiveImage from "react-progressive-graceful-image";
import { Api } from "../api/Api";
import { Resource } from "../api/Model";
import {dateMillisToString, durationInMillisToString, labelForResolution} from "../api/Util";
import Dialog from './common/Dialog';
import { DropDown, MenuItem } from './common/DropDown';
import FragmentsPlayer from "./common/FragmentsPlayer";
import ImgWithAlt from "./common/ImgWithAlt";
import Modal from './common/Modal';
import MediaInfo from './dialogs/MediaInfo';
import './Preview.scss';

export type PreviewProps = {
  resource: Resource,
  style?: CSSProperties,
  className?: string,
  lazyLoad?: boolean,
  options: PreviewOptions,
  onClick: (v: Resource) => any
}

export type PreviewOptions = {
  showPreviewOnHover: boolean,
  showPreviewOnHoverDelay?: number,
  showInfoBar: boolean,
  showDates: boolean,
  showDuration: boolean,
  showMenu: boolean,
}

const Preview = (props: PreviewProps) => {
  const [media, setMedia] = useState(props.resource)
  const [isHovering, setIsHovering] = useState(false)
  const [showVideoPreview, setShowVideoPreview] = useState(false)

  const durationStr = durationInMillisToString(media.resourceMeta.duration)

  const isVideo = media.resourceMeta.mediaType.startsWith("video")

  useEffect(() => {
    setShowVideoPreview(isHovering)
  }, [isHovering])

  const titlePanel =
      <div className = "preview-info-bar">
        <span className="media-title" title={media.userMeta.title}>{media.userMeta.title}</span>
        { props.options.showDates && <span className="media-date">{dateMillisToString(media.uploadTimestamp)}</span> }
        { !props.options.showDates && <span className="media-date">{`${media.resourceMeta.height}p` }</span>}
      </div>

  const overlay =
      <div className="preview-overlay">
        {
            (props.options.showMenu && isHovering) &&
            <div className = "preview-menu-container">
              <PreviewMenu
                  resource     = { media }
                  setVideo     = { setMedia }
                  onDialogOpen = { () => { setShowVideoPreview(false) } }/>
            </div>
        }
        { (isVideo && props.options.showDuration) && <div className="duration-overlay">{durationStr}</div> }
        {/* { <div className="abs-bottom-right"><FiDownload /></div> } */}
      </div>

  const primaryThumbnail =
      <ProgressiveImage src = { media.urls.thumbnailUrl } placeholder="/image_placeholder.svg">
        { (src: string) =>
            <img
                src       = { src } alt="an image"
                onClick   = { () => props.onClick(props.resource) }
                className = { `preview-thumbnail preview-media` }
            />
        }
      </ProgressiveImage>

  const videoPreview =
      <FragmentsPlayer
          key       = { `video-preview-${props.resource.id}` }
          className = { `preview-video preview-media` }
          onClick   = { () => props.onClick(props.resource) }
          fragments = { props.resource.highlights } />

  const preview =
      <div className    = "preview-media-container"
           onMouseEnter = { () => props.options.showPreviewOnHover && setIsHovering(true) }
           onMouseLeave = { () => setIsHovering(false) }>
        { isVideo && showVideoPreview && videoPreview }
        { primaryThumbnail }
        { overlay }
      </div>

  return (
      <div className = "preview-media">
        { preview }
        { props.options.showInfoBar && titlePanel }
      </div>
  )
}

const PreviewMenu = (props: {resource: Resource, setVideo: (v: Resource) => void, onDialogOpen: () => any}) => {

  const [showDeleteDialog, setShowDeleteDialog] = useState(false);
  const [showInfoModal, setShowInfoModal] = useState(false)

  const cancelDelete = () => setShowDeleteDialog(false);
  const confirmDelete = () => {
    Api.deleteMediaById(props.resource.id).then(() => {
      console.log("video was deleted")
      setShowDeleteDialog(false)
    })
  };

  return (
    <>
      <Modal visible = { showInfoModal } onHide = {() => setShowInfoModal(false)} >
        <MediaInfo 
          meta = { props.resource.userMeta }
          onClose = { (meta) => {
            Api.updateMediaMetaData(props.resource.bucketId, props.resource.id, meta).then(() => {
              props.setVideo({...props.resource, userMeta: meta });
              setShowInfoModal(false)
            })
          } } 
        />
      </Modal>
      
      <Modal visible = { showDeleteDialog } onHide = { cancelDelete }>
        <Dialog title = "Are you sure?">
          <p>Do you want to delete: '{props.resource.userMeta.title}'</p>
          <p>
            <button className = "button-primary" onClick = { confirmDelete }>Yes</button>
            <button onClick = { cancelDelete }>No / Cancel</button>
          </p>
        </Dialog>
      </Modal>

      <div className = "preview-menu">

        <DropDown 
          align = 'right' 
          contentClassName = "dropdown-menu" 
          toggleIcon = { <ImgWithAlt className = "preview-menu-icon" src="/icons/more.svg" /> } 
          hideOnClick = {true} >
          <MenuItem onClick = { () => { setShowInfoModal(true); props.onDialogOpen() } }>
            <ImgWithAlt className="menu-icon" src="/icons/info.svg" />Info
          </MenuItem>
          <MenuItem href={`/editor/${props.resource.id}`}>
            <ImgWithAlt className="menu-icon" src="/icons/edit.svg" />Fragments
          </MenuItem>
          <MenuItem onClick = { () => { setShowDeleteDialog(true); props.onDialogOpen() } }>
            <ImgWithAlt className="menu-icon" src="/icons/delete.svg" />Delete
          </MenuItem>
        </DropDown>
      </div>
    </>
  );
}

export default Preview
