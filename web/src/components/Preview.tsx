import React, {CSSProperties, SyntheticEvent, useState} from 'react';
import Image from 'react-bootstrap/Image';
import './Preview.scss';
import {durationInMillisToString} from "../api/Util";
import {Video} from "../api/Model";
import {Form, Modal} from "react-bootstrap";
import FragmentsPlayer from "./shared/FragmentsPlayer";
import TripleDotMenu from "./shared/TripleDotMenu";
import Dropdown from "react-bootstrap/Dropdown";
import ImgWithAlt from "./shared/ImgWithAlt";
import Button from "react-bootstrap/Button";
import {Api} from "../api/Api";

type PreviewProps = {
  vid: Video,
  style?: CSSProperties,
  className?: string,
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

  const titlePanel =
    <div className="media-title">{vid.title.substring(0, 38)}</div>

  const overlayIcons =
    <div>
      {
        props.showMenu &&
          <div style={ { zIndex: 5 }} className="abs-top-right">
            <PreviewMenu vid={vid} showInfo={ () => setShowInfoPanel(true)} />
          </div>
      }

      { props.showDuration && <div className="abs-bottom-left duration-overlay">{durationStr}</div> }
    </div>

  const primaryThumbnail = <Image className="preview-thumbnail preview-media" src={vid.thumbnail_uri} fluid />

  let preview =
    // <a href={`/video/${props.vid.id}`}>
      <div className = "preview-container"
           onMouseEnter={() => setShowVideoPreview(true)}
           onMouseLeave={() => setShowVideoPreview(false)}
           >
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
    } else if (eventKey === "editor") {

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
          <Dropdown.Item className="menu-item" eventKey="editor" href={`/editor/${props.vid.id}`}>
            <ImgWithAlt className="menu-icon" src="/edit_black_24dp.svg" />Edit
          </Dropdown.Item>
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
        <div className={`info-panel`}>
          <div className="info-title"><p>Title:</p><div><Form.Control size="sm" type="text" defaultValue={props.vid.title}/></div></div>
            <div className="info-panel-content">
                <p>Info: {durationStr}, {props.vid.fps}fps, {props.vid.resolution_x}x{props.vid.resolution_y}</p>
              <p>Tags:</p>

            </div>
            <div className="abs-bottom-right">
              <ImgWithAlt className="action-icon-small" src="/done_outline_black_24dp.svg" onClick={(e) => { props.onClickInfo() } } />
            </div>
        </div>
    );
}

export default Preview;
