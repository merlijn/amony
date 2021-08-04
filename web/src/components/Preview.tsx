import React, {CSSProperties, SyntheticEvent, useState} from 'react';
import Image from 'react-bootstrap/Image';
import './Preview.scss';
import {durationInMillisToString} from "../api/Util";
import {Video} from "../api/Model";
import {Form} from "react-bootstrap";
import {imgAlt} from "../api/Constants";
import FragmentsPlayer from "./shared/FragmentsPlayer";

const Preview = (props: {vid: Video, style?: CSSProperties, className?: string, admin?: boolean}) => {

  const [vid, setVid] = useState(props.vid)

  const [showInfoOverlay, setShowInfoPanel] = useState(false)
  const [showVideoPreview, setShowVideoPreview] = useState(false)

  const durationStr = durationInMillisToString(vid.duration)

  const infoPanel = <InfoPanel vid={props.vid} onClickInfo={() => setShowInfoPanel(false) } />

  const videoPreview =
    <FragmentsPlayer className="preview-video preview-media" fragments={ props.vid.fragments } />

  const titlePanel =
    <div className="media-title">{vid.title.substring(0, 38)}</div>

  const overlayIcons =
    <div>
      { props.admin && <div className="abs-top-right action-icon-small"><img src="/more_vert_black_24dp.svg" /></div> }
      { props.admin &&
         <div className="abs-top-left info-button" onClick={(e) => { setShowInfoPanel(true)} }>
            <img alt={imgAlt} src="/info_black_24dp.svg" />
          </div>
      }

      <div className="abs-bottom-left duration-overlay">{durationStr}</div>

      { props.admin &&
        <div className="abs-bottom-right action-icon-small play-button">
          <a href={`/video/${props.vid.id}`}>
            <img alt={imgAlt} src="/play_circle_black_24dp.svg"/>
          </a>
        </div>
      }
    </div>

  const primaryThumbnail = <Image className="preview-thumbnail preview-media" src={vid.thumbnail_uri} fluid />

  let preview =
    <div className = "preview-container"
         onMouseEnter={() => setShowVideoPreview(true)}
         onMouseLeave={() => setShowVideoPreview(false)}>
      { showVideoPreview && videoPreview }
      { primaryThumbnail }
      { overlayIcons }
      { showInfoOverlay && infoPanel }
    </div>

  if (!props.admin) {
    preview = <a href={`/video/${props.vid.id}`}>{preview}</a>
  }

  return (
    <div style={props.style} className={ `${props.className}` }>
      { preview }
      { titlePanel }
    </div>
  )
}

const InfoPanel = (props: {vid: Video, onClickInfo: () => any }) => {

    const durationStr = durationInMillisToString(props.vid.duration)

    return(
        <div className={`info-panel`}>
            <div className="abs-top-left action-icon-small info-button" onClick={(e) => { props.onClickInfo() } }>
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
                            <img alt={imgAlt} className="tag-delete-button" src="/cancel_black_24dp.svg" />
                        </div>
                    </td></tr>
                    </tbody>
                </table>
            </div>
            <div className="abs-bottom-right"><img alt={imgAlt} src="/done_outline_black_24dp.svg" /></div>
        </div>
    );
}

export default Preview;
