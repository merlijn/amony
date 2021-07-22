import React, {useEffect, useRef, useState} from 'react';
import Image from 'react-bootstrap/Image';
import './Thumbnail.scss';
import {buildUrl, durationInMillisToString} from "../api/Util";
import {Video} from "../api/Model";
import {createThumbnailAt, doPOST} from "../api/Api";
import {Form} from "react-bootstrap";

const Thumbnail = (props: {vid: Video}) => {

  const link: string = "/video/" + props.vid.id;
  const [vid, setVid] = useState(props.vid)
  const [previewUri, setPreviewUri] = useState("")

  const [showInfoPanel, setShowInfoPanel] = useState(false)

  const durationStr = durationInMillisToString(vid.duration)

  const genThumbnailAt = (timestamp: number) => {
    createThumbnailAt(props.vid.id, timestamp).then (response => {
      setVid(response)
    });
  }

  useEffect(() => {
    setVid(props.vid)
    if (previewUri) {
      setPreviewUri(props.vid.thumbnail.webp_uri)
    }
  }, [props])

  const sliderChanged = (v: any) => {
    const value = v.target.value as number

    genThumbnailAt(Math.trunc(value))
  }

  const infoPanel =
    <div className="info-panel">
      <div className="top-left menu-icon info-button" onClick={(e) => { setShowInfoPanel(false)} }>
        <img src="/info_black_24dp.svg" />
      </div>
      <div className="info-panel-content">
        <ul>
          <li>Title: {vid.title}</li>
          <li>Duration: {vid.duration}</li>
          <li>Resolution {vid.resolution_x}x{vid.resolution_y}</li>
        </ul>
      </div>
    </div>

  return (
    <div id={`thumbnail-${props.vid.id}`} className="thumbnail-container media-preview" onMouseEnter={() => setPreviewUri(vid.thumbnail.webp_uri)} onMouseLeave={() => setPreviewUri("")}>

      <Image className="thumbnail" src={vid.thumbnail.uri} fluid />
      <Image className="preview" src={previewUri} fluid />

      <div className="top-right menu-icon"><img src="/more_vert_black_24dp.svg" /></div>
      <div className="top-left menu-icon info-button" onClick={(e) => { setShowInfoPanel(true)} }>
        <img src="/info_black_24dp.svg" />
      </div>

      <div className="bottom-left duration-overlay">{durationStr}</div>

      <div className="bottom-right menu-icon play-button">
        <a href={link} >
          <img src="/play_circle_black_24dp.svg" />
        </a>
      </div>
      {showInfoPanel && infoPanel }
    </div>
  );
}

export default Thumbnail;
