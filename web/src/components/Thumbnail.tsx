import React from 'react';
import Image from 'react-bootstrap/Image';
import './Thumbnail.scss';
import {durationInMillisToString} from "../api/Util";



const Thumbnail = (props: {src: string, link: string, title: string, duration: number}) => {

  const durationStr = durationInMillisToString(props.duration)

  return (
    <div className="thumbnail thumbnail-container">
      <a href={props.link}>
        <Image className="thumb" src={props.src} fluid />
        <div className="bottom-right">{props.title}</div>
        <div className="bottom-left">{durationStr}</div>
      </a>
    </div>
  );
}

export default Thumbnail;
