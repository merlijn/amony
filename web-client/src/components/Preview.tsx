import React, { CSSProperties, useRef, useState } from 'react';
import ProgressiveImage from "react-progressive-graceful-image";
import { Api } from "../api/Api";
import { Video } from "../api/Model";
import { dateMillisToString, durationInMillisToString, zeroPad } from "../api/Util";
import config from "../AppConfig.json";
import MediaInfo from './MediaInfo';
import './Preview.scss';
import { DropDown, Menu, MenuItem } from './common/DropDown';
import FragmentsPlayer from "./common/FragmentsPlayer";
import ImgWithAlt from "./common/ImgWithAlt";
import Modal from './common/Modal';

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

  const durationStr = durationInMillisToString(vid.duration)

  const titlePanel =
    <div className = "preview-info-bar">
      <span className="media-title" title={vid.meta.title}>{vid.meta.title}</span>
      {props.options.showDates && <span className="media-date">{dateMillisToString(vid.addedOn)}</span>}
    </div>

  const overlay =
    <div className="preview-overlay">
      {
        (props.options.showMenu && config["enable-video-menu"]) &&
          <div className = "preview-menu-icon">
            <PreviewMenu video={vid} setVideo = { setVid }/>
          </div>
      }
      { props.options.showDuration && <div className="duration-overlay">{durationStr}</div> }
      {/* { <div className="abs-bottom-right"><FiDownload /></div> } */}
    </div>

  const primaryThumbnail =
    <ProgressiveImage src={vid.thumbnail_url} placeholder="/image_placeholder.svg">
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
           onMouseEnter = { () => props.options.showPreviewOnHover && setIsHovering(true)}
           onMouseLeave = { () => setIsHovering(false)}>
        { isHovering && videoPreview }
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

const PreviewMenu = (props: {video: Video, setVideo: (v: Video) => void}) => {

  const [showConfirmDelete, setShowConfirmDelete] = useState(false);
  const [showInfoModal, setShowInfoModal] = useState(false)

  const cancelDelete = () => setShowConfirmDelete(false);
  const confirmDelete = () => {
    Api.deleteMediaById(props.video.id).then(() => {
      console.log("video was deleted")
      setShowConfirmDelete(false)
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
      
      <Modal visible = { showConfirmDelete } onHide = { cancelDelete }>
        <div className = "modal-dialog">
          <h2>Are you sure?</h2>
          <p>Do you want to delete: <br /> '{props.video.meta.title}'</p>
          <p>
            <button onClick = { confirmDelete }>Yes</button>
            <button onClick = { cancelDelete }>No / Cancel</button>
          </p>
        </div>
      </Modal>

      <div className = "preview-menu">

        <DropDown align = 'right' contentClassName="dropdown-menu" toggleIcon = { <ImgWithAlt className="action-icon-small" src="/icons/more.svg" /> } hideOnClick = {true} >
          <MenuItem onClick = { () => setShowInfoModal(true) }>
            <ImgWithAlt className="menu-icon" src="/icons/info.svg" />Info
          </MenuItem>
          <MenuItem href={`/editor/${props.video.id}`}>
            <ImgWithAlt className="menu-icon" src="/icons/edit.svg" />Fragments
          </MenuItem>
          <MenuItem onClick = { () => setShowConfirmDelete(true) }>
            <ImgWithAlt className="menu-icon" src="/icons/delete.svg" />Delete
          </MenuItem>
        </DropDown>
      </div>
    </>
  );
}

export default Preview
