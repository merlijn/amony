import React, { CSSProperties, useEffect, useState } from 'react';
import ProgressiveImage from "react-progressive-graceful-image";
import { Api } from "../api/Api";
import { Video } from "../api/Model";
import { dateMillisToString, durationInMillisToString } from "../api/Util";
import Dialog from './common/Dialog';
import { DropDown, MenuItem } from './common/DropDown';
import FragmentsPlayer from "./common/FragmentsPlayer";
import ImgWithAlt from "./common/ImgWithAlt";
import Modal from './common/Modal';
import MediaInfo from './dialogs/MediaInfo';
import './Preview.scss';

export type PreviewProps = {
  vid: Video,
  style?: CSSProperties,
  className?: string,
  lazyLoad?: boolean,
  options: PreviewOptions,
  onClick: (v: Video) => any
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

  const [vid, setVid] = useState(props.vid)
  const [isHovering, setIsHovering] = useState(false)
  const [showVideoPreview, setShowVideoPreview] = useState(false)

  const durationStr = durationInMillisToString(vid.mediaInfo.duration)

  useEffect(() => {
    setShowVideoPreview(isHovering)
  }, [isHovering])

  const titlePanel =
    <div className = "preview-info-bar">
      <span className="media-title" title={vid.meta.title}>{vid.meta.title}</span>
      {props.options.showDates && <span className="media-date">{dateMillisToString(vid.uploadTimestamp)}</span>}
    </div>

  const overlay =
    <div className="preview-overlay">
      {
        (props.options.showMenu && isHovering) &&
          <div className = "preview-menu-container">
            <PreviewMenu 
              video        = { vid } 
              setVideo     = { setVid }
              onDialogOpen = { () => { setShowVideoPreview(false) } }/>
          </div>
      }
      { props.options.showDuration && <div className="duration-overlay">{durationStr}</div> }
      {/* { <div className="abs-bottom-right"><FiDownload /></div> } */}
    </div>

  const primaryThumbnail =
    <ProgressiveImage src = { vid.urls.thumbnailUrl } placeholder="/image_placeholder.svg">
      { (src: string) => 
          <img 
            src       = { src } alt="an image"
            onClick   = { () => props.onClick(props.vid) } 
            className = { `preview-thumbnail preview-media` } 
          />
      }
    </ProgressiveImage>

  const videoPreview =
    <FragmentsPlayer 
      key       = { `video-preview-${props.vid.id}` }
      className = { `preview-video preview-media` }
      onClick   = { () => props.onClick(props.vid) }
      fragments = { props.vid.fragments } />

  const preview =
      <div className    = "preview-media-container"
           onMouseEnter = { () => props.options.showPreviewOnHover && setIsHovering(true) }
           onMouseLeave = { () => setIsHovering(false) }>
        { showVideoPreview && videoPreview }
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

const PreviewMenu = (props: {video: Video, setVideo: (v: Video) => void, onDialogOpen: () => any}) => {

  const [showDeleteDialog, setShowDeleteDialog] = useState(false);
  const [showInfoModal, setShowInfoModal] = useState(false)

  const cancelDelete = () => setShowDeleteDialog(false);
  const confirmDelete = () => {
    Api.deleteMediaById(props.video.id).then(() => {
      console.log("video was deleted")
      setShowDeleteDialog(false)
    })
  };

  return (
    <>
      <Modal visible = { showInfoModal } onHide = {() => setShowInfoModal(false)} >
        <MediaInfo 
          meta = { props.video.meta }
          onClose = { (meta) => {
            Api.updateVideoMetaData(props.video.id, meta).then(() => {
              props.setVideo({...props.video, meta: meta });
              setShowInfoModal(false)
            })
          } } 
        />
      </Modal>
      
      <Modal visible = { showDeleteDialog } onHide = { cancelDelete }>
        <Dialog title = "Are you sure?">
          <p>Do you want to delete: '{props.video.meta.title}'</p>
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
          <MenuItem href={`/editor/${props.video.id}`}>
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
