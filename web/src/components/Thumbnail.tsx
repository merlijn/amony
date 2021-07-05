import React, {useEffect, useState} from 'react';
import Image from 'react-bootstrap/Image';
import './Thumbnail.scss';
import {buildUrl, durationInMillisToString} from "../api/Util";
import Button from "react-bootstrap/Button";
import {Video} from "../api/Model";
import {doPOST} from "../api/Api";
import {Form} from "react-bootstrap";

const Thumbnail = (props: {vid: Video}) => {

  const link: string = "/video/" + props.vid.id;
  const [vid, setVid] = useState(props.vid)
  const [pickThumb, setPickThumb] = useState(false)

  const durationStr = durationInMillisToString(vid.duration)

  const switchThumb =  (e: any) => {
    setPickThumb(!pickThumb)
  }

  const genThumbnailAt = (timestamp: number) => {
    const url = buildUrl("/api/thumbnail/" + props.vid.id, new Map())
    doPOST(url, timestamp).then( response => {
      setVid(response)
    })
  }

  useEffect(() => { setVid(props.vid) }, [props])

  const sliderChanged = (v: any) => {
    const value = v.target.value as number

    genThumbnailAt(Math.trunc(vid.duration * value / 100))
  }

  const info =
    <div>
      <div className="bottom-left duration-overlay">{durationStr}</div>
      <div className="bottom-right title-overlay">{vid.title}</div>
    </div>

  const thumbnailPicker =
    <Form className="thumbnail-picker">
      <Form.Group controlId="formBasicRange">
        <Form.Control type="range" onChange={sliderChanged} />
      </Form.Group>
    </Form>

  const bottom = pickThumb ? thumbnailPicker : info;

  return (
    <div className="thumbnail-container">
      <a href={link}><Image className="thumbnail" src={vid.thumbnail} fluid /></a>
      <div className="top-right"><img className="menu-icon" src="/more_vert_black_24dp.svg" onClick={switchThumb} /></div>
      { bottom }
    </div>
  );
}

export default Thumbnail;
