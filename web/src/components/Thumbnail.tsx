import React from 'react';
import Image from 'react-bootstrap/Image';
import './Thumbnail.scss';

const Thumbnail = (props: {src: string, link: string, title: string}) => {

  return (
    <div className="thumbnail thumbnail-container">
      <a href={props.link}>
        <Image className="thumb" src={props.src} fluid />
        <div className="bottom-right thumbnail-overlay">{props.title}</div>
      </a>
    </div>
  );
}

export default Thumbnail;
