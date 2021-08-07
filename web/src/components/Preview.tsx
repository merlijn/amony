import React, {CSSProperties, SyntheticEvent, useState} from 'react';
import Image from 'react-bootstrap/Image';
import './Preview.scss';
import {durationInMillisToString} from "../api/Util";
import {Video} from "../api/Model";
import {Form} from "react-bootstrap";
import FragmentsPlayer from "./shared/FragmentsPlayer";
import TripleDotMenu from "./shared/TripleDotMenu";
import Dropdown from "react-bootstrap/Dropdown";
import ImgWithAlt from "./shared/ImgWithAlt";

const Preview = (props: {vid: Video, style?: CSSProperties, className?: string, showTitles: boolean}) => {

  const [vid, setVid] = useState(props.vid)

  const [showInfoOverlay, setShowInfoPanel] = useState(false)
  const [showVideoPreview, setShowVideoPreview] = useState(false)

  const durationStr = durationInMillisToString(vid.duration)

  const infoPanel = <InfoPanel vid={props.vid} onClickInfo={() => setShowInfoPanel(false) } />

  const videoPreview =
    <FragmentsPlayer className="preview-video preview-media" fragments={ props.vid.fragments } />

  const titlePanel =
    <div className="media-title">{vid.title.substring(0, 38)}</div>

  const selectFn = (eventKey: string | null) => {
    if (eventKey === "info") {
      setShowInfoPanel(true)
    }
  }

  const overlayIcons =
    <div>
      <div style={ { zIndex: 5 }} className="abs-top-right">
        <TripleDotMenu onSelect={selectFn}>
          <Dropdown.Item className="menu-item" eventKey="info">
            <ImgWithAlt className="menu-icon" src="/info_black_24dp.svg" />Info</Dropdown.Item>
          <Dropdown.Item className="menu-item" eventKey="delete">
            <ImgWithAlt className="menu-icon" src="/delete_black_24dp.svg" />Delete</Dropdown.Item>
        </TripleDotMenu>
      </div>

      <div className="abs-bottom-left duration-overlay">{durationStr}</div>
    </div>

  const primaryThumbnail = <Image className="preview-thumbnail preview-media" src={vid.thumbnail_uri} fluid />

  let preview =
    <a href={`/video/${props.vid.id}`}>
      <div className = "preview-container"
           onMouseEnter={() => setShowVideoPreview(true)}
           onMouseLeave={() => setShowVideoPreview(false)}>
        { showVideoPreview && videoPreview }
        { primaryThumbnail }
        { overlayIcons }
      </div>
    </a>

  return (
    <div style={props.style} className={ `${props.className}` }>
      { showInfoOverlay && infoPanel }
      { preview }
      { props.showTitles && titlePanel }
    </div>
  )
}

const InfoPanel = (props: {vid: Video, onClickInfo: () => any }) => {

    const durationStr = durationInMillisToString(props.vid.duration)

    return(
        <div className={`info-panel`}>
            <div className="abs-top-left action-icon-small info-button" onClick={(e) => { props.onClickInfo() } }>
                <ImgWithAlt src="/info_black_24dp.svg" />
            </div>
            <div className="info-title"><b>{props.vid.title}</b></div>
            <div className="info-panel-content">
                <table>
                    <tbody>
                    <tr><td>Duration</td><td>{durationStr}</td></tr>
                    <tr><td>Fps</td><td>{props.vid.fps}</td></tr>
                    <tr><td>Resolution</td><td>{props.vid.resolution_x}x{props.vid.resolution_y}</td></tr>
                    <tr><td>Tags<ImgWithAlt className="tag-add-button" src="/add_box_black_24dp.svg" /></td><td>
                        <div className="tag-entry">
                            <Form.Control className="tag-input" size="sm" type="text" defaultValue="" />
                            <ImgWithAlt className="tag-delete-button" src="/cancel_black_24dp.svg" />
                        </div>
                    </td></tr>
                    </tbody>
                </table>
            </div>
            <div className="abs-bottom-right"><ImgWithAlt src="/done_outline_black_24dp.svg" /></div>
        </div>
    );
}

export default Preview;
