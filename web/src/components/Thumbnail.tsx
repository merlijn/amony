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
  const [foo, setFoo] = useState("")

  const genThumbnail =  (e: any) => {
      e.preventDefault();
      const timestamp = Math.trunc(Math.random() * props.vid.duration)
      const url = buildUrl("/api/thumbnail/" + props.vid.id, new Map())
      doPOST(url, timestamp).then( response => {
        setFoo("blah")
      })
  }

  return (
    <div className="thumbnail thumbnail-container">
      <a href={link}>
        <Image className="thumb" src={props.vid.thumbnail} fluid />
        <div className="top-right"><Button onClick={genThumbnail} variant="success">~</Button></div>
        <div className="bottom-right">{props.vid.title}</div>
        <div className="bottom-left">{durationStr}</div>
      </a>
    </div>
  );
}

export default Thumbnail;
