import React, {CSSProperties, useState} from 'react';
import './Preview.scss';
import {durationInMillisToString, zeroPad} from "../api/Util";
import {Video, VideoMeta} from "../api/Model";
import {Form, Modal} from "react-bootstrap";
import FragmentsPlayer from "./shared/FragmentsPlayer";
import TripleDotMenu from "./shared/TripleDotMenu";
import Dropdown from "react-bootstrap/Dropdown";
import ImgWithAlt from "./shared/ImgWithAlt";
import Button from "react-bootstrap/Button";
import ProgressiveImage from "react-progressive-graceful-image";
import {Api} from "../api/Api";
import * as config from "../AppConfig.json";
import TagEditor from "./shared/TagEditor";

type PreviewProps = {
  vid: Video,
  style?: CSSProperties,
  className?: string,
  lazyLoad?: boolean,
  showPreviewOnHover: boolean,
  showPreviewOnHoverDelay?: number,
  showInfoBar: boolean,
  showDates: boolean,
  showDuration: boolean,
  showMenu: boolean,
  onClick: (v: Video) => any
}

const Preview = (props: PreviewProps) => {

  const [vid, setVid] = useState(props.vid)

  const [showMetaPanel, setShowMetaPanel] = useState(false)
  const [showVideoPreview, setShowVideoPreview] = useState(false)

  const durationStr = durationInMillisToString(vid.duration)

  const metaPanel = <MetaPanel meta={vid.meta}
                               onClose={(meta) => {
                                 Api.updateVideoMetaData(vid.id, meta).then(() => {
                                   setVid({...vid, meta: meta });
                                   setShowMetaPanel(false)
                                 })
                               }} />

  const previewClass = props.showInfoBar ? "preview-media" : "preview-media rounded-corners"
  const videoPreview =
    <FragmentsPlayer id={`video-preview-${props.vid.id}`}
                     onClick={ () => props.onClick(props.vid) }
                     className= { `preview-video ${previewClass}` }
                     fragments={ props.vid.fragments } />

  const addOnDate = new Date(vid.addedOn)
  const titlePanel =
    <div className="info-bar">
      <span className="media-title">{vid.meta.title}</span>
      <span className="media-date">{zeroPad(addOnDate.getUTCDate(), 2)}-{zeroPad(addOnDate.getMonth(), 2)}-{addOnDate.getFullYear()}</span>
    </div>

  const overlayIcons =
    <div>
      {
        (props.showMenu && config["enable-video-menu"]) &&
          <div style={ { zIndex: 5 }} className="abs-top-right">
            <PreviewMenu vid={vid} showInfo={ () => setShowMetaPanel(true)} />
          </div>
      }

      { props.showDuration && <div className="abs-bottom-left duration-overlay">{durationStr}</div> }
    </div>

  const primaryThumbnail =
    <ProgressiveImage src={vid.thumbnail_uri} placeholder="/image_placeholder.svg">
      { (src: string) =>
        <img onClick={ () => props.onClick(props.vid) } className={ `preview-thumbnail ${previewClass}` } src={src} alt="an image" />
      }
    </ProgressiveImage>

  let preview =
      <div className = "preview-container"
           onMouseEnter={() => props.showPreviewOnHover && setShowVideoPreview(true)}
           onMouseLeave={() => setShowVideoPreview(false)}>
        { showVideoPreview && videoPreview }
        { primaryThumbnail }
        { overlayIcons }
      </div>
    // </a>

  return (
    <div style={props.style} className={ `${props.className}` }>
      { preview }
      { props.showInfoBar && titlePanel }
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

  const selectFn = (eventKey: string | null) => {
    if (eventKey === "delete") {
      setShowConfirmDelete(true)
    } else if (eventKey === "info") {
      props.showInfo()
    }
  }

  return (
    <>
      <Modal show={showConfirmDelete} onHide={cancelDelete}>
        <Modal.Header closeButton>
          <Modal.Title>Are you sure?</Modal.Title>
        </Modal.Header>
        <Modal.Body>Do you want to delete: <br /> '{props.vid.meta.title}'</Modal.Body>
        <Modal.Footer>
          <Button variant="danger" onClick={confirmDelete}>Yes</Button>
          <Button variant="secondary" onClick={cancelDelete}>No / Cancel</Button>
        </Modal.Footer>
      </Modal>

      <div style={ { zIndex: 5 }} className="preview-menu">
        <TripleDotMenu onSelect={selectFn}>
          <Dropdown.Item className="menu-item" eventKey="info">
            <ImgWithAlt className="menu-icon" src="/icons/info.svg" />Info
          </Dropdown.Item>
          { config["enable-video-editor"] && <Dropdown.Item className="menu-item" eventKey="editor" href={`/editor/${props.vid.id}`}>
            <ImgWithAlt className="menu-icon" src="/icons/edit.svg" />Fragments
          </Dropdown.Item> }
          <Dropdown.Item className="menu-item" eventKey="delete">
            <ImgWithAlt className="menu-icon" src="/icons/delete.svg" />Delete
          </Dropdown.Item>
        </TripleDotMenu>
      </div>
    </>
  );
}

const MetaPanel = (props: {meta: VideoMeta, onClose: (meta: VideoMeta) => any }) => {

    const [meta, setMeta] = useState(props.meta)

    return(
        <div className="info-panel">
          <div className="info-panel-title">Title</div>
          <Form.Control size="sm" type="text" defaultValue={meta.title}/>
          <div className="info-panel-title">Comment</div>
          <Form.Control as="textarea" size="sm" type="" placeholder="comment" />
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
