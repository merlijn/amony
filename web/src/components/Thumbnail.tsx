import React, {useState} from 'react';
import Image from 'react-bootstrap/Image';
import './Thumbnail.scss';
import {buildUrl, durationInMillisToString} from "../api/Util";
import Button from "react-bootstrap/Button";
import {Video} from "../api/Model";
import {doPOST} from "../api/Api";

const Thumbnail = (props: {vid: Video}) => {

  const durationStr = durationInMillisToString(props.vid.duration)
  const link: string = "/video/" + props.vid.id;
  const [vid, setVid] = useState(props.vid)

  const genThumbnail =  (e: any) => {
      e.preventDefault();
      const timestamp = Math.trunc(Math.random() * props.vid.duration)
      const url = buildUrl("/api/thumbnail/" + props.vid.id, new Map())
      doPOST(url, timestamp).then( response => {
        setVid(response)
      })
  }

  return (
    <div className="thumbnail-container">
      <a href={link}>
        <Image className="thumbnail" src={vid.thumbnail} fluid />
        <div className="top-right"><img className="menu-icon" src="/restore_page_black_24dp.svg" onClick={genThumbnail} /></div>
        <div className="bottom-right">{props.vid.title}</div>
        <div className="bottom-left">{durationStr}</div>
      </a>
    </div>
  );
}

export default Thumbnail;
