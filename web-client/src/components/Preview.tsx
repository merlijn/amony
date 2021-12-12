import React, { CSSProperties, useRef, useState } from 'react';
import ProgressiveImage from "react-progressive-graceful-image";
import { Api } from "../api/Api";
import { Video } from "../api/Model";
import { durationInMillisToString, zeroPad } from "../api/Util";
import * as config from "../AppConfig.json";
import MediaInfo from './MediaInfo';
import './Preview.scss';
import { DropDown, Menu, MenuItem } from './shared/DropDown';
import FragmentsPlayer from "./shared/FragmentsPlayer";
import ImgWithAlt from "./shared/ImgWithAlt";
import ReactModal from 'react-modal';
import Modal from './shared/Modal';

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

  const [showInfoModal, setShowInfoModal] = useState(false)
  const [isHovering, setIsHovering] = useState(false)

  const durationStr = durationInMillisToString(vid.duration)

  const infoModal = 
    <MediaInfo 
      meta = { vid.meta }
      onClose = { (meta) => {
        Api.updateVideoMetaData(vid.id, meta).then(() => {
          setVid({...vid, meta: meta });
          setShowInfoModal(false)
        })
      } } />

  const addOnDate = new Date(vid.addedOn)
  const titlePanel =
    <div className="info-bar">
      <span className="media-title" title={vid.meta.title}>{vid.meta.title}</span>
      {props.options.showDates && <span className="media-date">{zeroPad(addOnDate.getUTCDate(), 2)}-{zeroPad(addOnDate.getMonth(), 2)}-{addOnDate.getFullYear()}</span>}
    </div>


  const previewRef = useRef<HTMLDivElement>(null)

  const overlay =
    <div className="preview-overlay">
      {
        (props.options.showMenu && config["enable-video-menu"]) &&
          <div style={ { zIndex: 5 }} className="abs-top-right">
            <PreviewMenu vid={vid} showInfo={ () => setShowInfoModal(true)} />
          </div>
      }
      { props.options.showDuration && <div className="abs-bottom-left duration-overlay">{durationStr}</div> }
      {/* { <div className="abs-bottom-right"><FiDownload /></div> } */}
    </div>

  const primaryThumbnail =
    <ProgressiveImage src={vid.thumbnail_url} placeholder="/image_placeholder.svg">
      { (src: string) => 
          <img 
            src={src} alt="an image"
            onClick={ () => props.onClick(props.vid) } 
            className={ `preview-thumbnail preview-media` } 
          />
      }
    </ProgressiveImage>

  const videoPreview =
    <FragmentsPlayer id={`video-preview-${props.vid.id}`}
                    onClick={ () => props.onClick(props.vid) }
                    className= { `preview-video preview-media` }
                    fragments={ props.vid.fragments } />

  let preview =
      <div className = "preview-container"
           onMouseEnter={() => props.options.showPreviewOnHover && setIsHovering(true)}
           onMouseLeave={() => setIsHovering(false)}>
        { isHovering && videoPreview }
        { primaryThumbnail }
        { overlay }
      </div>
    // </a>

  return (
    <div ref={previewRef} style={props.style} className={ `${props.className}` }>
      { preview }
      { props.options.showInfoBar && titlePanel }
      { showInfoModal && infoModal }
    </div>
  )
}

const PreviewMenu = (props: {vid: Video, showInfo: () => void}) => {

  const [showConfirmDelete, setShowConfirmDelete] = useState(false);

  const cancelDelete = () => setShowConfirmDelete(false);
  const confirmDelete = () => {
    Api.deleteMediaById(props.vid.id).then(() => {
      console.log("video was deleted")
      setShowConfirmDelete(false)
    })
  };

  ReactModal.setAppElement('#root');

  return (
    <>
      {
        <Modal visible = { showConfirmDelete } onHide = { cancelDelete }>
          <div className = "default-modal-dialog">
            <h2>Are you sure?</h2>
            <p>Do you want to delete: <br /> '{props.vid.meta.title}'</p>
            <p>
              <button onClick = { confirmDelete }>Yes</button>
              <button onClick = { cancelDelete }>No / Cancel</button>
            </p>
          </div>
        </Modal>
      }

      <div style={ { zIndex: 5 } } className = "preview-menu">

        <DropDown align = 'right' toggleIcon = { <ImgWithAlt className="action-icon-small" src="/icons/more.svg" /> } hideOnClick = {true} >
          <Menu style={ { width: 170 } }>
            <MenuItem onClick = { () => props.showInfo() }>
              <ImgWithAlt className="menu-icon" src="/icons/info.svg" />Info
            </MenuItem>
            <MenuItem href={`/editor/${props.vid.id}`}>
              <ImgWithAlt className="menu-icon" src="/icons/edit.svg" />Fragments
            </MenuItem>
            <MenuItem onClick = { () => setShowConfirmDelete(true) }>
              <ImgWithAlt className="menu-icon" src="/icons/delete.svg" />Delete
            </MenuItem>
          </Menu>
        </DropDown>
      </div>
    </>
  );
}

export default Preview
