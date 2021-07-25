import Plyr from 'plyr';
import React, {useEffect, useState} from 'react';
import './Player.scss';
import {Video} from "../api/Model";
import {createThumbnailAt, getMediaById} from "../api/Api";
import {useWindowSize} from "../api/Util";
import {Button} from "react-bootstrap";

const Player = (props: {videoId: string}) => {

  const id = '#video-' + props.videoId
  const videoSrc = '/files/videos/' + props.videoId

  const initialStyle = { };
  const fillFactor = 80;

  const windowSize = useWindowSize(((oldSize, newSize) => Math.abs(newSize.width - oldSize.width) > 5));
  const [sizeStyle, setSizeStyle] = useState(initialStyle)
  const [plyr, setPlyr] = useState<Plyr | null>(null)
  const [vid, setVid] = useState<Video | null>(null)

  useEffect(() => {

    const id = '#video-' + props.videoId
    const element = document.getElementById(id);

    getMediaById(props.videoId).then(response => {

        const vid = (response as Video)

        if (element) {
          const player = new Plyr(element)
          // autoplay is not allowed https://developers.google.com/web/updates/2017/09/autoplay-policy-changes
          // player.play();
          console.log("Setting player")
          setPlyr(player)
        }

        setVid(vid)
      }
    );
  }, [props]);

  useEffect(() => {

    if (vid) {
      const vidRatio = vid.resolution_x / vid.resolution_y;
      const scrRatio = windowSize.width / windowSize.height;

      const calcStyle = () => {
        if (vidRatio > scrRatio) {
          return {
            width: `${fillFactor}vw`,
            height: `${Math.trunc(1/vidRatio*fillFactor)}vw`
          }
        } else {
          return {
            width: `${Math.trunc(vidRatio*fillFactor)}vh`,
            height: `${fillFactor}vh`
          }
        }
      };

      const newStyle = calcStyle()

      setSizeStyle(newStyle)
    }
  },[windowSize, vid]);

  const setThumbnail = (e: any) => {
    // e.preventDefault()

    if (plyr && vid) {

      const timestamp = Math.trunc(plyr.currentTime * 1000);
      console.log(timestamp)

      createThumbnailAt(vid.id, timestamp).then (response => {
        console.log("thumbnail set")
      });
    }
    // console.log(plyr.currentTime)
  }

  const forwards = (amount: number) => {
    if (plyr && vid) {
      plyr.currentTime = plyr.currentTime + amount
    }
  }

  return (
    <div className="videoBackground">
      <div style={sizeStyle} className="videoContainer">
        <video className="videoPlayer" id={id} playsInline controls>
          <source src={videoSrc} type="video/mp4"/>
        </video>

        <Button size="sm" onClick={(e) => forwards(-0.1)}>&lt;</Button>
        <Button size="sm" onClick={(e) => forwards(-1)}>&lt;</Button>
        <Button size="sm" onClick={setThumbnail}>O</Button>
        <Button size="sm" onClick={(e) => forwards(1)}>&gt;</Button>
      </div>
    </div>
  );
}

export default Player;


