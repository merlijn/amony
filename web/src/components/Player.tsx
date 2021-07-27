import Plyr from 'plyr';
import React, {useEffect, useState} from 'react';
import './Player.scss';
import {Video} from "../api/Model";
import {Api} from "../api/Api";
import {durationInMillisToString, useWindowSize} from "../api/Util";
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

    Api.getMediaById(props.videoId).then(response => {

        const vid = (response as Video)

        const vidRatio = vid.resolution_x / vid.resolution_y;
        const test = {
          width: `min(80vw, 80vh * ${vidRatio}`,
          height: `min(80vh, 80vw * 1 / ${vidRatio}`
        }
        setSizeStyle(test)

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


  let startTime: number | undefined = 0
  let endTime: number | undefined = 0

  const setThumbnail = (e: any) => {
    // e.preventDefault()

    if (plyr && vid && startTime && endTime) {

      const from = Math.trunc(startTime * 1000)
      const to = Math.trunc(endTime * 1000)

      console.log(from)
      console.log(to)

      Api.addFragment(vid.id, from, to).then (response => {
        console.log(`creating preview`)
      });
    }
    // console.log(plyr.currentTime)
  }

  const seek = (to?: number) => {
    if (plyr && to) {
      plyr.currentTime = to
    }
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

        <Button size="sm" onClick={(e) => forwards(-1)}>-1s</Button>
        <Button size="sm" onClick={(e) => forwards(-0.1)}>-.1ms</Button>
        <Button size="sm" onClick={(e) => seek(startTime) }>|&lt;</Button>
        <Button variant="success" size="sm" onClick={(e) => startTime = plyr?.currentTime }>o&lt;</Button>
        <Button variant="success" size="sm" onClick={setThumbnail}>o</Button>
        <Button variant="success" size="sm" onClick={(e) => endTime = plyr?.currentTime}>&gt;o</Button>
        <Button size="sm" onClick={(e) => seek(endTime) }>&gt;|</Button>
        <Button size="sm" onClick={(e) => forwards(0.1)}>+.1s</Button>
        <Button size="sm" onClick={(e) => forwards(1)}>+1s</Button>
      </div>


      <div className="fragment-list">


      </div>
    </div>
  );
}

export default Player;


