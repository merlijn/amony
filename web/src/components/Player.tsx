import Plyr from 'plyr';
import React, {useEffect, useState} from 'react';
import './Player.scss';
import {Video} from "../api/Model";
import {getMediaById} from "../api/Api";
import {useWindowSize} from "../api/Util";


const Player = (props: {videoId: string}) => {

  const id = '#video-' + props.videoId
  const videoSrc = '/files/videos/' + props.videoId

  const initialStyle = { };
  const fillFactor = 80;

  const [sizeStyle, setSizeStyle] = useState(initialStyle)
  const [vid, setVid] = useState<Video | null>(null)
  const windowSize = useWindowSize(((oldSize, newSize) => Math.abs(newSize.width - oldSize.width) > 5));

  useEffect(() => {

    const id = '#video-' + props.videoId
    const element = document.getElementById(id);

    getMediaById(props.videoId).then(response => {

        const vid = (response as Video)

        if (element) {
          const player = new Plyr(element)
          // autoplay is not allowed https://developers.google.com/web/updates/2017/09/autoplay-policy-changes
          // player.play();
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

      // const [w, h] = calculateSize();

      const newStyle = calcStyle()
      // {
      //   width: `${w}px`,
      //   height: `${h}px`
      // }

      setSizeStyle(newStyle)
    }
  },[windowSize, vid]);



  return (
    <div className="videoBackground">
      <div style={sizeStyle} className="videoContainer">
        <video className="videoPlayer" id={id} playsInline controls>
          <source src={videoSrc} type="video/mp4"/>
        </video>
      </div>
    </div>
  );
}

export default Player;


