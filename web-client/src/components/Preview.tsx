import React, {CSSProperties, useState} from 'react';
import './Preview.scss';
import {durationInMillisToString, zeroPad} from "../api/Util";
import {Video} from "../api/Model";
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
  showTitles: boolean,
  showDuration: boolean,
  showMenu: boolean,
  onClick: (v: Video) => any
}

const Preview = (props: PreviewProps) => {

  const [vid, setVid] = useState(props.vid)

  const [showInfoOverlay, setShowInfoPanel] = useState(false)
  const [showVideoPreview, setShowVideoPreview] = useState(false)

  const durationStr = durationInMillisToString(vid.duration)

  const infoPanel = <InfoPanel vid={props.vid} onClickInfo={() => setShowInfoPanel(false) } />

  const videoPreview =
    <FragmentsPlayer id={`video-preview-${props.vid.id}`}
                     onClick={ () => props.onClick(props.vid) }
                     className="preview-video preview-media"
                     fragments={ props.vid.fragments } />

  const addOnDate = new Date(vid.addedOn)
  const titlePanel =
    <div className="info-bar">
      <span className="media-title">{vid.title}</span>
      <span className="media-date">{zeroPad(addOnDate.getUTCDate(), 2)}-{zeroPad(addOnDate.getMonth(), 2)}-{addOnDate.getFullYear()}</span>
    </div>

  const overlayIcons =
    <div>
      {
        (props.showMenu && config["enable-video-menu"]) &&
          <div style={ { zIndex: 5 }} className="abs-top-right">
            <PreviewMenu vid={vid} showInfo={ () => setShowInfoPanel(true)} />
          </div>
      }

      { props.showDuration && <div className="abs-bottom-left duration-overlay">{durationStr}</div> }
    </div>

  const primaryThumbnail =
    <ProgressiveImage src={vid.thumbnail_uri} placeholder="/image_placeholder.svg">
      { (src: string) =>
        <img onClick={ () => props.onClick(props.vid) } className="preview-thumbnail preview-media" src={src} alt="an image" />
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
      { props.showTitles && titlePanel }
      { showInfoOverlay && infoPanel }
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
        <Modal.Body>Do you want to delete: <br /> '{props.vid.title}'</Modal.Body>
        <Modal.Footer>
          <Button variant="danger" onClick={confirmDelete}>Yes</Button>
          <Button variant="secondary" onClick={cancelDelete}>No / Cancel</Button>
        </Modal.Footer>
      </Modal>

      <div style={ { zIndex: 5 }} className="preview-menu">
        <TripleDotMenu onSelect={selectFn}>
          <Dropdown.Item className="menu-item" eventKey="info">
            <ImgWithAlt className="menu-icon" src="/info_black_24dp.svg" />Info
          </Dropdown.Item>
          { config["enable-video-editor"] && <Dropdown.Item className="menu-item" eventKey="editor" href={`/editor/${props.vid.id}`}>
            <ImgWithAlt className="menu-icon" src="/edit_black_24dp.svg" />Fragments
          </Dropdown.Item> }
          <Dropdown.Item className="menu-item" eventKey="delete">
            <ImgWithAlt className="menu-icon" src="/delete_black_24dp.svg" />Delete
          </Dropdown.Item>
        </TripleDotMenu>
      </div>
    </>
  );
}


const InfoPanel = (props: {vid: Video, onClickInfo: () => any }) => {

    const durationStr = durationInMillisToString(props.vid.duration)

    return(
        <div className="info-panel">
          <div className="info-panel-title">Title</div>
          <Form.Control size="sm" type="text" defaultValue={props.vid.title}/>
          <div className="info-panel-title">Comment</div>
          <Form.Control as="textarea" size="sm" type="" placeholder="comment" />
          <div className="abs-bottom-right">
            <ImgWithAlt className="action-icon-small" src="/save_black_24dp.svg" onClick={(e) => { props.onClickInfo() } } />
          </div>
          <div className="info-panel-title">Tags</div>
          <TagEditor tags={["nature"]} callBack={ (tags) => { } } />
        </div>
    );
}

export default Preview;
