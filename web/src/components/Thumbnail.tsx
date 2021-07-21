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
  const [pickThumb, setPickThumb] = useState(false)
  const [previewUri, setPreviewUri] = useState("")

  const durationStr = durationInMillisToString(vid.duration)

  const switchThumb =  (e: any) => {
    setPickThumb(!pickThumb)
  }

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

  const info =
    <div>
      <div className="bottom-left duration-overlay">{durationStr}</div>
      {/*<div className="bottom-right title-overlay">{vid.title}</div>*/}
    </div>

  const thumbnailPicker =
    <Form className="thumbnail-picker thumbnail-overlay">
      <Form.Group controlId="formBasicRange">
        <Form.Control type="range" min="0" max={vid.duration} value={vid.thumbnail.timestamp} onChange={sliderChanged} />
      </Form.Group>
    </Form>

  const bottom = pickThumb ? thumbnailPicker : info;

  return (
    <div id={`thumbnail-${props.vid.id}`} className="thumbnail-container">
      <a href={link} onMouseEnter={() => setPreviewUri(vid.thumbnail.webp_uri)} onMouseLeave={() => setPreviewUri("")}>
        <Image className="thumbnail" src={vid.thumbnail.uri} fluid />
        <Image className="preview" src={previewUri} fluid />
      </a>
      <div className="top-right menu-icon"><img src="/more_vert_black_24dp.svg" onClick={switchThumb} /></div>
      <div className="top-left menu-icon"><img src="/info_black_24dp.svg" /></div>
      { bottom }
    </div>
  );
}

export default Thumbnail;
