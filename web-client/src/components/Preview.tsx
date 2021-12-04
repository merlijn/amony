import React, { CSSProperties, useRef, useState } from 'react';
import ProgressiveImage from "react-progressive-graceful-image";
import { Api } from "../api/Api";
import { Video, VideoMeta } from "../api/Model";
import { durationInMillisToString, zeroPad } from "../api/Util";
import * as config from "../AppConfig.json";
import './Preview.scss';
import { DropDown, Menu, MenuItem } from './shared/DropDown';
import FragmentsPlayer from "./shared/FragmentsPlayer";
import ImgWithAlt from "./shared/ImgWithAlt";
import Modal from './shared/Modal';
import TagEditor from "./shared/TagEditor";

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

  const [showMetaPanel, setShowMetaPanel] = useState(false)
  const [isHovering, setIsHovering] = useState(false)

  const durationStr = durationInMillisToString(vid.duration)

  const metaPanel = <MetaPanel meta={vid.meta}
                               onClose={(meta) => {
                                 Api.updateVideoMetaData(vid.id, meta).then(() => {
                                   setVid({...vid, meta: meta });
                                   setShowMetaPanel(false)
                                 })
                               }} />

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
            <PreviewMenu vid={vid} showInfo={ () => setShowMetaPanel(true)} />
          </div>
      }
      { props.options.showDuration && <div className="abs-bottom-left duration-overlay">{durationStr}</div> }
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
      { showMetaPanel && metaPanel }
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

  return (
    <>

      {
        showConfirmDelete && 
          <Modal onHide={cancelDelete}>
            <h2>Are you sure?</h2>
            <p>Do you want to delete: <br /> '{props.vid.meta.title}'</p>
            <p>
              <button onClick={confirmDelete}>Yes</button>
              <button onClick={cancelDelete}>No / Cancel</button>
            </p>
          </Modal>
      }

      <div style={ { zIndex: 5 }} className="preview-menu">

        <DropDown align = 'right' toggleIcon = { <ImgWithAlt className="action-icon-small" src="/icons/more.svg" /> } hideOnClick = {true} >
          <Menu style={ { width: 170 } }>
            <MenuItem onClick = { () => props.showInfo() }><ImgWithAlt className="menu-icon" src="/icons/info.svg" />Info</MenuItem>
            <MenuItem href={`/editor/${props.vid.id}`}><ImgWithAlt className="menu-icon" src="/icons/edit.svg" />Fragments</MenuItem>
            <MenuItem onClick = { () => setShowConfirmDelete(true) }><ImgWithAlt className="menu-icon" src="/icons/delete.svg" />Delete</MenuItem>
          </Menu>
        </DropDown>
      </div>
    </>
  );
}

const MetaPanel = (props: {meta: VideoMeta, onClose: (meta: VideoMeta) => any }) => {

    const [meta, setMeta] = useState(props.meta)

    return(
        <div className="info-panel">
          <div className="info-panel-title">Title</div>
          <input type="text" defaultValue={meta.title}/>
          <div className="info-panel-title">Comment</div>
          <textarea placeholder="comment">{meta.comment}</textarea>
          <div className="abs-bottom-right">
            <ImgWithAlt className="action-icon-small" title="save" src="/icons/task.svg" onClick={(e) => { props.onClose(meta) } } />
          </div>
          <div className="info-panel-title">Tags</div>
          <TagEditor tags={meta.tags} callBack={ (updatedTags) => {
              setMeta({...meta, tags: updatedTags })
            }
          } />
        </div>
    );
}

export default Preview;
