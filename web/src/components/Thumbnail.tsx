import React, {SyntheticEvent, useState} from 'react';
import Image from 'react-bootstrap/Image';
import './Thumbnail.scss';
import {durationInMillisToString} from "../api/Util";
import {Video} from "../api/Model";

const Thumbnail = (props: {vid: Video, showTitle?: boolean}) => {

  const ncols = 4

  const [vid, setVid] = useState(props.vid)

  const [showInfoPanel, setShowInfoPanel] = useState(false)
  const [showVideoPreview, setShowVideoPreview] = useState(false)
  const [currentPreviewIdx, setCurrentPreviewIdx] = useState(0)

  const durationStr = durationInMillisToString(vid.duration)

  const infoPanel =
    <div className={`info-panel`}>
      <div className="top-left menu-icon info-button" onClick={(e) => { setShowInfoPanel(false)} }>
        <img src="/info_black_24dp.svg" />
      </div>
      <div className="info-panel-content">
        <ul>
          <li>Title: {vid.title}</li>
          <li>Duration: {durationStr}</li>
          <li>Fps: {vid.fps}</li>
          <li>Resolution: {vid.resolution_x}x{vid.resolution_y}</li>
          <li>Tags: {vid.tags.toString()}</li>
        </ul>
      </div>
    </div>

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

    <div className="grid-preview-cell">
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

export default Thumbnail;
