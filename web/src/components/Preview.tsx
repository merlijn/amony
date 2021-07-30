import React, {CSSProperties, SyntheticEvent, useState} from 'react';
import Image from 'react-bootstrap/Image';
import './Preview.scss';
import {durationInMillisToString} from "../api/Util";
import {Video} from "../api/Model";
import {Col, Form, Row} from "react-bootstrap";

const Preview = (props: {vid: Video, style?: CSSProperties, className?: string, showTitle?: boolean}) => {

  const [vid, setVid] = useState(props.vid)

  const [showInfoPanel, setShowInfoPanel] = useState(false)
  const [showVideoPreview, setShowVideoPreview] = useState(false)
  const [currentPreviewIdx, setCurrentPreviewIdx] = useState(0)

  const durationStr = durationInMillisToString(vid.duration)

  const infoPanel = <InfoPanel vid={props.vid} onClickInfo={() => setShowInfoPanel(false) } />

  const nextPreview = (e: SyntheticEvent<HTMLVideoElement>) => {

    // back the the 1st (0)
    if (currentPreviewIdx < props.vid.previews.length - 1) {
      setCurrentPreviewIdx(currentPreviewIdx + 1)
      e.currentTarget.load()
      e.currentTarget.play()
    }

    // on to the next
    if (currentPreviewIdx > 0 && currentPreviewIdx + 1 >= props.vid.previews.length) {
      setCurrentPreviewIdx(0)
      e.currentTarget.load()
      e.currentTarget.play()
    }

    // no need to load a new preview if there is only 1
    if (props.vid.previews.length === 1) {
      e.currentTarget.play()
    }
  }

  const videoPreview =
     <video className="preview-video preview-media" muted
            onMouseOver={(e) => e.currentTarget.play()}
            onEnded={nextPreview} >

         <source src={props.vid.previews[currentPreviewIdx].uri} type="video/mp4"/>
     </video>

  const titlePanel =
    <div className="media-title">{vid.title.substring(0, 38)}</div>

  const overlayIcons =
    <div>
      <div className="top-right menu-icon"><img src="/more_vert_black_24dp.svg" /></div>
      <div className="top-left menu-icon info-button" onClick={(e) => { setShowInfoPanel(true)} }>
        <img src="/info_black_24dp.svg" />
      </div>

      <div className="bottom-left duration-overlay">{durationStr}</div>

      <div className="bottom-right menu-icon play-button">
        <a href={`/video/${props.vid.id}`} >
          <img src="/play_circle_black_24dp.svg" />
        </a>
      </div>
    </div>

  const primaryThumbnail = <Image className="preview-thumbnail preview-media" src={vid.thumbnail_uri} fluid />

  return (

    <div style={props.style} className={ `${props.className}` }>
      <div className = "preview-container" onMouseEnter={() => setShowVideoPreview(true)} onMouseLeave={() => setShowVideoPreview(false)}>
        { showVideoPreview && videoPreview }
        { primaryThumbnail }
        { overlayIcons }
        { showInfoPanel && infoPanel }
      </div>
      { titlePanel }
    </div>
  );
}

const InfoPanel = (props: {vid: Video, onClickInfo: () => any }) => {

    const durationStr = durationInMillisToString(props.vid.duration)

    return(
        <div className={`info-panel`}>
            <div className="top-left menu-icon info-button" onClick={(e) => { props.onClickInfo() } }>
                <img src="/info_black_24dp.svg" />
            </div>
            <div className="info-title"><b>{props.vid.title}</b></div>
            <div className="info-panel-content">
                <table>
                    <tbody>
                    <tr><td>Duration</td><td>{durationStr}</td></tr>
                    <tr><td>Fps</td><td>{props.vid.fps}</td></tr>
                    <tr><td>Resolution</td><td>{props.vid.resolution_x}x{props.vid.resolution_y}</td></tr>
                    <tr><td>Tags<img className="tag-add-button" src="/add_box_black_24dp.svg" /></td><td>
                        <div className="tag-entry">
                            <Form.Control className="tag-input" size="sm" type="text" defaultValue="" />
                            <img className="tag-delete-button" src="/cancel_black_24dp.svg" />
                        </div>
                    </td></tr>
                    </tbody>
                </table>
            </div>
            <div className="bottom-right"><img src="/done_outline_black_24dp.svg" /></div>
        </div>
    );
}

export default Preview;
