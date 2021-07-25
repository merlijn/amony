import React, {useEffect, useRef, useState} from 'react';
import Image from 'react-bootstrap/Image';
import './Thumbnail.scss';
import {buildUrl, durationInMillisToString} from "../api/Util";
import {Video} from "../api/Model";
import {createThumbnailAt, doPOST} from "../api/Api";

const Thumbnail = (props: {vid: Video}) => {

  const [vid, setVid] = useState(props.vid)

  const [showInfoPanel, setShowInfoPanel] = useState(false)
  const [showVideoPreview, setShowVideoPreview] = useState(false)

  const durationStr = durationInMillisToString(vid.duration)

  const infoPanel =
    <div className={`info-panel`}>
      <div className="top-left menu-icon info-button" onClick={(e) => { setShowInfoPanel(false)} }>
        <img src="/info_black_24dp.svg" />
      </div>
      <div className="info-panel-content">
        <ul>
          <li>Title: {vid.title}</li>
          <li>Duration: {vid.duration}</li>
          <li>Fps: {vid.fps}</li>
          <li>Resolution: {vid.resolution_x}x{vid.resolution_y}</li>
        </ul>
      </div>
    </div>

  const videoPanel =
     <video className="preview-video preview-media" autoPlay={true} loop>
         <source src={vid.previews[0].uri} type="video/mp4"/>
     </video>

  const titlePanel =
    <div className="media-title">{vid.title.substring(0, 38)}</div>

  return (
    <div className={`preview-container`} onMouseEnter={() => setShowVideoPreview(true)} onMouseLeave={() => setShowVideoPreview(false)}>

      {showVideoPreview && videoPanel }
      <Image className="preview-thumbnail preview-media" src={vid.thumbnail_uri} fluid />

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

      {showInfoPanel && infoPanel }
    </div>
  );
}

export default Thumbnail;
